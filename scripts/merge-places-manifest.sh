#!/usr/bin/env bash
# Merge region entries into places-overlay-manifest.json in ONE upload (the race-safe half of the
# CI matrix: each bake job drops one entry file; this folds them all in). Replace-by-id; regions
# not in this batch are preserved. Sibling of merge-maxspeed-manifest.sh.
#
#   scripts/merge-places-manifest.sh <dir-of-entry-json-files>
set -euo pipefail
DIR="${1:?dir of *.json entry files}"
REPO="${VELA_REPO:-PimpinPumpkin/Vela}"
TAG="places-overlays"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
gh release download "$TAG" --repo "$REPO" -p places-overlay-manifest.json -O "$WORK/manifest.json" 2>/dev/null \
  || echo '{"regions":[]}' > "$WORK/manifest.json"
jq -s '.' "$DIR"/*.json > "$WORK/batch.json"
jq --slurpfile batch "$WORK/batch.json" '
  ($batch[0] | map(.id)) as $ids
  | .regions = ([.regions[] | select(.id as $i | $ids | index($i) | not)] + $batch[0])
  | .regions |= sort_by(.name)
' "$WORK/manifest.json" > "$WORK/places-overlay-manifest.json"
jq -r '.regions[] | "   \(.name)  \(.sizeMb) MB  \(.bbox)"' "$WORK/places-overlay-manifest.json"
gh release upload "$TAG" "$WORK/places-overlay-manifest.json" --clobber --repo "$REPO"
echo "places manifest now lists $(jq '.regions | length' "$WORK/places-overlay-manifest.json") regions"
