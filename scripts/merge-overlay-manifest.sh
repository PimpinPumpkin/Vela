#!/usr/bin/env bash
# Merge many region entries into building-overlay-manifest.json in ONE upload — the race-safe half of the
# CI matrix (build-overlay-region.sh MANIFEST_MODE=emit drops one entry file per region; this folds them
# all in). Replace-by-id, so re-running a region updates it; regions not in this batch are preserved.
# Sibling of merge-routing-manifest.sh.
#
#   scripts/merge-overlay-manifest.sh <dir-of-entry-json-files>
#
# Each file in <dir> is one {id,name,url,sizeMb,bbox} object (any filename). Needs: gh (auth), jq.
set -euo pipefail

DIR="${1:?dir of *.json entry files}"
REPO="${VELA_REPO:-PimpinPumpkin/Vela}"
TAG="building-overlays"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

# start from the live manifest (preserve regions not in this batch); empty catalog if none yet
gh release download "$TAG" --repo "$REPO" -p building-overlay-manifest.json -O "$WORK/manifest.json" 2>/dev/null \
  || echo '{"regions":[]}' > "$WORK/manifest.json"

jq -s '.' "$DIR"/*.json > "$WORK/batch.json"
COUNT=$(jq 'length' "$WORK/batch.json")
echo "→ merging $COUNT region entr$( [ "$COUNT" = 1 ] && echo y || echo ies ) into the overlay manifest"

jq --slurpfile batch "$WORK/batch.json" '
  ($batch[0] | map(.id)) as $ids
  | .regions = ([.regions[] | select(.id as $i | $ids | index($i) | not)] + $batch[0])
  | .regions |= sort_by(.name)
' "$WORK/manifest.json" > "$WORK/building-overlay-manifest.json"

jq -r '.regions[] | "   \(.name)  \(.sizeMb) MB  \(.bbox)"' "$WORK/building-overlay-manifest.json"
gh release upload "$TAG" "$WORK/building-overlay-manifest.json" --clobber --repo "$REPO"
echo "✓ overlay manifest now lists $(jq '.regions | length' "$WORK/building-overlay-manifest.json") regions"
