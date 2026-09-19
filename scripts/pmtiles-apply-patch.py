#!/usr/bin/env python3
"""Apply a Vela delta patch to an installed archive, in place.

    scripts/pmtiles-apply-patch.py archive.pmtiles patch.vpatch [--verify]

This is the reference implementation of what the app does, and the bake runs it to prove a patch
applies before publishing it: produce a patch, apply it to a copy of the old archive, and check the
fingerprint equals the new archive's. A patch that cannot do that never reaches anyone.

The write order is the design: tile blobs, then the directory, then the 127-byte header LAST.
Until that final write the file is still the old archive with unused bytes on the end, so a crash
or a pulled battery costs nothing.

The patch names no offsets (see `pmtiles-make-patch.py`), so the directory is built HERE, against
whatever shape the archive on this machine is in: kept tiles stay where they already sit, carried
ones land where they are appended. That is what makes a patch land on an archive an earlier patch
has already changed.
"""
import gzip
import json
import struct
import sys
from pathlib import Path

from velapmtiles import HEADER_LEN, MAGIC, entries_of, fingerprint, varint, write_dir


def read_plan(raw, comp):
    buf = gzip.decompress(raw) if comp == 2 else raw
    i = 0
    n, i = varint(buf, i)
    ids, last = [], 0
    for _ in range(n):
        d, i = varint(buf, i); last += d; ids.append(last)
    runs = []
    for _ in range(n):
        v, i = varint(buf, i); runs.append(v)
    lens = []
    for _ in range(n):
        v, i = varint(buf, i); lens.append(v)
    flags = []
    for _ in range(n):
        v, i = varint(buf, i); flags.append(v)
    return list(zip(ids, runs, lens, flags))


def main():
    arc = Path(sys.argv[1])
    patch = Path(sys.argv[2])
    with open(patch, "rb") as p:
        assert p.read(8) == MAGIC, "not a Vela patch"
        version = p.read(1)[0]
        assert version == 2, f"patch version {version} is not this applier's"
        head = json.loads(p.read(struct.unpack("<I", p.read(4))[0]))
        plan = read_plan(p.read(struct.unpack("<I", p.read(4))[0]), head["internalCompression"])

        ah, mine = entries_of(arc)
        have = {e[0]: e for e in mine}
        # The archive has to hold every tile the patch expects it to keep, at the length it
        # expects. Cheap, and it catches the wrong file before a byte is written; the fingerprint
        # at the end catches anything subtler.
        for tid, run, ln, flag in plan:
            if flag == 0 and (tid not in have or have[tid][2] != ln):
                raise SystemExit(f"archive is missing the tile {tid} this patch keeps")

        size = arc.stat().st_size
        append_at = size - ah["tile_off"]
        entries, carried_at = [], append_at
        for tid, run, ln, flag in plan:
            if flag == 0:
                entries.append((tid, have[tid][1], ln, run))
            else:
                entries.append((tid, carried_at, ln, run))
                carried_at += ln
        directory = write_dir(entries, ah["internal"])

        with open(arc, "r+b") as a:
            a.seek(0, 2)
            for t in head["tiles"]:
                a.write(p.read(t["len"]))
            root_at = a.tell()
            a.write(directory)
            a.flush()
            eof = a.seek(0, 2)
            a.seek(0)
            hdr = bytearray(a.read(HEADER_LEN))
            struct.pack_into("<Q", hdr, 8, root_at)            # root directory
            struct.pack_into("<Q", hdr, 16, len(directory))
            struct.pack_into("<Q", hdr, 40, 0)                 # leaves are folded into the root
            struct.pack_into("<Q", hdr, 48, 0)
            struct.pack_into("<Q", hdr, 64, eof - ah["tile_off"])
            hdr[96] = 0                                        # no longer clustered
            a.seek(0)
            a.write(bytes(hdr))
            a.flush()
    print(f"applied {len(head['tiles'])} tiles, archive is now {arc.stat().st_size / 1e6:.2f} MB")

    if "--verify" in sys.argv:
        # The acceptance criterion, checked rather than hoped for: a patched archive must hold
        # exactly what a fresh download of that rev holds.
        h, entries = entries_of(arc)
        got = fingerprint(arc, entries, h["tile_off"])
        want = head.get("toFingerprint")
        if want and got != want:
            raise SystemExit(f"FINGERPRINT MISMATCH: patched archive is {got}, patch says {want}")
        print(f"fingerprint {got} matches, {len(entries)} tiles")


if __name__ == "__main__":
    main()
