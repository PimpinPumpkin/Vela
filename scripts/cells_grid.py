#!/usr/bin/env python3
"""List the grid cells of one catalog region (scripts/build-cells-region.sh).

Usage: cells_grid.py <region-id> '<[S,W,N,E]>' <region_polys.json> <geojson-dir>

Cells are the 0.5 degree tiles of a global grid that the region box touches, clipped to the box.
Key = the tile's SW corner (n38.5w075.5, fixed width so ids sort); cell id = <region>.<key>.
Prints one TSV row per cell: id, S, W, N, E, cut. `cut` says how the places slice is cut:
`region` = the region polygon clipped to the cell (written to <geojson-dir>/<id>.geojson),
`bbox` = the cell box (the region has no polygon), `-` = the polygon misses the cell. The places
archive is baked by box, so without the polygon a border cell would carry the neighbor's places.
"""
import json
import math
import sys

STEP = 0.5  # degrees; the bake doubles it for a region whose box would hold too many cells


def key(lat, lng):
    return "%s%04.1f%s%05.1f" % ("n" if lat >= 0 else "s", abs(lat), "e" if lng >= 0 else "w", abs(lng))


def clip(ring, x0, y0, x1, y1):
    """Sutherland-Hodgman clip of a (lng, lat) ring against a box; None when nothing is left."""
    def cut(pts, inside, meet):
        out = []
        for k, p in enumerate(pts):
            q = pts[k - 1]
            if inside(p):
                if not inside(q):
                    out.append(meet(q, p))
                out.append(p)
            elif inside(q):
                out.append(meet(q, p))
        return out

    def at_x(x):
        return lambda a, b: (x, a[1] + (b[1] - a[1]) * (x - a[0]) / (b[0] - a[0]))

    def at_y(y):
        return lambda a, b: (a[0] + (b[0] - a[0]) * (y - a[1]) / (b[1] - a[1]), y)

    pts = ring
    for inside, meet in ((lambda p: p[0] >= x0, at_x(x0)), (lambda p: p[0] <= x1, at_x(x1)),
                         (lambda p: p[1] >= y0, at_y(y0)), (lambda p: p[1] <= y1, at_y(y1))):
        if not pts:
            break
        pts = cut(pts, inside, meet)
    return pts if len(pts) >= 3 else None


def main():
    global STEP
    rid, (s, w, n, e) = sys.argv[1], json.loads(sys.argv[2])
    polys, outdir = json.load(open(sys.argv[3])), sys.argv[4]
    if len(sys.argv) > 5:
        STEP = float(sys.argv[5])
    # region_polys.json rings are flat [lat, lng, ...]; outer rings only, holes do not matter here
    rings = [[(r[k + 1], r[k]) for k in range(0, len(r), 2)] for r in polys.get(rid, {}).get("o", [])]
    for i in range(math.floor(s / STEP), math.ceil(n / STEP)):
        for j in range(math.floor(w / STEP), math.ceil(e / STEP)):
            cs, cw = i * STEP, j * STEP
            bs, bw, bn, be = max(s, cs), max(w, cw), min(n, cs + STEP), min(e, cw + STEP)
            if bn - bs <= 1e-9 or be - bw <= 1e-9:
                continue
            cid = "%s.%s" % (rid, key(cs, cw))
            how = "bbox"
            if rings:
                parts = [c for c in (clip(r, bw, bs, be, bn) for r in rings) if c]
                how = "region" if parts else "-"
                if not parts:
                    continue  # box tile with no region land in it (ocean, a neighbor): nothing to bake
                if parts:
                    geom = {"type": "MultiPolygon",
                            "coordinates": [[[list(p) for p in c + [c[0]]]] for c in parts]}
                    with open("%s/%s.geojson" % (outdir, cid), "w") as f:
                        json.dump({"type": "Feature", "properties": {}, "geometry": geom}, f)
            print("%s\t%s\t%s\t%s\t%s\t%s" % (cid, round(bs, 6), round(bw, 6), round(bn, 6), round(be, 6), how))


if __name__ == "__main__":
    main()
