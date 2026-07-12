#!/usr/bin/env bash
# Merge many region entries into maxspeed-overlay-manifest.json in ONE upload - the race-safe half of the
# CI matrix (build-maxspeed-region.sh MANIFEST_MODE=emit drops one entry file per region; this folds them
# all in). Replace-by-id; regions not in this batch are preserved. Sibling of merge-overlay-manifest.sh.
#
#   scripts/merge-maxspeed-manifest.sh <dir-of-entry-json-files>
#
# Each file in <dir> is one {id,name,url,sizeMb,bbox} object. Needs: gh (auth), jq.
set -euo pipefail

DIR="${1:?dir of *.json entry files}"
REPO="${VELA_REPO:-PimpinPumpkin/Vela}"
TAG="maxspeed-overlays"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

gh release download "$TAG" --repo "$REPO" -p maxspeed-overlay-manifest.json -O "$WORK/manifest.json" 2>/dev/null \
  || echo '{"regions":[]}' > "$WORK/manifest.json"

jq -s '.' "$DIR"/*.json > "$WORK/batch.json"
COUNT=$(jq 'length' "$WORK/batch.json")
echo "→ merging $COUNT region entr$( [ "$COUNT" = 1 ] && echo y || echo ies ) into the maxspeed manifest"

jq --slurpfile batch "$WORK/batch.json" '
  ($batch[0] | map(.id)) as $ids
  | .regions = ([.regions[] | select(.id as $i | $ids | index($i) | not)] + $batch[0])
  | .regions |= sort_by(.name)
' "$WORK/manifest.json" > "$WORK/maxspeed-overlay-manifest.json"

jq -r '.regions[] | "   \(.name)  \(.sizeMb) MB  \(.bbox)"' "$WORK/maxspeed-overlay-manifest.json"
gh release upload "$TAG" "$WORK/maxspeed-overlay-manifest.json" --clobber --repo "$REPO"
echo "✓ maxspeed manifest now lists $(jq '.regions | length' "$WORK/maxspeed-overlay-manifest.json") regions"
