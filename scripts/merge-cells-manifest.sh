#!/usr/bin/env bash
# Build cells-manifest.json from what the `cells-<region>` releases hold and upload it to the
# `grid-cells` release. The manifest is DERIVED from the published assets, never folded from this
# run's entries: a merge job pending in a concurrency group is canceled when a newer one joins, so
# a fold loses regions (see repair-places-manifest.sh). A region's row is its fragment
# (cells-<region>.json, uploaded after its zips) narrowed to the cells whose zip is on the
# release, sizes from the release listing. A release with zips and no fragment still gets rows,
# with the cell's box read off its key. Fragments in [fragments-dir] (this run's) win for their
# regions, since the release listing can lag an upload.
#
#   scripts/merge-cells-manifest.sh [fragments-dir]
#
# DRY_RUN=1 writes the manifest to $MANIFEST_OUT (default ./cells-manifest.json), uploads nothing.
set -euo pipefail
REPO="${VELA_REPO:-PimpinPumpkin/Vela}"
TAG="grid-cells"
FRESH="${1:-}"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

listing() {
  gh api --paginate "repos/$REPO/releases?per_page=100" -q '.[] | select(.tag_name | startswith("cells-")) | .tag_name' \
    | sort > "$WORK/tags.txt"
  : > "$WORK/assets.txt"
  while read -r T; do
    gh release view "$T" --repo "$REPO" --json assets \
      -q ".assets[] | \"$T \(.name) \(.size) \(.updatedAt | .[0:10] | gsub(\"-\"; \"\"))\"" >> "$WORK/assets.txt"
  done < "$WORK/tags.txt"
  sort -o "$WORK/assets.txt" "$WORK/assets.txt"
}

derive() {
  : > "$WORK/regions.ndjson"
  while read -r T; do
    RID="${T#cells-}"
    FRAG="$WORK/frag-$RID.json"
    if [ -n "$FRESH" ] && [ -f "$FRESH/$T.json" ]; then
      jq -c '{id, name, rev, cells}' "$FRESH/$T.json" >> "$WORK/regions.ndjson"; continue
    fi
    awk -v t="$T" '$1 == t && $2 ~ /\.zip$/ {print $2, $3, $4}' "$WORK/assets.txt" > "$WORK/zips.txt"
    [ -s "$WORK/zips.txt" ] || { echo "skip $T (no cell zips)"; continue; }
    ZIPS="$(jq -R -s -c 'split("\n") | map(select(length > 0) | split(" ")
      | {key: (.[0] | sub("\\.zip$"; "")), value: {bytes: (.[1] | tonumber), date: (.[2] | tonumber)}}) | from_entries' "$WORK/zips.txt")"
    gh release download "$T" --repo "$REPO" -p "$T.json" -O "$FRAG" --clobber 2>/dev/null || echo '{}' > "$FRAG"
    jq -c --arg id "$RID" --arg repo "$REPO" --arg tag "$T" --argjson zips "$ZIPS" '
      def box(k): (k | capture("(?<ns>[ns])(?<la>[0-9.]+)(?<ew>[ew])(?<lo>[0-9.]+)$")) as $m
        | (($m.la | tonumber) * (if $m.ns == "s" then -1 else 1 end)) as $s
        | (($m.lo | tonumber) * (if $m.ew == "w" then -1 else 1 end)) as $w
        | [$s, $w, $s + 0.5, $w + 0.5];
      ((.cells // []) | map({key: .id, value: .}) | from_entries) as $frag
      | ($zips | to_entries | map(.key as $cid | .value as $z | ($frag[$cid] // null) as $f
          | { id: $cid,
              bbox: ($f.bbox // box($cid)),
              url: "https://github.com/\($repo)/releases/download/\($tag)/\($cid).zip",
              sizeMb: (($z.bytes / 10000 | ceil) / 100),
              installedMb: ($f.installedMb // (($z.bytes / 10000 | ceil) / 100)),
              rev: ($f.rev // $z.date),
              parts: ($f.parts // null) }
          | if .parts == null then del(.parts) else . end) | sort_by(.id)) as $cells
      | { id: $id, name: (.name // $id), rev: ($cells | map(.rev) | max), cells: $cells }' "$FRAG" \
      >> "$WORK/regions.ndjson"
  done < "$WORK/tags.txt"
  # A fresh fragment whose release is not listed yet still counts.
  if [ -n "$FRESH" ]; then
    for F in "$FRESH"/cells-*.json; do
      [ -f "$F" ] || continue
      grep -qx "$(basename "$F" .json)" "$WORK/tags.txt" || jq -c '{id, name, rev, cells}' "$F" >> "$WORK/regions.ndjson"
    done
  fi
  jq -s '{version: 1, regions: (sort_by(.id))}' "$WORK/regions.ndjson" > "$WORK/cells-manifest.json"
}

# Parallel merges race on the one manifest asset (a 422 "already exists" or a 404 on the replaced
# asset); every merge derives the whole manifest, so the loser retries a few seconds later.
upload_manifest() {
  local f="$1" try
  for try in 1 2 3 4 5; do
    gh release upload "$TAG" "$f" --clobber --repo "$REPO" && return 0
    echo "manifest upload lost a race (try $try); retrying"
    sleep $((RANDOM % 15 + 5))
  done
  local live="$f.live"
  if gh release download "$TAG" --repo "$REPO" -p "$(basename "$f")" -O "$live" --clobber 2>/dev/null \
     && cmp -s "$f" "$live"; then
    echo "the live manifest already matches; another run uploaded it"
    return 0
  fi
  return 1
}

if [ "${DRY_RUN:-0}" = "1" ]; then
  listing; derive
  OUT="${MANIFEST_OUT:-./cells-manifest.json}"
  cp "$WORK/cells-manifest.json" "$OUT"
  echo "dry run: $OUT lists $(jq '.regions | length' "$OUT") regions, $(jq '[.regions[].cells | length] | add // 0' "$OUT") cells"
  exit 0
fi

gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1 || \
  gh release create "$TAG" --repo "$REPO" --prerelease --title "Offline grid cells" \
    --notes "Index of the grid-cell offline bundles (cells-manifest.json). Data assets, not a code release."

for attempt in 1 2 3; do
  listing; derive
  cp "$WORK/assets.txt" "$WORK/assets.before"
  upload_manifest "$WORK/cells-manifest.json"
  echo "cells manifest lists $(jq '.regions | length' "$WORK/cells-manifest.json") regions (attempt $attempt)"
  # A region uploaded while this ran is not in the listing above; go round once more.
  listing
  if diff -q <(grep -E '\.(zip|json) ' "$WORK/assets.before") <(grep -E '\.(zip|json) ' "$WORK/assets.txt") >/dev/null; then break; fi
  echo "the releases changed during the merge; rebuilding"
done
