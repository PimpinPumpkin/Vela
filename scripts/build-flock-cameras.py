#!/usr/bin/env python3
"""Bake the bundled ALPR / Flock surveillance-camera dataset into an app asset.

The cameras are the community DeFlock project's OpenStreetMap nodes
(`surveillance:type=ALPR`). Rather than query Overpass live per viewport (slow,
and it 504s under load), the whole global set is tiny (~124k points), so we bake
it into `app/src/main/assets/flock_cameras.tsv.gz` and query it on-device -
instant to draw and instant/ reliable for the route "passes N cameras" count.

Refresh: re-run this script and commit the regenerated asset. It fetches from an
Overpass mirror (the main instance is often overloaded), two rounds over the three servers
ten minutes apart. Exit 3 means no usable answer (every server failed, or fewer rows than
FLOCK_MIN_COUNT); the weekly workflow then keeps the published file.

    python3 scripts/build-flock-cameras.py

Format: gzipped TSV, one camera per line - `lat<TAB>lon<TAB>operator<TAB>direction`. The
direction column (degrees clockwise from north the camera FACES, from OSM `direction` /
`camera:direction`, cardinal names normalized; empty when untagged) was added 2026-07-21 -
the app reads 3-column files too, so old bundled/hosted datasets keep decoding. Simple,
robust to decode, and gzip crushes the repetitive operator column.
"""
import csv
import gzip
import io
import os
import sys
import time
import urllib.parse
import urllib.request

# CSV, not JSON: the same 157,576 cameras are 7 MB against 59 MB (2026-11), and the JSON reply ran
# past the mirrors' gateway timeouts. Columns: lat, lon, operator, manufacturer, direction,
# camera:direction, tab separated, no header.
QUERY = ('[out:csv(::lat,::lon,operator,manufacturer,direction,"camera:direction";false)][timeout:600];'
         'node["surveillance:type"="ALPR"];out;')
COLUMNS = 6
# Mirrors first: the main overpass-api.de is frequently 504-overloaded on a full-planet query.
ENDPOINTS = [
    "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
]
ROUNDS = int(os.environ.get("FLOCK_ROUNDS", "2"))
PAUSE_S = int(os.environ.get("FLOCK_PAUSE_S", "600"))
# Exit code for "Overpass gave nothing usable": the workflow keeps the published file and warns.
UPSTREAM_FAILED = 3
# Named `.bin` (not `.gz`) on purpose: aapt special-cases `.gz` assets and un-gzips them at build time,
# which stops the app opening the file by its `.gz` name. A neutral extension is left untouched.
ASSETS = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets")
OUT = os.path.join(ASSETS, "flock_cameras.bin")
VER_OUT = os.path.join(ASSETS, "flock_cameras_version.txt")


def fetch() -> str:
    data = urllib.parse.urlencode({"data": QUERY}).encode()
    last = None
    for round_no in range(ROUNDS):
        if round_no:
            print(f"every endpoint failed; trying again in {PAUSE_S} s", file=sys.stderr)
            time.sleep(PAUSE_S)
        for ep in ENDPOINTS:
            try:
                print(f"fetching from {ep} ...", file=sys.stderr)
                req = urllib.request.Request(ep, data=data, headers={"User-Agent": "VelaMaps/0.1 (build-flock-cameras)"})
                with urllib.request.urlopen(req, timeout=600) as r:
                    return r.read().decode("utf-8")
            except Exception as e:  # noqa: BLE001
                print(f"  failed: {e}", file=sys.stderr)
                last = e
    print(f"all endpoints failed: {last}", file=sys.stderr)
    sys.exit(UPSTREAM_FAILED)


def parse(text: str) -> list:
    """Rows of (lat, lon, operator, direction). A line that is not six columns with a numeric
    position is skipped, so error text in a reply never becomes a camera."""
    rows = []
    # Overpass quotes a value holding a comma or a quote ("Neology, Inc.") and doubles inner quotes.
    for cols in csv.reader(io.StringIO(text), delimiter="\t", quotechar='"'):
        if len(cols) != COLUMNS:
            continue
        try:
            lat, lon = float(cols[0]), float(cols[1])
        except ValueError:
            continue
        operator, manufacturer, direction, camera_direction = (c.strip().replace("\t", " ") for c in cols[2:])
        # Real DeFlock nodes tag the vendor as `manufacturer` ("Flock Safety"); `operator` (the agency)
        # is usually absent - fall back to it so the camera still shows who runs it.
        rows.append((lat, lon, operator or manufacturer, norm_direction(direction or camera_direction)))
    return rows


# 16-wind cardinal names -> degrees; OSM also allows plain degrees and semicolon lists
# (multi-head poles) - keep the FIRST head, empty when nothing parses.
_CARDINAL = {
    "N": 0, "NNE": 22, "NE": 45, "ENE": 67, "E": 90, "ESE": 112, "SE": 135, "SSE": 157,
    "S": 180, "SSW": 202, "SW": 225, "WSW": 247, "W": 270, "WNW": 292, "NW": 315, "NNW": 337,
    "NORTH": 0, "EAST": 90, "SOUTH": 180, "WEST": 270,
}


def norm_direction(raw):
    v = (raw or "").strip().split(";")[0].strip().upper()
    if not v:
        return ""
    if v in _CARDINAL:
        return str(_CARDINAL[v])
    try:
        return str(int(round(float(v))) % 360)
    except ValueError:
        return ""


def main() -> None:
    rows = parse(fetch())
    # FLOCK_MIN_COUNT (the workflow passes 90% of the published count): a reply cut short by an
    # overloaded server must not replace the published file with part of it.
    floor = int(os.environ.get("FLOCK_MIN_COUNT") or 0)
    if len(rows) < floor:
        print(f"only {len(rows)} cameras in the reply, under the floor of {floor}", file=sys.stderr)
        sys.exit(UPSTREAM_FAILED)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with gzip.open(OUT, "wt", encoding="utf-8", compresslevel=9) as f:
        for lat, lon, op, d in rows:
            f.write(f"{lat:.6f}\t{lon:.6f}\t{op}\t{d}\n")
    # Version stamp (int, newest wins): CI passes FLOCK_VERSION (a run number); locally default to today's
    # date. Written beside the data as the bundled floor's version and read back by the app / CI manifest.
    import datetime
    version = os.environ.get("FLOCK_VERSION") or datetime.date.today().strftime("%Y%m%d")
    with open(VER_OUT, "w", encoding="utf-8") as f:
        f.write(str(int(version)))
    print(f"wrote {len(rows)} cameras -> {OUT} ({os.path.getsize(OUT)} bytes), version {version}")


if __name__ == "__main__":
    main()
