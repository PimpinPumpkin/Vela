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
set -euo pipefail
ID="$1"; S="$2"; W="$3"; N="$4"; E="$5"; OUT="$6"; RELEASE="${7:-2026-08-19.0}"; LOCAL="${8:-}"
WORK="$(mktemp -d)"
if [ -n "$LOCAL" ]; then
  SRC="read_parquet('$LOCAL')"
  SEL="id, name, category, confidence, brand, addr, website, phone, operating_status, lng, lat"
else
  SRC="read_parquet('s3://overturemaps-us-west-2/release/$RELEASE/theme=places/type=place/*', hive_partitioning=1)"
  SEL="id, names.primary AS name, categories.primary AS category, confidence, brand.names.primary AS brand, addresses[1].freeform AS addr, websites[1] AS website, phones[1] AS phone, operating_status, ST_X(geometry) AS lng, ST_Y(geometry) AS lat"
fi
duckdb <<SQL
INSTALL httpfs; LOAD httpfs; INSTALL spatial; LOAD spatial; SET s3_region='us-west-2';
CREATE TABLE raw AS SELECT $SEL FROM $SRC
  WHERE lng BETWEEN $W AND $E AND lat BETWEEN $S AND $N;
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
-- A third, ~6.5 km cell (`xrank`) picks the landmarks Google still draws zoomed out to z11/z12:
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
      'brand', brand, 'addr', addr, 'website', website, 'phone', phone, 'src', 'overture'
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
