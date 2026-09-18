#!/usr/bin/env python3
"""What a rebake actually changes, and what a delta update would therefore cost.

    scripts/archive-churn.py old.pmtiles new.pmtiles [--patch]

Answers the question the delta-update decision turns on: when a region is rebaked, how much of
the archive is genuinely different? Reports, per zoom, how many tiles are identical, changed,
added or dropped, and the share of bytes that changed. With --patch it also builds a real
`zstd --patch-from` delta and reports its size, which IS what a delta update would transfer.

Reads PMTiles v3 directly (header at byte 0, root directory, leaf directories) rather than
depending on a tile library: the same 127-byte header and varint directory the app's
PmtilesReader parses, so what this measures is what the phone would have to apply.
"""
import gzip
import hashlib
import struct
import subprocess
import sys
import tempfile
from collections import defaultdict
from pathlib import Path

HEADER_LEN = 127


def varint(buf, i):
    shift = value = 0
    while True:
        b = buf[i]
        i += 1
        value |= (b & 0x7F) << shift
        if not b & 0x80:
            return value, i
        shift += 7


def header(f):
    f.seek(0)
    head = f.read(HEADER_LEN)
    if head[:7] != b"PMTiles" or head[7] != 3:
        raise SystemExit("not a PMTiles v3 archive")
    u64 = lambda at: struct.unpack_from("<Q", head, at)[0]
    return {
        "root_offset": u64(8), "root_length": u64(16),
        "leaf_offset": u64(40), "tile_offset": u64(56),
        "internal_compression": head[97],
    }


def directory(f, offset, length, compression):
    f.seek(offset)
    raw = f.read(length)
    buf = gzip.decompress(raw) if compression == 2 else raw
    i = 0
    n, i = varint(buf, i)
    ids, last = [], 0
    for _ in range(n):
        d, i = varint(buf, i)
        last += d
        ids.append(last)
    runs = []
    for _ in range(n):
        v, i = varint(buf, i)
        runs.append(v)
    lengths = []
    for _ in range(n):
        v, i = varint(buf, i)
        lengths.append(v)
    offsets = []
    for k in range(n):
        v, i = varint(buf, i)
        # 0 means "directly after the previous entry", how a clustered archive avoids an offset per tile
        offsets.append(offsets[k - 1] + lengths[k - 1] if v == 0 and k > 0 else v - 1)
    return list(zip(ids, offsets, lengths, runs))


def zoom_of(tile_id):
    # The Hilbert ordering numbers zoom z after sum(4^t) for t < z.
    acc, z = 0, 0
    while True:
        span = 1 << (2 * z)
        if tile_id < acc + span:
            return z
        acc += span
        z += 1


def tiles(path):
    """tile id -> (offset, length), following leaf directories."""
    out = {}
    with open(path, "rb") as f:
        h = header(f)
        stack = [(h["root_offset"], h["root_length"])]
        while stack:
            off, ln = stack.pop()
            for tid, toff, tlen, run in directory(f, off, ln, h["internal_compression"]):
                if run == 0:
                    stack.append((h["leaf_offset"] + toff, tlen))
                else:
                    for k in range(run):
                        out[tid + k] = (toff, tlen)
    return out


def digests(path, entries):
    """Content hash per tile id. Runs share one blob, so each (offset,length) is read once."""
    seen, out = {}, {}
    with open(path, "rb") as f:
        h = header(f)
        for tid, (off, ln) in sorted(entries.items(), key=lambda kv: kv[1]):
            key = (off, ln)
            if key not in seen:
                f.seek(h["tile_offset"] + off)
                seen[key] = hashlib.blake2b(f.read(ln), digest_size=16).digest()
            out[tid] = seen[key]
    return out


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    want_patch = "--patch" in sys.argv
    if len(args) != 2:
        raise SystemExit(__doc__)
    old, new = Path(args[0]), Path(args[1])

    a_entries, b_entries = tiles(old), tiles(new)
    a_hash, b_hash = digests(old, a_entries), digests(new, b_entries)

    per_zoom = defaultdict(lambda: dict(same=0, changed=0, added=0, dropped=0, bytes_changed=0, bytes_total=0))
    for tid, h in b_hash.items():
        z = per_zoom[zoom_of(tid)]
        size = b_entries[tid][1]
        z["bytes_total"] += size
        if tid not in a_hash:
            z["added"] += 1
            z["bytes_changed"] += size
        elif a_hash[tid] == h:
            z["same"] += 1
        else:
            z["changed"] += 1
            z["bytes_changed"] += size
    for tid in a_hash.keys() - b_hash.keys():
        per_zoom[zoom_of(tid)]["dropped"] += 1

    tot = dict(same=0, changed=0, added=0, dropped=0, bytes_changed=0, bytes_total=0)
    print(f"{'z':>3} {'same':>8} {'changed':>8} {'added':>7} {'dropped':>8} {'changed bytes':>16}")
    for z in sorted(per_zoom):
        r = per_zoom[z]
        for k in tot:
            tot[k] += r[k]
        pct = 100.0 * r["bytes_changed"] / r["bytes_total"] if r["bytes_total"] else 0
        print(f"{z:>3} {r['same']:>8} {r['changed']:>8} {r['added']:>7} {r['dropped']:>8} "
              f"{r['bytes_changed'] / 1e6:>10.1f} MB {pct:>4.0f}%")
    tiles_total = tot["same"] + tot["changed"] + tot["added"]
    moved = tot["changed"] + tot["added"]
    print(f"\ntiles: {tiles_total} in the new archive, {tot['same']} identical, {tot['changed']} changed, "
          f"{tot['added']} added, {tot['dropped']} dropped")
    if tiles_total:
        print(f"tiles differing: {100.0 * moved / tiles_total:.1f}%")
    if tot["bytes_total"]:
        print(f"bytes differing: {tot['bytes_changed'] / 1e6:.1f} MB of {tot['bytes_total'] / 1e6:.1f} MB "
              f"({100.0 * tot['bytes_changed'] / tot['bytes_total']:.1f}%)")
    print(f"archive on disk: old {old.stat().st_size / 1e6:.1f} MB, new {new.stat().st_size / 1e6:.1f} MB")

    if want_patch:
        # The number that actually decides it: what a delta update would put on the wire.
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "patch.zst"
            cmd = ["zstd", "-19", "--long=31", "-q", f"--patch-from={old}", str(new), "-o", str(out)]
            r = subprocess.run(cmd, capture_output=True, text=True)
            if r.returncode != 0:
                print(f"\nzstd --patch-from failed: {r.stderr.strip()[:200]}")
            else:
                p = out.stat().st_size
                full = new.stat().st_size
                print(f"\nzstd delta: {p / 1e6:.1f} MB, {100.0 * p / full:.1f}% of a full download")


if __name__ == "__main__":
    main()
