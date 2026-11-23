#!/usr/bin/env bash
# Get a region's OSM extract: download it, surviving a broken redirect on the mirror, or cut it
# out of a bigger extract.
#
#   scripts/fetch-pbf.sh <url> <out>
#
# <url> is a catalog row's pbf_url: a Geofabrik "<region>-latest.osm.pbf", a local path (copied),
# or a cut:
#
#   cut:<parent id>:<polygon file>
#
# A cut is a region Geofabrik does not publish (Texas has no sub-extracts). The parent is a row id
# in tools/routing-regions.json and the polygon an osmium .poly file, relative to the repository
# root. The parent's extract is downloaded like any other and `osmium extract` cuts the polygon out
# of it: `--strategy smart` keeps a road, a turn restriction's ways and a multipolygon that cross
# the edge whole, and `--set-bounds` writes the polygon's box into the header, which every bake
# reads as the region's box. Needs osmium and jq.
#
# PBF_CACHE_DIR=<dir> keeps the parent extract there between cuts (several parts of one parent on
# one machine). PBF_DATE=<YYMMDD> takes Geofabrik's dated file instead of -latest.
#
# Every bake pulls a Geofabrik "<region>-latest.osm.pbf". On 2026-09-30 Geofabrik answered those with
# a redirect to the same path plus a trailing slash, and the slashed path redirected to the dated file
# with a trailing slash too, so a plain `curl -L` looped until its redirect cap and 213 of 231
# place-pack jobs failed. The dated file itself (nord-est-260929.osm.pbf) downloaded fine.
#
# So: try the plain download first. If it fails, walk the redirects one hop at a time, and wherever a
# hop only adds a trailing slash to a file name, try the name without it.
set -euo pipefail
URL="$1"
OUT="$2"

case "$URL" in
  cut:*)
    ROOT="$(cd "$(dirname "$0")/.." && pwd)"
    spec="${URL#cut:}"
    parent="${spec%%:*}"
    poly="${spec#*:}"
    if [ "$parent" = "$spec" ] || [ -z "$parent" ] || [ -z "$poly" ]; then
      echo "fetch-pbf: '$URL' is not cut:<parent id>:<polygon file>" >&2; exit 1
    fi
    case "$poly" in /*) ;; *) poly="$ROOT/$poly" ;; esac
    [ -f "$poly" ] || { echo "fetch-pbf: no polygon file $poly" >&2; exit 1; }
    command -v osmium >/dev/null 2>&1 || { echo "fetch-pbf: cutting $URL needs osmium (apt install osmium-tool)" >&2; exit 1; }
    purl="$(jq -r --arg id "$parent" '.regions[] | select(.id == $id) | .pbf_url // empty' "$ROOT/tools/routing-regions.json")"
    [ -n "$purl" ] || { echo "fetch-pbf: no row '$parent' in tools/routing-regions.json to cut from" >&2; exit 1; }
    case "$purl" in cut:*) echo "fetch-pbf: the parent '$parent' is a cut itself; cut from a downloaded extract" >&2; exit 1 ;; esac
    if [ -n "${PBF_CACHE_DIR:-}" ]; then
      mkdir -p "$PBF_CACHE_DIR"
      src="$PBF_CACHE_DIR/$parent${PBF_DATE:+-$PBF_DATE}.osm.pbf"
      if [ ! -s "$src" ]; then
        bash "$0" "$purl" "$src.part"
        mv "$src.part" "$src"
      fi
    else
      # beside the output, so the parent lands on the volume the bake chose for its work
      src="$(dirname "$OUT")/.cut-parent-$$.osm.pbf"
      trap 'rm -f "$src"' EXIT
      bash "$0" "$purl" "$src"
    fi
    echo "fetch-pbf: cutting $(basename "$poly") out of $parent" >&2
    osmium extract -p "$poly" --strategy smart --set-bounds --overwrite -o "$OUT" "$src" >&2
    exit 0 ;;
esac

if [ -f "$URL" ]; then cp "$URL" "$OUT"; exit 0; fi

if [ -n "${PBF_DATE:-}" ]; then URL="${URL%-latest.osm.pbf}-$PBF_DATE.osm.pbf"; fi

if curl -fsSL --retry 3 --retry-delay 5 --max-redirs 10 -o "$OUT" "$URL"; then exit 0; fi
echo "fetch-pbf: plain download failed, walking the redirects of $URL" >&2

u="$URL"
seen=" "
for _ in 1 2 3 4 5 6 7 8; do
  case "$seen" in *" $u "*) break ;; esac
  seen="$seen$u "
  # curl prints "000 " through -w when it cannot connect, and exits non-zero; || true keeps set -e
  # from ending the walk there (an `|| echo` after it wrote into a closed pipe: "Broken pipe").
  out=$(curl -s -o /dev/null -r 0-0 --max-redirs 0 -w '%{http_code} %{redirect_url}' "$u" || true)
  code="${out%% *}"; loc="${out#* }"; [ "$loc" = "$out" ] && loc=""; [ -n "$code" ] || code=000
  case "$code" in
    200|206)
      echo "fetch-pbf: downloading $u" >&2
      curl -fsS --retry 3 --retry-delay 5 --max-redirs 0 -o "$OUT" "$u"
      exit 0 ;;
    30[1278])
      [ -n "$loc" ] || break
      trimmed="${loc%/}"
      if [ "$trimmed" != "$u" ]; then u="$trimmed"; else u="$loc"; fi ;;
    *) break ;;
  esac
done
# The redirects can also run in a circle (seen from GitHub's runners: -latest -> -latest/ -> -latest),
# or point at a path that is not there (2026-11-20 to 23, while Geofabrik moved its files). So read
# the region's dated files from the listings and take the newest, then the one before it (a file
# just announced can 404 for a while). Since 2026-11-19 they sit in a folder of their own,
# <parent>/<region>/<region>-YYMMDD.osm.pbf, linked by full URLs; before, they sat beside the
# -latest name, linked by name. Both listings are read. YYMMDD sorts by date.
case "$URL" in
  *-latest.osm.pbf)
    dir="${URL%/*}"
    base="${URL##*/}"; base="${base%-latest.osm.pbf}"
    host="${URL%%://*}://$(printf '%s' "${URL#*://}" | cut -d/ -f1)"
    for folder in "$dir/$base" "$dir"; do
      hrefs=$(curl -fsSL --retry 2 --max-redirs 3 "$folder/" 2>/dev/null \
        | grep -oE "href=\"[^\"]*${base}-[0-9]{6}\.osm\.pbf\"" | sed -E 's/^href="//; s/"$//' || true)
      [ -n "$hrefs" ] || continue
      for h in $(printf '%s\n' $hrefs | awk -F/ '{print $NF" "$0}' | sort -u -r | head -2 | cut -d' ' -f2); do
        case "$h" in http*) f="$h" ;; /*) f="$host$h" ;; *) f="$folder/$h" ;; esac
        echo "fetch-pbf: downloading the dated extract $f" >&2
        if curl -fsS --retry 3 --retry-delay 5 --max-redirs 0 -o "$OUT" "$f"; then exit 0; fi
      done
    done ;;
esac
echo "fetch-pbf: could not download $URL (last try $u, HTTP ${code:-none})" >&2
exit 1
