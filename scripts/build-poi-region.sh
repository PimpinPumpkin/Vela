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

# bbox first (from the header), so the source PBF can be deleted as soon as it's filtered - a big
# country needs the disk back. [S,W,N,E] from the declared extract region, NOT data.bbox (same rule
# as routing graphs - node extent is polluted by outlier nodes). osmium prints (minlon,minlat,...).
read -r MINLON MINLAT MAXLON MAXLAT < <(osmium fileinfo -g header.boxes "$WORK/region.osm.pbf" | tr -d '()' | tr ',' ' ')
BBOX="[$MINLAT,$MINLON,$MAXLAT,$MAXLON]"

echo "→ filtering POIs / addresses / named roads"
osmium tags-filter "$WORK/region.osm.pbf" \
  nwr/amenity nwr/shop nwr/tourism nwr/leisure nwr/public_transport nwr/boundary=national_park \
  nwr/addr:housenumber \
  w/highway=motorway,trunk,primary,secondary,tertiary,unclassified,residential,living_street,service,road,motorway_link,trunk_link,primary_link,secondary_link,tertiary_link \
  -o "$WORK/filtered.osm.pbf" --overwrite
rm -f "$WORK/region.osm.pbf" # reclaim disk before the build (country PBFs are GB-scale)

# The export STREAMS into the pack builder - never written to disk. The geojsonseq is ~12x the
# filtered PBF (Washington: 161 MB -> 1.9 GB), so a country-sized export on disk would blow a
# 14 GB CI runner; piped, the peak disk is just filtered.pbf + the SQLite db.
echo "→ exporting features → building SQLite pack (streamed)"
osmium export "$WORK/filtered.osm.pbf" -f geojsonseq --add-unique-id=type_id -o - \
  | python3 "$ROOT/scripts/poipack_build.py" - "$WORK/$ID.db"
rm -f "$WORK/filtered.osm.pbf"

( cd "$WORK" && zip -q "$WORK/$ID.zip" "$ID.db" )
SIZE=$(( ( $(stat -f%z "$WORK/$ID.zip" 2>/dev/null || stat -c%s "$WORK/$ID.zip") + 1048575 ) / 1048576 ))
ASSET_URL="https://github.com/$REPO/releases/download/$TAG/$ID.zip"
COUNTS="$(cat "$WORK/$ID.db.counts.json")"
UPDATED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "→ $ID: ${SIZE} MB, bbox $BBOX"

gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1 || \
  gh release create "$TAG" --repo "$REPO" --prerelease --title "Offline place packs" \
    --notes "Prebuilt SQLite place/address packs (OpenStreetMap, ODbL) for Vela offline search. Data assets, not a code release."

# Revision + delta against the currently published pack, BEFORE the new zip clobbers it. The delta is
# a small row-level SQLite (poipack_delta.py) the app can apply instead of re-downloading the full
# pack. Only published when it is genuinely smaller (a format jump like v1->v2 diffs everything, and a
# delta near the pack's size helps nobody). rev counts up from the live manifest entry.
OLD_REV=0
DELTA_JSON=null
gh release download "$TAG" --repo "$REPO" -p poi-pack-manifest.json -O "$WORK/live-manifest.json" 2>/dev/null || true
if [ -s "$WORK/live-manifest.json" ]; then
  OLD_REV=$(jq -r --arg id "$ID" '[.regions[] | select(.id==$id) | .rev // 0] | first // 0' "$WORK/live-manifest.json")
fi
REV=$(( OLD_REV + 1 ))
if [ "$OLD_REV" -gt 0 ] && gh release download "$TAG" --repo "$REPO" -p "$ID.zip" -O "$WORK/old.zip" 2>/dev/null; then
  ( cd "$WORK" && unzip -qo old.zip -d oldpack ) && OLD_DB="$(ls "$WORK"/oldpack/*.db 2>/dev/null | head -1)" || OLD_DB=""
  if [ -n "$OLD_DB" ]; then
    echo "→ building delta rev $OLD_REV → $REV"
    python3 "$ROOT/scripts/poipack_delta.py" "$OLD_DB" "$WORK/$ID.db" "$WORK/$ID.delta.db" || true
    if [ -s "$WORK/$ID.delta.db" ]; then
      ( cd "$WORK" && zip -q "$ID.delta.zip" "$ID.delta.db" )
      DSIZE_B=$(stat -f%z "$WORK/$ID.delta.zip" 2>/dev/null || stat -c%s "$WORK/$ID.delta.zip")
      FSIZE_B=$(stat -f%z "$WORK/$ID.zip" 2>/dev/null || stat -c%s "$WORK/$ID.zip")
      if [ "$DSIZE_B" -lt $(( FSIZE_B / 2 )) ]; then
        gh release upload "$TAG" "$WORK/$ID.delta.zip" --clobber --repo "$REPO"
        DSIZE_MB=$(( ( DSIZE_B + 1048575 ) / 1048576 ))
        DELTA_JSON="$(jq -nc --arg url "https://github.com/$REPO/releases/download/$TAG/$ID.delta.zip" \
          --argjson from "$OLD_REV" --argjson size "$DSIZE_MB" '{fromRev:$from,url:$url,sizeMb:$size}')"
        echo "→ delta published: ${DSIZE_MB} MB (full is ${SIZE} MB)"
      else
        echo "→ delta too big ($(( DSIZE_B / 1048576 )) MB vs full ${SIZE} MB) - clients will full-download"
      fi
    fi
  fi
  rm -rf "$WORK/oldpack" "$WORK/old.zip" "$WORK/$ID.delta.db"
fi
rm -f "$WORK/$ID.db"

gh release upload "$TAG" "$WORK/$ID.zip" --clobber --repo "$REPO"

ENTRY="$(jq -nc --arg id "$ID" --arg name "$NAME" --arg url "$ASSET_URL" --argjson size "$SIZE" --argjson bbox "$BBOX" \
  --argjson rev "$REV" --arg updated "$UPDATED_AT" --argjson counts "$COUNTS" --argjson delta "$DELTA_JSON" \
  '{id:$id,name:$name,url:$url,sizeMb:$size,bbox:$bbox,rev:$rev,updatedAt:$updated,counts:$counts}
   + (if $delta != null then {delta:$delta} else {} end)')"

# MANIFEST_MODE=emit (CI matrix): drop the entry for the central merge job (parallel builds must not
# read-modify-write the manifest). Default (local single-region): merge it ourselves.
if [ "${MANIFEST_MODE:-merge}" = "emit" ]; then
  printf '%s\n' "$ENTRY" > "${ENTRY_OUT:?set ENTRY_OUT in emit mode}"
  echo "[x] built $ID, zip uploaded, entry → $ENTRY_OUT (manifest merged separately)"
else
  gh release download "$TAG" --repo "$REPO" -p poi-pack-manifest.json -O "$WORK/manifest.json" 2>/dev/null \
    || echo '{"regions":[]}' > "$WORK/manifest.json"
  jq --argjson entry "$ENTRY" \
    '.regions = ([.regions[] | select(.id != ($entry.id))] + [$entry])' \
    "$WORK/manifest.json" > "$WORK/poi-pack-manifest.json"
  gh release upload "$TAG" "$WORK/poi-pack-manifest.json" --clobber --repo "$REPO"
  echo "[x] published $ID - state downloads will now pull its place pack"
fi
