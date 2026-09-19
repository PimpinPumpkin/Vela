#!/usr/bin/env python3
"""Reading PMTiles v3 the way Vela's delta tooling needs it, in one place.

Shared by pmtiles-make-patch.py, pmtiles-apply-patch.py and archive-churn.py so the fingerprint
that decides whether a patched archive matches a fresh download is computed by ONE piece of code.

    python3 scripts/velapmtiles.py <archive.pmtiles>     print its fingerprint
"""
import gzip
import hashlib
import json
import struct
import sys
from pathlib import Path

HEADER_LEN = 127
MAGIC = b"VELAPTCH"
VERSION = 1
MAGIC = b"VELAPTCH"
VERSION = 1


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


def header_of(f):
    f.seek(0)
    h = f.read(HEADER_LEN)
    assert h[:7] == b"PMTiles" and h[7] == 3, "not a PMTiles v3 archive"
    u64 = lambda at: struct.unpack_from("<Q", h, at)[0]
    return h, {"root_off": u64(8), "root_len": u64(16), "leaf_off": u64(40),
               "tile_off": u64(56), "internal": h[97]}


def read_dir(f, off, ln, comp):
    f.seek(off)
    raw = f.read(ln)
    buf = gzip.decompress(raw) if comp == 2 else raw
    i = 0
    n, i = varint(buf, i)
    ids, last = [], 0
    for _ in range(n):
        d, i = varint(buf, i); last += d; ids.append(last)
    runs = [0] * n
    for k in range(n):
        runs[k], i = varint(buf, i)
    lens = [0] * n
    for k in range(n):
        lens[k], i = varint(buf, i)
    offs = []
    for k in range(n):
        v, i = varint(buf, i)
        offs.append(offs[k - 1] + lens[k - 1] if v == 0 and k > 0 else v - 1)
    return list(zip(ids, offs, lens, runs))


def entries_of(path):
    """Every tile entry, leaf directories walked. Runs are kept: they are how an archive stores
    one blob for many ids, and expanding them would inflate the directory enormously."""
    with open(path, "rb") as f:
        _, h = header_of(f)
        out, stack = [], [(h["root_off"], h["root_len"])]
        while stack:
            off, ln = stack.pop()
            for e in read_dir(f, off, ln, h["internal"]):
                if e[3] == 0:
                    stack.append((h["leaf_off"] + e[1], e[2]))
                else:
                    out.append(e)
        out.sort(key=lambda e: e[0])
        return h, out


def blob(f, tile_off, off, ln):
    f.seek(tile_off + off)
    return f.read(ln)


def write_dir(entries, comp):
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


def fingerprint(path, entries, tile_off):
    """A hash over (tile id, run, tile bytes) for the whole archive. Two archives with the same
    fingerprint hold the same tiles, whatever order they sit in on disk, which is what makes
    "a patched archive is indistinguishable from a fresh download" a checkable claim.

    SHA-256, truncated: the phone has to compute this too and Android ships no blake2b."""
    hsh = hashlib.sha256()
    with open(path, "rb") as f:
        seen = {}
        for tid, off, ln, run in entries:
            key = (off, ln)
            if key not in seen:
                seen[key] = hashlib.sha256(blob(f, tile_off, off, ln)).digest()
            hsh.update(struct.pack("<QH", tid, run))
            hsh.update(seen[key])
    return hsh.hexdigest()[:32]




if __name__ == "__main__":
    p = Path(sys.argv[1])
    h, entries = entries_of(p)
    print(fingerprint(p, entries, h["tile_off"]), len(entries), "tiles")
