#!/usr/bin/env python3
"""Build a Vela delta patch between two bakes of the same region.

    scripts/pmtiles-make-patch.py old.pmtiles new.pmtiles out.vpatch [--rev-from N --rev-to N]

The patch is applied by APPENDING to the installed archive and flipping its header last, so the
phone never holds two copies of a region (see ROADMAP). That means the producer decides the final
layout: it knows the old file's size, so it can compute exactly where each appended tile will land
and ship the finished directory, and the applier only has to write bytes in the order given.

Format, all little endian:

    "VELAPTCH" u8 version
    u32 header_len, header (utf-8 json)
    u32 dir_len,    directory bytes, gzipped exactly as they must be written
    tile blobs, concatenated, in the order of header["tiles"]

The header carries what the applier needs to refuse the wrong file (`fromSize`, `fromHeaderSha`)
and what it needs to prove the result (`toFingerprint`, a hash over the new archive's sorted tile
ids and tile hashes). A fresh download of the same rev has the same fingerprint, which is the
acceptance criterion: an archive that has taken patches must be indistinguishable from one that
has not.
"""
import hashlib
import json
import struct
import sys
from pathlib import Path

from velapmtiles import (  # the one reader, so producer and applier cannot drift
    HEADER_LEN, MAGIC, VERSION, blob, entries_of, fingerprint, header_of, write_dir,
)


def main():
    old_p, new_p, out_p = (Path(a) for a in sys.argv[1:4])
    rev_from = int(sys.argv[sys.argv.index("--rev-from") + 1]) if "--rev-from" in sys.argv else 0
    rev_to = int(sys.argv[sys.argv.index("--rev-to") + 1]) if "--rev-to" in sys.argv else 0

    oh, old = entries_of(old_p)
    nh, new = entries_of(new_p)
    old_by_id = {e[0]: e for e in old}

    with open(old_p, "rb") as fo, open(new_p, "rb") as fn:
        old_hashes = {}
        for tid, off, ln, run in old:
            old_hashes[tid] = hashlib.sha256(blob(fo, oh["tile_off"], off, ln)).digest()

        # What the applier has to append, in id order. A tile is carried when it is new or its
        # bytes changed; everything else keeps pointing at where it already sits.
        carried, payload, dir_entries = [], bytearray(), []
        append_at = old_p.stat().st_size - oh["tile_off"]  # offsets are relative to tile data
        for tid, off, ln, run in new:
            nb = blob(fn, nh["tile_off"], off, ln)
            h = hashlib.sha256(nb).digest()
            if tid in old_hashes and old_hashes[tid] == h:
                dir_entries.append((tid, old_by_id[tid][1], old_by_id[tid][2], run))
            else:
                dir_entries.append((tid, append_at + len(payload), len(nb), run))
                carried.append({"id": tid, "len": len(nb)})
                payload += nb

    directory = write_dir(dir_entries, oh["internal"])
    head = {
        "version": VERSION,
        "fromRev": rev_from, "toRev": rev_to,
        "fromSize": old_p.stat().st_size,
        "fromHeaderSha": hashlib.sha256(open(old_p, "rb").read(HEADER_LEN)).hexdigest(),
        "tileDataOffset": oh["tile_off"],
        "internalCompression": oh["internal"],
        "tiles": carried,
        "dirLen": len(directory),
        # Dead bytes this patch leaves behind: the old blobs of the tiles it replaces. The app
        # tracks the running total and re-downloads instead of patching once it gets silly.
        "deadBytes": sum(old_by_id[t["id"]][2] for t in carried if t["id"] in old_by_id),
        "toFingerprint": fingerprint(new_p, new, nh["tile_off"]),
        "toTiles": len(new),
    }
    blobj = json.dumps(head, separators=(",", ":")).encode()
    with open(out_p, "wb") as f:
        f.write(MAGIC + bytes([VERSION]))
        f.write(struct.pack("<I", len(blobj))); f.write(blobj)
        f.write(struct.pack("<I", len(directory))); f.write(directory)
        f.write(payload)
    full = new_p.stat().st_size
    size = out_p.stat().st_size
    print(f"{len(carried)} of {len(new)} tiles carried, patch {size / 1e6:.2f} MB "
          f"({100.0 * size / full:.1f}% of a {full / 1e6:.1f} MB download), "
          f"directory {len(directory) / 1e6:.2f} MB, dead {head['deadBytes'] / 1e6:.2f} MB")


if __name__ == "__main__":
    main()
