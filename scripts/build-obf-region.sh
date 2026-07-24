#!/usr/bin/env bash
# Bake ONE region's Vela obf and publish it to the `obf-regions` release - the successor pipeline to
# build-routing-region.sh (issue #214). The obf carries routing + address + POI sections only; the
# map-rendering and transport sections are excluded by scripts/VelaObfShim.java (MapLibre draws the
# map, GTFS covers transit), which is most of the size win against a stock OsmAnd file. The asset is
# served RAW (an obf's blocks are already deflate-compressed), so download size == installed size.
#
#   scripts/build-obf-region.sh <id> "<display name>" <pbf-url>
#
# MANIFEST_MODE=emit (CI matrix) drops a single manifest-entry json to $ENTRY_OUT for the central
# merge job; the default merges into obf-manifest.json directly (local single-region use).
# Needs: curl, unzip, osmium, jq, a JDK, gh (auth) for upload.
set -euo pipefail

ID="${1:?region id}"
NAME="${2:?display name}"
PBF_URL="${3:?pbf url}"
REPO="${VELA_REPO:-PimpinPumpkin/Vela}"
TAG="obf-regions"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

# The pinned bake tool (OsmAndMapCreator) lives on the obf-tools release - forks fall back upstream.
curl -fSL -o "$WORK/mapcreator.zip" "https://github.com/$REPO/releases/download/obf-tools/mapcreator.zip" \
  || curl -fSL -o "$WORK/mapcreator.zip" "https://github.com/PimpinPumpkin/Vela/releases/download/obf-tools/mapcreator.zip"
unzip -q "$WORK/mapcreator.zip" -d "$WORK/mapcreator"

curl -fSL --retry 3 -o "$WORK/region.osm.pbf" "$PBF_URL"

# bbox [S,W,N,E] from the extract's declared HEADER box - same rule as every other region pipeline
# (data.bbox is polluted by outlier nodes). osmium prints (minlon,minlat,maxlon,maxlat).
read -r MINLON MINLAT MAXLON MAXLAT < <(osmium fileinfo -g header.boxes "$WORK/region.osm.pbf" | tr -d '()' | tr ',' ' ')
BBOX="[$MINLAT,$MINLON,$MAXLAT,$MAXLON]"

javac -cp "$WORK/mapcreator/OsmAndMapCreator.jar:$WORK/mapcreator/lib/*" -d "$WORK" "$ROOT/scripts/VelaObfShim.java"
# Not processInRam: MapCreator's disk-backed pipeline is what lets a whole country bake inside a
# 16 GB runner. The heap bound is for its indexes, not the region.
( cd "$WORK" && java -Xmx12g -cp "$WORK/mapcreator/OsmAndMapCreator.jar:$WORK/mapcreator/lib/*:$WORK" VelaObfShim region.osm.pbf )
OBF="$(ls "$WORK"/*.obf | head -1)"  # generateObf names the output from the pbf filename
mv "$OBF" "$WORK/$ID.obf"

SIZE=$(( ( $(stat -f%z "$WORK/$ID.obf" 2>/dev/null || stat -c%s "$WORK/$ID.obf") + 1048575 ) / 1048576 ))
ASSET_URL="https://github.com/$REPO/releases/download/$TAG/$ID.obf"
echo "→ $ID: ${SIZE} MB obf (download == installed), bbox $BBOX"

gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1 || \
  gh release create "$TAG" --repo "$REPO" --prerelease --title "Offline obf regions" \
    --notes "Vela-baked obf files (routing + address + POI, no rendering section) for offline routing and search. Data assets, not a code release."

gh release upload "$TAG" "$WORK/$ID.obf" --clobber --repo "$REPO"

# Raw obf: the download size IS the installed size, so both fields carry the same number and the
# Settings row needs no unpack estimate.
ENTRY="$(jq -nc --arg id "$ID" --arg name "$NAME" --arg url "$ASSET_URL" --argjson size "$SIZE" --argjson bbox "$BBOX" \
  '{id:$id,name:$name,url:$url,sizeMb:$size,installedMb:$size,bbox:$bbox}')"

if [ "${MANIFEST_MODE:-merge}" = "emit" ]; then
  printf '%s\n' "$ENTRY" > "${ENTRY_OUT:?ENTRY_OUT required in emit mode}"
  echo "emitted manifest entry to $ENTRY_OUT"
  exit 0
fi

ENTRY_DIR="$(mktemp -d)"; trap 'rm -rf "$WORK" "$ENTRY_DIR"' EXIT
printf '%s\n' "$ENTRY" > "$ENTRY_DIR/$ID.json"
bash "$ROOT/scripts/merge-obf-manifest.sh" "$ENTRY_DIR"
