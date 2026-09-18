#!/usr/bin/env bash
# The basemap bake's manifest merge. It DERIVES the manifest from the archives published on the
# basemap-tiles release and then lets this run's own entry files win for the regions it baked, so
# the result is a function of what is published rather than of which merge jobs happened to run.
#
# It used to fold this run's entries into whatever the manifest held, which made every archive
# depend on its own merge job surviving. GitHub cancels a job that is PENDING in a concurrency
# group when a newer one joins it, so a wave of runs loses the merges in the middle of it: on
# 2026-09-18, 10 of 25 runs baked and uploaded their archives and the manifest ended up listing 99
# of 414 regions. Deriving from the release makes a lost merge cost nothing, because the next one
# picks up everything.
#
#   scripts/merge-basemap-manifest.sh <dir-of-entry-json-files>
set -euo pipefail
DIR="${1:?dir of *.json entry files}"
HERE="$(cd "$(dirname "$0")" && pwd)"
exec bash "$HERE/repair-basemap-manifest.sh" "$(date -u +%Y%m%d)" "$DIR"
