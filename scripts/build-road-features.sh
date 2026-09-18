#!/usr/bin/env bash
# Build + publish ONE region's ROAD FEATURES file (traffic lights, stop signs, level crossings,
# speed humps, fixed speed cameras) to the `road-features` GitHub release, and emit/merge its
# manifest entry. These used to be live Overpass queries from every phone at street zoom and along
# every driven route (issue #304); now they come from the same Geofabrik extract the place packs
# use, baked once per region on CI, and the app downloads the small file for the region it is in.
#
#   scripts/build-road-features.sh <id> "<Display name>" <geofabrik .osm.pbf URL>
#
# Output: gzipped TSV, one node per line, `lat<TAB>lon<TAB>kind` where kind is one letter:
#   S traffic signal, T stop sign, R level crossing, H speed hump/bump/table/cushion, C speed camera.
# Named `.bin` on purpose (aapt un-gzips `.gz` assets; the app reads these from filesDir, but one
# convention for every baked dataset). Needs: gh (authenticated), osmium-tool, jq, python3.
set -euo pipefail

ID="${1:?region id}"; NAME="${2:?display name}"; URL="${3:?geofabrik pbf url}"
REPO="${VELA_REPO:-PimpinPumpkin/Vela}"
TAG="road-features"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

echo "→ downloading $URL"
curl -fsSL "$URL" -o "$WORK/region.osm.pbf"

# [S,W,N,E] from the declared extract region (header.boxes), same rule as every other bake.
read -r MINLON MINLAT MAXLON MAXLAT < <(osmium fileinfo -g header.boxes "$WORK/region.osm.pbf" | tr -d '()' | tr ',' ' ')
BBOX="[$MINLAT,$MINLON,$MAXLAT,$MAXLON]"

echo "→ filtering road-feature nodes"
osmium tags-filter "$WORK/region.osm.pbf" \
  n/highway=traffic_signals,stop,speed_camera \
  n/railway=level_crossing \
  n/traffic_calming=bump,hump,table,cushion \
  -o "$WORK/filtered.osm.pbf" --overwrite

# The HIGHWAY ways, for the orientation of the road each control sits on (the app uses it to tell a
# stop sign that holds you from the one that holds the side street). A failure here is not fatal:
# the column comes out empty and the app keeps every sign, as it did before.
echo "→ exporting highway ways (for road bearings)"
osmium tags-filter "$WORK/region.osm.pbf" w/highway -o "$WORK/ways.osm.pbf" --overwrite 2>/dev/null || true
WAYS_ARG=""
if [ -s "$WORK/ways.osm.pbf" ] && osmium export "$WORK/ways.osm.pbf" -f geojsonseq -o "$WORK/ways.geojsonseq" --overwrite 2>/dev/null; then
  WAYS_ARG="--ways $WORK/ways.geojsonseq"
else
  echo "  (no way geometry; bearings will be empty)"
fi
rm -f "$WORK/ways.osm.pbf"

echo "→ exporting → TSV"
osmium export "$WORK/filtered.osm.pbf" -f geojsonseq -o - \
  | python3 "$ROOT/scripts/road_features_tsv.py" "$WORK/$ID.bin" $WAYS_ARG
COUNT=$(gzip -dc "$WORK/$ID.bin" | wc -l | tr -d ' ')
rm -f "$WORK/filtered.osm.pbf" "$WORK/region.osm.pbf" "$WORK/ways.geojsonseq"
SIZE_KB=$(( ( $(stat -f%z "$WORK/$ID.bin" 2>/dev/null || stat -c%s "$WORK/$ID.bin") + 1023 ) / 1024 ))
ASSET_URL="https://github.com/$REPO/releases/download/$TAG/$ID.bin"
UPDATED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "→ $ID: $COUNT features, ${SIZE_KB} KB, bbox $BBOX"

gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1 || \
  gh release create "$TAG" --repo "$REPO" --prerelease --title "Road features" \
    --notes "Baked per-region road features (traffic lights, stop signs, level crossings, speed humps, speed cameras) from OpenStreetMap (ODbL) for on-device use. INFRA release - do not delete."
gh release upload "$TAG" "$WORK/$ID.bin" --clobber --repo "$REPO"

ENTRY="$(jq -nc --arg id "$ID" --arg name "$NAME" --arg url "$ASSET_URL" --argjson size "$SIZE_KB" --argjson bbox "$BBOX" \
  --argjson count "$COUNT" --arg updated "$UPDATED_AT" \
  '{id:$id,name:$name,url:$url,sizeKb:$size,bbox:$bbox,count:$count,updatedAt:$updated}')"

if [ "${MANIFEST_MODE:-merge}" = "emit" ]; then
  printf '%s\n' "$ENTRY" > "${ENTRY_OUT:?set ENTRY_OUT in emit mode}"
  echo "✓ built $ID, file uploaded, entry → $ENTRY_OUT (manifest merged separately)"
else
  gh release download "$TAG" --repo "$REPO" -p road-features-manifest.json -O "$WORK/manifest.json" 2>/dev/null \
    || echo '{"regions":[]}' > "$WORK/manifest.json"
  jq --argjson entry "$ENTRY" \
    '.regions = ([.regions[] | select(.id != ($entry.id))] + [$entry])' \
    "$WORK/manifest.json" > "$WORK/road-features-manifest.json"
  gh release upload "$TAG" "$WORK/road-features-manifest.json" --clobber --repo "$REPO"
  echo "✓ published $ID"
fi
