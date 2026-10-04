#!/bin/bash
# Builds the English dictionary Vela's Piper voices read with: eSpeak NG's own word list plus the
# place names in tools/pronunciation/places.tsv (local pronunciations collected from English
# Wikipedia for the Alyo reader app; CC BY-SA 4.0, credited in Settings > About).
# The app puts the result in place of the dictionary inside each English Piper voice
# (app/voice/PlaceDictionary).
#
#   scripts/build-espeak-dict.sh <espeak-ng-data>
#
# <espeak-ng-data> is that folder from any unpacked Piper voice (the sherpa-onnx tts-models
# archives): the dictionary has to be built against the same sound tables the voices carry.
# Needs espeak-ng (brew install espeak-ng, apt install espeak-ng) and git. Commit the two files
# it writes under app/src/main/assets/pronunciation/.
set -eu
cd "$(dirname "$0")/.."
DATA="$(cd "${1:?give the espeak-ng-data folder of an unpacked Piper voice}" && pwd)"
PLACES="$PWD/tools/pronunciation/places.tsv"
OUT="$PWD/app/src/main/assets/pronunciation"
WORK="$PWD/build/espeak"
# The source the voices' dictionary was built from: compiled untouched it gives the same file.
SOURCE_REPO=https://github.com/rhasspy/espeak-ng
SOURCE_COMMIT=8593723f10cfd9befd50de447f14bf0a9d2a14a4

mkdir -p "$WORK"
if [ ! -d "$WORK/src" ]; then
    git init -q "$WORK/src"
    git -C "$WORK/src" fetch -q --depth 1 "$SOURCE_REPO" "$SOURCE_COMMIT"
    git -C "$WORK/src" checkout -q FETCH_HEAD
fi
rm -rf "$WORK/data" && mkdir -p "$WORK/data" && cp -R "$DATA" "$WORK/data/espeak-ng-data"
sum() { shasum -a 256 "$1" | cut -c1-16; }
BEFORE="$(sum "$WORK/data/espeak-ng-data/en_dict")"

cd "$WORK/src/dictsource"
rm -f en_extra
espeak-ng --path="$WORK/data" --compile=en >/dev/null 2>&1
if [ "$(sum "$WORK/data/espeak-ng-data/en_dict")" != "$BEFORE" ]; then
    echo "The eSpeak source no longer reproduces the voices' dictionary; other words could change. Stopping." >&2
    exit 1
fi

# word<tab>sounds<tab>american|british|(blank)  ->  an eSpeak list line. Names of several words go
# in brackets, and a full stop is a word of its own there. ?3 keeps a line to American voices and
# ?!3 to the others (dictrules 3 in eSpeak's en-US voice).
grep -v '^#' "$PLACES" | awk -F'\t' 'NF >= 2 && $2 != "" {
    word = tolower($1); gsub(/\./, " .", word); gsub(/-/, " ", word)
    if (word ~ / /) word = "(" word ")"
    prefix = $3 == "american" ? "?3 " : $3 == "british" ? "?!3 " : ""
    print prefix word "\t" $2
}' > en_extra
espeak-ng --path="$WORK/data" --compile=en > "$WORK/compile.log" 2>&1 || true
if grep -i -E "error|bad " "$WORK/compile.log"; then
    echo "eSpeak rejected an entry." >&2
    exit 1
fi

mkdir -p "$OUT"
cp "$WORK/data/espeak-ng-data/en_dict" "$OUT/en_dict"
sum "$OUT/en_dict" > "$OUT/en_dict.version"
echo "Built $(wc -l < en_extra | tr -d ' ') place names into the dictionary ($(wc -c < "$OUT/en_dict" | tr -d ' ') bytes)."
for word in Tucson Worcester "La Jolla" Edinburgh; do
    printf '  %-12s %s\n' "$word" "$(espeak-ng --path="$WORK/data" -v en-us -q --ipa "$word")"
done
