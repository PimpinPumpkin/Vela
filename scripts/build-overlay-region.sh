#!/usr/bin/env bash
# Build + publish ONE US state's OPEN BUILDING-FOOTPRINT overlay (Microsoft US Building Footprints,
# ODbL) as a PMTiles archive to the `building-overlays` GitHub release, merged into
# building-overlay-manifest.json. The app (OverlayTileStore) downloads it and renders the footprints
# BENEATH the OSM building layer, filling gaps where OSM lacks buildings (suburbs the Microsoft→OSM
# import never reached, e.g. Snohomish County / Silver Firs). Sibling of build-routing-region.sh.
#
#   scripts/build-overlay-region.sh <id> "<Display name>" <MS .geojson.zip URL> "<S,W,N,E>"
#   e.g. scripts/build-overlay-region.sh washington "Washington (state)" \
#          https://minedbuildings.z5.web.core.windows.net/legacy/usbuildings-v2/Washington.geojson.zip \
#          "45.54,-124.85,49.00,-116.92"
#
# Needs: gh (authenticated), tippecanoe, jq, unzip, curl. LICENSE: Microsoft US Building Footprints is
# ODbL — a DATA licence orthogonal to the app's GPLv3 (same as the OSM tiles). Obligation met by the
# tippecanoe --attribution below (shown in-app) + this release publishing the derived tiles under ODbL.
set -euo pipefail

ID="${1:?region id}"; NAME="${2:?display name}"; URL="${3:?Microsoft .geojson.zip URL}"; BBOX_CSV="${4:?bbox S,W,N,E}"
REPO="${VELA_REPO:-PimpinPumpkin/Vela}"
TAG="building-overlays"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

echo "→ downloading $URL"
curl -fsSL "$URL" -o "$WORK/b.zip"
unzip -q "$WORK/b.zip" -d "$WORK/geo"
GEOJSON="$(find "$WORK/geo" -iname '*.geojson' | head -1)"
[ -n "$GEOJSON" ] || { echo "!! no .geojson found in $URL" >&2; exit 1; }

# Footprints render from neighbourhood zoom (z12) to street level (z16 — overzoomed above). Buildings are
# dense, so drop the densest to keep tiles small; no size cap so a packed downtown tile isn't truncated.
echo "→ tiling with tippecanoe"
tippecanoe -o "$WORK/$ID.pmtiles" -l building -n "Vela building overlay: $NAME" \
  -Z12 -z16 --drop-densest-as-needed --extend-zooms-if-still-dropping --no-tile-size-limit \
  --attribution "Buildings © Microsoft (ODbL)" --force "$GEOJSON"

SIZE=$(( ( $(stat -f%z "$WORK/$ID.pmtiles" 2>/dev/null || stat -c%s "$WORK/$ID.pmtiles") + 1048575 ) / 1048576 ))
BBOX="[$(echo "$BBOX_CSV" | tr -d ' ')]" # [S,W,N,E], same shape the routing manifest + picker use
ASSET_URL="https://github.com/$REPO/releases/download/$TAG/$ID.pmtiles"
echo "→ $ID: ${SIZE} MB, bbox $BBOX"

# The overlay catalog release — prerelease so it never becomes the "Latest" the APK auto-tracks.
gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1 || \
  gh release create "$TAG" --repo "$REPO" --prerelease --title "Open building overlays" \
    --notes "Microsoft US Building Footprints (ODbL) as PMTiles for Vela's gap-fill building overlay. Data assets, not a code release. Buildings © Microsoft, licensed under ODbL (opendatacommons.org/licenses/odbl/1-0)."

gh release upload "$TAG" "$WORK/$ID.pmtiles" --clobber --repo "$REPO"

ENTRY="$(jq -nc --arg id "$ID" --arg name "$NAME" --arg url "$ASSET_URL" --argjson size "$SIZE" --argjson bbox "$BBOX" \
  '{id:$id,name:$name,url:$url,sizeMb:$size,bbox:$bbox}')"

# MANIFEST_MODE=emit (CI matrix): drop the entry to $ENTRY_OUT and stop — the merge is centralised in one
# job (merge-overlay-manifest.sh) so parallel region builds can't clobber the manifest. Default (local
# single-region): read-modify-write the manifest here.
if [ "${MANIFEST_MODE:-merge}" = "emit" ]; then
  printf '%s\n' "$ENTRY" > "${ENTRY_OUT:?set ENTRY_OUT in emit mode}"
  echo "✓ built $ID, pmtiles uploaded, entry → $ENTRY_OUT (manifest merged separately)"
else
  gh release download "$TAG" --repo "$REPO" -p building-overlay-manifest.json -O "$WORK/m.json" 2>/dev/null \
    || echo '{"regions":[]}' > "$WORK/m.json"
  jq --argjson entry "$ENTRY" \
    '.regions = ([.regions[] | select(.id != ($entry.id))] + [$entry])' \
    "$WORK/m.json" > "$WORK/building-overlay-manifest.json"
  gh release upload "$TAG" "$WORK/building-overlay-manifest.json" --clobber --repo "$REPO"
  echo "✓ published $ID — the app's Settings → Offline will list it"
fi
