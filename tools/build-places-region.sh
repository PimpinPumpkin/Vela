#!/usr/bin/env bash
# Bake an open-data places layer for one region: Overture Places -> PMTiles.
#
#   tools/build-places-region.sh <id> <S> <W> <N> <E> <out.pmtiles> [overture-release] [local.parquet]
#
# Needs duckdb (with httpfs + spatial), tippecanoe. Reads Overture straight from its public S3
# bucket unless a local parquet extract is given (a dev shortcut: the same columns, see the
# SELECT below). Business places only: parks, schools, civic and transit stay with OSM, whose
# area mapping is far better for them. Each feature carries the icon group the app already
# themes with, a prominence on the ambient layer's 0-9.5 scale (category prior, brand, contact
# details, Overture confidence), a rank within its ~400 m cell (`rank`) and within its ~1.6 km cell
# (`crank`) by that prominence, and a tippecanoe minzoom from the ranks: the best place in each
# 1.6 km cell is in the z13/z14 tiles, the top three per 400 m cell reach z15, the top twelve z16,
# everything z17. The app then decides per zoom which of the features in a tile get an icon, a
# label, or just a dot (VelaMapView), so a downtown thins to its landmarks the way Google's does
# and a village keeps its one cafe at z15.
#
# ALLTHEPLACES (2026-09-15): Overture's places come mostly from Meta and Bing, so a chain store
# with no Facebook page is simply absent. AllThePlaces (alltheplaces.xyz, CC0) scrapes every
# chain's OWN store locator weekly and publishes the result as one world PMTiles; the region's
# z15 tiles are pulled with `pmtiles extract` (a few range requests, seconds), decoded, filtered
# to real businesses by their OSM-style tags, and merged into the Overture rows: a locator point
# that has an Overture row of the same brand or the same leading name words within ~150 m is
# dropped, the rest join with a lower confidence than Overture's own. Chain rows carry
# `opening_hours`, which Overture never has. ATP_RUN=none skips it (also when the pmtiles or
# tippecanoe-decode binaries are missing); ATP_LOCAL points at a local extract for dev runs.
set -euo pipefail
# The repo root, for files the bake reads beside itself (the generic-word list pinned to
# core/util/PlaceNames.GENERIC); CI runs the script from the checkout root, a dev may not.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ID="$1"; S="$2"; W="$3"; N="$4"; E="$5"; OUT="$6"; RELEASE="${7:-2026-08-19.0}"; LOCAL="${8:-}"
# The AllThePlaces run: the NEWEST published one unless the caller pins ATP_RUN. It was a fixed
# id until 2026-09-22, so every bake since 2026-09-15 carried the same week-old locator data
# while AllThePlaces publishes weekly; the fixed id stays as the fallback when the pointer fetch
# fails, so a bake never runs with no chain data because a JSON was unreachable.
if [ -z "${ATP_RUN:-}" ]; then
  ATP_RUN="$(curl -fsSL --max-time 30 https://data.alltheplaces.xyz/runs/latest.json 2>/dev/null | jq -r '.run_id // empty' 2>/dev/null || true)"
  ATP_RUN="${ATP_RUN:-2026-09-05-13-32-25}"
  echo "AllThePlaces run: $ATP_RUN"
fi
# TMPDIR decides where the scratch goes, and for a continent-sized region that matters: the
# AllThePlaces extract, the OSM extract, DuckDB's spill and tippecanoe's temp files add up to more
# than a CI runner's root disk holds (see the workflow, which points it at the big mount).
WORK="$(mktemp -d)"
echo "work dir $WORK ($(df -Pm "$WORK" | awk 'NR==2 {print $4}') MB free)"
ATP_NDJSON=""
if [ "$ATP_RUN" != "none" ] && command -v pmtiles >/dev/null 2>&1 && command -v tippecanoe-decode >/dev/null 2>&1 && command -v jq >/dev/null 2>&1; then
  ATP_SRC="${ATP_LOCAL:-https://alltheplaces-data.openaddresses.io/runs/$ATP_RUN/output.pmtiles}"
  # Both halves report what they cost. Silencing them meant a bake that died here died with no
  # output at all, which reads as flaky infrastructure rather than as a step (five regions, three
  # waves, 2026-09-18).
  # The tag test is `isbiz` in cheap form, and it runs HERE rather than in DuckDB because
  # AllThePlaces carries national ADDRESS registers alongside the store locators: Belgium's decode
  # wrote a 3.5 GB ndjson of 7.1 million rows, nearly all of them addresses from one Flemish
  # dataset, which DuckDB then parsed only to throw away. Nothing `isbiz` would accept lacks one of
  # these keys, so the pre-filter cannot drop a business.
  echo "alltheplaces: extracting z15 for $ID"
  if pmtiles extract "$ATP_SRC" "$WORK/atp.pmtiles" --bbox="$W,$S,$E,$N" --minzoom=15 --maxzoom=15 >/dev/null 2>"$WORK/atp.err"; then
    echo "alltheplaces: extract $(du -m "$WORK/atp.pmtiles" | cut -f1) MB, decoding"
    # grep FIRST, and that is not a micro-optimization: tippecanoe-decode emits the whole tileset
    # as ONE json document, so `jq ..` over it parses the entire thing into memory and a dense
    # country took the CI runner down with it. Every feature is exactly one line, so grep turns the
    # document into a stream jq reads one small value at a time, in constant memory.
    tippecanoe-decode -z15 -Z15 "$WORK/atp.pmtiles" 2>/dev/null \
      | grep -F '"type": "Feature",' \
      | sed 's/,$//' \
      | jq -c 'select(.geometry.type == "Point")
               | select(.properties | has("shop") or has("amenity") or has("tourism") or has("leisure") or has("healthcare") or has("office"))
               | {props: .properties, lng: .geometry.coordinates[0], lat: .geometry.coordinates[1]}' \
      > "$WORK/atp.ndjson" || true
    echo "alltheplaces: $(wc -l < "$WORK/atp.ndjson") rows, $(du -m "$WORK/atp.ndjson" | cut -f1) MB"
    rm -f "$WORK/atp.pmtiles"
    if [ -s "$WORK/atp.ndjson" ]; then ATP_NDJSON="$WORK/atp.ndjson"; else echo "alltheplaces: no rows in the box"; fi
  else
    echo "alltheplaces: extract failed for $ID, baking Overture only: $(tail -2 "$WORK/atp.err" 2>/dev/null | tr '\n' ' ')"
  fi
fi
# OSM SHOPS (2026-09-17): the region's OpenStreetMap extract, filtered to named business nodes, is
# the FIRST choice for a place's coordinate. OSM maps the shop where the shop is, and when it is
# wrong anyone can fix it in a minute and every map benefits, which is not true of a parcel
# centroid in a bulk dataset. Needs osmium; OSM_PBF is a Geofabrik URL or a local file, and with it
# unset the bake behaves exactly as before.
OSM_NDJSON=""
if [ -n "${OSM_PBF:-}" ] && command -v osmium >/dev/null 2>&1 && command -v jq >/dev/null 2>&1; then
  OSM_SRC="$OSM_PBF"
  if [ "${OSM_PBF#http}" != "$OSM_PBF" ]; then
    echo "osm: fetching $OSM_PBF"
    if curl -sSL --retry 3 -o "$WORK/region.osm.pbf" "$OSM_PBF"; then OSM_SRC="$WORK/region.osm.pbf"; else OSM_SRC=""; echo "osm: download failed, baking without it"; fi
  fi
  if [ -n "$OSM_SRC" ]; then
    # Named NODES that are businesses. Ways/relations are the building, whose centroid is the same
    # kind of guess the parcel point already is, so only nodes qualify.
    osmium tags-filter --overwrite -R -o "$WORK/shops.osm.pbf" "$OSM_SRC"       n/shop n/amenity=restaurant,fast_food,cafe,bar,pub,pharmacy,bank,fuel,car_wash,car_rental,veterinary,dentist,doctors,clinic,cinema,post_office,atm       n/tourism=hotel,motel,guest_house,hostel n/leisure=fitness_centre >/dev/null 2>&1 || true
    if [ -s "$WORK/shops.osm.pbf" ]; then
      # --add-unique-id puts "n123456" in the feature's own `id` (NOT in properties, checked
      # against osmium 1.16 output), which becomes the row's id and makes an OSM-sourced place
      # traceable back to the node anyone can edit.
      osmium export -f geojsonseq --add-unique-id=type_id --overwrite -o "$WORK/shops.geojsonseq" "$WORK/shops.osm.pbf" >/dev/null 2>&1 || true
      if [ -s "$WORK/shops.geojsonseq" ]; then
        # tr: geojsonseq writes an ASCII record separator (0x1e) before every line and jq will not
        # parse it; the option that turns it off is not in every osmium build.
        tr -d '\036' < "$WORK/shops.geojsonseq" \
          | jq -c 'select(.geometry.type == "Point" and (.properties.name // "") != "") | {id: (.id // ""), name: .properties.name, props: .properties, lng: .geometry.coordinates[0], lat: .geometry.coordinates[1]}' \
          > "$WORK/osm.ndjson" 2>/dev/null || true
        [ -s "$WORK/osm.ndjson" ] && OSM_NDJSON="$WORK/osm.ndjson"
      fi
    fi
    [ -n "$OSM_NDJSON" ] && echo "osm: $(wc -l < "$OSM_NDJSON") named business nodes" || echo "osm: no usable nodes"
  fi
fi

ATP_SQL=""
if [ -n "$ATP_NDJSON" ]; then
read -r -d '' ATP_SQL <<ATPSQL || true
CREATE TABLE atp_raw AS SELECT props, lng, lat FROM read_json('$ATP_NDJSON', format = 'newline_delimited', columns = {props: 'JSON', lng: 'DOUBLE', lat: 'DOUBLE'});
CREATE TABLE atp AS
SELECT 'atp:' || (json_extract_string(props, '@spider')) || ':' || COALESCE(json_extract_string(props, 'ref'), md5(CAST(lng AS VARCHAR) || ',' || CAST(lat AS VARCHAR))) AS id,
  -- Some locators name a branch after its town ("Davis", "Davis, CA"); the brand is the name then.
  CASE WHEN json_extract_string(props, 'brand') IS NOT NULL
        AND (lower(json_extract_string(props, 'name')) = lower(COALESCE(json_extract_string(props, 'addr:city'), ''))
             OR json_extract_string(props, 'name') ILIKE '%, ' || COALESCE(json_extract_string(props, 'addr:state'), '~'))
       THEN json_extract_string(props, 'brand') ELSE json_extract_string(props, 'name') END AS name,
  osmcat(props) AS category,
  0.85 AS confidence,
  json_extract_string(props, 'brand') AS brand,
  COALESCE(json_extract_string(props, 'addr:full'), json_extract_string(props, 'addr:street_address')) AS addr,
  json_extract_string(props, 'website') AS website, json_extract_string(props, 'phone') AS phone, 'open' AS operating_status,
  json_extract_string(props, 'opening_hours') AS hours, lng, lat,
  -- A locator's addr:full already names the town; only a split address gets the town appended.
  CASE WHEN json_extract_string(props, 'addr:full') IS NULL THEN fmtloc(json_extract_string(props, 'addr:city'),
    json_extract_string(props, 'addr:state'), json_extract_string(props, 'addr:postcode'),
    coalesce(json_extract_string(props, 'addr:country'), (SELECT cc FROM regioncc))) END AS loc
FROM atp_raw
WHERE json_extract_string(props, 'name') IS NOT NULL AND json_extract_string(props, 'name') <> ''
  AND lng BETWEEN $W AND $E AND lat BETWEEN $S AND $N
  AND isbiz(props);

CREATE TABLE rawkeys AS SELECT id, lat, lng, nkey(name) AS nk, lower(brand) AS bk, snapkey(name) AS sk FROM raw;
CREATE TABLE atpkeys AS SELECT id, lat, lng, nkey(name) AS nk, lower(brand) AS bk, snapkey(name) AS sk FROM atp;
-- The Overture row a locator point matches keeps the locator's COORDINATE (atp_snap below):
-- Overture puts a tenant on its parcel point, which in a strip mall is out in the parking lot,
-- while a chain's own store locator gives the storefront (user 2026-09-17, a Subway pinned in the
-- lot in front of the mall). Only when they disagree by more than ~30 m; below that the two
-- sources agree to a median 7.4 m and moving the row would be noise.
CREATE TABLE atpdupes AS
SELECT DISTINCT a.id FROM atpkeys a JOIN rawkeys o ON o.nk = a.nk
WHERE a.nk IS NOT NULL AND a.nk <> '' AND abs(o.lat - a.lat) < 0.0015 AND abs(o.lng - a.lng) < 0.002
UNION
SELECT DISTINCT a.id FROM atpkeys a JOIN rawkeys o ON o.bk = a.bk
WHERE a.bk IS NOT NULL AND abs(o.lat - a.lat) < 0.0015 AND abs(o.lng - a.lng) < 0.002;
-- A locator point that duplicates an Overture row is dropped, but what it knows is not: a chain's
-- locator carries the store's hours, which Overture never has (the same rule as the OSM fill
-- below). Name only, never brand, so one branch's hours cannot land on another.
CREATE TABLE atpfill AS
SELECT rid, hours, phone, website, loc FROM (
  SELECT o.id AS rid, a.hours, a.phone, a.website, a.loc,
    row_number() OVER (PARTITION BY o.id ORDER BY abs(o.lat - ak.lat) + abs(o.lng - ak.lng)) AS rn
  FROM rawkeys o JOIN atpkeys ak ON ak.nk = o.nk JOIN atp a ON a.id = ak.id
  WHERE ak.nk IS NOT NULL AND ak.nk <> '' AND abs(o.lat - ak.lat) < 0.0015 AND abs(o.lng - ak.lng) < 0.002
    AND (a.hours IS NOT NULL OR a.phone IS NOT NULL OR a.website IS NOT NULL OR a.loc IS NOT NULL)
) WHERE rn = 1;
UPDATE raw SET hours = coalesce(raw.hours, f.hours), phone = coalesce(raw.phone, f.phone),
  website = coalesce(raw.website, f.website)
FROM atpfill f WHERE raw.id = f.rid;
INSERT INTO locs SELECT rid, loc FROM atpfill WHERE loc IS NOT NULL;
INSERT INTO raw
SELECT a.id, a.name, a.category, a.confidence, a.brand, a.addr, a.website, a.phone, a.operating_status, a.lng, a.lat, a.hours
FROM atp a WHERE a.id NOT IN (SELECT id FROM atpdupes);
INSERT INTO locs SELECT id, loc FROM atp WHERE id NOT IN (SELECT id FROM atpdupes) AND loc IS NOT NULL;
SELECT (SELECT count(*) FROM atpfill WHERE hours IS NOT NULL) AS atp_hours_carried;
CREATE TABLE atp_snap AS
SELECT id, alat, alng FROM (
  SELECT o.id, a.lat AS alat, a.lng AS alng,
    row_number() OVER (PARTITION BY o.id ORDER BY abs(a.lat - o.lat) + abs(a.lng - o.lng)) AS rn
  -- NAME key only, never brand alone: a brand match moved "Safeway Pharmacy" onto the Safeway
  -- and the store onto the pharmacy's locator point (Sacramento test box, 2026-09-17). And a
  -- storefront correction is tens of meters; anything past ~120 m is a different branch.
  FROM rawkeys o JOIN atpkeys a ON o.sk = a.sk
  WHERE abs(a.lat - o.lat) < 0.0015 AND abs(a.lng - o.lng) < 0.002
    AND 111320 * sqrt(pow(a.lat - o.lat, 2) + pow((a.lng - o.lng) * cos(radians(o.lat)), 2)) BETWEEN 30 AND 120
) WHERE rn = 1;
SELECT (SELECT count(*) FROM atp) AS atp_in_box, (SELECT count(*) FROM raw WHERE id LIKE 'atp:%') AS atp_added, (SELECT count(*) FROM atp_snap) AS storefront_snaps;
ATPSQL
fi
if [ -n "$LOCAL" ]; then
  SRC="read_parquet('$LOCAL')"
  SEL="id, name, category, confidence, brand, addr, website, phone, operating_status, lng, lat, CAST(NULL AS VARCHAR) AS loc, CAST(NULL AS VARCHAR) AS cc"
  # The dev extract has plain lng/lat columns and no bbox struct.
  BBOXPRED=""
else
  SRC="read_parquet('s3://overturemaps-us-west-2/release/$RELEASE/theme=places/type=place/*', hive_partitioning=1)"
  SEL="id, names.primary AS name, categories.primary AS category, confidence, brand.names.primary AS brand, addresses[1].freeform AS addr, websites[1] AS website, phones[1] AS phone, operating_status, ST_X(geometry) AS lng, ST_Y(geometry) AS lat, fmtloc(addresses[1].locality, addresses[1].region, addresses[1].postcode, addresses[1].country) AS loc, addresses[1].country AS cc"
  # PRUNE ON bbox, NOT ON THE GEOMETRY (2026-09-18). The region filter below is on ST_X/ST_Y, which
  # DuckDB has to decode per row, so every place on earth was read for every region: 504 s of a
  # 570 s Kentucky bake, once per region, 414 times. Overture's own `bbox` struct is a plain column
  # with row-group statistics, so the same filter expressed against it skips the row groups outside
  # the region: the identical 400,608 rows came back in 3.7 s. The addresses query below has always
  # done this. The geometry test STAYS as the exact filter (this is only a pruning hint, and a point
  # has xmin = xmax = lng), so the rows are the same set either way.
  BBOXPRED="AND bbox.xmin BETWEEN $W AND $E AND bbox.ymin BETWEEN $S AND $N"
fi
# Overture ADDRESSES for the unit-level snap above. Skipped on the local-parquet dev path (the
# extract has places only), which leaves the table empty and every stacked row on the ring.
if [ -n "$LOCAL" ]; then
  ADDR_SQL="CREATE TABLE addrpts (anum VARCHAR, aunit VARCHAR, alat DOUBLE, alng DOUBLE);"
else
  ADDR_SQL="CREATE TABLE addrpts AS
SELECT number AS anum,
       regexp_replace(upper(regexp_replace(coalesce(unit, ''), '(?i)^(ste|suite|unit|apt|apartment|rm|room|no|#)[ .]*', '')), '[^A-Za-z0-9]', '') AS aunit,
       ST_Y(geometry) AS alat, ST_X(geometry) AS alng
FROM read_parquet('s3://overturemaps-us-west-2/release/$RELEASE/theme=addresses/type=*/*', hive_partitioning=1)
WHERE unit IS NOT NULL AND number IS NOT NULL
  AND bbox.xmin BETWEEN $W AND $E AND bbox.ymin BETWEEN $S AND $N;"
fi
# OSM AS A SOURCE, not only a position (user 2026-09-18). Overture publishes monthly and neither
# we nor anyone reading this can correct it; OpenStreetMap is the one source in the stack a person
# can fix, and see fixed, so a business somebody adds or repairs there has to be able to reach the
# map. The rows go in beside Overture's and AllThePlaces', through the same tag mapping, the same
# name/brand dedupe and the same ranking. Only NODES qualify, same as the snap: a building way's
# centroid is the same kind of guess as the parcel point we already have.
OSM_BIZ_SQL=""
if [ -n "$OSM_NDJSON" ]; then
read -r -d '' OSM_BIZ_SQL <<OSMBIZSQL || true
CREATE TABLE osm_src AS SELECT id, name, props, lng, lat FROM read_json('$OSM_NDJSON', format = 'newline_delimited',
  columns = {id: 'VARCHAR', name: 'VARCHAR', props: 'JSON', lng: 'DOUBLE', lat: 'DOUBLE'})
  WHERE lng BETWEEN $W AND $E AND lat BETWEEN $S AND $N;
CREATE TABLE osmbiz AS
SELECT 'osm:' || id AS id, name, osmcat(props) AS category,
  -- Under AllThePlaces' 0.85 and Overture's own scores: an OSM node is as good as its last editor,
  -- and where the other two have the same place they should keep the row.
  0.8 AS confidence,
  json_extract_string(props, 'brand') AS brand,
  nullif(trim(coalesce(json_extract_string(props, 'addr:housenumber'), '') || ' ' || coalesce(json_extract_string(props, 'addr:street'), '')), '') AS addr,
  coalesce(json_extract_string(props, 'website'), json_extract_string(props, 'contact:website')) AS website,
  coalesce(json_extract_string(props, 'phone'), json_extract_string(props, 'contact:phone')) AS phone,
  'open' AS operating_status, json_extract_string(props, 'opening_hours') AS hours, lng, lat,
  fmtloc(json_extract_string(props, 'addr:city'), json_extract_string(props, 'addr:state'),
    json_extract_string(props, 'addr:postcode'), coalesce(json_extract_string(props, 'addr:country'), (SELECT cc FROM regioncc))) AS loc
FROM osm_src WHERE name IS NOT NULL AND name <> '' AND id <> '' AND isbiz(props);
-- Keys are recomputed here, AFTER the AllThePlaces insert, so OSM dedupes against everything
-- already in the table rather than against Overture alone.
CREATE TABLE rawkeys2 AS SELECT id, lat, lng, nkey(name) AS nk, lower(brand) AS bk FROM raw;
CREATE TABLE osmbizkeys AS SELECT id, lat, lng, nkey(name) AS nk, lower(brand) AS bk FROM osmbiz;
CREATE TABLE osmdupes AS
SELECT DISTINCT o.id FROM osmbizkeys o JOIN rawkeys2 r ON r.nk = o.nk
WHERE o.nk IS NOT NULL AND o.nk <> '' AND abs(r.lat - o.lat) < 0.0015 AND abs(r.lng - o.lng) < 0.002
UNION
SELECT DISTINCT o.id FROM osmbizkeys o JOIN rawkeys2 r ON r.bk = o.bk
WHERE o.bk IS NOT NULL AND abs(r.lat - o.lat) < 0.0015 AND abs(r.lng - o.lng) < 0.002;
-- A DUPLICATE STILL KNOWS THINGS (user 2026-09-22: a convenience store's OSM node carried its
-- opening hours, Overture's row for the same shop did not, and the dedupe dropped the node with
-- them, so the map showed no hours for a place OSM describes fully). The kept row takes the
-- nearest same-name OSM node's hours, phone and website wherever it has none; a value it already
-- has (Overture's, or a chain locator's) stays. Name only, never brand, so one branch's hours
-- cannot land on another branch of the chain a block away.
CREATE TABLE osmfill AS
SELECT rid, hours, phone, website, loc FROM (
  SELECT r.id AS rid, o.hours, o.phone, o.website, o.loc,
    row_number() OVER (PARTITION BY r.id ORDER BY abs(r.lat - ok.lat) + abs(r.lng - ok.lng)) AS rn
  FROM rawkeys2 r JOIN osmbizkeys ok ON ok.nk = r.nk JOIN osmbiz o ON o.id = ok.id
  WHERE ok.nk IS NOT NULL AND ok.nk <> '' AND abs(r.lat - ok.lat) < 0.0015 AND abs(r.lng - ok.lng) < 0.002
    AND (o.hours IS NOT NULL OR o.phone IS NOT NULL OR o.website IS NOT NULL OR o.loc IS NOT NULL)
) WHERE rn = 1;
UPDATE raw SET hours = coalesce(raw.hours, f.hours), phone = coalesce(raw.phone, f.phone),
  website = coalesce(raw.website, f.website)
FROM osmfill f WHERE raw.id = f.rid;
INSERT INTO locs SELECT rid, loc FROM osmfill WHERE loc IS NOT NULL;
SELECT (SELECT count(*) FROM osmfill WHERE hours IS NOT NULL) AS osm_hours_carried;
INSERT INTO raw
SELECT o.id, o.name, o.category, o.confidence, o.brand, o.addr, o.website, o.phone, o.operating_status, o.lng, o.lat, o.hours
FROM osmbiz o WHERE o.id NOT IN (SELECT id FROM osmdupes) AND o.category IS NOT NULL;
INSERT INTO locs SELECT id, loc FROM osmbiz WHERE id NOT IN (SELECT id FROM osmdupes) AND category IS NOT NULL AND loc IS NOT NULL;
SELECT (SELECT count(*) FROM osmbiz) AS osm_biz_in_box, (SELECT count(*) FROM raw WHERE id LIKE 'osm:%') AS osm_biz_added;
OSMBIZSQL
fi
OSM_SQL=""
if [ -n "$OSM_NDJSON" ]; then
read -r -d '' OSM_SQL <<OSMSQL || true
CREATE TABLE osm_raw AS SELECT name, lng, lat FROM osm_src
  WHERE lng BETWEEN $W AND $E AND lat BETWEEN $S AND $N;
CREATE TABLE osmkeys AS SELECT snapkey(name) AS sk, lat, lng FROM osm_raw WHERE snapkey(name) IS NOT NULL;
-- Same shape as the chain-locator snap: whole-name key, 30-120 m, nearest candidate, one per row.
CREATE TABLE osm_snap AS
SELECT id, olat, olng FROM (
  SELECT k.id, o.lat AS olat, o.lng AS olng,
    row_number() OVER (PARTITION BY k.id ORDER BY abs(o.lat - k.lat) + abs(o.lng - k.lng)) AS rn
  FROM (SELECT id, lat, lng, snapkey(name) AS sk FROM scored) k
  JOIN osmkeys o ON o.sk = k.sk
  WHERE k.sk IS NOT NULL AND abs(o.lat - k.lat) < 0.0015 AND abs(o.lng - k.lng) < 0.002
    AND 111320 * sqrt(pow(o.lat - k.lat, 2) + pow((o.lng - k.lng) * cos(radians(k.lat)), 2)) BETWEEN 30 AND 120
) WHERE rn = 1;
SELECT (SELECT count(*) FROM osm_raw) AS osm_nodes, (SELECT count(*) FROM osm_snap) AS osm_snaps;
OSMSQL
fi

duckdb <<SQL
.timer on
INSTALL httpfs; LOAD httpfs; INSTALL spatial; LOAD spatial; SET s3_region='us-west-2';
-- SPILL RATHER THAN DIE. A continent-sized box materializes millions of rows, and a runner that
-- runs out of memory reports it as "the runner has received a shutdown signal" with no output at
-- all, which reads like flaky infrastructure (Australia and its states, twice, 2026-09-18). A
-- limit under the runner's 16 GB with somewhere to spill turns that into a slower bake.
SET memory_limit = '11GB'; SET temp_directory = '$WORK/duckdb-spill';
-- THE REST OF THE ADDRESS (user 2026-09-22: "a lot of the places don't have the full address, no
-- zip, city or state"). `addr` is the street line only, and it stays that way because several
-- steps below use it as a JOIN KEY (tenant matching, the unit snap, the fuel-lot house number).
-- The city / region / postcode travel in a side table `locs` and are exported as the tile's `loc`,
-- which the app appends. Formatted the way the country writes an address: "Davis, CA 95616" in
-- the US, Canada and Australia (ZIP+4 cut to the ZIP), "London SW1A 1AA" in Britain and Ireland,
-- "10115 Berlin" everywhere else. OSM and AllThePlaces rows rarely say their country, so they
-- take the region's own most common Overture country.
CREATE MACRO fmtloc(city, region, postcode, country) AS nullif(trim(CASE
  WHEN upper(coalesce(country, 'US')) IN ('US', 'CA', 'AU') THEN concat_ws(', ', nullif(trim(city), ''),
    nullif(trim(concat_ws(' ', nullif(trim(region), ''), nullif(split_part(trim(coalesce(postcode, '')), '-', 1), ''))), ''))
  WHEN upper(country) IN ('GB', 'IE') THEN concat_ws(' ', nullif(trim(city), ''), nullif(trim(postcode), ''))
  ELSE concat_ws(' ', nullif(trim(postcode), ''), nullif(trim(city), '')) END), '');
CREATE TABLE raw AS SELECT $SEL, CAST(NULL AS VARCHAR) AS hours FROM $SRC
  WHERE lng BETWEEN $W AND $E AND lat BETWEEN $S AND $N $BBOXPRED;
CREATE TABLE regioncc AS SELECT coalesce(mode(cc), 'US') AS cc FROM raw;
CREATE TABLE locs AS SELECT id, loc FROM raw WHERE loc IS NOT NULL;
ALTER TABLE raw DROP COLUMN loc;
ALTER TABLE raw DROP COLUMN cc;
-- The snap key is the WHOLE name, normalized, with a trailing store number dropped ("Safeway
-- #1561" -> "safeway"): the two-word dedupe key is deliberately loose, and moving a point needs a
-- tighter test than dropping a duplicate does (it dragged a campus onto its own outreach office,
-- 2026-09-17).
-- Since 2026-09-21 the key mirrors the app's core/util/PlaceNames.normalized: accents folded,
-- parentheticals out, "&" read as "and", legal suffixes dropped, a possessive "'s" kept on its
-- word, then punctuation, spaces and the trailing store number. "SpeeDee Oil Change & Auto
-- Service", "Caffé Italia", "James W. Childress, DDS Inc." and "Nugget #12" key the way the tap
-- resolve reads them, so a row the bake keeps is one the app can match.
CREATE MACRO snapkey(n) AS nullif(trim(regexp_replace(regexp_replace(regexp_replace(regexp_replace(regexp_replace(regexp_replace(
  strip_accents(lower(coalesce(n, ''))),
  '\\([^)]*\\)', ' ', 'g'), '''s\\b', 's', 'g'), '&', ' and ', 'g'), '[^a-z0-9]+', ' ', 'g'),
  '\\b(llc|inc|corp|co|ltd|company|incorporated|corporation|pc|apc|llp|pllc)\\b', ' ', 'g'),
  '[ ]+(no|num|store|unit|#)?[ ]*[0-9]{2,6}$', '')), '');
-- SHARED TAG MAPPING. AllThePlaces and OpenStreetMap both describe a place with OSM tags, so the
-- tag-to-category mapping and the "is this a business" test live here as macros and both sources
-- use them; they used to be forty lines inside the AllThePlaces block.
CREATE MACRO osmcat(props) AS (
  CASE
    WHEN json_extract_string(props, 'amenity') = 'fast_food' THEN 'fast_food_restaurant'
    WHEN json_extract_string(props, 'amenity') = 'cafe' THEN 'coffee_shop'
    WHEN json_extract_string(props, 'amenity') = 'fuel' THEN 'gas_station'
    WHEN json_extract_string(props, 'amenity') = 'cinema' THEN 'movie_theater'
    WHEN json_extract_string(props, 'amenity') = 'ice_cream' THEN 'ice_cream_shop'
    WHEN json_extract_string(props, 'amenity') IN ('doctors', 'clinic') THEN 'doctor'
    WHEN json_extract_string(props, 'amenity') = 'veterinary' THEN 'veterinarian'
    WHEN json_extract_string(props, 'amenity') = 'charging_station' THEN 'ev_charging_station'
    WHEN json_extract_string(props, 'amenity') = 'car_repair' THEN 'automotive_repair'
    WHEN json_extract_string(props, 'amenity') = 'theatre' THEN 'theater'
    WHEN json_extract_string(props, 'amenity') IS NOT NULL THEN json_extract_string(props, 'amenity')
    WHEN json_extract_string(props, 'shop') IS NOT NULL THEN CASE json_extract_string(props, 'shop')
      WHEN 'supermarket' THEN 'supermarket' WHEN 'convenience' THEN 'convenience_store' WHEN 'department_store' THEN 'department_store'
      WHEN 'hardware' THEN 'hardware_store' WHEN 'doityourself' THEN 'home_improvement_store' WHEN 'electronics' THEN 'electronics'
      WHEN 'furniture' THEN 'furniture_store' WHEN 'florist' THEN 'florist' WHEN 'laundry' THEN 'laundromat' WHEN 'dry_cleaning' THEN 'dry_cleaner'
      WHEN 'hairdresser' THEN 'hair_salon' WHEN 'beauty' THEN 'beauty_salon' WHEN 'jewelry' THEN 'jewelry_store' WHEN 'books' THEN 'bookstore'
      WHEN 'pet' THEN 'pet_store' WHEN 'clothes' THEN 'clothing_store' WHEN 'shoes' THEN 'shoe_store' WHEN 'toys' THEN 'toy_store'
      WHEN 'bicycle' THEN 'bicycle_shop' WHEN 'alcohol' THEN 'liquor_store' WHEN 'tobacco' THEN 'tobacco_shop' WHEN 'sports' THEN 'sporting_goods'
      WHEN 'mall' THEN 'shopping_center' WHEN 'wholesale' THEN 'wholesale_store' WHEN 'variety_store' THEN 'discount_store' WHEN 'car' THEN 'car_dealer'
      WHEN 'car_repair' THEN 'automotive_repair' WHEN 'car_parts' THEN 'auto_parts_store' WHEN 'chemist' THEN 'drugstore' WHEN 'optician' THEN 'optometrist'
      ELSE (json_extract_string(props, 'shop')) || '_store' END
    WHEN json_extract_string(props, 'tourism') IN ('hotel', 'motel', 'hostel') THEN json_extract_string(props, 'tourism')
    WHEN json_extract_string(props, 'tourism') = 'guest_house' THEN 'bed_and_breakfast'
    WHEN json_extract_string(props, 'tourism') = 'museum' THEN 'museum'
    WHEN json_extract_string(props, 'leisure') = 'fitness_centre' THEN 'gym'
    WHEN json_extract_string(props, 'healthcare') IS NOT NULL THEN 'medical_center'
    WHEN json_extract_string(props, 'office') IS NOT NULL THEN (json_extract_string(props, 'office')) || '_office'
    ELSE NULL END);
CREATE MACRO isbiz(props) AS ((json_extract_string(props, 'shop') IS NOT NULL
    OR json_extract_string(props, 'tourism') IN ('hotel', 'motel', 'hostel', 'guest_house', 'museum')
    OR json_extract_string(props, 'leisure') = 'fitness_centre'
    OR json_extract_string(props, 'healthcare') IS NOT NULL
    OR json_extract_string(props, 'office') IN ('insurance', 'financial_advisor', 'estate_agent', 'tax_advisor', 'lawyer', 'accountant', 'travel_agent')
    OR json_extract_string(props, 'amenity') IN ('restaurant', 'fast_food', 'cafe', 'bar', 'pub', 'ice_cream', 'fuel', 'pharmacy', 'bank', 'dentist', 'doctors', 'clinic',
      'veterinary', 'cinema', 'car_wash', 'car_rental', 'car_repair', 'post_office', 'charging_station', 'gym', 'hospital', 'childcare', 'kindergarten',
      'coworking_space', 'theatre', 'nightclub', 'food_court', 'bureau_de_change', 'money_transfer', 'driving_school', 'language_school',
      'music_school', 'dancing_school', 'library', 'marketplace', 'bicycle_rental')));
-- The first two significant words of a name, the app's own namesAgree rule in SQL form.
CREATE MACRO nkey(n) AS trim(regexp_extract(regexp_replace(lower(n), '[^a-z0-9 ]', ' ', 'g'), '\\b([a-z0-9]{2,})\\b', 1) || ' ' ||
  regexp_extract(regexp_replace(lower(n), '[^a-z0-9 ]', ' ', 'g'), '\\b[a-z0-9]{2,}\\b(?: [a-z0-9] )* +\\b([a-z0-9]{2,})\\b', 1));
$ATP_SQL
$OSM_BIZ_SQL
-- ONE ROW PER BUSINESS (user 2026-09-21, "two POIs that really should be one"). Overture itself
-- carries the same business twice (a gas station under "Chevron" and "Chevron Station Davis", a
-- shop under "SpeeDee" and "SpeeDee-Midas", a store and the counter inside it named after the
-- store), and the source dedupes above only ever compared a NEW source against what was there.
-- Rows with the same snap key within ~60 m collapse onto one leader: not a kiosk category, then
-- the higher confidence, then the row that knows more (address, phone, website, hours). A hash
-- join on the key with the box as the residual, never a correlated lookup (state-scale rule).
CREATE TABLE dupk AS SELECT id, lat, lng, sk, confidence, kiosk, fields FROM (
  SELECT id, lat, lng, snapkey(name) AS sk, confidence,
    (CASE WHEN category IN ('rental_kiosks','bank_equipment_service','money_transfer_services','atms','key_and_locksmith','vending_machine','photo_booth') THEN 1 ELSE 0 END) AS kiosk,
    ((addr IS NOT NULL)::INT + (phone IS NOT NULL)::INT + (website IS NOT NULL)::INT + (hours IS NOT NULL)::INT) AS fields
  FROM raw
  UNION ALL
  -- A FORECOURT IS ONE PER LOT: two fuel rows with one house number within the box are one station
  -- named after different things (the brand and the shop inside it). The NUMBER, not the street
  -- line: the two rows spell the same road three ways ("State Route 9 Se #1", "WA-9", "STATE RTE 9 SE").
  SELECT id, lat, lng, 'fuel@' || regexp_extract(addr, '^([0-9]+)', 1) AS sk, confidence, 0 AS kiosk,
    ((addr IS NOT NULL)::INT + (phone IS NOT NULL)::INT + (website IS NOT NULL)::INT + (hours IS NOT NULL)::INT) AS fields
  FROM raw WHERE category = 'gas_station' AND addr IS NOT NULL AND regexp_matches(addr, '^[0-9]')
) WHERE sk IS NOT NULL AND sk <> '';
CREATE TABLE dupleader AS
SELECT a.id, first(b.id ORDER BY b.kiosk, b.confidence DESC, b.fields DESC, b.id) AS leader
FROM dupk a JOIN dupk b ON b.sk = a.sk AND abs(b.lat - a.lat) < 0.00055 AND abs(b.lng - a.lng) < 0.0007
GROUP BY a.id;
DELETE FROM raw WHERE id IN (SELECT id FROM dupleader WHERE id <> leader);
SELECT (SELECT count(*) FROM dupleader WHERE id <> leader) AS same_business_rows_dropped;
-- THE VARIANT FAMILY (2026-09-22): "Chevron Gas Station" beside "Chevron", "Walgreens Pharmacy"
-- beside "Walgreens", "Starbucks Coffee Company" beside "Starbucks" are one business whose names
-- differ only by GENERIC words. The core key is the snap key minus the app's generic word list
-- (tools/place-generic-words.txt, pinned to core/util/PlaceNames.GENERIC by a unit test); rows
-- with the same non-empty core key within ~60 m fold onto the leader the same way. A key that is
-- only a street number ("38th") or a single short word is not a name and is left out, which is
-- the app's strong-core rule in SQL.
CREATE TABLE generic AS SELECT w FROM read_csv('$ROOT/tools/place-generic-words.txt', header = false, columns = {'w': 'VARCHAR'});
-- (No lambda here: DuckDB refuses a subquery inside one, so the tokens are unnested and the
-- generic ones anti-joined away, then re-joined in order.)
CREATE TABLE corek AS
WITH toks AS (
  SELECT r.id, r.lat, r.lng, r.confidence, r.category, r.addr, r.phone, r.website, r.hours, t.tok, t.i
  FROM (SELECT *, string_split(snapkey(name), ' ') AS tl FROM raw WHERE snapkey(name) IS NOT NULL) r,
       unnest(r.tl) WITH ORDINALITY AS t(tok, i)
  WHERE t.tok <> '' AND t.tok NOT IN (SELECT w FROM generic)
)
SELECT id, any_value(lat) AS lat, any_value(lng) AS lng, string_agg(tok, ' ' ORDER BY i) AS ck, any_value(confidence) AS confidence,
  any_value(CASE WHEN category IN ('rental_kiosks','bank_equipment_service','money_transfer_services','atms','key_and_locksmith','vending_machine','photo_booth') THEN 1 ELSE 0 END) AS kiosk,
  any_value((addr IS NOT NULL)::INT + (phone IS NOT NULL)::INT + (website IS NOT NULL)::INT + (hours IS NOT NULL)::INT) AS fields
FROM toks GROUP BY id
HAVING NOT regexp_matches(string_agg(tok, ' ' ORDER BY i), '^[0-9]+(st|nd|rd|th)?$')
   AND (length(string_agg(tok, ' ' ORDER BY i)) >= 5 OR string_agg(tok, ' ' ORDER BY i) LIKE '% %');
CREATE TABLE coreleader AS
SELECT a.id, first(b.id ORDER BY b.kiosk, b.confidence DESC, b.fields DESC, b.id) AS leader
FROM corek a JOIN corek b ON b.ck = a.ck AND abs(b.lat - a.lat) < 0.00055 AND abs(b.lng - a.lng) < 0.0007
GROUP BY a.id;
DELETE FROM raw WHERE id IN (SELECT id FROM coreleader WHERE id <> leader);
SELECT (SELECT count(*) FROM coreleader WHERE id <> leader) AS same_business_variant_rows_dropped;
CREATE TABLE scored AS
SELECT *,
  CASE
    WHEN category IN ('hospital','university','college_university','airport','stadium_arena','museum','zoo','amusement_park','shopping_center','supermarket','department_store','grocery_store','convention_center','casino','aquarium') THEN 4.5
    WHEN category IN ('hotel','accommodation','pharmacy','bank','movie_theater','gym','library','church_cathedral','bowling_alley','hardware_store','car_dealer','furniture_store','electronics','sporting_goods','home_improvement_store','wholesale_store','discount_store') THEN 3.2
    WHEN category IS NULL THEN 1.6
    WHEN category LIKE '%restaurant%' OR category IN ('coffee_shop','cafe','bar','pub','fast_food_restaurant','bakery','ice_cream_shop','brewery','winery','gas_station','ev_charging_station','automotive_repair','car_wash','pet_store','bookstore','clothing_store','shoe_store','jewelry_store','florist','liquor_store','tobacco_shop','toy_store','bicycle_shop','dentist','veterinarian','optometrist','urgent_care_clinic','post_office','atms','laundromat','dry_cleaner','barber','hair_salon','beauty_salon','nail_salon','spa','tattoo') THEN 2.2
    ELSE 1.0
  END
  + CASE WHEN brand IS NOT NULL AND brand <> '' THEN 1.6 ELSE 0 END
  + CASE WHEN website IS NOT NULL THEN 0.5 ELSE 0 END
  + CASE WHEN phone IS NOT NULL THEN 0.4 ELSE 0 END
  + CASE WHEN addr IS NOT NULL THEN 0.2 ELSE 0 END
  + (COALESCE(confidence, 0.5) - 0.5) * 1.6 AS prominence,
  CASE
    WHEN category LIKE '%gas_station%' OR category LIKE '%charging%' THEN 'fuel'
    WHEN category LIKE '%restaurant%' OR category IN ('coffee_shop','cafe','bar','pub','bakery','ice_cream_shop','brewery','winery','food_court','deli','juice_bar','tea_room','sandwich_shop','donut_shop','bagel_shop','dessert_shop','frozen_yogurt_shop','cupcake_shop','smoothie_shop','bubble_tea','taqueria','diner','steakhouse','cafeteria','buffet') OR category LIKE '%food%' THEN 'food'
    WHEN category IN ('hotel','accommodation','motel','bed_and_breakfast','hostel','resort') THEN 'lodging'
    WHEN category IN ('hospital','pharmacy','dentist','veterinarian','optometrist','urgent_care_clinic','doctor','health_and_medical','diagnostic_services','physical_therapy','chiropractor','medical_center') OR category LIKE '%clinic%' OR category LIKE '%medical%' THEN 'health'
    WHEN category LIKE '%parking%' THEN 'parking'
    WHEN category IN ('university','college_university','library','school','preschool','tutoring_center') OR category LIKE '%school%' THEN 'edu'
    WHEN category IN ('museum','movie_theater','art_gallery','performing_arts','theater','zoo','aquarium','landmark_and_historical_building','cultural_center') THEN 'culture'
    WHEN category IN ('gym','stadium_arena','bowling_alley','yoga_studio','sports_club','golf_course','climbing_gym','ice_skating_rink','martial_arts_club','swimming_pool') OR category LIKE '%fitness%' OR category LIKE '%sport%' THEN 'sport'
    WHEN category IN ('bank','atms','post_office','police_station','fire_station','city_hall','courthouse','church_cathedral','mosque','synagogue','temple','place_of_worship','community_center','cemetery','government_office') OR category LIKE '%religious%' THEN 'civic'
    WHEN category LIKE '%store%' OR category LIKE '%shop%' OR category IN ('supermarket','grocery_store','shopping_center','florist','laundromat','dry_cleaner','barber','hair_salon','beauty_salon','nail_salon','spa','car_dealer','automotive_repair','car_wash','hardware_store','electronics','furniture_store','tattoo','jewelry','retail','boutique','market') OR category LIKE '%salon%' THEN 'shop'
    ELSE 'default'
  END AS grp
FROM raw
WHERE name IS NOT NULL AND name <> ''
  AND COALESCE(operating_status, 'open') <> 'permanently_closed'
  AND COALESCE(confidence, 0.5) >= 0.4
  AND (category IS NULL OR category NOT IN ('park','campus_building','apartments','housing_development','real_estate','transportation','bus_station','train_station','public_transportation','school','elementary_school','middle_school','high_school'))
  AND NOT (category IS NULL AND website IS NULL);
-- Rank by prominence inside a fine (~400 m) and a coarse (~1.6 km) cell. Longitude cells are
-- widened by 1/cos(lat) so the cells stay roughly square away from the equator.
-- A third, ~6.5 km cell (xrank) picks the landmarks Google still draws zoomed out to z11/z12:
-- airports, hospitals, universities, stadiums, malls, zoos. Only the landmark categories qualify
-- there, so a branded gas station never becomes a town's z11 marker.
-- TENANTS (2026-09-15): a supermarket's pharmacy, its money-transfer counter, the optician inside
-- the department store all carry the anchor's address and often the anchor's brand, and their
-- own category prior + brand bonus let them outrank the store in a 400 m cell (a Safeway pharmacy
-- drawn where the Safeway should be). A row at an anchor category's address, within ~200 m of
-- it and not an anchor itself, loses 2 points, so the store wins the cell and the tenant fills in
-- as you zoom.
-- Address as a JOIN KEY: lowercased, with the unit part dropped ("2121 Cowell Blvd Ste B" and
-- "2121 Cowell Blvd, Suite B" both become "2121 cowell blvd"). Exact string equality missed a
-- department whose row carried the suite and the store's did not, which is how a Safeway Pharmacy
-- kept outranking its own Safeway (user 2026-09-16).
CREATE MACRO anorm(a) AS nullif(trim(regexp_replace(regexp_replace(lower(coalesce(a, '')), '[,#].*$', ''), '[ ]+(ste|suite|unit|apt|bldg|rm|room|no|fl|floor)[ .]*[a-z0-9-]*$', '')), '');
-- The first word of a name, for the department test below.
-- The anchor's first WORD, store number dropped: "Safeway", "SAFEWAY #1561" and "Safeway Store
-- 1561" all key to "safeway", so a "Safeway Pharmacy" next door reads as its department however
-- either row happens to be named (user 2026-09-16).
CREATE MACRO nhead(n) AS nullif(lower(regexp_extract(coalesce(n, ''), '^[A-Za-z][A-Za-z.-]{2,}')), '');
CREATE TABLE anchors AS
SELECT id, name, brand, addr, lat, lng FROM scored
WHERE category IN ('supermarket','grocery_store','department_store','shopping_center','hospital','university','college_university','hardware_store','home_improvement_store','wholesale_store','warehouse_club','sporting_goods','electronics','furniture_store');
-- Which non-anchor rows are DEPARTMENTS of a nearby anchor. Three HASH JOINS, one per way a
-- department shows itself (same normalized address, same brand, or a name that is the anchor's
-- first word plus more), each with the ~200 m box as a residual, then DISTINCT ids. The first cut
-- was one correlated EXISTS with the three tests OR-ed together, which cannot be hashed: it ran
-- every row against every anchor, fine for Davis and effectively quadratic over a state (a world
-- bake did 19 regions in 2.5 hours, 2026-09-16). DISTINCT, never a plain join onto the rows: a
-- tenant can sit at more than one anchor, and a join duplicated the row once per match.
CREATE TABLE tenants AS
SELECT DISTINCT id FROM (
  SELECT s.id FROM scored s JOIN anchors a ON anorm(s.addr) = anorm(a.addr)
  WHERE s.id <> a.id AND abs(s.lat - a.lat) < 0.002 AND abs(s.lng - a.lng) < 0.003
    AND (s.category IS NULL OR s.category NOT IN ('supermarket','grocery_store','department_store','shopping_center','hospital','university','college_university','hardware_store','home_improvement_store','wholesale_store','warehouse_club','sporting_goods','electronics','furniture_store'))
  UNION ALL
  SELECT s.id FROM scored s JOIN anchors a ON lower(s.brand) = lower(a.brand)
  WHERE s.id <> a.id AND abs(s.lat - a.lat) < 0.002 AND abs(s.lng - a.lng) < 0.003
    AND (s.category IS NULL OR s.category NOT IN ('supermarket','grocery_store','department_store','shopping_center','hospital','university','college_university','hardware_store','home_improvement_store','wholesale_store','warehouse_club','sporting_goods','electronics','furniture_store'))
  UNION ALL
  -- The store's OWN fuel station and its little shop, which carry the store's brand out in the
  -- lot: they must not take the brand's icon off the store itself (user 2026-09-17, a Safeway
  -- fuel kiosk drew as "Safeway" while the store showed only its counters). They keep their fuel
  -- group, so the pumps still draw as fuel when you are close.
  SELECT s.id FROM scored s JOIN anchors a ON lower(s.brand) = lower(a.brand)
  WHERE s.id <> a.id AND abs(s.lat - a.lat) < 0.0025 AND abs(s.lng - a.lng) < 0.0035
    AND s.category IN ('gas_station','convenience_store','ev_charging_station')
  UNION ALL
  SELECT s.id FROM scored s JOIN anchors a ON nhead(s.name) = nhead(a.name)
  WHERE s.id <> a.id AND abs(s.lat - a.lat) < 0.0025 AND abs(s.lng - a.lng) < 0.0035
    AND length(nhead(a.name)) >= 4 AND s.category IN ('gas_station','convenience_store','ev_charging_station')
  UNION ALL
  SELECT s.id FROM scored s JOIN anchors a ON nhead(s.name) = nhead(a.name)
  WHERE s.id <> a.id AND abs(s.lat - a.lat) < 0.002 AND abs(s.lng - a.lng) < 0.003
    AND length(nhead(a.name)) >= 4 AND lower(s.name) LIKE nhead(a.name) || ' %'
    AND (s.category IS NULL OR s.category NOT IN ('supermarket','grocery_store','department_store','shopping_center','hospital','university','college_university','hardware_store','home_improvement_store','wholesale_store','warehouse_club','sporting_goods','electronics','furniture_store'))
);
-- KIOSKS AND COUNTERS (2026-09-17): a Redbox, a Coinstar, an ecoATM, a Western Union window or a
-- key-cutting machine is a fixture INSIDE a shop, never a destination you navigate to, and at a
-- Sacramento Safeway nine of them sat within 42 m of the store. They stay in the data (searchable,
-- tappable) but never earn a browse-zoom icon.
CREATE MACRO iskiosk(c, n) AS (
  c IN ('rental_kiosks','bank_equipment_service','money_transfer_services','atms','key_and_locksmith','vending_machine','photo_booth')
  OR lower(coalesce(n, '')) IN ('redbox','coinstar','ecoatm','western union','keyme locksmiths','keyme')
);
-- TWO ICONS, ONE NAME (user 2026-09-18). A brand's forecourt is often published under the BARE
-- brand name, so the lot ends up with two icons labeled identically a few tens of meters apart:
-- the store and the pumps. Demoting the forecourt does not help, because fuel is exempt from the
-- tenant minzoom (you want pumps while driving), so both draw and a tap on "the store" is a coin
-- toss. The forecourt keeps its own row and its own group; it just says which one it is. Only an
-- EXACT name match is touched - a row already called "<brand> Fuel Station" is left alone.
CREATE TABLE brandsame AS
SELECT DISTINCT s.id, s.category FROM scored s JOIN anchors a ON lower(s.name) = lower(a.name)
WHERE s.id <> a.id AND abs(s.lat - a.lat) < 0.0025 AND abs(s.lng - a.lng) < 0.0035
  AND s.category IN ('gas_station', 'convenience_store', 'ev_charging_station');
CREATE TABLE anchored AS
SELECT s.* REPLACE (
    CASE WHEN t.id IS NOT NULL THEN s.prominence - 2.0 ELSE s.prominence END AS prominence,
    CASE
      WHEN b.id IS NULL THEN s.name
      WHEN b.category = 'gas_station' THEN s.name || ' Fuel'
      WHEN b.category = 'ev_charging_station' THEN s.name || ' Charging'
      ELSE s.name || ' Market'
    END AS name),
  CASE WHEN t.id IS NOT NULL OR iskiosk(s.category, s.name) THEN 1 ELSE 0 END AS tenant
FROM scored s LEFT JOIN tenants t ON t.id = s.id LEFT JOIN brandsame b ON b.id = s.id;
-- STACKED POINTS (2026-09-15): Overture puts every tenant of a building on the same parcel point
-- (17% of Davis rows share their point with another: medical suites, strip-mall tenants), and
-- coincident icons collide at every zoom, so all but the top one never drew. Spread the stack on
-- a small ring (about 8 to 20 m, golden-angle steps, best row stays put) so they separate at the
-- zooms where a person is looking for one shop in a row of them.
-- UNIT-LEVEL SNAP (2026-09-16). A stacked row has no coordinate of its own: Overture puts every
-- tenant of a building on one parcel point, which usually sits at the lot's address out front.
-- Overture's ADDRESSES theme does carry a point per unit ("APT 112", "STE B"), so a tenant whose
-- own address names a unit can be put on its own door instead of an invented ring slot. Match on
-- house NUMBER + UNIT within ~200 m and ignore the street name: a number plus a unit is
-- effectively unique that close, and street abbreviations ("Blvd" vs "Boulevard") differ between
-- the two themes. Rows that do not match keep the parcel point and fall through to the ring.
-- Only STACKED rows are snapped; an unstacked place already has a real coordinate (measured
-- 2026-09-16: Overture and AllThePlaces agree to a median 7.4 m on Davis chains).
CREATE MACRO unitkey(a) AS nullif(upper(regexp_replace(regexp_extract(coalesce(a, ''), '(?i)(ste|suite|unit|apt|apartment|rm|room|no|#)[ .]*([a-z0-9-]+)[ ]*$', 2), '[^A-Za-z0-9]', '')), '');
CREATE MACRO numkey(a) AS nullif(regexp_extract(coalesce(a, ''), '^[0-9]+'), '');
$ADDR_SQL
-- The chain-locator coordinate wins over Overture's parcel point (atp_snap), before the stack
-- test: moving a row off the shared parcel point is exactly what takes it out of the stack.
CREATE TABLE IF NOT EXISTS atp_snap (id VARCHAR, alat DOUBLE, alng DOUBLE);
$OSM_SQL
CREATE TABLE IF NOT EXISTS osm_snap (id VARCHAR, olat DOUBLE, olng DOUBLE);
-- OSM first, then the chain locator, then Overture's own point.
CREATE TABLE located AS
SELECT a.* REPLACE (
  CASE WHEN a.tenant = 1 THEN a.lat ELSE COALESCE(o.olat, p.alat, a.lat) END AS lat,
  CASE WHEN a.tenant = 1 THEN a.lng ELSE COALESCE(o.olng, p.alng, a.lng) END AS lng
) FROM anchored a LEFT JOIN atp_snap p ON p.id = a.id LEFT JOIN osm_snap o ON o.id = a.id;
CREATE TABLE stacked AS
SELECT *, count(*) OVER (PARTITION BY round(lat, 5), round(lng, 5)) > 1 AS in_stack FROM located;
-- A HASH JOIN on (number, unit) with the distance as a residual and the nearest candidate per
-- place by row_number - not a correlated LATERAL lookup per row, which is quadratic at state scale.
CREATE TABLE snapkeys AS
SELECT id, lat, lng, numkey(addr) AS knum, unitkey(addr) AS kunit FROM stacked
WHERE in_stack AND numkey(addr) IS NOT NULL AND unitkey(addr) IS NOT NULL;
CREATE TABLE snapcand AS
SELECT k.id, a.alat, a.alng,
  row_number() OVER (PARTITION BY k.id ORDER BY abs(a.alat - k.lat) + abs(a.alng - k.lng)) AS rn
FROM snapkeys k JOIN addrpts a ON a.anum = k.knum AND a.aunit = k.kunit
WHERE abs(a.alat - k.lat) < 0.002 AND abs(a.alng - k.lng) < 0.003;
CREATE TABLE snapped AS
SELECT s.* REPLACE (COALESCE(c.alat, s.lat) AS lat, COALESCE(c.alng, s.lng) AS lng)
FROM stacked s LEFT JOIN (SELECT id, alat, alng FROM snapcand WHERE rn = 1) c ON c.id = s.id;
SELECT count(*) FILTER (WHERE in_stack) AS stacked_rows,
       count(*) FILTER (WHERE in_stack AND unitkey(addr) IS NOT NULL) AS stacked_with_unit,
       (SELECT count(*) FROM snapped s JOIN stacked t USING (id) WHERE s.lat <> t.lat OR s.lng <> t.lng) AS snapped_to_unit
FROM stacked;
-- Whatever still shares a point after the snap gets the ring, as before.
CREATE TABLE spread AS
SELECT * EXCLUDE (in_stack) REPLACE (
  lat + CASE WHEN dup = 0 THEN 0 ELSE (8 + least(dup, 6) * 2) / 111320.0 * sin(dup * 2.399963) END AS lat,
  lng + CASE WHEN dup = 0 THEN 0 ELSE (8 + least(dup, 6) * 2) / (111320.0 * cos(radians(lat))) * cos(dup * 2.399963) END AS lng
) FROM (
  SELECT *, row_number() OVER (PARTITION BY round(lat, 5), round(lng, 5) ORDER BY prominence DESC, id) - 1 AS dup FROM snapped
);
CREATE TABLE ranked AS
SELECT * EXCLUDE (dup),
  -- ~100 m cell: the high-zoom icon budget. rank's 400 m cell is the whole screen at z17.5, so a
  -- cap on it never opens up as you zoom in; a finer cell lets more icons in the closer you get.
  row_number() OVER (PARTITION BY floor(lat / 0.0009), floor(lng * cos(radians(lat)) / 0.0009) ORDER BY prominence DESC, id) AS frank,
  row_number() OVER (PARTITION BY floor(lat / 0.0036), floor(lng * cos(radians(lat)) / 0.0036) ORDER BY prominence DESC, id) AS rank,
  row_number() OVER (PARTITION BY floor(lat / 0.0144), floor(lng * cos(radians(lat)) / 0.0144) ORDER BY prominence DESC, id) AS crank,
  row_number() OVER (PARTITION BY floor(lat / 0.058), floor(lng * cos(radians(lat)) / 0.058) ORDER BY landmark DESC, prominence DESC, id) AS xrank
FROM (
  SELECT *, CASE WHEN category IN ('airport','hospital','university','college_university','stadium_arena','shopping_center','zoo','amusement_park','convention_center','casino','aquarium','museum') THEN 1 ELSE 0 END AS landmark
  FROM spread
);
COPY (
  SELECT json_object(
    'type', 'Feature',
    'tippecanoe', json_object('minzoom', CASE
      -- A counter or a department inside a shop belongs to that shop until you are right on top
      -- of it; it never competes with its own store for the block's icon.
      -- ...except a fuel station, which is a destination of its own while driving.
      WHEN tenant = 1 AND grp <> 'fuel' THEN 17
      WHEN landmark = 1 AND xrank = 1 THEN 11
      WHEN landmark = 1 AND xrank <= 3 THEN 12
      WHEN crank = 1 AND prominence >= 6 THEN 13
      WHEN crank <= 2 OR prominence >= 5 THEN 14
      WHEN rank <= 3 OR prominence >= 4.5 THEN 15
      WHEN rank <= 12 OR prominence >= 3.5 THEN 16
      ELSE 17 END),
    'geometry', json_object('type', 'Point', 'coordinates', [lng, lat]),
    'properties', json_object(
      'id', id, 'name', name,
      'class', COALESCE(upper(substr(replace(category, '_', ' '), 1, 1)) || substr(replace(category, '_', ' '), 2), 'Place'),
      'group', grp, 'icon', 'vela-poi-' || grp, 'prominence', round(prominence, 2), 'confidence', round(COALESCE(confidence, 0.5), 2),
      'rank', rank, 'crank', crank, 'xrank', xrank, 'frank', frank, 'landmark', landmark, 'tenant', tenant,
      'brand', brand, 'addr', addr, 'loc', loc, 'website', website, 'phone', phone, 'hours', hours,
      'src', 'overture', 'origin', CASE WHEN id LIKE 'atp:%' THEN 'atp' WHEN id LIKE 'osm:%' THEN 'osm' ELSE 'overture' END
    )
  ) FROM ranked LEFT JOIN (SELECT id, any_value(loc) AS loc FROM locs GROUP BY id) lx USING (id)
) TO '$WORK/places.ndjson' (FORMAT CSV, HEADER false, QUOTE '', ESCAPE '', DELIMITER '\t');
SELECT count(*) AS features, sum(tenant) AS tenants, round(avg(prominence),2) AS prom_avg, sum(CASE WHEN landmark = 1 AND xrank <= 3 THEN 1 ELSE 0 END) AS z12, sum(CASE WHEN crank <= 2 OR prominence >= 5 THEN 1 ELSE 0 END) AS z14, sum(CASE WHEN rank <= 3 OR prominence >= 4.5 THEN 1 ELSE 0 END) AS z15, sum(CASE WHEN rank <= 12 OR prominence >= 3.5 THEN 1 ELSE 0 END) AS z16 FROM ranked;
SQL
# Uninhabited rows (Ashmore and Cartier, coral-sea specks) have no businesses at all; tippecanoe
# refuses an empty input, so leave no archive and let the workflow skip the upload.
if [ ! -s "$WORK/places.ndjson" ]; then
  echo "no places in region $ID bbox [$S,$W,$N,$E]; nothing to bake"
  rm -rf "$WORK"
  exit 0
fi
tippecanoe -o "$OUT" -l places -f -P -Z11 -z17 -B12 --no-feature-limit --no-tile-size-limit --extend-zooms-if-still-dropping "$WORK/places.ndjson" >/dev/null 2>&1
rm -rf "$WORK"
echo "wrote $OUT ($(du -h "$OUT" | cut -f1)) region $ID bbox [$S,$W,$N,$E]"
