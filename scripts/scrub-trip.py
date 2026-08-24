#!/usr/bin/env python3
"""Trim the private ends off a recorded Vela trip so it can be shared.

A trip CSV is raw GPS. Its first and last fixes are, almost always, exactly the
places you would least like to publish - home, work, a friend's house. But the
MIDDLE of the drive is what a nav bug lives in, and that part is just roads.

So this TRIMS rather than blurs: every fix within a radius of the start and of
the end is removed outright, and everything between is left at full precision.
Rounding coordinates instead would protect the endpoints only weakly (a 1 km
round still names your block) while destroying the geometry the trip was
recorded to diagnose. Trimming gives up nothing that matters and gives away
nothing that does.

The route line (RP) and the maneuvers (M) are dropped too when they are inside
a trimmed zone: a polyline that starts at your driveway leaks the same fact the
fixes would have.

    scripts/scrub-trip.py drive.csv                 -> drive.scrubbed.csv
    scripts/scrub-trip.py drive.csv -r 800          -> a wider trim
    scripts/scrub-trip.py drive.csv --at 47.1,-122.3  -> also trim around a place

Verify what it did before sharing: it prints how many fixes went and how much
distance was removed from each end.
"""
import argparse, math, os, sys

def hav(a, b):
    R = 6371000.0
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dp = p2 - p1
    dl = math.radians(b[1] - a[1])
    h = math.sin(dp/2)**2 + math.cos(p1)*math.cos(p2)*math.sin(dl/2)**2
    return 2*R*math.asin(min(1.0, math.sqrt(h)))

def parse_fix(line):
    """A fix line is `lat,lng,t,...`; every other kind starts with a tag word."""
    parts = line.split(",")
    if len(parts) < 3:
        return None
    try:
        lat, lng = float(parts[0]), float(parts[1])
    except ValueError:
        return None
    if not (-90 <= lat <= 90 and -180 <= lng <= 180):
        return None
    return (lat, lng)

def decode_polyline(s):
    pts, i, lat, lng = [], 0, 0, 0
    while i < len(s):
        for first in (True, False):
            shift, result = 0, 0
            while i < len(s):
                b = ord(s[i]) - 63; i += 1
                result |= (b & 0x1f) << shift; shift += 5
                if b < 0x20: break
            d = ~(result >> 1) if result & 1 else result >> 1
            if first: lat += d
            else: lng += d
        pts.append((lat / 1e5, lng / 1e5))
    return pts

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("trip")
    ap.add_argument("-r", "--radius", type=float, default=400.0,
                    help="metres to trim around each end (default 400)")
    ap.add_argument("--at", action="append", default=[],
                    help="also trim around lat,lng (repeatable) - e.g. work, a friend's place")
    ap.add_argument("-o", "--out")
    args = ap.parse_args()

    lines = open(args.trip).read().splitlines()
    fixes = [(i, p) for i, l in enumerate(lines) if (p := parse_fix(l))]
    if not fixes:
        sys.exit("no GPS fixes found - is this a Vela trip CSV?")

    zones = [fixes[0][1], fixes[-1][1]]
    # The META header carries the DESTINATION coordinate, which on a drive home is precisely the
    # address being protected - and it can sit outside the radius around the last FIX (recording
    # stops at the curb, or early). It is both a zone and something to strip outright.
    for line in lines:
        if line.startswith("META,"):
            parts = line.split(",")
            if len(parts) >= 5:
                try:
                    zones.append((float(parts[3]), float(parts[4])))
                except ValueError:
                    pass
    for extra in args.at:
        lat, lng = (float(x) for x in extra.split(","))
        zones.append((lat, lng))

    def private(p):
        return any(hav(p, z) <= args.radius for z in zones)

    kept, dropped_idx = [], set()
    for i, p in fixes:
        if private(p):
            dropped_idx.add(i)
        else:
            kept.append((i, p))
    if not kept:
        sys.exit(f"every fix is within {args.radius:.0f} m of an end - the whole drive is "
                 f"inside the trim zone; use a smaller radius or do not share this one")

    out = []
    for i, line in enumerate(lines):
        if i in dropped_idx:
            continue
        tag = line.split(",", 1)[0]
        if tag == "META":
            # Keep the label and start time (useful context), drop the destination coordinate.
            parts = line.split(",")
            out.append(",".join(parts[:3]) + ",,")
            continue
        if tag == "RP":
            # The route line starts at the origin, so trim its ends the same way.
            body = line.split(",", 1)[1] if "," in line else ""
            pts = [p for p in decode_polyline(body) if not private(p)]
            if pts:
                out.append("# RP removed by scrub-trip.py (route line spans a trimmed area)")
            continue
        if tag == "M":
            parts = line.split(",")
            try:
                mp = (float(parts[2]), float(parts[3]))
            except (IndexError, ValueError):
                out.append(line); continue
            if private(mp):
                continue
        out.append(line)

    dest = args.out or os.path.splitext(args.trip)[0] + ".scrubbed.csv"
    open(dest, "w").write("\n".join(out) + "\n")

    head = hav(fixes[0][1], kept[0][1])
    tail = hav(fixes[-1][1], kept[-1][1])
    print(f"wrote {dest}")
    print(f"  fixes: {len(fixes)} -> {len(kept)}  ({len(dropped_idx)} removed)")
    print(f"  trimmed {head:.0f} m off the start and {tail:.0f} m off the end")
    print(f"  route line and maneuvers inside the trimmed areas were dropped too")
    print(f"  REMAINING first fix: {kept[0][1][0]:.5f},{kept[0][1][1]:.5f} - check it is not "
          f"somewhere that identifies you before sharing")

if __name__ == "__main__":
    main()
