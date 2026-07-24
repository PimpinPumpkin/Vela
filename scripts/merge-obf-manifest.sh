#!/usr/bin/env bash
# Merge many obf region entries into obf-manifest.json in ONE upload - the race-safe half of the
# obf-regions CI matrix, same shape as merge-routing-manifest.sh. Replace-by-id, so re-running a
# region updates it; regions not in this batch are preserved.
#
#   scripts/merge-obf-manifest.sh <dir-of-entry-json-files>
#
# Each file is one {id,name,url,sizeMb,installedMb,bbox} object. Needs: gh (auth), jq.
set -euo pipefail

DIR="${1:?dir of *.json entry files}"
REPO="${VELA_REPO:-PimpinPumpkin/Vela}"
TAG="obf-regions"
MANIFEST_NAME="obf-manifest.json"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

gh release download "$TAG" --repo "$REPO" -p "$MANIFEST_NAME" -O "$WORK/manifest.json" 2>/dev/null \
  || echo '{"regions":[]}' > "$WORK/manifest.json"

jq -s '.' "$DIR"/*.json > "$WORK/batch.json"
COUNT=$(jq 'length' "$WORK/batch.json")
echo "→ merging $COUNT obf region entr$( [ "$COUNT" = 1 ] && echo y || echo ies ) into the manifest"

jq --slurpfile batch "$WORK/batch.json" '
  ($batch[0] | map(.id)) as $ids
  | .regions = ([.regions[] | select(.id as $i | $ids | index($i) | not)] + $batch[0])
  | .regions |= sort_by(.name)
' "$WORK/manifest.json" > "$WORK/$MANIFEST_NAME"

jq -r '.regions[] | "   \(.name)  \(.sizeMb) MB  \(.bbox)"' "$WORK/$MANIFEST_NAME"
gh release upload "$TAG" "$WORK/$MANIFEST_NAME" --clobber --repo "$REPO"
echo "✓ $MANIFEST_NAME now lists $(jq '.regions | length' "$WORK/$MANIFEST_NAME") regions"
