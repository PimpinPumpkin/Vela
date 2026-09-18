#!/usr/bin/env python3
"""Prove the delta design before building it: patch an archive IN PLACE by appending.

    scripts/pmtiles-append-patch.py <archive.pmtiles> [--tiles N]

Rewrites N tiles of a COPY of the archive the way a delta update would have to: append the new
tile blobs to the end of the file, append rebuilt directories after them, and flip the 127-byte
header's pointers as the very last write. Nothing before that write changes, so a crash leaves
the old archive intact, and the extra disk is the patch rather than a second copy of the region.

What this exists to answer: the appended layout is UNCLUSTERED, and the app's local archives are
read by MapLibre's PMTiles implementation, not by ours. Unclustered is legal, but that is a third
implementation and worth proving rather than assuming.
"""
import gzip
import shutil
import struct
import sys
from pathlib import Path

HEADER_LEN = 127


def varint(buf, i):
    shift = value = 0
    while True:
        b = buf[i]; i += 1
        value |= (b & 0x7F) << shift
        if not b & 0x80:
            return value, i
        shift += 7


def put_varint(out, v):
    while True:
        b = v & 0x7F
        v >>= 7
        out.append(b | (0x80 if v else 0))
        if not v:
            return


def read_header(f):
    f.seek(0)
    h = f.read(HEADER_LEN)
    assert h[:7] == b"PMTiles" and h[7] == 3, "not a PMTiles v3 archive"
    u64 = lambda at: struct.unpack_from("<Q", h, at)[0]
    return bytearray(h), {
        "root_off": u64(8), "root_len": u64(16), "leaf_off": u64(40), "leaf_len": u64(48),
        "tile_off": u64(56), "tile_len": u64(64), "internal": h[97], "clustered": h[96],
    }


def read_dir(f, off, ln, comp):
    f.seek(off)
    raw = f.read(ln)
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
    offs = []
    for k in range(n):
        v, i = varint(buf, i)
        offs.append(offs[k - 1] + lens[k - 1] if v == 0 and k > 0 else v - 1)
    return list(zip(ids, offs, lens, runs))


def write_dir(entries, comp):
    """Explicit offsets for every entry: the archive is no longer clustered, so the
    'zero means directly after the previous one' shorthand no longer holds."""
    out = bytearray()
    put_varint(out, len(entries))
    last = 0
    for tid, _, _, _ in entries:
        put_varint(out, tid - last); last = tid
    for _, _, _, run in entries:
        put_varint(out, run)
    for _, _, ln, _ in entries:
        put_varint(out, ln)
    for _, off, _, _ in entries:
        put_varint(out, off + 1)
    return gzip.compress(bytes(out), mtime=0) if comp == 2 else bytes(out)


def main():
    src = Path(sys.argv[1])
    count = int(sys.argv[sys.argv.index("--tiles") + 1]) if "--tiles" in sys.argv else 20
    dst = src.with_name(src.stem + "-patched.pmtiles")
    shutil.copy(src, dst)

    with open(dst, "r+b") as f:
        head, h = read_header(f)
        # A real region archive has LEAF directories (found on a 152 MB one; the toy did not).
        # Expand them into one list here: an applier has to walk them, and rewriting a single root
        # is the simplest correct thing for a LOCAL archive, which is never range-requested.
        entries, stack, leaves = [], [(h["root_off"], h["root_len"])], 0
        while stack:
            off, ln = stack.pop()
            for e in read_dir(f, off, ln, h["internal"]):
                if e[3] == 0:
                    leaves += 1
                    stack.append((h["leaf_off"] + e[1], e[2]))
                else:
                    entries.append(e)
        entries.sort(key=lambda e: e[0])
        print(f"{len(entries)} entries across {leaves} leaf directories, "
              f"clustered={h['clustered']}, tile data at {h['tile_off']}")

        # Rewrite the first `count` tiles with their own bytes plus a trailing byte, which is
        # enough to move them: the point is the layout, not the content.
        f.seek(0, 2)
        end = f.tell()
        changed = {}
        for tid, off, ln, run in entries[:count]:
            f.seek(h["tile_off"] + off)
            blob = f.read(ln) + b"\x00"
            f.seek(0, 2)
            changed[tid] = (f.tell() - h["tile_off"], len(blob))
            f.write(blob)

        merged = [(tid, *changed[tid], run) if tid in changed else (tid, off, ln, run)
                  for tid, off, ln, run in entries]
        f.seek(0, 2)
        root_at = f.tell()
        blob = write_dir(merged, h["internal"])
        f.write(blob)
        f.flush()

        # LAST write, and the only one that changes anything already in the file. The LENGTH
        # fields have to move with it: go-pmtiles' verify checks that the header accounts for the
        # whole file, and an archive that grew without saying so is reported as corrupt.
        eof = f.seek(0, 2)
        struct.pack_into("<Q", head, 8, root_at)
        struct.pack_into("<Q", head, 16, len(blob))
        struct.pack_into("<Q", head, 40, 0)   # no leaf directories any more
        struct.pack_into("<Q", head, 48, 0)
        struct.pack_into("<Q", head, 64, eof - h["tile_off"])  # tile data now runs to the end
        head[96] = 0  # no longer clustered
        f.seek(0)
        f.write(bytes(head))
        f.flush()
        print(f"appended {count} tiles + a {len(blob)} byte directory, "
              f"grew {(f.seek(0, 2) - end) / 1024:.1f} KB, header flipped last")
    print(dst)


if __name__ == "__main__":
    main()
