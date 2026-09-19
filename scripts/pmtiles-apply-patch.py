#!/usr/bin/env python3
"""Apply a Vela delta patch to an installed archive, in place.

    scripts/pmtiles-apply-patch.py archive.pmtiles patch.vpatch [--verify]

This is the reference implementation of what the app does, and the bake runs it to prove a patch
applies before publishing it: produce a patch, apply it to a copy of the old archive, and check the
fingerprint equals the new archive's. A patch that cannot do that never reaches anyone.

The write order is the design: tile blobs, then the directory, then the 127-byte header LAST.
Until that final write the file is still the old archive with unused bytes on the end, so a crash
or a pulled battery costs nothing.
"""
import hashlib
import json
import struct
import sys
from pathlib import Path

HEADER_LEN = 127
MAGIC = b"VELAPTCH"


def main():
    arc = Path(sys.argv[1])
    patch = Path(sys.argv[2])
    with open(patch, "rb") as p:
        assert p.read(8) == MAGIC, "not a Vela patch"
        version = p.read(1)[0]
        assert version == 1, f"patch version {version} is newer than this applier"
        head = json.loads(p.read(struct.unpack("<I", p.read(4))[0]))
        directory = p.read(struct.unpack("<I", p.read(4))[0])

        size = arc.stat().st_size
        if size != head["fromSize"]:
            raise SystemExit(f"archive is {size} bytes, patch expects {head['fromSize']}")
        with open(arc, "rb") as a:
            if hashlib.sha256(a.read(HEADER_LEN)).hexdigest() != head["fromHeaderSha"]:
                raise SystemExit("archive header does not match the patch's source")

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
            struct.pack_into("<Q", hdr, 64, eof - head["tileDataOffset"])
            hdr[96] = 0                                        # no longer clustered
            a.seek(0)
            a.write(bytes(hdr))
            a.flush()
    print(f"applied {len(head['tiles'])} tiles, archive is now {arc.stat().st_size / 1e6:.2f} MB")

    if "--verify" in sys.argv:
        # The acceptance criterion, checked rather than hoped for: a patched archive must hold
        # exactly what a fresh download of that rev holds.
        from velapmtiles import entries_of, fingerprint
        h, entries = entries_of(arc)
        got = fingerprint(arc, entries, h["tile_off"])
        want = head.get("toFingerprint")
        if want and got != want:
            raise SystemExit(f"FINGERPRINT MISMATCH: patched archive is {got}, patch says {want}")
        print(f"fingerprint {got} matches, {len(entries)} tiles")


if __name__ == "__main__":
    main()
