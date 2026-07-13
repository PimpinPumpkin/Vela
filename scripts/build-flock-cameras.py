#!/usr/bin/env python3
"""Bake the bundled ALPR / Flock surveillance-camera dataset into an app asset.

The cameras are the community DeFlock project's OpenStreetMap nodes
(`surveillance:type=ALPR`). Rather than query Overpass live per viewport (slow,
and it 504s under load), the whole global set is tiny (~124k points), so we bake
it into `app/src/main/assets/flock_cameras.tsv.gz` and query it on-device -
instant to draw and instant/ reliable for the route "passes N cameras" count.

Refresh: re-run this script and commit the regenerated asset. It fetches from an
Overpass mirror (the main instance is often overloaded).

    python3 scripts/build-flock-cameras.py

Format: gzipped TSV, one camera per line - `lat<TAB>lon<TAB>operator`. Simple,
robust to decode, and gzip crushes the repetitive operator column.
"""
import gzip
import json
import os
import sys
import urllib.parse
import urllib.request

QUERY = '[out:json][timeout:600];node["surveillance:type"="ALPR"];out body;'
# Mirrors first: the main overpass-api.de is frequently 504-overloaded on a full-planet query.
ENDPOINTS = [
    "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
]
# Named `.bin` (not `.gz`) on purpose: aapt special-cases `.gz` assets and un-gzips them at build time,
# which stops the app opening the file by its `.gz` name. A neutral extension is left untouched.
ASSETS = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets")
OUT = os.path.join(ASSETS, "flock_cameras.bin")
VER_OUT = os.path.join(ASSETS, "flock_cameras_version.txt")


def fetch() -> dict:
    data = urllib.parse.urlencode({"data": QUERY}).encode()
    last = None
    for ep in ENDPOINTS:
        try:
            print(f"fetching from {ep} ...", file=sys.stderr)
            req = urllib.request.Request(ep, data=data, headers={"User-Agent": "VelaMaps/0.1 (build-flock-cameras)"})
            with urllib.request.urlopen(req, timeout=600) as r:
                return json.load(r)
        except Exception as e:  # noqa: BLE001
            print(f"  failed: {e}", file=sys.stderr)
            last = e
    raise SystemExit(f"all endpoints failed: {last}")


def main() -> None:
    doc = fetch()
    rows = []
    for e in doc.get("elements", []):
        if e.get("type") != "node":
            continue
        lat, lon = e.get("lat"), e.get("lon")
        if lat is None or lon is None:
            continue
        tags = e.get("tags") or {}
        # Real DeFlock nodes tag the vendor as `manufacturer` ("Flock Safety"); `operator` (the agency)
        # is usually absent - fall back to it so the camera still shows who runs it.
        op = (tags.get("operator") or tags.get("manufacturer") or "").strip().replace("\t", " ")
        rows.append((lat, lon, op))
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with gzip.open(OUT, "wt", encoding="utf-8", compresslevel=9) as f:
        for lat, lon, op in rows:
            f.write(f"{lat:.6f}\t{lon:.6f}\t{op}\n")
    # Version stamp (int, newest wins): CI passes FLOCK_VERSION (a run number); locally default to today's
    # date. Written beside the data as the bundled floor's version and read back by the app / CI manifest.
    import datetime
    version = os.environ.get("FLOCK_VERSION") or datetime.date.today().strftime("%Y%m%d")
    with open(VER_OUT, "w", encoding="utf-8") as f:
        f.write(str(int(version)))
    print(f"wrote {len(rows)} cameras -> {OUT} ({os.path.getsize(OUT)} bytes), version {version}")


if __name__ == "__main__":
    main()
