#!/usr/bin/env bash
# Download an OSM extract, surviving a broken redirect on the mirror.
#
#   scripts/fetch-pbf.sh <url> <out>
#
# Every bake pulls a Geofabrik "<region>-latest.osm.pbf". On 2026-09-30 Geofabrik answered those with
# a redirect to the same path plus a trailing slash, and the slashed path redirected to the dated file
# with a trailing slash too, so a plain `curl -L` looped until its redirect cap and 213 of 231
# place-pack jobs failed. The dated file itself (nord-est-260929.osm.pbf) downloaded fine.
#
# So: try the plain download first. If it fails, walk the redirects one hop at a time, and wherever a
# hop only adds a trailing slash to a file name, try the name without it. A local path is copied.
set -euo pipefail
URL="$1"
OUT="$2"

if [ -f "$URL" ]; then cp "$URL" "$OUT"; exit 0; fi

if curl -fsSL --retry 3 --retry-delay 5 --max-redirs 10 -o "$OUT" "$URL"; then exit 0; fi
echo "fetch-pbf: plain download failed, walking the redirects of $URL" >&2

u="$URL"
seen=" "
for _ in 1 2 3 4 5 6 7 8; do
  case "$seen" in *" $u "*) break ;; esac
  seen="$seen$u "
  read -r code loc < <(curl -s -o /dev/null -r 0-0 --max-redirs 0 -w '%{http_code} %{redirect_url}\n' "$u" || echo "000 ")
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
# The redirects can also run in a circle (seen from GitHub's runners: -latest -> -latest/ -> -latest).
# Geofabrik keeps the dated files beside the -latest name, so read the folder listing and take the
# newest one for this region (names are <region>-YYMMDD.osm.pbf, so the largest sorts last).
case "$URL" in
  *-latest.osm.pbf)
    dir="${URL%/*}"
    base="${URL##*/}"; base="${base%-latest.osm.pbf}"
    dated=$(curl -fsSL --retry 3 --max-redirs 3 "$dir/" 2>/dev/null \
      | grep -oE "href=\"${base}-[0-9]{6}\.osm\.pbf\"" | grep -oE "${base}-[0-9]{6}\.osm\.pbf" | sort -u | tail -1 || true)
    if [ -n "$dated" ]; then
      echo "fetch-pbf: downloading the newest dated extract $dir/$dated" >&2
      curl -fsS --retry 3 --retry-delay 5 --max-redirs 0 -o "$OUT" "$dir/$dated"
      exit 0
    fi ;;
esac
echo "fetch-pbf: could not download $URL (last try $u, HTTP ${code:-none})" >&2
exit 1
