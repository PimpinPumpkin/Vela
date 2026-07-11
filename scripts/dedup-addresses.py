#!/usr/bin/env python3
"""Collapse OpenAddresses' repeated address points before tiling.

OpenAddresses statewide data carries one row per UNIT or PARCEL, so an apartment
complex contributes the same house number dozens of times across its footprint and
Vela's house-number overlay printed it over the whole building (user 2026-07-10).
One label per address is what a map wants: keep the FIRST point for each
(number, street, ~150 m grid cell) and drop the rest. Streaming, one pass, no
geometry math - the grid cell stands in for "the same building/complex". Points
with no number render nothing and are dropped outright.

Usage: dedup-addresses.py <in.geojsonl> <out.geojsonl>   (prints stats to stderr)
"""
import json
import sys

CELL = 0.0015  # degrees, ~165 m of latitude - spans a big complex, well under a city block pair

def main() -> None:
    inp, out = sys.argv[1], sys.argv[2]
    seen = set()
    kept = dropped = bad = 0
    with open(inp, encoding="utf-8", errors="replace") as f, open(out, "w", encoding="utf-8") as o:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                feat = json.loads(line)
                props = feat.get("properties") or {}
                num = str(props.get("number") or "").strip()
                street = str(props.get("street") or "").strip().lower()
                lng, lat = feat["geometry"]["coordinates"][:2]
            except Exception:
                bad += 1
                continue
            if not num:
                dropped += 1
                continue
            key = hash((num, street, round(lat / CELL), round(lng / CELL)))
            if key in seen:
                dropped += 1
                continue
            seen.add(key)
            o.write(line + "\n")
            kept += 1
    print(f"dedup: kept {kept}, dropped {dropped} (dupes + numberless), {bad} unparsable", file=sys.stderr)

if __name__ == "__main__":
    main()
