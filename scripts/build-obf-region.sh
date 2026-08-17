#!/usr/bin/env bash
# Bake ONE region's Vela obf and publish it to the `obf-regions` release - the successor pipeline to
# build-routing-region.sh (issue #214). The obf carries routing + address + POI sections only; the
# map-rendering and transport sections are excluded by scripts/VelaObfShim.java (MapLibre draws the
# map, GTFS covers transit), which is most of the size win against a stock OsmAnd file. The asset is
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

# The pinned bake tool (OsmAndMapCreator) lives on the obf-tools release - forks fall back upstream.
curl -fSL -o "$WORK/mapcreator.zip" "https://github.com/$REPO/releases/download/obf-tools/mapcreator.zip" \
  || curl -fSL -o "$WORK/mapcreator.zip" "https://github.com/PimpinPumpkin/Vela/releases/download/obf-tools/mapcreator.zip"
unzip -q "$WORK/mapcreator.zip" -d "$WORK/mapcreator"

curl -fSL --retry 3 -o "$WORK/region.osm.pbf" "$PBF_URL"

# bbox [S,W,N,E] from the extract's declared HEADER box - same rule as every other region pipeline
# (data.bbox is polluted by outlier nodes). osmium prints (minlon,minlat,maxlon,maxlat).
read -r MINLON MINLAT MAXLON MAXLAT < <(osmium fileinfo -g header.boxes "$WORK/region.osm.pbf" | tr -d '()' | tr ',' ' ')
BBOX="[$MINLAT,$MINLON,$MAXLAT,$MAXLON]"

javac -cp "$WORK/mapcreator/OsmAndMapCreator.jar:$WORK/mapcreator/lib/*" -d "$WORK" "$ROOT/scripts/VelaObfShim.java"
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
JAVA_HEAP="${JAVA_HEAP:-14g}"
echo "→ index heap: $JAVA_HEAP"
set +e
( cd "$WORK" && java -Xmx"$JAVA_HEAP" -XX:+UseSerialGC -cp "$WORK/mapcreator/OsmAndMapCreator.jar:$WORK/mapcreator/lib/*:$WORK" VelaObfShim region.osm.pbf )
RC=$?
set -e
if [ $RC -ne 0 ]; then
  # Say WHICH region was too big and how big its source was, so a world bake reports a usable list
  # of what needs baking elsewhere instead of a wall of identical red crosses.
  PBF_MB=$(( ( $(stat -f%z "$WORK/region.osm.pbf" 2>/dev/null || stat -c%s "$WORK/region.osm.pbf") + 1048575 ) / 1048576 ))
  echo "::error::$ID FAILED to index (exit $RC). Source PBF was ${PBF_MB} MB. If this was an" \
       "OutOfMemoryError, this region does not fit a $JAVA_HEAP heap - bake it on a bigger machine."
  exit $RC
fi
OBF="$(ls "$WORK"/*.obf | head -1)"  # generateObf names the output from the pbf filename
mv "$OBF" "$WORK/$ID.obf"

SIZE=$(( ( $(stat -f%z "$WORK/$ID.obf" 2>/dev/null || stat -c%s "$WORK/$ID.obf") + 1048575 ) / 1048576 ))
ASSET_URL="https://github.com/$REPO/releases/download/$TAG/$ID.obf"
echo "→ $ID: ${SIZE} MB obf (download == installed), bbox $BBOX"

gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1 || \
  gh release create "$TAG" --repo "$REPO" --prerelease --title "Offline obf regions" \
    --notes "Vela-baked obf files (routing + address + POI, no rendering section) for offline routing and search. Data assets, not a code release."

gh release upload "$TAG" "$WORK/$ID.obf" --clobber --repo "$REPO"

# Raw obf: the download size IS the installed size, so both fields carry the same number and the
# Settings row needs no unpack estimate.
ENTRY="$(jq -nc --arg id "$ID" --arg name "$NAME" --arg url "$ASSET_URL" --argjson size "$SIZE" --argjson bbox "$BBOX" \
  '{id:$id,name:$name,url:$url,sizeMb:$size,installedMb:$size,bbox:$bbox}')"

if [ "${MANIFEST_MODE:-merge}" = "emit" ]; then
  printf '%s\n' "$ENTRY" > "${ENTRY_OUT:?ENTRY_OUT required in emit mode}"
  echo "emitted manifest entry to $ENTRY_OUT"
  exit 0
fi

ENTRY_DIR="$(mktemp -d)"; trap 'rm -rf "$WORK" "$ENTRY_DIR"' EXIT
printf '%s\n' "$ENTRY" > "$ENTRY_DIR/$ID.json"
bash "$ROOT/scripts/merge-obf-manifest.sh" "$ENTRY_DIR"
