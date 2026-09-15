#!/usr/bin/env bash
# Merge region entries into basemap-manifest.json in ONE upload (the race-safe half of the
# basemap-tiles matrix: each bake job drops one entry file; this folds them all in). Replace-by-id;
# regions not in this batch are preserved. Sibling of merge-places-manifest.sh.
#
#   scripts/merge-basemap-manifest.sh <dir-of-entry-json-files>
set -euo pipefail
DIR="${1:?dir of *.json entry files}"
REPO="${VELA_REPO:-PimpinPumpkin/Vela}"
TAG="basemap-tiles"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
gh release download "$TAG" --repo "$REPO" -p basemap-manifest.json -O "$WORK/manifest.json" 2>/dev/null \
  || echo '{"regions":[]}' > "$WORK/manifest.json"
jq -s '.' "$DIR"/*.json > "$WORK/batch.json"
jq --slurpfile batch "$WORK/batch.json" '
  ($batch[0] | map(.id)) as $ids
  | .regions = ([.regions[] | select(.id as $i | $ids | index($i) | not)] + $batch[0])
  | .regions |= sort_by(.name)
' "$WORK/manifest.json" > "$WORK/basemap-manifest.json"
jq -r '.regions[] | "   \(.name)  \(.sizeMb) MB  \(.bbox)"' "$WORK/basemap-manifest.json"
gh release upload "$TAG" "$WORK/basemap-manifest.json" --clobber --repo "$REPO"
echo "basemap manifest now lists $(jq '.regions | length' "$WORK/basemap-manifest.json") regions"
