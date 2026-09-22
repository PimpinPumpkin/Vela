#!/usr/bin/env python3
"""Read a [S, W, N, E] bbox on stdin and print it clamped for the given region id.

Alaska's extract crosses the antimeridian (the Aleutians reach past 180), so every source that
derives its box (the PMTiles header, osmium's header box, Geofabrik's index) reports -180..180,
which the app read as "every point between 49.8 N and 73 N" (issue #257: the Alaska address
overlay claimed Germany and hid the house numbers there). The inhabited state lies west of the
antimeridian, so the box is cut there; nothing addressed sits on the far side.
Usage: python3 scripts/clamp-bbox.py <region-id>  (bbox JSON on stdin, JSON out)"""
import json, sys
rid = sys.argv[1] if len(sys.argv) > 1 else ""
s, w, n, e = json.load(sys.stdin)
if rid == "alaska" and w <= -179 and e >= 179:
    e = -129.9
print("[%s, %s, %s, %s]" % (s, w, n, e))
