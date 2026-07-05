#!/usr/bin/env bash
# Build + publish ONE region's OPEN HOUSE-NUMBER overlay as a PMTiles archive to the `address-overlays`
# GitHub release, merged into address-overlay-manifest.json. The app (VelaMapView) STREAMS it and renders
# the `number` field as a SymbolLayer of house numbers where OSM lacks `addr:housenumber` (new suburbs).
# Sibling of build-overlay-region.sh (which does building FOOTPRINTS); this does ADDRESS POINTS.
#
#   scripts/build-address-region.sh <id> "<Display name>" <openaddresses-source> "<S,W,N,E>"
#   e.g. scripts/build-address-region.sh washington "Washington (state)" us/wa/statewide "45.54,-124.85,49.00,-116.92"
#
# Data: OpenAddresses (openaddresses.io) — address points aggregated from open/government sources, per-source
# licences (open). The batch API resolves the source's CURRENT job (job ids rotate per data refresh), whose
# GeoJSONL output is one Point per line with a `number` (+ `street`, `unit`, `city`, `postcode`) property.
# Needs: gh (authenticated), tippecanoe, jq, curl, gzip. LICENCE note in the release body.
set -euo pipefail

ID="${1:?region id}"; NAME="${2:?display name}"; SRC="${3:?openaddresses source e.g. us/wa/statewide}"; BBOX_CSV="${4:?bbox S,W,N,E}"
REPO="${VELA_REPO:-PimpinPumpkin/Vela}"
TAG="address-overlays"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

# Resolve the source's CURRENT job id (job ids rotate each data refresh, so we can't hardcode a URL).
echo "→ resolving OpenAddresses job for $SRC"
JOB="$(curl -fsSL "https://batch.openaddresses.io/api/data?source=${SRC}&layer=addresses" \
  | jq -r 'map(select(.source=="'"$SRC"'" and .layer=="addresses")) | (max_by(.job).job // empty)')"
[ -n "$JOB" ] || { echo "!! no OpenAddresses address job for source '$SRC'" >&2; exit 1; }
GEOJSON_URL="https://v2.openaddresses.io/batch-prod/job/${JOB}/source.geojson.gz"
echo "→ job $JOB → $GEOJSON_URL"

echo "→ downloading + decompressing → GeoJSONL"
curl -fsSL --retry 4 --retry-delay 2 --retry-all-errors "$GEOJSON_URL" | gzip -dc > "$WORK/$ID.geojsonl"
LINES=$(wc -l < "$WORK/$ID.geojsonl" | tr -d ' ')
[ "$LINES" -gt 0 ] || { echo "!! empty address data for $SRC" >&2; exit 1; }
echo "→ $LINES address points"

# House numbers only need high zoom (rendered at minZoom 16, tiles overzoom above). Keep ONLY the `number`
# attribute (`-y number`) so the tiles stay small; --drop-densest-as-needed bounds a packed downtown tile.
echo "→ tiling with tippecanoe"
tippecanoe -o "$WORK/$ID.pmtiles" -l address -n "Vela address overlay: $NAME" \
  -Z14 -z16 -y number --drop-densest-as-needed --extend-zooms-if-still-dropping -P \
  --attribution "Addresses © OpenAddresses contributors" --force "$WORK/$ID.geojsonl"

SIZE=$(( ( $(stat -f%z "$WORK/$ID.pmtiles" 2>/dev/null || stat -c%s "$WORK/$ID.pmtiles") + 1048575 ) / 1048576 ))
BBOX="[$(echo "$BBOX_CSV" | tr -d ' ')]"
ASSET_URL="https://github.com/$REPO/releases/download/$TAG/$ID.pmtiles"
echo "→ $ID: ${SIZE} MB, bbox $BBOX"

gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1 || \
  gh release create "$TAG" --repo "$REPO" --prerelease --title "Open address (house-number) overlays" \
    --notes "OpenAddresses (openaddresses.io) address points as PMTiles for Vela's house-number labels, rendered where OSM lacks addr:housenumber. Data assets, not a code release. Addresses © OpenAddresses contributors, per-source open licences."

gh release upload "$TAG" "$WORK/$ID.pmtiles" --clobber --repo "$REPO"

ENTRY="$(jq -nc --arg id "$ID" --arg name "$NAME" --arg url "$ASSET_URL" --argjson size "$SIZE" --argjson bbox "$BBOX" \
  '{id:$id,name:$name,url:$url,sizeMb:$size,bbox:$bbox}')"

# emit (CI matrix) vs merge (local single-region) — identical to build-overlay-region.sh.
if [ "${MANIFEST_MODE:-merge}" = "emit" ]; then
  printf '%s\n' "$ENTRY" > "${ENTRY_OUT:?set ENTRY_OUT in emit mode}"
  echo "✓ built $ID, pmtiles uploaded, entry → $ENTRY_OUT (manifest merged separately)"
else
  gh release download "$TAG" --repo "$REPO" -p address-overlay-manifest.json -O "$WORK/m.json" 2>/dev/null \
    || echo '{"regions":[]}' > "$WORK/m.json"
  jq --argjson entry "$ENTRY" \
    '.regions = ([.regions[] | select(.id != ($entry.id))] + [$entry])' \
    "$WORK/m.json" > "$WORK/address-overlay-manifest.json"
  gh release upload "$TAG" "$WORK/address-overlay-manifest.json" --clobber --repo "$REPO"
  echo "✓ published $ID"
fi
