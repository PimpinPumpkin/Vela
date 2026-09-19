#!/usr/bin/env python3
"""Rewrite a patched archive with the dead space taken out, in place.

    scripts/pmtiles-compact.py archive.pmtiles [--verify]

Applying a patch APPENDS: the tiles it replaces stay on disk with nothing pointing at them. That is
the only way an archive that has taken patches differs from a fresh download of the same revision,
and it is why the app used to fall back to re-downloading the whole region once the dead bytes
added up. It never needed to. Every live tile is already on the phone; putting them back in tile
order and dropping the rest is a local rewrite with no network at all.

This is the reference implementation of what `PmtilesCompact.kt` does on the phone. The layout it
writes is the one the bake publishes: header, directory, metadata, then tile data in tile id order,
clustered, no leaf directories and no gaps. The fingerprint is unchanged by construction, and
--verify checks that rather than assuming it.
"""
import struct
import sys
from pathlib import Path

from velapmtiles import HEADER_LEN, entries_of, fingerprint, header_of, write_dir


def compact(path: Path) -> tuple[int, int]:
    """Rewrite [path] without its dead bytes. Returns (bytes before, bytes after)."""
    before = path.stat().st_size
    head, entries = entries_of(path)
    tmp = path.with_suffix(path.suffix + ".compact")

    with open(path, "rb") as src:
        hdr = bytearray(header_of(src)[0])
        src.seek(head["meta_off"])
        meta = src.read(head["meta_len"])

        # New offsets, in tile id order. Two ids that share a blob keep sharing it: an archive
        # stores one copy of an empty-ish tile for a whole run of ids, and expanding that here
        # would undo the bake's own deduplication.
        placed, plan, new_entries, at = {}, [], [], 0
        for tid, off, ln, run in entries:
            where = placed.get((off, ln))
            if where is None:
                where = at
                placed[(off, ln)] = at
                plan.append((off, ln))
                at += ln
            new_entries.append((tid, where, ln, run))

        directory = write_dir(new_entries, head["internal"])
        tile_off = HEADER_LEN + len(directory) + len(meta)
        struct.pack_into("<Q", hdr, 8, HEADER_LEN)                  # root directory
        struct.pack_into("<Q", hdr, 16, len(directory))
        struct.pack_into("<Q", hdr, 24, HEADER_LEN + len(directory))  # metadata
        struct.pack_into("<Q", hdr, 32, len(meta))
        struct.pack_into("<Q", hdr, 40, 0)                          # no leaf directories
        struct.pack_into("<Q", hdr, 48, 0)
        struct.pack_into("<Q", hdr, 56, tile_off)
        struct.pack_into("<Q", hdr, 64, at)
        struct.pack_into("<Q", hdr, 72, sum(e[3] for e in new_entries))  # addressed tiles
        struct.pack_into("<Q", hdr, 80, len(new_entries))           # tile entries
        struct.pack_into("<Q", hdr, 88, len(plan))                  # distinct tile blobs
        hdr[96] = 1                                                 # clustered again

        with open(tmp, "wb") as out:
            out.write(bytes(hdr))
            out.write(directory)
            out.write(meta)
            for off, ln in plan:
                src.seek(head["tile_off"] + off)
                out.write(src.read(ln))

    tmp.replace(path)
    return before, path.stat().st_size


def main():
    arc = Path(sys.argv[1])
    was = None
    if "--verify" in sys.argv:
        head, entries = entries_of(arc)
        was = fingerprint(arc, entries, head["tile_off"])
    before, after = compact(arc)
    print(f"compacted {before / 1e6:.2f} MB to {after / 1e6:.2f} MB "
          f"({(before - after) / 1e6:.2f} MB of dead space dropped)")
    if was is not None:
        head, entries = entries_of(arc)
        got = fingerprint(arc, entries, head["tile_off"])
        if got != was:
            raise SystemExit(f"FINGERPRINT CHANGED: {was} -> {got}")
        print(f"fingerprint {got} unchanged, {len(entries)} tiles")


if __name__ == "__main__":
    main()
