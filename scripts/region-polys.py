#!/usr/bin/env python3
"""Bake the catalog's real boundaries into an app asset (issue #599).

A region's bounding box is not its coverage: Vietnam's Geofabrik extract carries the island claims,
so its box reaches 114.6 E and swallows Hong Kong, and "download the area you're viewing" from
Hong Kong announced "Downloading Vietnam". Geofabrik publishes the polygon every extract was cut
with, next to the pbf (`<name>.poly`), so this fetches one per catalog row, simplifies it, and
writes them all as ONE compact JSON the app ships in assets/region_polys.json. The app tests a point
against the polygon when it has one and falls back to the box otherwise, so a region missing here
behaves exactly as before.

Usage: scripts/region-polys.py [tools/routing-regions.json] [app/src/main/assets/region_polys.json]
"""
import json, math, sys, urllib.request, concurrent.futures as cf

CATALOG = sys.argv[1] if len(sys.argv) > 1 else "tools/routing-regions.json"
OUT = sys.argv[2] if len(sys.argv) > 2 else "app/src/main/assets/region_polys.json"
TOL_DEG = 0.05   # ~5 km; the pick decides which country a phone is in, not where a border runs
MIN_PTS = 4

def parse_poly(text):
    """Geofabrik .poly: name line, then sections (a label line, 'lon lat' lines, END), final END.
    A label starting with '!' is a hole. Returns (outer rings, holes) as lists of (lat, lng)."""
    outers, holes = [], []
    cur, hole = None, False
    for i, line in enumerate(text.splitlines()):
        s = line.strip()
        if i == 0 or not s:
            continue
        if s == "END":
            if cur is not None:
                (holes if hole else outers).append(cur)
                cur = None
            continue
        parts = s.split()
        if len(parts) == 2 and cur is not None:
            try:
                lng, lat = float(parts[0]), float(parts[1])
                cur.append((lat, lng))
                continue
            except ValueError:
                pass
        # a section label
        cur, hole = [], s.startswith("!")
    return outers, holes

def dp(points, tol):
    """Douglas-Peucker on a closed ring (kept closed by the caller)."""
    if len(points) <= 2:
        return points
    def dist(p, a, b):
        (y, x), (ay, ax), (by, bx) = p, a, b
        dx, dy = bx - ax, by - ay
        if dx == 0 and dy == 0:
            return math.hypot(x - ax, y - ay)
        t = max(0.0, min(1.0, ((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy)))
        return math.hypot(x - (ax + t * dx), y - (ay + t * dy))
    a, b = points[0], points[-1]
    idx, dmax = 0, 0.0
    for i in range(1, len(points) - 1):
        d = dist(points[i], a, b)
        if d > dmax:
            idx, dmax = i, d
    if dmax > tol:
        return dp(points[: idx + 1], tol)[:-1] + dp(points[idx:], tol)
    return [a, b]

def simplify_ring(ring, tol):
    if len(ring) < MIN_PTS:
        return ring
    if ring[0] != ring[-1]:
        ring = ring + [ring[0]]
    # split at the farthest point so DP has two anchors on a closed ring
    far = max(range(len(ring)), key=lambda i: math.hypot(ring[i][0] - ring[0][0], ring[i][1] - ring[0][1]))
    a = dp(ring[: far + 1], tol)
    b = dp(ring[far:], tol)
    out = a[:-1] + b[:-1]
    return out if len(out) >= 3 else ring[:-1]

def fetch(row):
    url = row["pbf_url"].replace("-latest.osm.pbf", ".poly")
    text, err = None, None
    for attempt in range(3):  # Geofabrik's DNS drops a request now and then; a miss is a hole in the pick
        try:
            with urllib.request.urlopen(url, timeout=60) as r:
                text = r.read().decode("utf-8", "replace")
            break
        except Exception as e:
            err = e
    if text is None:
        return row["id"], None, f"{url}: {err}"
    outers, holes = parse_poly(text)
    if not outers:
        return row["id"], None, f"{url}: no rings"
    rings = [simplify_ring(o, TOL_DEG) for o in outers]
    hs = [simplify_ring(h, TOL_DEG) for h in holes]
    # flat [lat, lng, lat, lng, ...] per ring, 4 decimals (~10 m), holes carried separately
    flat = lambda r: [round(v, 3) for p in r for v in p]
    entry = {"o": [flat(r) for r in rings]}
    if hs:
        entry["h"] = [flat(r) for r in hs]
    return row["id"], entry, None

def main():
    cat = json.load(open(CATALOG))
    rows = cat if isinstance(cat, list) else cat["regions"]
    out, errs = {}, []
    with cf.ThreadPoolExecutor(12) as ex:
        for rid, entry, err in ex.map(fetch, rows):
            if err:
                errs.append(err)
            else:
                out[rid] = entry
    json.dump(out, open(OUT, "w"), separators=(",", ":"))
    pts = sum(len(r) // 2 for e in out.values() for r in e["o"])
    print(f"{len(out)} of {len(rows)} regions, {pts} outer points, {sum(1 for e in out.values() if 'h' in e)} with holes -> {OUT}")
    for e in errs:
        print("MISSING", e)

if __name__ == "__main__":
    main()
