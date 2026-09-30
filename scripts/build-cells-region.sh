#!/usr/bin/env bash
# Cut ONE catalog region into grid cells and bake a bundle per cell: the routing obf, the place
# pack and the region's places PMTiles clipped to the cell. Cells are 0.5 degree tiles of a global
# grid clipped to the region's header box. See SPEC "Grid-cell downloads" for the format.
#
#   scripts/build-cells-region.sh <region-id> [local.osm.pbf]
#
# Reads name + pbf_url from tools/routing-regions.json; a local extract skips the download.
# Writes $OUT_DIR/<cell-id>.zip per cell and $OUT_DIR/cells-<region>.json (the manifest
# fragment). CELLS_UPLOAD=1 also uploads both to the `cells-<region>` release.
#
# Env:
#   OUT_DIR          output dir (default ./cells-out/<region>)
#   PLACES_PMTILES   places archive, path or URL (default: places-<region>.pmtiles on the
#                    places-overlays release; `none` skips the places slice)
#   CELL_JOBS        cells baked in parallel (default 2)
#   CELL_HEAP        MapCreator heap per cell (default 3g)
#   CELLS_BATCH      extracts per osmium pass (default 4)
#   MAPCREATOR_ZIP   local copy of the obf-tools mapcreator.zip
#   REV              revision stamp (default today, YYYYMMDD UTC)
# Needs: curl, unzip, zip, osmium, jq, python3, a JDK, pmtiles (go-pmtiles), gh (upload only).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/bake-lib.sh"
REPO="${VELA_REPO:-PimpinPumpkin/Vela}"
MAX_CELLS=999 # a GitHub release holds 1000 assets: the zips plus the fragment

# --- per-cell worker (re-entered through xargs so cells bake in parallel) -------------------
if [ "${1:-}" = "--cell" ]; then
  CID="$2"; S="$3"; W="$4"; N="$5"; E="$6"; CUT="$7"
  D="$CELLS_WORK/$CID"; mkdir -p "$D"
  RAW="$CELLS_WORK/raw/$CID.osm.pbf"
  T0=$(date +%s)
  PARTS=()
  # routing obf: skipped when the cell has no road
  bake_obf_filter "$RAW" "$D/cell.osm.pbf" >/dev/null
  WAYS=$(osmium fileinfo -e -g data.count.ways "$D/cell.osm.pbf")
  if [ "$WAYS" -gt 0 ]; then
    if ! bake_obf_index "$D" cell.osm.pbf "${CELL_HEAP:-3g}" "$CELLS_TOOLS" >"$D/obf.log" 2>&1; then
      echo "::error::$CID obf bake failed"; tail -20 "$D/obf.log"; exit 1
    fi
    mv "$(ls "$D"/*.obf | head -1)" "$D/$CID.obf"
    PARTS+=("$CID.obf")
  fi
  rm -f "$D/cell.osm.pbf"
  T1=$(date +%s)
  # place pack: skipped when it holds no POI, address or street
  bake_pack_filter "$RAW" "$D/pack.osm.pbf" >/dev/null
  bake_pack_build "$D/pack.osm.pbf" "$D/$CID.db" >"$D/pack.log" 2>&1
  rm -f "$D/pack.osm.pbf"
  ROWS=$(jq '.poi + .addr + .streetpt' "$D/$CID.db.counts.json")
  if [ "$ROWS" -gt 0 ]; then PARTS+=("$CID.db"); else rm -f "$D/$CID.db"; fi
  T2=$(date +%s)
  # A cell with no road and no place is dropped; a places slice alone would be the neighbor's.
  if [ ${#PARTS[@]} -eq 0 ]; then echo "  $CID: no roads or places, dropped"; rm -rf "$D" "$RAW"; exit 0; fi
  # places slice: the region archive's tiles inside the region polygon clipped to the cell
  AREA="--bbox=$W,$S,$E,$N"
  [ "$CUT" = "region" ] && AREA="--region=$CELLS_WORK/regions/$CID.geojson"
  if [ -n "$CELLS_PLACES" ] && [ "$CUT" != "-" ]; then
    if pmtiles extract "$CELLS_PLACES" "$D/places-$CID.pmtiles" "$AREA" >"$D/places.log" 2>&1 \
       && [ "$(pmtiles show "$D/places-$CID.pmtiles" 2>/dev/null | awk -F': ' '/addressed tiles count/ {print $2}')" -gt 0 ] 2>/dev/null; then
      PARTS+=("places-$CID.pmtiles")
    else
      rm -f "$D/places-$CID.pmtiles"
    fi
  fi
  T3=$(date +%s)
  INSTALLED=0; for p in "${PARTS[@]}"; do INSTALLED=$(( INSTALLED + $(bake_bytes "$D/$p") )); done
  # obf blocks and PMTiles tiles are already compressed: store them, deflate only the pack
  ( cd "$D" && zip -q -n .obf:.pmtiles "$CELLS_OUT/$CID.zip" "${PARTS[@]}" )
  jq -nc --arg id "$CID" --argjson bbox "[$S,$W,$N,$E]" --argjson installed "$INSTALLED" \
    --argjson bytes "$(bake_bytes "$CELLS_OUT/$CID.zip")" \
    --argjson parts "$(printf '%s\n' "${PARTS[@]}" | sed -E 's/.*\.obf$/obf/; s/.*\.db$/pack/; s/.*\.pmtiles$/places/' | jq -R . | jq -sc .)" \
    --argjson tobf $((T1 - T0)) --argjson tpack $((T2 - T1)) --argjson tplaces $((T3 - T2)) \
    '{id:$id,bbox:$bbox,bytes:$bytes,installed:$installed,parts:$parts,secs:{obf:$tobf,pack:$tpack,places:$tplaces}}' \
    > "$CELLS_WORK/rows/$CID.json"
  echo "  $CID: ${PARTS[*]} ($(( $(bake_bytes "$CELLS_OUT/$CID.zip") / 1024 )) KB, $((T3 - T0)) s)"
  rm -rf "$D" "$RAW"
  exit 0
fi

# --- region driver -------------------------------------------------------------------------
ID="${1:?region id}"
LOCAL_PBF="${2:-}"
ROW="$(jq -c --arg id "$ID" '.regions[] | select(.id == $id)' "$ROOT/tools/routing-regions.json")"
[ -n "$ROW" ] || { echo "no row '$ID' in tools/routing-regions.json" >&2; exit 1; }
NAME="$(jq -r .name <<<"$ROW")"
PBF_URL="$(jq -r .pbf_url <<<"$ROW")"
TAG="cells-$ID"
REV="${REV:-$(date -u +%Y%m%d)}"
OUT_DIR="${OUT_DIR:-$PWD/cells-out/$ID}"
mkdir -p "$OUT_DIR"; OUT_DIR="$(cd "$OUT_DIR" && pwd)"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
START=$(date +%s)

if [ -n "$LOCAL_PBF" ]; then
  PBF="$(cd "$(dirname "$LOCAL_PBF")" && pwd)/$(basename "$LOCAL_PBF")"
else
  PBF="$WORK/region.osm.pbf"
  bash "$ROOT/scripts/fetch-pbf.sh" "$PBF_URL" "$PBF"
fi
BBOX="$(bake_header_bbox "$PBF" | python3 "$ROOT/scripts/clamp-bbox.py" "$ID")"
echo "→ $ID: bbox $BBOX"

# Places archive: a local path, a URL (downloaded once), or skipped with a message.
PLACES_SRC="${PLACES_PMTILES:-https://github.com/$REPO/releases/download/places-overlays/places-$ID.pmtiles}"
PLACES=""
if [ "$PLACES_SRC" = "none" ]; then
  echo "→ places slice skipped (PLACES_PMTILES=none)"
elif [ -f "$PLACES_SRC" ]; then
  PLACES="$PLACES_SRC"
elif curl -fsSL --retry 3 -o "$WORK/places.pmtiles" "$PLACES_SRC" 2>/dev/null; then
  PLACES="$WORK/places.pmtiles"
else
  echo "→ places slice skipped: no archive at $PLACES_SRC (set PLACES_PMTILES to a path or URL)"
fi

bake_mapcreator "$WORK"

# The cells (scripts/cells_grid.py): STEP degree tiles of a global grid, clipped to the box and
# to the region's polygon. STEP starts at 0.5 and doubles until the region fits one release
# (Alaska: 4949 half-degree box tiles; its land fits at a coarser step).
STEP="${CELL_STEP:-0.5}"
while :; do
  rm -rf "$WORK/regions"; mkdir -p "$WORK/regions"
  python3 "$ROOT/scripts/cells_grid.py" "$ID" "$BBOX" "$ROOT/app/src/main/assets/region_polys.json" "$WORK/regions" "$STEP" \
    > "$WORK/cells.tsv"
  NCELLS=$(wc -l < "$WORK/cells.tsv" | tr -d ' ')
  [ "$NCELLS" -gt "$MAX_CELLS" ] || break
  echo "→ $NCELLS cells at $STEP degrees is over $MAX_CELLS; doubling the step"
  STEP=$(awk -v s="$STEP" 'BEGIN { print s * 2 }')
  [ "$(awk -v s="$STEP" 'BEGIN { print (s > 8) }')" = 0 ] || { echo "::error::$ID does not fit $MAX_CELLS cells at 8 degrees"; exit 1; }
done
echo "→ $NCELLS grid cells at $STEP degrees"

# The extract is first cut to what the two per-cell filters can keep (the union of their tag
# expressions), then one osmium pass writes every cell of a batch; complete_ways keeps a way that
# crosses the edge whole in both cells. osmium keeps id sets per extract sized by the id range,
# about 2 GB for a dense cell (one dense Northern California cell peaked at 3.7 GB, five at
# 11.8 GB), so a batch of 4 fits a 16 GB runner.
mkdir -p "$WORK/raw" "$WORK/rows"
T_SPLIT=$(date +%s)
osmium tags-filter "$PBF" "${BAKE_OBF_EXPR[@]}" "${BAKE_PACK_EXPR[@]}" -o "$WORK/cellsrc.osm.pbf" --overwrite
echo "→ cell source: $(bake_mib "$PBF") MB extract -> $(bake_mib "$WORK/cellsrc.osm.pbf") MB"
split -l "${CELLS_BATCH:-4}" "$WORK/cells.tsv" "$WORK/batch."
for B in "$WORK"/batch.*; do
  jq -Rn --arg dir "$WORK/raw" '{directory:$dir, extracts:[inputs | split("\t")
    | {output:(.[0] + ".osm.pbf"), bbox:[(.[2]|tonumber),(.[1]|tonumber),(.[4]|tonumber),(.[3]|tonumber)]}]}' \
    < "$B" > "$B.json"
  osmium extract -c "$B.json" -s complete_ways --overwrite "$WORK/cellsrc.osm.pbf"
done
rm -f "$WORK/cellsrc.osm.pbf" "$WORK/region.osm.pbf"
echo "→ split in $(( $(date +%s) - T_SPLIT )) s"

export CELLS_WORK="$WORK" CELLS_OUT="$OUT_DIR" CELLS_TOOLS="$WORK" CELLS_PLACES="$PLACES" CELL_HEAP="${CELL_HEAP:-3g}"
rm -f "$OUT_DIR"/*.zip
T_BAKE=$(date +%s)
tr '\t' ' ' < "$WORK/cells.tsv" | xargs -P "${CELL_JOBS:-2}" -L 1 bash "$0" --cell
echo "→ baked in $(( $(date +%s) - T_BAKE )) s"

# The region's manifest fragment: the same shape as its row in cells-manifest.json.
jq -s --arg id "$ID" --arg name "$NAME" --arg repo "$REPO" --arg tag "$TAG" --argjson rev "$REV" '
  { id: $id, name: $name, rev: $rev,
    cells: (sort_by(.id) | map({
      id, bbox,
      url: "https://github.com/\($repo)/releases/download/\($tag)/\(.id).zip",
      sizeMb: ((.bytes / 10000 | ceil) / 100),
      installedMb: ((.installed / 10000 | ceil) / 100),
      rev: $rev, parts })) }' "$WORK"/rows/*.json > "$OUT_DIR/$TAG.json"
jq -s '{cells: length, zipMb: ((map(.bytes) | add) / 1e6), installedMb: ((map(.installed) | add) / 1e6),
        obfSecs: (map(.secs.obf) | add), packSecs: (map(.secs.pack) | add), placesSecs: (map(.secs.places) | add)}' \
  "$WORK"/rows/*.json > "$OUT_DIR/stats.json"
echo "→ $ID: $(jq -r '"\(.cells) cells, \(.zipMb*100|round/100) MB zipped, \(.installedMb*100|round/100) MB installed"' "$OUT_DIR/stats.json"), $(( $(date +%s) - START )) s total"

[ "${CELLS_UPLOAD:-0}" = "1" ] || exit 0

# Upload: the zips, then the fragment last, so the merge never lists a cell whose zip is missing.
# The Actions token has 1,000 API requests an hour for the whole repository, shared by every job
# and workflow in it; a wave of eight states uploading a few hundred cells each spent it in
# minutes and lost five finished bakes to HTTP 403 (2026-09-28). So: big batches (one listing per
# call), and when the limit is exhausted the retry waits for the reset (about an hour at worst;
# rate_limit itself is free) instead of giving up after a few minutes.
rate_wait() {
  local rem reset now
  rem=$(gh api rate_limit --jq .resources.core.remaining 2>/dev/null || echo 1)
  # Stop while RATE_RESERVE requests are left, not at zero: the budget is the whole repository's,
  # and a bake that spent it to the last request failed the canary release and the F-Droid index.
  if [ "${rem:-1}" -le "${RATE_RESERVE:-200}" ]; then
    reset=$(gh api rate_limit --jq .resources.core.reset 2>/dev/null || echo 0); now=$(date +%s)
    if [ "$reset" -gt "$now" ]; then
      echo "API rate limit exhausted; waiting $((reset - now + 15)) s for the reset"; sleep $((reset - now + 15))
    fi
  fi
}
# A refused upload that names the rate limit backs off for minutes, not seconds (the first world
# wave lost five finished regions to 403s twenty seconds apart while rate_wait's pre-check still
# read requests as available): 5, 10, 20, 30, 30, 30, 30 minutes, about two hours in all.
RATE_BACKOFF=(300 600 1200 1800 1800 1800 1800)
upload() {
  local try out
  for try in 1 2 3 4 5 6 7 8; do
    rate_wait
    if out=$(gh release upload "$TAG" "$@" --clobber --repo "$REPO" 2>&1); then return 0; fi
    echo "$out" | tail -2
    if echo "$out" | grep -qi "rate limit"; then
      local wait=${RATE_BACKOFF[$((try - 1 < 6 ? try - 1 : 6))]}
      echo "upload attempt $try hit the API rate limit; waiting $wait s"; sleep "$wait"
    else
      echo "upload attempt $try failed; retrying in $((try * 20)) s"; sleep $((try * 20))
    fi
  done
  return 1
}
# The release itself goes through the same backoff: the second world wave lost finished regions on
# the view/create call before their first upload, where a 403 read as "no release" and the create
# then failed too.
ensure_release() {
  local try out wait
  for try in 1 2 3 4 5 6 7 8; do
    rate_wait
    out=$(gh release view "$TAG" --repo "$REPO" --json tagName 2>&1) && return 0
    if ! echo "$out" | grep -qi "rate limit"; then
      out=$(gh release create "$TAG" --repo "$REPO" --prerelease --target "$CELLS_RELEASE_TARGET" \
        --title "Offline cells: $NAME" \
        --notes "Grid-cell offline bundles (routing obf, place pack, places tiles) for $NAME. Data assets, not a code release." 2>&1) \
        && return 0
      echo "$out" | grep -qi "already exists" && return 0
    fi
    echo "$out" | tail -2
    if echo "$out" | grep -qi "rate limit"; then
      wait=${RATE_BACKOFF[$((try - 1 < 6 ? try - 1 : 6))]}
      echo "release attempt $try hit the API rate limit; waiting $wait s"; sleep "$wait"
    else
      echo "release attempt $try failed; retrying in $((try * 20)) s"; sleep $((try * 20))
    fi
  done
  return 1
}
ensure_release
ZIPS=("$OUT_DIR"/*.zip)
for ((k = 0; k < ${#ZIPS[@]}; k += 100)); do upload "${ZIPS[@]:k:100}"; done
upload "$OUT_DIR/$TAG.json"
echo "✓ uploaded $ID cells to $TAG"
