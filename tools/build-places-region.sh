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
# details, Overture confidence), and a tippecanoe minzoom from that prominence so density on the
# map falls out of the data, not a runtime rank.
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
    WHEN category LIKE '%restaurant%' OR category IN ('coffee_shop','cafe','bar','pub','bakery','ice_cream_shop','brewery','winery','food_court','deli','juice_bar','tea_room') OR category LIKE '%food%' THEN 'food'
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
COPY (
  SELECT json_object(
    'type', 'Feature',
    'tippecanoe', json_object('minzoom', CASE WHEN prominence >= 6 THEN 13 WHEN prominence >= 4.5 THEN 14 WHEN prominence >= 3.5 THEN 15 WHEN prominence >= 2.5 THEN 16 ELSE 17 END),
    'geometry', json_object('type', 'Point', 'coordinates', [lng, lat]),
    'properties', json_object(
      'id', id, 'name', name,
      'class', COALESCE(upper(substr(replace(category, '_', ' '), 1, 1)) || substr(replace(category, '_', ' '), 2), 'Place'),
      'group', grp, 'icon', 'vela-poi-' || grp, 'prominence', round(prominence, 2), 'confidence', round(COALESCE(confidence, 0.5), 2),
      'brand', brand, 'addr', addr, 'website', website, 'phone', phone, 'src', 'overture'
    )
  ) FROM scored
) TO '$WORK/places.ndjson' (FORMAT CSV, HEADER false, QUOTE '', ESCAPE '', DELIMITER '\t');
SELECT count(*) AS features, round(avg(prominence),2) AS prom_avg, sum(CASE WHEN prominence>=4.5 THEN 1 ELSE 0 END) AS z14, sum(CASE WHEN prominence>=3 THEN 1 ELSE 0 END) AS z15 FROM scored;
SQL
tippecanoe -o "$OUT" -l places -f -P -Z12 -z17 -B12 --no-feature-limit --no-tile-size-limit --extend-zooms-if-still-dropping "$WORK/places.ndjson" >/dev/null 2>&1
rm -rf "$WORK"
echo "wrote $OUT ($(du -h "$OUT" | cut -f1)) region $ID bbox [$S,$W,$N,$E]"
