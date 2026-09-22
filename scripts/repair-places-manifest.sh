#!/usr/bin/env bash
# Build places-overlay-manifest.json from whatever places-*.pmtiles archives sit on the
# places-overlays release: name and bounds from tools/places-regions.json (the bake's own entry
# carries the same catalog bbox), size from the release, rev from this run's entry when it has
# one, else the old manifest's rev when the archive is unchanged (same size), else the stamp
# given; a delta from the entry, else the `places-<id>.<oldrev>.vpatch` asset the bake published
# against the old rev, else the old manifest's delta when the rev did not move.
#
# This is the bake's MERGE as well as its repair. It used to fold this run's entries into the
# manifest, which made every archive depend on its own merge job surviving; a job that is pending
# in a concurrency group is CANCELLED when a newer one joins, so a 54-region wave on 2026-09-22
# lost 34 merges (and mailed every one of them). Deriving from the release makes a lost or racing
# merge cost nothing: the manifest is a function of what is published, and the assets are listed
# again after the upload, so a merge that was overtaken by a newer upload rebuilds once more.
#
#   scripts/repair-places-manifest.sh [rev] [entries-dir]
set -euo pipefail
REPO="${VELA_REPO:-PimpinPumpkin/Vela}"
TAG="places-overlays"
REV="${1:-$(date -u +%Y%m%d)}"
ENTRIES="${2:-}"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
CATALOG="tools/places-regions.json"

derive() {
  gh release view "$TAG" --repo "$REPO" --json assets -q '.assets[] | "\(.name) \(.size)"' | sort > "$WORK/assets.txt"
  gh release download "$TAG" --repo "$REPO" -p places-overlay-manifest.json -O "$WORK/old.json" 2>/dev/null || echo '{"regions":[]}' > "$WORK/old.json"
  : > "$WORK/entries.ndjson"
  while read -r NAME SIZE; do
    case "$NAME" in places-*.pmtiles) ;; *) continue ;; esac
    ID="${NAME#places-}"; ID="${ID%.pmtiles}"
    URL="https://github.com/$REPO/releases/download/$TAG/$NAME"
    MB="$(echo "scale=2; $SIZE/1000000" | bc)"
    OLD=$(jq -c --arg id "$ID" '.regions[] | select(.id == $id)' "$WORK/old.json"); [ -n "$OLD" ] || OLD='{}'
    ROW=$(jq -c --arg id "$ID" '.regions[] | select(.id == $id)' "$CATALOG"); [ -n "$ROW" ] || ROW='{}'
    NAME_=$(jq -r '.name // empty' <<<"$ROW"); [ -n "$NAME_" ] || NAME_="$ID"
    BBOX=$(jq -c '.bbox // empty' <<<"$ROW"); [ -n "$BBOX" ] || BBOX=$(jq -c '.bbox // empty' <<<"$OLD")
    [ -n "$BBOX" ] || { echo "skip $ID (no bounds in the catalog or the old manifest)"; continue; }
    if [ "$OLD" != "{}" ] && [ "$(jq -r '.sizeMb' <<<"$OLD")" = "$MB" ]; then
      # unchanged archive: keep its rev and delta, refresh name and bounds from the catalog
      jq -c --arg name "$NAME_" --argjson bbox "$BBOX" '.name = $name | .bbox = $bbox' <<<"$OLD" >> "$WORK/entries.ndjson"; continue
    fi
    DELTA=null
    OLDREV=$(jq -r '.rev // 0' <<<"$OLD")
    if [ "$OLDREV" != "0" ]; then
      PATCH=$(awk -v n="places-$ID.$OLDREV.vpatch" '$1 == n {print $2}' "$WORK/assets.txt")
      if [ -n "$PATCH" ]; then
        DELTA=$(jq -nc --argjson fromRev "$OLDREV" --arg url "https://github.com/$REPO/releases/download/$TAG/places-$ID.$OLDREV.vpatch" \
          --argjson sizeMb "$(echo "scale=2; $PATCH/1000000" | bc)" '{fromRev:$fromRev,url:$url,sizeMb:$sizeMb}')
      fi
    fi
    jq -nc --arg id "$ID" --arg name "$NAME_" --arg url "$URL" --argjson sizeMb "$MB" --argjson bbox "$BBOX" --argjson rev "$REV" --argjson delta "$DELTA" \
      '{id:$id,name:$name,url:$url,sizeMb:$sizeMb,bbox:$bbox,rev:$rev} + (if $delta == null then {} else {delta:$delta} end)' >> "$WORK/entries.ndjson"
  done < "$WORK/assets.txt"
  if [ -n "$ENTRIES" ] && ls "$ENTRIES"/*.json >/dev/null 2>&1; then
    # This run's own entries are authoritative for the regions it baked.
    jq -s '.' "$ENTRIES"/*.json > "$WORK/fresh.json"
    jq -s --slurpfile fresh "$WORK/fresh.json" '
      ($fresh[0] | map(.id)) as $ids
      | {regions: (([.[] | select(.id as $i | $ids | index($i) | not)] + $fresh[0]) | sort_by(.name))}
    ' "$WORK/entries.ndjson" > "$WORK/places-overlay-manifest.json"
  else
    jq -s '{regions: (. | sort_by(.name))}' "$WORK/entries.ndjson" > "$WORK/places-overlay-manifest.json"
  fi
}

for attempt in 1 2 3; do
  derive
  cp "$WORK/assets.txt" "$WORK/assets.before"
  gh release upload "$TAG" "$WORK/places-overlay-manifest.json" --clobber --repo "$REPO"
  echo "places manifest now lists $(jq '.regions | length' "$WORK/places-overlay-manifest.json") regions (attempt $attempt)"
  # An archive uploaded while this ran is not in the listing above; go round once more.
  gh release view "$TAG" --repo "$REPO" --json assets -q '.assets[] | "\(.name) \(.size)"' | sort > "$WORK/assets.after"
  if diff -q <(grep '\.pmtiles$' "$WORK/assets.before") <(grep '\.pmtiles$' "$WORK/assets.after") >/dev/null; then break; fi
  echo "the release changed during the merge; rebuilding"
done
