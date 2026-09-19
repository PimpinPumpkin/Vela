#!/usr/bin/env python3
"""Write the tiny archives the compaction test runs against.

    scripts/pmtiles-test-fixture.py app/src/test/resources

Builds a small PMTiles v3 archive, a second revision of it, a patch between them, and then the
PATCHED archive: the one with dead bytes in it, which is what `PmtilesCompactTest` compacts. The
tile payloads are not real vector tiles on purpose. Nothing in the patch or compaction path parses
a tile; it moves bytes and hashes them, and a fixture made of recognisable filler makes a failure
easier to read than one made of gzipped geometry.
"""
import struct
import subprocess
import sys
from pathlib import Path

from velapmtiles import HEADER_LEN, entries_of, fingerprint, write_dir

HERE = Path(__file__).resolve().parent


def build(path: Path, tiles: dict[int, bytes], meta: bytes = b'{"vela":"fixture"}') -> None:
    """A clustered archive of [tiles], laid out the way a bake publishes one."""
    entries, payload = [], bytearray()
    for tid in sorted(tiles):
        entries.append((tid, len(payload), len(tiles[tid]), 1))
        payload += tiles[tid]
    directory = write_dir(entries, 2)
    tile_off = HEADER_LEN + len(directory) + len(meta)

    hdr = bytearray(HEADER_LEN)
    hdr[0:7] = b"PMTiles"
    hdr[7] = 3
    put = lambda at, v: struct.pack_into("<Q", hdr, at, v)
    put(8, HEADER_LEN); put(16, len(directory))
    put(24, HEADER_LEN + len(directory)); put(32, len(meta))
    put(40, 0); put(48, 0)
    put(56, tile_off); put(64, len(payload))
    put(72, len(entries)); put(80, len(entries)); put(88, len(entries))
    hdr[96] = 1   # clustered
    hdr[97] = 2   # gzipped directories
    hdr[98] = 2   # gzipped tiles
    hdr[99] = 1   # mvt
    hdr[100] = 0; hdr[101] = 14
    path.write_bytes(bytes(hdr) + directory + meta + bytes(payload))


def main():
    out = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
    out.mkdir(parents=True, exist_ok=True)
    work = out / ".fixture-work"
    work.mkdir(exist_ok=True)

    old = {tid: f"tile {tid} as it was".encode() * 8 for tid in range(100, 140)}
    new = dict(old)
    for tid in (103, 117, 138):
        new[tid] = f"tile {tid} after the edit".encode() * 11
    new[140] = b"a tile that did not exist before" * 5

    a, b = work / "old.pmtiles", work / "new.pmtiles"
    build(a, old)
    build(b, new)
    patch = work / "delta.vpatch"
    subprocess.run([sys.executable, str(HERE / "pmtiles-make-patch.py"), str(a), str(b), str(patch),
                    "--rev-from", "1", "--rev-to", "2"], check=True)

    patched = out / "patched.pmtiles"
    patched.write_bytes(a.read_bytes())
    subprocess.run([sys.executable, str(HERE / "pmtiles-apply-patch.py"), str(patched), str(patch),
                    "--verify"], check=True)

    h, entries = entries_of(patched)
    print(f"{patched}: {patched.stat().st_size} bytes, {len(entries)} tiles, "
          f"fingerprint {fingerprint(patched, entries, h['tile_off'])}, "
          f"fresh is {b.stat().st_size} bytes")
    for f in work.iterdir():
        f.unlink()
    work.rmdir()


if __name__ == "__main__":
    main()
