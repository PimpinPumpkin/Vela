#!/usr/bin/env bash
# Build + publish ONE region's OPEN BUILDING-FOOTPRINT overlay as a PMTiles archive to the
# `building-overlays` GitHub release, merged into building-overlay-manifest.json. The app
# (OverlayTileStore) downloads it and renders the footprints BENEATH the OSM building layer, filling gaps
# where OSM lacks buildings (suburbs the Microsoft→OSM import never reached, e.g. Silver Firs). Sibling of
# build-routing-region.sh.
#
# TWO data sources (both Microsoft, both ODbL), picked by the SOURCE env var:
#   SOURCE=us-legacy (default) — a US STATE from Microsoft US Building Footprints, one .geojson.zip:
#     scripts/build-overlay-region.sh washington "Washington (state)" \
#       https://minedbuildings.z5.web.core.windows.net/legacy/usbuildings-v2/Washington.geojson.zip \
#       "45.54,-124.85,49.00,-116.92"
#   SOURCE=ms-global LOCATION=<Name> — a COUNTRY from Microsoft's Global ML Building Footprints
#     (quadkey-partitioned GeoJSONL under global-buildings/, listed in dataset-links.csv). The 3rd arg
#     (URL) is ignored — pass "-"; LOCATION is the dataset's Location column (spaces stripped, e.g. "Andorra"):
#     SOURCE=ms-global LOCATION=Andorra scripts/build-overlay-region.sh andorra "Andorra" - "42.42,1.41,42.66,1.79"
#
# Needs: gh (authenticated), tippecanoe, jq, unzip, curl, gzip. LICENSE: Microsoft Building Footprints is
# ODbL — a DATA licence orthogonal to the app's GPLv3 (same as the OSM tiles). Obligation met by the
# tippecanoe --attribution below (shown in-app) + this release publishing the derived tiles under ODbL.
set -euo pipefail

ID="${1:?region id}"; NAME="${2:?display name}"; URL="${3:?source URL or - for ms-global}"; BBOX_CSV="${4:?bbox S,W,N,E}"
REPO="${VELA_REPO:-PimpinPumpkin/Vela}"
TAG="building-overlays"
SOURCE="${SOURCE:-us-legacy}"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

# Acquire the region's raw footprints into $GEOJSON. Two shapes:
#   us-legacy  → ONE .geojson (a FeatureCollection) unzipped from the state's .geojson.zip.
#   ms-global  → GeoJSONL (one Feature per line) concatenated from every quadkey the global dataset lists
#                for LOCATION. tippecanoe reads line-delimited input in parallel with -P (set TIPPE_P).
TIPPE_P=""
if [ "$SOURCE" = "ms-global" ]; then
  LOCATION="${LOCATION:?ms-global needs LOCATION (the dataset-links.csv Location column)}"
  echo "→ ms-global: listing $LOCATION quadkeys from dataset-links.csv"
  curl -fsSL "https://minedbuildings.z5.web.core.windows.net/global-buildings/dataset-links.csv" -o "$WORK/dl.csv"
  awk -F, -v loc="$LOCATION" '$1==loc{print $3}' "$WORK/dl.csv" > "$WORK/urls.txt"
  N=$(wc -l < "$WORK/urls.txt" | tr -d ' ')
  [ "$N" -gt 0 ] || { echo "!! no quadkeys for LOCATION='$LOCATION' in dataset-links.csv" >&2; exit 1; }
  echo "→ $N quadkey file(s); downloading + decompressing → GeoJSONL"
  GEOJSON="$WORK/$ID.geojsonl"; : > "$GEOJSON"; i=0
  while IFS= read -r u; do
    i=$((i+1))
    # --retry: a big country is thousands of quadkeys; one transient blip shouldn't fail the whole build.
    curl -fsSL --retry 4 --retry-delay 2 --retry-all-errors "$u" | gzip -dc >> "$GEOJSON" \
      || { echo "!! failed quadkey $i/$N: $u" >&2; exit 1; }
    [ $((i % 50)) -eq 0 ] && echo "  …$i/$N"
  done < "$WORK/urls.txt"
  TIPPE_P="-P"
else
  echo "→ us-legacy: downloading $URL"
  curl -fsSL "$URL" -o "$WORK/b.zip"
  unzip -q "$WORK/b.zip" -d "$WORK/geo"
  GEOJSON="$(find "$WORK/geo" -iname '*.geojson' | head -1)"
  [ -n "$GEOJSON" ] || { echo "!! no .geojson found in $URL" >&2; exit 1; }
fi

# Footprints render z14→z16 only (overzoomed above), matching the app's OSM `building` layer (minzoom 14)
# — starting at z14 (not z12) drops the giant statewide low-zoom tiles that made WA balloon to 271 MB.
# --drop-densest-as-needed + the default 500 KB tile cap keep a packed downtown tile from bloating; the
# gap-fill overlay doesn't need every last footprint in a dense core (OSM already has those).
echo "→ tiling with tippecanoe ($SOURCE)"
tippecanoe -o "$WORK/$ID.pmtiles" -l building -n "Vela building overlay: $NAME" \
  -Z14 -z16 --drop-densest-as-needed --extend-zooms-if-still-dropping $TIPPE_P \
  --attribution "Buildings © Microsoft (ODbL)" --force "$GEOJSON"

SIZE=$(( ( $(stat -f%z "$WORK/$ID.pmtiles" 2>/dev/null || stat -c%s "$WORK/$ID.pmtiles") + 1048575 ) / 1048576 ))
BBOX="[$(echo "$BBOX_CSV" | tr -d ' ')]" # [S,W,N,E], same shape the routing manifest + picker use
ASSET_URL="https://github.com/$REPO/releases/download/$TAG/$ID.pmtiles"
echo "→ $ID: ${SIZE} MB, bbox $BBOX"

# The overlay catalog release — prerelease so it never becomes the "Latest" the APK auto-tracks.
gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1 || \
  gh release create "$TAG" --repo "$REPO" --prerelease --title "Open building overlays" \
    --notes "Microsoft Building Footprints (US + Global ML, ODbL) as PMTiles for Vela's gap-fill building overlay. Data assets, not a code release. Buildings © Microsoft, licensed under ODbL (opendatacommons.org/licenses/odbl/1-0)."

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
