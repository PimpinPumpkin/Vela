#!/usr/bin/env bash
# Build + publish ONE region's offline POI pack (searchable places + addresses + streets for the
# whole region, Organic-Maps-style) to the `poi-packs` GitHub release, and merge it into
# poi-pack-manifest.json. Runnable locally or from CI (.github/workflows/poi-packs.yml).
#
#   scripts/build-poi-region.sh <id> "<Display name>" <geofabrik .osm.pbf URL>
#   e.g. scripts/build-poi-region.sh washington "Washington (state)" \
#          https://download.geofabrik.de/north-america/us/washington-latest.osm.pbf
#
# Needs: gh (authenticated), osmium-tool, jq, zip, python3. The pack is a SQLite db whose tables
# match the app's OfflinePoiStore/OfflineAddressStore schemas (see poipack_build.py).
set -euo pipefail

ID="${1:?region id}"; NAME="${2:?display name}"; URL="${3:?geofabrik pbf url}"
REPO="${VELA_REPO:-PimpinPumpkin/Vela}"
TAG="poi-packs"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

echo "→ downloading $URL"
curl -fsSL "$URL" -o "$WORK/region.osm.pbf"

echo "→ filtering POIs / addresses / named roads"
osmium tags-filter "$WORK/region.osm.pbf" \
  nwr/amenity nwr/shop nwr/tourism nwr/leisure nwr/public_transport nwr/boundary=national_park \
  nwr/addr:housenumber \
  w/highway=motorway,trunk,primary,secondary,tertiary,unclassified,residential,living_street,service,road,motorway_link,trunk_link,primary_link,secondary_link,tertiary_link \
  -o "$WORK/filtered.osm.pbf" --overwrite

echo "→ exporting features"
osmium export "$WORK/filtered.osm.pbf" -f geojsonseq --add-unique-id=type_id \
  -o "$WORK/features.geojsonl" --overwrite

echo "→ building SQLite pack"
python3 "$ROOT/scripts/poipack_build.py" "$WORK/features.geojsonl" "$WORK/$ID.db"

( cd "$WORK" && zip -q "$WORK/$ID.zip" "$ID.db" )
SIZE=$(( ( $(stat -f%z "$WORK/$ID.zip" 2>/dev/null || stat -c%s "$WORK/$ID.zip") + 1048575 ) / 1048576 ))

# bbox [S,W,N,E] from the extract's HEADER box (same rule as routing graphs — data.bbox is
# polluted by outlier nodes). osmium prints (minlon,minlat,maxlon,maxlat).
read -r MINLON MINLAT MAXLON MAXLAT < <(osmium fileinfo -g header.boxes "$WORK/region.osm.pbf" | tr -d '()' | tr ',' ' ')
BBOX="[$MINLAT,$MINLON,$MAXLAT,$MAXLON]"
ASSET_URL="https://github.com/$REPO/releases/download/$TAG/$ID.zip"
echo "→ $ID: ${SIZE} MB, bbox $BBOX"

gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1 || \
  gh release create "$TAG" --repo "$REPO" --prerelease --title "Offline place packs" \
    --notes "Prebuilt SQLite place/address packs (OpenStreetMap, ODbL) for Vela offline search. Data assets, not a code release."

gh release upload "$TAG" "$WORK/$ID.zip" --clobber --repo "$REPO"

ENTRY="$(jq -nc --arg id "$ID" --arg name "$NAME" --arg url "$ASSET_URL" --argjson size "$SIZE" --argjson bbox "$BBOX" \
  '{id:$id,name:$name,url:$url,sizeMb:$size,bbox:$bbox}')"

# MANIFEST_MODE=emit (CI matrix): drop the entry for the central merge job (parallel builds must not
# read-modify-write the manifest). Default (local single-region): merge it ourselves.
if [ "${MANIFEST_MODE:-merge}" = "emit" ]; then
  printf '%s\n' "$ENTRY" > "${ENTRY_OUT:?set ENTRY_OUT in emit mode}"
  echo "✓ built $ID, zip uploaded, entry → $ENTRY_OUT (manifest merged separately)"
else
  gh release download "$TAG" --repo "$REPO" -p poi-pack-manifest.json -O "$WORK/manifest.json" 2>/dev/null \
    || echo '{"regions":[]}' > "$WORK/manifest.json"
  jq --argjson entry "$ENTRY" \
    '.regions = ([.regions[] | select(.id != ($entry.id))] + [$entry])' \
    "$WORK/manifest.json" > "$WORK/poi-pack-manifest.json"
  gh release upload "$TAG" "$WORK/poi-pack-manifest.json" --clobber --repo "$REPO"
  echo "✓ published $ID — state downloads will now pull its place pack"
fi
