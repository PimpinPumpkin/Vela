#!/usr/bin/env bash
# Bake the offline basemap for one region: a Geofabrik OSM extract -> OpenMapTiles-schema vector
# tiles in a PMTiles archive, with planetiler. The same schema OpenFreeMap serves online, so the
# app draws it with the same Liberty style, and the same extract the obf routing bake uses.
#
#   tools/build-basemap-region.sh <id> <pbf-url-or-path> <out.pmtiles> [maxzoom]
#
# Needs Java 21+ and planetiler.jar next to this script (or PLANETILER=/path/to/planetiler.jar).
# planetiler downloads its base data (Natural Earth, water polygons, lake centerlines, about
# 1.2 GB) into data/sources/ on the first run; cache that directory in CI. Saarland at z14 bakes
# to 33 MB in about two minutes on a laptop; a large US state is about 200 MB.
#
# ⚠️ --bounds is NOT optional. Geofabrik's PBF HEADER bbox can be far looser than the extract
# polygon (new-mexico shipped a header reaching ~7 degrees into Texas), and planetiler inherits
# it into the PMTiles header - the manifest copies that header, the app's installedFor() mounts
# the archive for views it does not cover, and the map draws NOTHING there. Always pin the bake
# to the extract's own polygon bbox from Geofabrik's index. (Found 2026-09-17: a phone with the
# new-mexico basemap showed no vector map at all in the DFW area, because the archive claimed to
# cover it and had no tiles there.)
set -euo pipefail
ID="$1"; PBF="$2"; OUT="$3"; MAXZOOM="${4:-14}"
JAR="${PLANETILER:-$(dirname "$0")/planetiler.jar}"
WORK="$(mktemp -d)"
if [[ "$PBF" == http* ]]; then
  curl -sSL --retry 5 -o "$WORK/region.osm.pbf" "$PBF"
  PBF="$WORK/region.osm.pbf"
fi
# True bounds from Geofabrik's index: the id is the pbf_url path without the -latest suffix
# ("north-america/us/new-mexico"); a local file or unknown id can only warn.
BOUNDS=""
if [[ "${2:-}" == http* || "${BASEMAP_GEOFABRIK_SLUG:-}" != "" ]]; then
  SLUG="${BASEMAP_GEOFABRIK_SLUG:-}"
  if [[ -z "$SLUG" ]]; then
    SLUG="${2#*download.geofabrik.de/}"; SLUG="${SLUG#download.geofabrik.de/}"
    SLUG="${SLUG%-latest.osm.pbf}"; SLUG="${SLUG%.osm.pbf}"
  fi
  # `|| true`: a failed fetch, a parse error or a row Geofabrik ships WITHOUT geometry must not
  # kill the bake - the script falls through to the warning below and bakes with the header bbox,
  # which is what it did before this check existed. Under `set -euo pipefail` an unguarded
  # substitution exits the script instead (11 catalog rows ship an EMPTY MultiPolygon:
  # afghanistan, armenia, azerbaijan, bhutan, israel-and-palestine, jordan, kazakhstan, lebanon,
  # mongolia, tajikistan, turkmenistan).
  BOUNDS=$(curl -sSL --retry 5 https://download.geofabrik.de/index-v1.json | python3 -c '
import json, sys
path = sys.argv[1]  # e.g. north-america/us/new-mexico (URL path minus the file suffix)
d = json.load(sys.stdin)
by_id = {f["properties"]["id"]: f for f in d["features"]}
# Index ids do NOT carry the continent component ("us/new-mexico", and some rows are bare
# slugs), so try the path suffixes longest-first until one matches.
parts = path.split("/")
feature = None
for k in range(len(parts), 0, -1):
    feature = by_id.get("/".join(parts[-k:]))
    if feature: break
if feature and feature.get("geometry", {}).get("coordinates"):
    g = feature["geometry"]
    polys = g["coordinates"] if g["type"] == "MultiPolygon" else [g["coordinates"]]
    xs = [p[0] for poly in polys for ring in poly for p in ring]
    ys = [p[1] for poly in polys for ring in poly for p in ring]
    if not xs:
        sys.exit(0)   # an empty geometry is not an error, it is a row with no polygon
    # planetiler --bounds is lon,lat,lon,lat = W,S,E,N (Arguments.bounds constructs
    # Envelope(v0, v2, v1, v3); JTS 4-arg Envelope is x1,x2,y1,y2).
    print(f"{min(xs):.5f},{min(ys):.5f},{max(xs):.5f},{max(ys):.5f}")
' "$SLUG" || true)
fi
if [[ -n "$BOUNDS" ]]; then
  echo "region $ID bounds (W,S,E,N): $BOUNDS"
  BOUNDS_ARG="--bounds=$BOUNDS"
else
  echo "WARNING: no Geofabrik index bbox for $ID - planetiler will inherit the PBF header bbox, which may claim more ground than the archive holds" >&2
  BOUNDS_ARG=""
fi
java -Xmx"${PLANETILER_XMX:-6g}" -jar "$JAR" --osm-path="$PBF" --output="$OUT" --download --http-timeout=10m --http-retries=8 --maxzoom="$MAXZOOM" $BOUNDS_ARG --force >"$WORK/planetiler.log" 2>&1 || { tail -20 "$WORK/planetiler.log"; rm -rf "$WORK"; exit 1; }
rm -rf "$WORK"
echo "wrote $OUT ($(du -h "$OUT" | cut -f1)) region $ID maxzoom $MAXZOOM bounds ${BOUNDS:-<pbf-header>}"
