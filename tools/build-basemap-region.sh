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
# to 33 MB in about two minutes on a laptop; Washington is about 200 MB.
set -euo pipefail
ID="$1"; PBF="$2"; OUT="$3"; MAXZOOM="${4:-14}"
JAR="${PLANETILER:-$(dirname "$0")/planetiler.jar}"
WORK="$(mktemp -d)"
if [[ "$PBF" == http* ]]; then
  curl -sSL --retry 5 -o "$WORK/region.osm.pbf" "$PBF"
  PBF="$WORK/region.osm.pbf"
fi
java -Xmx"${PLANETILER_XMX:-6g}" -jar "$JAR" --osm-path="$PBF" --output="$OUT" --download --http-timeout=10m --http-retries=8 --maxzoom="$MAXZOOM" --force >"$WORK/planetiler.log" 2>&1 || { tail -20 "$WORK/planetiler.log"; rm -rf "$WORK"; exit 1; }
rm -rf "$WORK"
echo "wrote $OUT ($(du -h "$OUT" | cut -f1)) region $ID maxzoom $MAXZOOM"
