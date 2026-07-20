#!/usr/bin/env bash
# Repackage a sherpa-onnx prebuilt ASR model into Vela's `asr-models` release convention and upload it.
#
#   scripts/build-asr-model.sh <engine-id>        # engine-id ∈ {sensevoice, moonshine}
#
# Vela's on-device dictation ([AsrEngine]/[AsrRecognizer]) downloads a `vela-asr-<id>.tar.gz` whose
# SINGLE top-level folder `<id>/` holds the model files this app expects PLUS `silero_vad.onnx` (every
# engine archive is self-contained so the VAD travels with it). We build that from the upstream
# k2-fsa/sherpa-onnx prebuilt (int8) and publish it beside the Whisper archive.
#
# Needs: gh (authenticated), curl, tar (with bzip2), a scratch dir. Whisper's archive supplies the VAD.
set -euo pipefail

ID="${1:?engine id (sensevoice|moonshine)}"
REPO="${VELA_REPO:-PimpinPumpkin/Vela}"
TAG="asr-models"
K2="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
OUT="$WORK/$ID"; mkdir -p "$OUT"

case "$ID" in
  sensevoice)
    SRC="sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2"
    # We keep only the int8 model + tokens (the fp32 model.onnx is huge and unused).
    declare -a PICK=("model.int8.onnx:model.int8.onnx" "tokens.txt:tokens.txt")
    ;;
  moonshine)
    SRC="sherpa-onnx-moonshine-tiny-en-int8.tar.bz2"
    declare -a PICK=(
      "preprocess.onnx:preprocess.onnx" "encode.int8.onnx:encode.int8.onnx"
      "uncached_decode.int8.onnx:uncached_decode.int8.onnx"
      "cached_decode.int8.onnx:cached_decode.int8.onnx" "tokens.txt:tokens.txt"
    )
    ;;
  *) echo "unknown engine id: $ID" >&2; exit 2 ;;
esac

echo "→ downloading upstream $SRC"
curl -fsSL "$K2/$SRC" -o "$WORK/src.tar.bz2"
mkdir -p "$WORK/src"; tar xjf "$WORK/src.tar.bz2" -C "$WORK/src"
INNER="$(find "$WORK/src" -mindepth 1 -maxdepth 1 -type d | head -1)"

for map in "${PICK[@]}"; do
  from="${map%%:*}"; to="${map##*:}"
  found="$(find "$INNER" -name "$from" | head -1)"
  [ -n "$found" ] || { echo "missing $from in upstream archive" >&2; exit 1; }
  cp "$found" "$OUT/$to"
done

echo "→ adding silero_vad.onnx from the Whisper archive"
curl -fsSL "https://github.com/$REPO/releases/download/$TAG/vela-asr-whisper-tiny.tar.gz" -o "$WORK/whisper.tar.gz"
tar xzf "$WORK/whisper.tar.gz" -C "$WORK"
cp "$WORK/whisper-tiny/silero_vad.onnx" "$OUT/silero_vad.onnx"

ASSET="vela-asr-$ID.tar.gz"
( cd "$WORK" && tar czf "$ASSET" "$ID" )
SIZE=$(( ( $(stat -f%z "$WORK/$ASSET" 2>/dev/null || stat -c%s "$WORK/$ASSET") + 1048575 ) / 1048576 ))
echo "→ built $ASSET (${SIZE} MB): $(tar tzf "$WORK/$ASSET" | tr '\n' ' ')"

gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1 || \
  gh release create "$TAG" --repo "$REPO" --prerelease --title "Offline voice-search models" \
    --notes "Prebuilt sherpa-onnx ASR models for Vela on-device dictation. Data assets, not a code release."
gh release upload "$TAG" "$WORK/$ASSET" --clobber --repo "$REPO"
echo "✓ published $ASSET to the $TAG release (${SIZE} MB)"
