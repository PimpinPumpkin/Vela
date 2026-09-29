#!/usr/bin/env bash
# Bake ONE region's Vela obf and publish it to the `obf-regions` release - the successor pipeline to
# build-routing-region.sh (issue #214). By default the obf carries the ROUTING section only, indexed
# lean (scripts/VelaObfShim.java: no multipolygon, route-relation, proximity or country-region
# passes); address + POI come from the poi-packs SQLite, and MapLibre draws the map, GTFS covers
# transit. VELA_OBF_SECTIONS / VELA_OBF_LEAN widen it. Routing-only lean is what fits a 16 GB CI
# runner for a US-state-sized extract (measured 2026-09-04); the full set does not. The asset is
# served RAW (an obf's blocks are already deflate-compressed), so download size == installed size.
#
#   scripts/build-obf-region.sh <id> "<display name>" <pbf-url>
#
# MANIFEST_MODE=emit (CI matrix) drops a single manifest-entry json to $ENTRY_OUT for the central
# merge job; the default merges into obf-manifest.json directly (local single-region use).
# Needs: curl, unzip, osmium, jq, a JDK, gh (auth) for upload.
set -euo pipefail

ID="${1:?region id}"
NAME="${2:?display name}"
PBF_URL="${3:?pbf url}"
REPO="${VELA_REPO:-PimpinPumpkin/Vela}"
TAG="obf-regions"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

source "$ROOT/scripts/bake-lib.sh"

# The pinned bake tool (OsmAndMapCreator) lives on the obf-tools release - forks fall back upstream.
bake_mapcreator "$WORK"

curl -fSL --retry 3 -o "$WORK/region.osm.pbf" "$PBF_URL"

# bbox [S,W,N,E] from the extract's declared HEADER box - same rule as every other region pipeline.
BBOX="$(bake_header_bbox "$WORK/region.osm.pbf")"
# Not processInRam (it already defaults to false): MapCreator's disk-backed pipeline is what lets a
# region bake inside a small runner at all. The heap bound is for its indexes, not the region.
#
# HEAP (2026-08-16). MEASURED, and the honest summary is that a GitHub runner cannot do this at
# country scale:
#   luxembourg  (~30 MB pbf)  -> 39 MB obf   OK at 12g
#   delaware    (~30 MB pbf)  -> 20 MB obf   OK at 12g
#   czech-republic            -> OOM at 12g (~37 min in)
#   de-bayern   (810 MB pbf)  -> OOM at 12g (~37 min), and OOM AGAIN at 14g after THREE HOURS
# So the ceiling sits somewhere between a US state and an 810 MB extract, and splitting a country
# into sub-areas does NOT by itself get under it - Bayern IS a sub-area. Note the 14g run's three
# hours: a job that slows down and then dies is thrashing a nearly-full heap, so a long runtime
# here is a symptom of the failure, not progress toward success. SerialGC keeps G1's region
# bookkeeping and concurrent threads (hundreds of MB) out of the way, but with the heap this close
# to full its single-threaded full collections are likely most of that three hours - try
# ParallelGC before reading any timing from this path as the tool's true cost.
# Regions this size need a machine with more RAM than a 16 GB runner; JAVA_HEAP exists so a local
# bake can be handed the headroom. MEASURED on a 32 GB machine: bayern at -Xmx22g SUCCEEDS in
# 3h07m and produces a 694 MB obf, with the heap peaking around 18.4 GB before a full GC - which is
# why 14g never had a chance, and why no GC flag was ever going to save it. ParallelGC vs SerialGC
# did not change the outcome, only the time to reach it (50 min vs 3 h to the same OOM), so it is
# still the right collector here: a doomed region should fail fast.
# 12g, not 14g: a 16 GB runner OOM-kills the JVM itself above that, and the lean routing-only
# bake (see VelaObfShim.java) was MEASURED to complete a 345 MB US state at 12g.
# ROUTING-ONLY bakes index a PRE-FILTERED extract (bake_obf_filter in bake-lib.sh says why).
INDEX_PBF="region.osm.pbf"
if [[ "${VELA_OBF_SECTIONS:-routing}" == "routing" ]]; then
  bake_obf_filter "$WORK/region.osm.pbf" "$WORK/region-routing.osm.pbf"
  FULL_MB=$(bake_mib "$WORK/region.osm.pbf")
  ROUT_MB=$(bake_mib "$WORK/region-routing.osm.pbf")
  echo "→ routing-only filter: ${FULL_MB} MB extract -> ${ROUT_MB} MB of roads"
  INDEX_PBF="region-routing.osm.pbf"
fi
JAVA_HEAP="${JAVA_HEAP:-12g}"
echo "→ index heap: $JAVA_HEAP"
set +e
bake_obf_index "$WORK" "$INDEX_PBF" "$JAVA_HEAP" "$WORK"
RC=$?
set -e
if [ $RC -ne 0 ]; then
  # Say WHICH region was too big and how big its source was, so a world bake reports a usable list
  # of what needs baking elsewhere instead of a wall of identical red crosses.
  PBF_MB=$(bake_mib "$WORK/region.osm.pbf")
  echo "::error::$ID FAILED to index (exit $RC). Source PBF was ${PBF_MB} MB. If this was an" \
       "OutOfMemoryError, this region does not fit a $JAVA_HEAP heap - bake it on a bigger machine."
  exit $RC
fi
OBF="$(ls "$WORK"/*.obf | head -1)"  # generateObf names the output from the pbf filename
mv "$OBF" "$WORK/$ID.obf"

# Highway hierarchy for the car profile (bake_obf_hh in bake-lib.sh says why). A region too big
# for it still ships, without HH, and routes as it always did; VELA_OBF_HH=0 skips the step.
HH=false
if [[ "${VELA_OBF_HH:-1}" == "1" ]]; then
  BEFORE_MB=$(bake_mib "$WORK/$ID.obf")
  set +e
  bake_obf_hh "$WORK" "$WORK/$ID.obf" "$JAVA_HEAP" > "$WORK/hh.log" 2>&1
  HRC=$?
  set -e
  if [ $HRC -eq 0 ]; then
    HH=true
    echo "→ highway hierarchy added: ${BEFORE_MB} MB -> $(bake_mib "$WORK/$ID.obf") MB"
  else
    echo "::warning::$ID: highway hierarchy step failed (exit $HRC), shipping without it"; tail -20 "$WORK/hh.log"
  fi
fi

SIZE=$(bake_mib "$WORK/$ID.obf")
ASSET_URL="https://github.com/$REPO/releases/download/$TAG/$ID.obf"
echo "→ $ID: ${SIZE} MB obf (download == installed), bbox $BBOX"

gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1 || \
  gh release create "$TAG" --repo "$REPO" --prerelease --title "Offline obf regions" \
    --notes "Vela-baked obf files (routing section only, lean bake) for offline routing. Data assets, not a code release."

gh release upload "$TAG" "$WORK/$ID.obf" --clobber --repo "$REPO"

# Raw obf: the download size IS the installed size, so both fields carry the same number and the
# Settings row needs no unpack estimate.
# rev = the bake date as an integer; the app re-downloads an installed region whose manifest rev is newer.
ENTRY="$(jq -nc --arg id "$ID" --arg name "$NAME" --arg url "$ASSET_URL" --argjson size "$SIZE" --argjson bbox "$BBOX" --argjson rev "$(date -u +%Y%m%d)" --argjson hh "$HH" \
  '{id:$id,name:$name,url:$url,sizeMb:$size,installedMb:$size,bbox:$bbox,rev:$rev,hh:$hh}')"

if [ "${MANIFEST_MODE:-merge}" = "emit" ]; then
  printf '%s\n' "$ENTRY" > "${ENTRY_OUT:?ENTRY_OUT required in emit mode}"
  echo "emitted manifest entry to $ENTRY_OUT"
  exit 0
fi

ENTRY_DIR="$(mktemp -d)"; trap 'rm -rf "$WORK" "$ENTRY_DIR"' EXIT
printf '%s\n' "$ENTRY" > "$ENTRY_DIR/$ID.json"
bash "$ROOT/scripts/merge-obf-manifest.sh" "$ENTRY_DIR"
