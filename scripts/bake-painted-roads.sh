#!/usr/bin/env bash
# Painted-roads developer test: bake one Geofabrik region into a PMTiles of road markings and
# publish it on the `painted-roads` release. Usage: bake-painted-roads.sh <region.osm.pbf> <name> [--upload]
# Needs osmium, tippecanoe and the repo's Gradle. Output: painted-<name>.pmtiles beside the input.
set -euo pipefail
PBF="$1"; NAME="$2"; UPLOAD="${3:-}"
DIR="$(cd "$(dirname "$PBF")" && pwd)"; REPO="$(cd "$(dirname "$0")/.." && pwd)"
osmium tags-filter "$PBF" \
  w/highway=motorway,trunk,primary,secondary,tertiary,unclassified,residential,living_street,motorway_link,trunk_link,primary_link,secondary_link,tertiary_link \
  w/footway=crossing w/cycleway=crossing n/highway=stop,traffic_signals,crossing \
  -o "$DIR/paint-roads.pbf" --overwrite
osmium export "$DIR/paint-roads.pbf" -f geojsonseq --geometry-types=point,linestring -o "$DIR/paint-roads.geojsonseq" --overwrite
(cd "$REPO" && ./gradlew -q :core:testDebugUnitTest --tests '*PaintedRoadsBakeTest' \
  -DvelaPaintIn="$DIR/paint-roads.geojsonseq" -DvelaPaintOut="$DIR/paint-marks.geojsonseq" --rerun-tasks)
grep -o 'PAINTBAKE[^<]*' "$REPO/core/build/test-results/testDebugUnitTest/TEST-app.vela.core.data.PaintedRoadsBakeTest.xml" || true
tippecanoe -o "$DIR/painted-$NAME.pmtiles" -l paint -z15 -Z15 -P -b 24 --no-feature-limit --no-tile-size-limit \
  --no-line-simplification --force --quiet "$DIR/paint-marks.geojsonseq"
ls -la "$DIR/painted-$NAME.pmtiles"
if [ "$UPLOAD" = "--upload" ]; then
  gh release view painted-roads >/dev/null 2>&1 || gh release create painted-roads --prerelease \
    --target "$(git -C "$REPO" rev-list --max-parents=0 HEAD | tail -1)" --title "Painted roads (developer test data)" \
    --notes "Road markings baked from OpenStreetMap (ODbL) for the painted-roads developer test."
  gh release upload painted-roads "$DIR/painted-$NAME.pmtiles" --clobber
fi
