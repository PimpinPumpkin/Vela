# Shared steps of the obf, place-pack and grid-cell bakes. Source it; it defines functions only.
#
#   source "$ROOT/scripts/bake-lib.sh"
#
# Needs: curl, unzip, osmium, python3, a JDK (obf steps).

# Size of a file in bytes (BSD and GNU stat).
# A release sorts by its TARGET COMMIT's date, not by when it was made. A cells release per region
# created on HEAD would fill the first page of the releases API (Obtainium reads only the first 100,
# and every `--limit N` query saw the same window), so the data releases are created on the repo's
# root commit and sort to the bottom, under every app release. Checked 2026-09-28: a release on this
# commit listed last of 33.
CELLS_RELEASE_TARGET=5e9cee460e0da3a0ec2a488eec2dbba372d0c1b7

bake_bytes() { stat -f%z "$1" 2>/dev/null || stat -c%s "$1"; }

# Size of a file in MiB, rounded up (the obf and pack manifests' sizeMb).
bake_mib() { echo $(( ( $(bake_bytes "$1") + 1048575 ) / 1048576 )); }

# [S,W,N,E] of an extract from its declared HEADER box, not data.bbox (outlier nodes pollute that).
# osmium prints (minlon,minlat,maxlon,maxlat).
bake_header_bbox() {
  local minlon minlat maxlon maxlat
  read -r minlon minlat maxlon maxlat < <(osmium fileinfo -g header.boxes "$1" | tr -d '()' | tr ',' ' ')
  echo "[$minlat,$minlon,$maxlat,$maxlon]"
}

# The pinned OsmAndMapCreator from the obf-tools release (forks fall back upstream) unpacked into
# <dir>/mapcreator, and VelaObfShim compiled into <dir>. MAPCREATOR_ZIP points at a local copy.
bake_mapcreator() {
  local dir="$1" repo="${VELA_REPO:-PimpinPumpkin/Vela}" root
  root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
  if [ ! -f "$dir/mapcreator/OsmAndMapCreator.jar" ]; then
    if [ -n "${MAPCREATOR_ZIP:-}" ]; then
      cp "$MAPCREATOR_ZIP" "$dir/mapcreator.zip"
    else
      curl -fSL -o "$dir/mapcreator.zip" "https://github.com/$repo/releases/download/obf-tools/mapcreator.zip" \
        || curl -fSL -o "$dir/mapcreator.zip" "https://github.com/PimpinPumpkin/Vela/releases/download/obf-tools/mapcreator.zip"
    fi
    unzip -q "$dir/mapcreator.zip" -d "$dir/mapcreator"
  fi
  javac -cp "$dir/mapcreator/OsmAndMapCreator.jar:$dir/mapcreator/lib/*" -d "$dir" "$root/scripts/VelaObfShim.java"
}

# ROUTING-ONLY bakes index a PRE-FILTERED extract: MapCreator's memory ceiling is its first
# pass over every node in the file, and buildings, landuse and the rest of the map are most of
# those nodes. Keeping only highway ways (with their nodes, so barriers, signals and crossings
# come along), ferry and shuttle-train routes and turn-restriction relations cuts a US-state
# extract to roughly a third of its bytes and a quarter of its nodes in a few seconds (route
# relations ride along for the bicycle profile's signed-route preference), which is
# what brings the big rows under a 16 GB runner's heap (measured on a state bake 2026-09-11, see
# CLAUDE.md). A bake that asks for the address or POI sections needs the whole file and skips it.
#   bake_obf_filter <in.pbf> <out.pbf>
BAKE_OBF_EXPR=(w/highway w/route=ferry,shuttle_train r/type=restriction r/type=route)
bake_obf_filter() {
  osmium tags-filter "$1" "${BAKE_OBF_EXPR[@]}" -o "$2" --overwrite
}

# Index <dir>/<pbf-name> with VelaObfShim (bake_mapcreator must have run into <tools>). MapCreator
# names the .obf after the input and writes it into <dir>. Returns java's exit code.
#   bake_obf_index <dir> <pbf-name> <heap> <tools-dir>
bake_obf_index() {
  local dir="$1" pbf="$2" heap="$3" tools="$4"
  ( cd "$dir" && java -Xmx"$heap" -XX:+UseParallelGC \
      -cp "$tools/mapcreator/OsmAndMapCreator.jar:$tools/mapcreator/lib/*:$tools" VelaObfShim "$pbf" )
}

# OsmAnd's HIGHWAY HIERARCHY (HH) for the car profile, written into the region's own obf. Without
# it the app's router searches the whole road graph and a long route in a dense region fails at its
# 256 MB budget ("not enough memory", or the heap itself: Cologne to Munster, Aachen to Bielefeld);
# with it the same trips take under a second in about 80 MB (measured 2026-09-29 on the
# North Rhine-Westphalia obf, SPEC 4.5). Three MapCreator steps in a scratch folder: cluster the
# network (hh-routing-prepare), precompute the shortcuts (hh-routing-shortcuts, which also writes
# them as a small standalone obf named after the folder), then combine that section into the
# region file (BinaryInspector -c). The app turns HH on with setDefaultHHRoutingConfig() and falls
# back to the plain search on its own, so a file without HH still routes exactly as before.
# Returns non-zero and leaves <obf> untouched on any failure (an out-of-memory on a huge region).
#   bake_obf_hh <tools-dir> <obf> <heap>
bake_obf_hh() {
  local tools="$1" obf="$2" heap="$3" hh cp threads
  hh="$(dirname "$obf")/hh"; rm -rf "$hh"; mkdir -p "$hh"
  cp="$tools/mapcreator/OsmAndMapCreator.jar:$tools/mapcreator/lib/*"
  threads="$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 2)"
  ln -s "$obf" "$hh/region.obf"
  ( cd "$hh" \
    && java -Xmx"$heap" -XX:+UseParallelGC -cp "$cp" net.osmand.MainUtilities hh-routing-prepare region.obf --routing_profile=car \
    && java -Xmx"$heap" -XX:+UseParallelGC -cp "$cp" net.osmand.MainUtilities hh-routing-shortcuts region.obf --routing_profile=car --threads="$threads" \
    && [ -s hh_car.obf ] \
    && java -Xmx2g -cp "$cp" net.osmand.obf.BinaryInspector -c combined.obf "$obf" hh_car.obf \
    && [ "$(stat -c%s combined.obf 2>/dev/null || stat -f%z combined.obf)" -gt "$(stat -c%s "$obf" 2>/dev/null || stat -f%z "$obf")" ] ) \
    || { rm -rf "$hh"; return 1; }
  mv "$hh/combined.obf" "$obf"
  rm -rf "$hh"
}

# The place-pack filter: POIs, addresses and named roads.
#   bake_pack_filter <in.pbf> <out.pbf>
BAKE_PACK_EXPR=(
  nwr/amenity nwr/shop nwr/tourism nwr/leisure nwr/public_transport nwr/boundary=national_park
  nwr/addr:housenumber
  w/highway=motorway,trunk,primary,secondary,tertiary,unclassified,residential,living_street,service,road,motorway_link,trunk_link,primary_link,secondary_link,tertiary_link
)
bake_pack_filter() {
  osmium tags-filter "$1" "${BAKE_PACK_EXPR[@]}" -o "$2" --overwrite
}

# The export STREAMS into the pack builder, never written to disk. The geojsonseq is ~12x the
# filtered PBF (a large state: 161 MB -> 1.9 GB), so a country-sized export on disk would blow a
# 14 GB CI runner; piped, the peak disk is just filtered.pbf + the SQLite db. Writes <out.db> and
# <out.db>.counts.json.
#   bake_pack_build <filtered.pbf> <out.db>
bake_pack_build() {
  local root
  root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
  osmium export "$1" -f geojsonseq --add-unique-id=type_id -o - \
    | python3 "$root/scripts/poipack_build.py" - "$2"
}
