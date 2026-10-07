#!/bin/bash
# Build the map-fonts glyph set: Google Sans Flex over Roboto over OpenFreeMap's Noto, per glyph.
#
# Roboto wins Latin/Cyrillic/Greek (896 glyphs per stack); Noto keeps every other
# script (CJK, Arabic, Devanagari, ...), so no label anywhere in the world loses
# coverage. Output folders keep the "Noto Sans ..." stack names the Liberty style
# requests, so the app only swaps the style's glyphs URL (see ui/map/MapFonts).
#
# Publish: upload map-fonts.zip to the fixed-tag `map-fonts` GitHub release
# (prerelease, like the other infrastructure releases); fdroid-repo.yml unpacks it
# into the Pages site at /Vela/fonts/. Re-run only if the fonts ever change.
#
# Usage: scripts/build-map-fonts.sh [workdir]
set -euo pipefail
WORK="${1:-/tmp/map-fonts-build}"
HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$WORK" && cd "$WORK"

# 1. Prebuilt Roboto glyph PBFs (openmaptiles/fonts v2.0 release).
if [ ! -d omt/"Roboto Regular" ]; then
  curl -sL -o omt-fonts.zip https://github.com/openmaptiles/fonts/releases/download/v2.0/v2.0.zip
  mkdir -p omt
  unzip -q -o omt-fonts.zip -d omt "Roboto Regular/*" "Roboto Bold/*" "Roboto Italic/*"
fi

# 2. OpenFreeMap's live Noto set (the exact glyphs the Liberty style uses today).
for stack in "Noto Sans Regular" "Noto Sans Bold" "Noto Sans Italic"; do
  mkdir -p "noto/$stack"
  enc=$(python3 -c "import urllib.parse;print(urllib.parse.quote('$stack'))")
  i=0
  while [ $i -lt 65536 ]; do
    j=$((i+255))
    f="noto/$stack/$i-$j.pbf"
    if [ ! -s "$f" ]; then
      curl -s --retry 3 -o "$f" "https://tiles.openfreemap.org/fonts/$enc/$i-$j.pbf" &
    fi
    while [ "$(jobs -r | wc -l)" -ge 12 ]; do sleep 0.05; done
    i=$((j+1))
  done
  wait
  n=$(ls "noto/$stack" | wc -l | tr -d ' ')
  [ "$n" = "256" ] || { echo "ERROR: $stack has $n/256 ranges (re-run to resume)"; exit 1; }
done

# 3. Composite (Roboto wins per glyph id).
rm -rf fonts_mid fonts_out
python3 "$HERE/composite_glyphs.py" omt noto fonts_mid

# 4. Google Sans Flex (SIL OFL) over that, for the glyphs it has: Latin, extended Latin and
#    Vietnamese. Roboto keeps Cyrillic and Greek, Noto everything else. The variable font is cut
#    into three static faces (fonttools), each rendered to glyph ranges (node fontnik), and laid
#    out under the "Roboto ..." folder names the composite script pairs with the Noto stacks.
#    Needs: pip install fonttools; npm install fontnik (in $WORK).
TTF="$HERE/../app/src/main/res/font/google_sans_flex.ttf"
AX="wdth=100 opsz=14 GRAD=0 ROND=0"
fonttools varLib.instancer "$TTF" wght=400 slnt=0 $AX -o flex-regular.ttf
fonttools varLib.instancer "$TTF" wght=700 slnt=0 $AX -o flex-bold.ttf
fonttools varLib.instancer "$TTF" wght=400 slnt=-10 $AX -o flex-italic.ttf
[ -d node_modules/fontnik ] || npm install --silent fontnik@0.7.7
rm -rf flex
node "$HERE/flex_glyphs.js" flex
python3 "$HERE/composite_glyphs.py" flex fonts_mid fonts_out
(cd fonts_out && zip -qr "$WORK/map-fonts.zip" "Noto Sans Regular" "Noto Sans Bold" "Noto Sans Italic")
echo "Built $WORK/map-fonts.zip"
echo "Publish: gh release create map-fonts $WORK/map-fonts.zip --prerelease --title 'Map font glyphs (infrastructure)' --notes 'Roboto-over-Noto glyph PBFs for the basemap; served via GitHub Pages by fdroid-repo.yml. Do not delete.'"
