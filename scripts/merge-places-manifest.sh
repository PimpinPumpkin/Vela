#!/usr/bin/env bash
# The places bake's manifest merge: DERIVES the manifest from the archives on the places-overlays
# release and lets this run's own entry files win for the regions it baked (see
# repair-places-manifest.sh for why folding entries into the old manifest was wrong).
#
#   scripts/merge-places-manifest.sh <dir-of-entry-json-files>
set -euo pipefail
DIR="${1:?dir of *.json entry files}"
HERE="$(cd "$(dirname "$0")" && pwd)"
exec bash "$HERE/repair-places-manifest.sh" "$(date -u +%Y%m%d)" "$DIR"
