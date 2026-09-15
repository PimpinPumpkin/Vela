#!/usr/bin/env bash
# Rebuild basemap-manifest.json from whatever basemap-*.pmtiles archives sit on the basemap-tiles
# release: name from tools/routing-regions.json, size from the release, bounds from the first 127
# bytes of each archive (one range request each). For when bake jobs uploaded their archive but
# lost the manifest entry (2026-09-15, the pmtiles CLI download was rate-limited on the runners).
#
#   scripts/repair-basemap-manifest.sh [rev]     rev defaults to today, YYYYMMDD
set -euo pipefail
REPO="${VELA_REPO:-PimpinPumpkin/Vela}"
TAG="basemap-tiles"
REV="${1:-$(date -u +%Y%m%d)}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
gh release view "$TAG" --repo "$REPO" --json assets -q '.assets[] | select(.name | startswith("basemap-") and endswith(".pmtiles")) | "\(.name) \(.size)"' > "$WORK/assets.txt"
gh release download "$TAG" --repo "$REPO" -p basemap-manifest.json -O "$WORK/old.json" 2>/dev/null || echo '{"regions":[]}' > "$WORK/old.json"
: > "$WORK/entries.ndjson"
while read -r NAME SIZE; do
  ID="${NAME#basemap-}"; ID="${ID%.pmtiles}"
  URL="https://github.com/$REPO/releases/download/$TAG/$NAME"
  # keep an existing entry's rev when the archive is unchanged (same size)
  OLD=$(jq -c --arg id "$ID" '.regions[] | select(.id == $id)' "$WORK/old.json")
  if [ -n "$OLD" ] && [ "$(jq -r '.sizeMb' <<<"$OLD")" = "$(echo "scale=2; $SIZE/1000000" | bc)" ]; then
    printf '%s\n' "$OLD" >> "$WORK/entries.ndjson"; continue
  fi
  curl -sL -r 0-126 "$URL" -o "$WORK/head.bin"
  BBOX=$(python3 "$HERE/pmtiles-bbox.py" "$WORK/head.bin") || { echo "skip $ID (no header)"; continue; }
  REGION_NAME=$(jq -r --arg id "$ID" '.regions[] | select(.id == $id) | .name' tools/routing-regions.json)
  jq -nc --arg id "$ID" --arg name "${REGION_NAME:-$ID}" --arg url "$URL" \
    --argjson sizeMb "$(echo "scale=2; $SIZE/1000000" | bc)" --argjson bbox "$BBOX" --argjson rev "$REV" \
    '{id:$id,name:$name,url:$url,sizeMb:$sizeMb,bbox:$bbox,rev:$rev}' >> "$WORK/entries.ndjson"
  echo "  $ID  $(echo "scale=1; $SIZE/1000000" | bc) MB  $BBOX"
done < "$WORK/assets.txt"
jq -s '{regions: (. | sort_by(.name))}' "$WORK/entries.ndjson" > "$WORK/basemap-manifest.json"
gh release upload "$TAG" "$WORK/basemap-manifest.json" --clobber --repo "$REPO"
echo "basemap manifest now lists $(jq '.regions | length' "$WORK/basemap-manifest.json") regions"
