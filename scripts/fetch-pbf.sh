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
for _ in 1 2 3 4 5 6 7 8; do
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
echo "fetch-pbf: could not download $URL (last try $u, HTTP ${code:-none})" >&2
exit 1
