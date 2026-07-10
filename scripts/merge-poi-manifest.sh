#!/usr/bin/env bash
# Merge many region entries into poi-pack-manifest.json in ONE upload - the race-safe half of the CI
# matrix (build-poi-region.sh MANIFEST_MODE=emit drops one entry file per region; this folds them
# all in). Replace-by-id, so re-running a region updates it; regions not in this batch are preserved.
#
#   scripts/merge-poi-manifest.sh <dir-of-entry-json-files>
#
# Each file in <dir> is one {id,name,url,sizeMb,bbox} object (any filename). Needs: gh (auth), jq.
set -euo pipefail

DIR="${1:?dir of *.json entry files}"
REPO="${VELA_REPO:-PimpinPumpkin/Vela}"
TAG="poi-packs"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

# start from the live manifest (preserve regions not in this batch); empty catalog if none yet
gh release download "$TAG" --repo "$REPO" -p poi-pack-manifest.json -O "$WORK/manifest.json" 2>/dev/null \
  || echo '{"regions":[]}' > "$WORK/manifest.json"

# collect this batch's entries into one array, then upsert each by id
jq -s '.' "$DIR"/*.json > "$WORK/batch.json"
COUNT=$(jq 'length' "$WORK/batch.json")
echo "→ merging $COUNT region entr$( [ "$COUNT" = 1 ] && echo y || echo ies ) into the manifest"

jq --slurpfile batch "$WORK/batch.json" '
  ($batch[0] | map(.id)) as $ids
  | .regions = ([.regions[] | select(.id as $i | $ids | index($i) | not)] + $batch[0])
  | .regions |= sort_by(.name)
' "$WORK/manifest.json" > "$WORK/poi-pack-manifest.json"

jq -r '.regions[] | "   \(.name)  \(.sizeMb) MB  \(.bbox)"' "$WORK/poi-pack-manifest.json"
gh release upload "$TAG" "$WORK/poi-pack-manifest.json" --clobber --repo "$REPO"
echo "[x] manifest now lists $(jq '.regions | length' "$WORK/poi-pack-manifest.json") regions"
