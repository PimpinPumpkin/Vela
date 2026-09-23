#!/usr/bin/env bash
# Build basemap-manifest.json from whatever basemap-*.pmtiles archives sit on the basemap-tiles
# release: name from tools/routing-regions.json, size from the release, bounds from the first 127
# bytes of each archive (one range request each, and only for an archive whose size is new to the
# manifest or that was uploaded after the rev it carries, so a normal run makes a handful).
#
# This is the bake's MERGE as well as its repair, and deriving from the release rather than folding
# a batch of per-run fragments is what makes it safe: the manifest is a function of what is
# published, so running it once after the last upload is enough and running it twice changes
# nothing. A merge job that never ran costs an archive nothing, which matters because GitHub
# CANCELS a job that is pending in a concurrency group when a newer one joins it - 10 of 25
# dispatched runs lost their merge that way on 2026-09-18 and 315 of 414 archives were missing
# from the manifest.
#
#   scripts/repair-basemap-manifest.sh [rev] [entries-dir]
#
# rev defaults to today (YYYYMMDD) and stamps rows derived from the release. entries-dir is the
# bake's own entry files: they carry the region's real bake rev and win over the derived row.
set -euo pipefail
REPO="${VELA_REPO:-PimpinPumpkin/Vela}"
TAG="basemap-tiles"
REV="${1:-$(date -u +%Y%m%d)}"
ENTRIES="${2:-}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
gh release view "$TAG" --repo "$REPO" --json assets -q '.assets[] | select(.name | startswith("basemap-") and endswith(".pmtiles")) | "\(.name) \(.size) \(.updatedAt | .[0:10] | gsub("-"; ""))"' > "$WORK/assets.txt"
gh release download "$TAG" --repo "$REPO" -p basemap-manifest.json -O "$WORK/old.json" 2>/dev/null || echo '{"regions":[]}' > "$WORK/old.json"
: > "$WORK/entries.ndjson"
while read -r NAME SIZE UPDATED; do
  ID="${NAME#basemap-}"; ID="${ID%.pmtiles}"
  URL="https://github.com/$REPO/releases/download/$TAG/$NAME"
  # keep an existing entry's rev when the archive is unchanged: same size AND not uploaded after
  # that rev. Size alone missed a rebake that happened to land on the same byte count, which then
  # kept the old rev and was never offered as an update.
  OLD=$(jq -c --arg id "$ID" '.regions[] | select(.id == $id)' "$WORK/old.json")
  if [ -n "$OLD" ] && [ "$(jq -r '.sizeMb' <<<"$OLD")" = "$(echo "scale=2; $SIZE/1000000" | bc)" ] \
     && [ "${UPDATED:-0}" -le "$(jq -r '.rev // 0' <<<"$OLD")" ]; then
    printf '%s\n' "$OLD" >> "$WORK/entries.ndjson"; continue
  fi
  curl -sL -r 0-126 "$URL" -o "$WORK/head.bin"
  BBOX=$(python3 "$HERE/pmtiles-bbox.py" "$WORK/head.bin" | python3 "$HERE/clamp-bbox.py" "$ID") || { echo "skip $ID (no header)"; continue; }
  REGION_NAME=$(jq -r --arg id "$ID" '.regions[] | select(.id == $id) | .name' tools/routing-regions.json)
  jq -nc --arg id "$ID" --arg name "${REGION_NAME:-$ID}" --arg url "$URL" \
    --argjson sizeMb "$(echo "scale=2; $SIZE/1000000" | bc)" --argjson bbox "$BBOX" --argjson rev "$REV" \
    '{id:$id,name:$name,url:$url,sizeMb:$sizeMb,bbox:$bbox,rev:$rev}' >> "$WORK/entries.ndjson"
  echo "  $ID  $(echo "scale=1; $SIZE/1000000" | bc) MB  $BBOX"
done < "$WORK/assets.txt"
if [ -n "$ENTRIES" ] && ls "$ENTRIES"/*.json >/dev/null 2>&1; then
  # This run's own entries are authoritative for the regions it baked (their rev is the bake date,
  # not today), so they replace the derived row by id.
  jq -s '.' "$ENTRIES"/*.json > "$WORK/fresh.json"
  jq -s --slurpfile fresh "$WORK/fresh.json" '
    ($fresh[0] | map(.id)) as $ids
    | {regions: (([.[] | select(.id as $i | $ids | index($i) | not)] + $fresh[0]) | sort_by(.name))}
  ' "$WORK/entries.ndjson" > "$WORK/basemap-manifest.json"
else
  jq -s '{regions: (. | sort_by(.name))}' "$WORK/entries.ndjson" > "$WORK/basemap-manifest.json"
fi
# Several merges finishing together race on the ONE manifest asset: --clobber deletes and
# re-uploads, so a concurrent merge sees "already exists" (422) or a 404 for the asset it was
# replacing (2026-09-22, two of nine parallel runs). Every merge derives the full manifest from
# the release, so the loser only has to try again a few seconds later.
upload_manifest() {
  local f="$1" try
  for try in 1 2 3 4 5; do
    gh release upload "$TAG" "$f" --clobber --repo "$REPO" && return 0
    echo "manifest upload lost a race (try $try); retrying"
    sleep $((RANDOM % 15 + 5))
  done
  # Every try can 404 on the SAME asset id when a parallel run replaced the manifest first: the
  # release listing keeps naming the deleted asset for a while (seen 2026-09-23, five tries over a
  # minute). If the live manifest already says what this run meant to write, that is a success.
  local live="$f.live"
  if gh release download "$TAG" --repo "$REPO" -p "$(basename "$f")" -O "$live" --clobber 2>/dev/null \
     && cmp -s "$f" "$live"; then
    echo "the live manifest already matches; another run uploaded it"
    return 0
  fi
  return 1
}
upload_manifest "$WORK/basemap-manifest.json"
echo "basemap manifest now lists $(jq '.regions | length' "$WORK/basemap-manifest.json") regions"
# An archive uploaded while this ran is not in the listing; without a concurrency group a
# merge can be overtaken by a newer upload, so run once more when the release moved.
gh release view "$TAG" --repo "$REPO" --json assets -q '.assets[] | select(.name | startswith("basemap-") and endswith(".pmtiles")) | "\(.name) \(.size) \(.updatedAt | .[0:10] | gsub("-"; ""))"' > "$WORK/assets.after"
if ! diff -q "$WORK/assets.txt" "$WORK/assets.after" >/dev/null && [ "${VELA_REPAIR_AGAIN:-0}" = "0" ]; then
  echo "the release changed during the merge; rebuilding once more"
  VELA_REPAIR_AGAIN=1 exec bash "$0" "$REV" "$ENTRIES"
fi
