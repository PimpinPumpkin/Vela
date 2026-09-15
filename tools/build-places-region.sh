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
ID="$1"; S="$2"; W="$3"; N="$4"; E="$5"; OUT="$6"; RELEASE="${7:-2026-08-19.0}"; LOCAL="${8:-}"
ATP_RUN="${ATP_RUN:-2026-09-05-13-32-25}"
WORK="$(mktemp -d)"
ATP_NDJSON=""
if [ "$ATP_RUN" != "none" ] && command -v pmtiles >/dev/null 2>&1 && command -v tippecanoe-decode >/dev/null 2>&1 && command -v jq >/dev/null 2>&1; then
  ATP_SRC="${ATP_LOCAL:-https://alltheplaces-data.openaddresses.io/runs/$ATP_RUN/output.pmtiles}"
  if pmtiles extract "$ATP_SRC" "$WORK/atp.pmtiles" --bbox="$W,$S,$E,$N" --minzoom=15 --maxzoom=15 >/dev/null 2>&1; then
    tippecanoe-decode -z15 -Z15 "$WORK/atp.pmtiles" 2>/dev/null \
      | jq -c '.. | objects | select(.type == "Feature" and .geometry.type == "Point") | {props: .properties, lng: .geometry.coordinates[0], lat: .geometry.coordinates[1]}' \
      > "$WORK/atp.ndjson" || true
    if [ -s "$WORK/atp.ndjson" ]; then ATP_NDJSON="$WORK/atp.ndjson"; else echo "alltheplaces: no rows in the box"; fi
  else
    echo "alltheplaces: extract failed for $ID, baking Overture only"
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
    ELSE NULL END AS category,
  0.85 AS confidence,
  json_extract_string(props, 'brand') AS brand,
  COALESCE(json_extract_string(props, 'addr:full'), json_extract_string(props, 'addr:street_address')) AS addr,
  json_extract_string(props, 'website') AS website, json_extract_string(props, 'phone') AS phone, 'open' AS operating_status,
  json_extract_string(props, 'opening_hours') AS hours, lng, lat
FROM atp_raw
WHERE json_extract_string(props, 'name') IS NOT NULL AND json_extract_string(props, 'name') <> ''
  AND lng BETWEEN $W AND $E AND lat BETWEEN $S AND $N
  AND (json_extract_string(props, 'shop') IS NOT NULL
    OR json_extract_string(props, 'tourism') IN ('hotel', 'motel', 'hostel', 'guest_house', 'museum')
    OR json_extract_string(props, 'leisure') = 'fitness_centre'
    OR json_extract_string(props, 'healthcare') IS NOT NULL
    OR json_extract_string(props, 'office') IN ('insurance', 'financial_advisor', 'estate_agent', 'tax_advisor', 'lawyer', 'accountant', 'travel_agent')
    OR json_extract_string(props, 'amenity') IN ('restaurant', 'fast_food', 'cafe', 'bar', 'pub', 'ice_cream', 'fuel', 'pharmacy', 'bank', 'dentist', 'doctors', 'clinic',
      'veterinary', 'cinema', 'car_wash', 'car_rental', 'car_repair', 'post_office', 'charging_station', 'gym', 'hospital', 'childcare', 'kindergarten',
      'coworking_space', 'theatre', 'nightclub', 'food_court', 'bureau_de_change', 'money_transfer', 'driving_school', 'language_school',
      'music_school', 'dancing_school', 'library', 'marketplace', 'bicycle_rental'));
-- The first two significant words of a name, the app's own namesAgree rule in SQL form.
CREATE MACRO nkey(n) AS trim(regexp_extract(regexp_replace(lower(n), '[^a-z0-9 ]', ' ', 'g'), '\\b([a-z0-9]{2,})\\b', 1) || ' ' ||
  regexp_extract(regexp_replace(lower(n), '[^a-z0-9 ]', ' ', 'g'), '\\b[a-z0-9]{2,}\\b(?: [a-z0-9] )* +\\b([a-z0-9]{2,})\\b', 1));
INSERT INTO raw
SELECT a.id, a.name, a.category, a.confidence, a.brand, a.addr, a.website, a.phone, a.operating_status, a.lng, a.lat, a.hours
FROM atp a
WHERE NOT EXISTS (
  SELECT 1 FROM raw o
  WHERE abs(o.lat - a.lat) < 0.0015 AND abs(o.lng - a.lng) < 0.002
    AND (nkey(o.name) = nkey(a.name) OR (o.brand IS NOT NULL AND a.brand IS NOT NULL AND lower(o.brand) = lower(a.brand)))
);
SELECT (SELECT count(*) FROM atp) AS atp_in_box, (SELECT count(*) FROM raw WHERE id LIKE 'atp:%') AS atp_added;
ATPSQL
fi
if [ -n "$LOCAL" ]; then
  SRC="read_parquet('$LOCAL')"
  SEL="id, name, category, confidence, brand, addr, website, phone, operating_status, lng, lat"
else
  SRC="read_parquet('s3://overturemaps-us-west-2/release/$RELEASE/theme=places/type=place/*', hive_partitioning=1)"
  SEL="id, names.primary AS name, categories.primary AS category, confidence, brand.names.primary AS brand, addresses[1].freeform AS addr, websites[1] AS website, phones[1] AS phone, operating_status, ST_X(geometry) AS lng, ST_Y(geometry) AS lat"
fi
duckdb <<SQL
INSTALL httpfs; LOAD httpfs; INSTALL spatial; LOAD spatial; SET s3_region='us-west-2';
CREATE TABLE raw AS SELECT $SEL, CAST(NULL AS VARCHAR) AS hours FROM $SRC
  WHERE lng BETWEEN $W AND $E AND lat BETWEEN $S AND $N;
$ATP_SQL
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
CREATE TABLE anchored AS
SELECT s.* REPLACE (CASE WHEN a.id IS NOT NULL THEN s.prominence - 2.0 ELSE s.prominence END AS prominence)
FROM scored s
LEFT JOIN (
  SELECT id, addr, lat, lng FROM scored
  WHERE addr IS NOT NULL AND category IN ('supermarket','grocery_store','department_store','shopping_center','hospital','university','college_university','hardware_store','home_improvement_store','wholesale_store','warehouse_club','sporting_goods','electronics','furniture_store')
) a ON s.addr = a.addr AND s.id <> a.id AND abs(s.lat - a.lat) < 0.002 AND abs(s.lng - a.lng) < 0.003
  AND s.category NOT IN ('supermarket','grocery_store','department_store','shopping_center','hospital','university','college_university','hardware_store','home_improvement_store','wholesale_store','warehouse_club','sporting_goods','electronics','furniture_store');
-- STACKED POINTS (2026-09-15): Overture puts every tenant of a building on the same parcel point
-- (17% of Davis rows share their point with another: medical suites, strip-mall tenants), and
-- coincident icons collide at every zoom, so all but the top one never drew. Spread the stack on
-- a small ring (about 8 to 20 m, golden-angle steps, best row stays put) so they separate at the
-- zooms where a person is looking for one shop in a row of them.
CREATE TABLE spread AS
SELECT * REPLACE (
  lat + CASE WHEN dup = 0 THEN 0 ELSE (8 + least(dup, 6) * 2) / 111320.0 * sin(dup * 2.399963) END AS lat,
  lng + CASE WHEN dup = 0 THEN 0 ELSE (8 + least(dup, 6) * 2) / (111320.0 * cos(radians(lat))) * cos(dup * 2.399963) END AS lng
) FROM (
  SELECT *, row_number() OVER (PARTITION BY round(lat, 5), round(lng, 5) ORDER BY prominence DESC, id) - 1 AS dup FROM anchored
);
CREATE TABLE ranked AS
SELECT * EXCLUDE (dup),
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
      'rank', rank, 'crank', crank, 'xrank', xrank, 'landmark', landmark,
      'brand', brand, 'addr', addr, 'website', website, 'phone', phone, 'hours', hours,
      'src', 'overture', 'origin', CASE WHEN id LIKE 'atp:%' THEN 'atp' ELSE 'overture' END
    )
  ) FROM ranked
) TO '$WORK/places.ndjson' (FORMAT CSV, HEADER false, QUOTE '', ESCAPE '', DELIMITER '\t');
SELECT count(*) AS features, round(avg(prominence),2) AS prom_avg, sum(CASE WHEN landmark = 1 AND xrank <= 3 THEN 1 ELSE 0 END) AS z12, sum(CASE WHEN crank <= 2 OR prominence >= 5 THEN 1 ELSE 0 END) AS z14, sum(CASE WHEN rank <= 3 OR prominence >= 4.5 THEN 1 ELSE 0 END) AS z15, sum(CASE WHEN rank <= 12 OR prominence >= 3.5 THEN 1 ELSE 0 END) AS z16 FROM ranked;
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
