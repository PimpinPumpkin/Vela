#!/usr/bin/env python3
"""osmium geojsonseq on stdin -> gzipped `lat<TAB>lon<TAB>kind[<TAB>bearing]` TSV for RoadFeatures.

kind: S traffic signal, T stop sign, R level crossing, H speed hump/bump/table/cushion,
C fixed speed camera. Anything else on stdin is skipped. Prints the row count.

BEARING (2026-09-17): the orientation of the ROAD the node sits on, 0-179 degrees (undirected, so
north-south is 0 and east-west is 90), or empty when it could not be worked out. It is what tells a
stop sign that holds YOU from the one that holds the side street entering your road: the app keeps a
sign whose road runs the way you are travelling and drops the one across it. Pass the highway ways
as a second geojsonseq file with --ways; without it the column is empty and the app keeps every
sign, exactly as before.

    osmium export filtered.osm.pbf -f geojsonseq -o - | python3 scripts/road_features_tsv.py out.bin --ways ways.geojsonseq
"""
import gzip
import json
import math
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


def bearings_from_ways(path, wanted):
    """Road orientation (0-179) at each wanted (lat, lon) key, from the highway ways' geometry."""
    out = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip().lstrip("\x1e")
            if not line:
                continue
            try:
                ft = json.loads(line)
            except ValueError:
                continue
            g = ft.get("geometry") or {}
            if g.get("type") == "LineString":
                lines = [g["coordinates"]]
            elif g.get("type") == "MultiLineString":
                lines = g["coordinates"]
            else:
                continue
            for coords in lines:
                for i, c in enumerate(coords):
                    key = (round(c[1], 7), round(c[0], 7))
                    if key not in wanted or key in out:
                        continue
                    a = coords[i - 1] if i > 0 else c
                    b = coords[i + 1] if i + 1 < len(coords) else c
                    if a is b or (a[0] == b[0] and a[1] == b[1]):
                        continue
                    dx = (b[0] - a[0]) * math.cos(math.radians(c[1]))
                    dy = b[1] - a[1]
                    out[key] = int(round(math.degrees(math.atan2(dx, dy)))) % 180
    return out


def main():
    out = sys.argv[1]
    ways = None
    if "--ways" in sys.argv:
        ways = sys.argv[sys.argv.index("--ways") + 1]
    n = 0
    kinds = {}
    rows = []
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
        rows.append((lat, lon, k))
        kinds[k] = kinds.get(k, 0) + 1

    bearings = {}
    if ways:
        wanted = {(round(la, 7), round(lo, 7)) for la, lo, _ in rows}
        bearings = bearings_from_ways(ways, wanted)

    with gzip.open(out, "wt", encoding="utf-8", compresslevel=9) as f:
        for la, lo, k in rows:
            b = bearings.get((round(la, 7), round(lo, 7)))
            f.write(f"{la:.6f}\t{lo:.6f}\t{k}\t{b if b is not None else ''}\n")
            n += 1
    print(f"{n} rows {kinds} bearings={len(bearings)}", file=sys.stderr)


if __name__ == "__main__":
    main()
