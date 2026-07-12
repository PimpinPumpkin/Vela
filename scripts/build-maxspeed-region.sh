#!/usr/bin/env bash
# Build + publish ONE region's POSTED-SPEED-LIMIT overlay as a PMTiles archive to the `maxspeed-overlays`
# GitHub release, merged into maxspeed-overlay-manifest.json. It carries OSM `maxspeed` on the road ways,
# so the app can show a posted speed-limit sign WITHOUT the (much larger) offline routing graph downloaded
# - the keyless "Speed B" source that GraphHopperRouteEngine.currentRoadLimit falls back to. Sibling of
# build-routing-region.sh (same Geofabrik OSM PBF source) and build-overlay-region.sh (same tippecanoe →
# PMTiles → release + manifest publish). ADDITIVE + reversible: its own release tag + manifest, nothing
# the routing/building/address artifacts touch; delete the tag to revert.
#
#   scripts/build-maxspeed-region.sh <id> "<Display name>" <geofabrik .osm.pbf URL>
#     scripts/build-maxspeed-region.sh oregon "Oregon" \
#       https://download.geofabrik.de/north-america/us/oregon-latest.osm.pbf
#
# Needs: gh (authenticated), osmium-tool, tippecanoe, jq, curl. LICENSE: OSM data, ODbL (same as the tiles).
set -euo pipefail

ID="${1:?region id}"; NAME="${2:?display name}"; URL="${3:?geofabrik pbf url}"
REPO="${VELA_REPO:-PimpinPumpkin/Vela}"
TAG="maxspeed-overlays"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

echo "→ downloading $URL"
curl -fsSL "$URL" -o "$WORK/region.osm.pbf"

# Keep ONLY the ways that carry a posted maxspeed (nearly always highways) - a tiny slice of the PBF, so the
# overlay is small. maxspeed on a non-highway is negligibly rare; filtering by the tag itself is enough.
echo "→ osmium: keeping ways with a maxspeed tag"
osmium tags-filter --overwrite -o "$WORK/ms.osm.pbf" "$WORK/region.osm.pbf" w/maxspeed

# Export the road LINES to GeoJSONSeq (one Feature per line, streams into tippecanoe).
echo "→ osmium export → GeoJSONSeq"
osmium export "$WORK/ms.osm.pbf" -f geojsonseq --geometry-types=linestring -o "$WORK/ms.geojsonseq" --overwrite

# Bbox from the PBF header (not the geometry extent - a stray node sends it to Alaska; same guard as routing).
read -r MINLON MINLAT MAXLON MAXLAT < <(osmium fileinfo -g header.boxes "$WORK/region.osm.pbf" | tr -d '()' | tr ',' ' ')
BBOX="[$MINLAT,$MINLON,$MAXLAT,$MAXLON]" # [S,W,N,E], the shape the routing manifest + picker use

# Tile z11→z16: roads must be present + queryable at nav/free-drive zoom (~14-17), and z11 keeps the file
# small while covering a snap. Keep ONLY the maxspeed attributes (-y) so the tiles carry no other tags.
echo "→ tiling with tippecanoe"
tippecanoe -o "$WORK/$ID.pmtiles" -l maxspeed -n "Vela speed-limit overlay: $NAME" \
  -Z11 -z16 --drop-densest-as-needed --extend-zooms-if-still-dropping -P \
  -y maxspeed -y maxspeed:forward -y maxspeed:backward \
  --attribution "Speed limits © OpenStreetMap contributors (ODbL)" --force "$WORK/ms.geojsonseq"

SIZE=$(( ( $(stat -f%z "$WORK/$ID.pmtiles" 2>/dev/null || stat -c%s "$WORK/$ID.pmtiles") + 1048575 ) / 1048576 ))
ASSET_URL="https://github.com/$REPO/releases/download/$TAG/$ID.pmtiles"
echo "→ $ID: ${SIZE} MB, bbox $BBOX"

# The overlay catalog release - prerelease so it never becomes the "Latest" the APK auto-tracks.
gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1 || \
  gh release create "$TAG" --repo "$REPO" --prerelease --title "Posted speed-limit overlays" \
    --notes "OSM \`maxspeed\` road ways as PMTiles, so Vela can show posted speed limits without the offline routing graph. Data assets, not a code release. Speed limits © OpenStreetMap contributors, ODbL."

gh release upload "$TAG" "$WORK/$ID.pmtiles" --clobber --repo "$REPO"

ENTRY="$(jq -nc --arg id "$ID" --arg name "$NAME" --arg url "$ASSET_URL" --argjson size "$SIZE" --argjson bbox "$BBOX" \
  '{id:$id,name:$name,url:$url,sizeMb:$size,bbox:$bbox}')"

# MANIFEST_MODE=emit (CI matrix): drop the entry to $ENTRY_OUT; the merge is centralised in one job so
# parallel region builds can't clobber the manifest. Default (local single-region): read-modify-write here.
if [ "${MANIFEST_MODE:-merge}" = "emit" ]; then
  printf '%s\n' "$ENTRY" > "${ENTRY_OUT:?set ENTRY_OUT in emit mode}"
  echo "✓ built $ID, pmtiles uploaded, entry → $ENTRY_OUT (manifest merged separately)"
else
  gh release download "$TAG" --repo "$REPO" -p maxspeed-overlay-manifest.json -O "$WORK/m.json" 2>/dev/null \
    || echo '{"regions":[]}' > "$WORK/m.json"
  jq --argjson entry "$ENTRY" \
    '.regions = ([.regions[] | select(.id != ($entry.id))] + [$entry])' \
    "$WORK/m.json" > "$WORK/maxspeed-overlay-manifest.json"
  gh release upload "$TAG" "$WORK/maxspeed-overlay-manifest.json" --clobber --repo "$REPO"
  echo "✓ published $ID"
fi
