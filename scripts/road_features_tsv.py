#!/usr/bin/env python3
"""osmium geojsonseq on stdin -> gzipped `lat<TAB>lon<TAB>kind` TSV for RoadFeatures (issue #304).

kind: S traffic signal, T stop sign, R level crossing, H speed hump/bump/table/cushion,
C fixed speed camera. Anything else on stdin is skipped. Prints the row count.

    osmium export filtered.osm.pbf -f geojsonseq -o - | python3 scripts/road_features_tsv.py out.bin
"""
import gzip
import json
import sys


def kind_of(p):
    hw = p.get("highway")
    if hw == "traffic_signals":
        return "S"
    if hw == "stop":
        return "T"
    if hw == "speed_camera":
        return "C"
    if p.get("railway") == "level_crossing":
        return "R"
    if p.get("traffic_calming") in ("bump", "hump", "table", "cushion"):
        return "H"
    return None


def main():
    out = sys.argv[1]
    n = 0
    kinds = {}
    with gzip.open(out, "wt", encoding="utf-8", compresslevel=9) as f:
        for line in sys.stdin:
            line = line.strip().lstrip("\x1e")  # geojsonseq record separator
            if not line:
                continue
            try:
                ft = json.loads(line)
            except ValueError:
                continue
            g = ft.get("geometry") or {}
            if g.get("type") != "Point":
                continue
            lon, lat = g["coordinates"][:2]
            k = kind_of(ft.get("properties") or {})
            if k is None:
                continue
            f.write(f"{lat:.6f}\t{lon:.6f}\t{k}\n")
            n += 1
            kinds[k] = kinds.get(k, 0) + 1
    print(f"{n} rows {kinds}", file=sys.stderr)


if __name__ == "__main__":
    main()
