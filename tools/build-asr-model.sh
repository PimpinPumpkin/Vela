#!/usr/bin/env bash
# Builds the lean on-device speech-to-text archive Vela downloads for voice search (tier-1),
# and uploads it to the `asr-models` GitHub release. Run once (or when bumping the model).
#
# The archive is Whisper tiny multilingual (int8) + Silero VAD, taken from the upstream
# sherpa-onnx pre-trained models and slimmed to the four files the app actually loads:
#   whisper-tiny/tiny-encoder.int8.onnx
#   whisper-tiny/tiny-decoder.int8.onnx
#   whisper-tiny/tiny-tokens.txt
#   whisper-tiny/silero_vad.onnx
# The full upstream tarball also ships the fp32 models + test wavs (~116 MB); dropping those
# keeps the download small (gzip, not bzip2: a phone unpacks gzip about ten times faster). sherpa-onnx models are Apache-2.0 / MIT.
#
# Usage: tools/build-asr-model.sh            # build the archive into ./out
#        UPLOAD=1 tools/build-asr-model.sh   # also create/refresh the asr-models release
set -euo pipefail

WHISPER_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2"
VAD_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
REPO="PimpinPumpkin/Vela"
OUT="${OUT:-out}"

mkdir -p "$OUT" && cd "$OUT"
echo "==> downloading upstream whisper-tiny + silero_vad"
[ -f whisper-tiny.tar.bz2 ] || curl -fL "$WHISPER_URL" -o whisper-tiny.tar.bz2
[ -f silero_vad.onnx ]      || curl -fL "$VAD_URL" -o silero_vad.onnx
tar xjf whisper-tiny.tar.bz2

echo "==> slimming to int8 + VAD"
rm -rf whisper-tiny && mkdir whisper-tiny
cp sherpa-onnx-whisper-tiny/tiny-encoder.int8.onnx \
   sherpa-onnx-whisper-tiny/tiny-decoder.int8.onnx \
   sherpa-onnx-whisper-tiny/tiny-tokens.txt \
   silero_vad.onnx \
   whisper-tiny/
tar czf vela-asr-whisper-tiny.tar.gz whisper-tiny
SIZE_MB=$(( $(stat -f%z vela-asr-whisper-tiny.tar.gz 2>/dev/null || stat -c%s vela-asr-whisper-tiny.tar.gz) / 1048576 ))
echo "==> vela-asr-whisper-tiny.tar.gz = ${SIZE_MB} MB"

cat > asr-manifest.json <<JSON
{
  "schema": 1,
  "models": [
    {
      "id": "whisper-tiny",
      "name": "Whisper tiny (multilingual)",
      "url": "https://github.com/${REPO}/releases/download/asr-models/vela-asr-whisper-tiny.tar.gz",
      "sizeMb": ${SIZE_MB},
      "dir": "whisper-tiny",
      "encoder": "tiny-encoder.int8.onnx",
      "decoder": "tiny-decoder.int8.onnx",
      "tokens": "tiny-tokens.txt",
      "vad": "silero_vad.onnx",
      "languages": ["en","fr","de","es","it","pt","nl","ru","pl","sv","uk"]
    }
  ]
}
JSON

if [ "${UPLOAD:-0}" = "1" ]; then
  echo "==> publishing to the asr-models release"
  gh release view asr-models --repo "$REPO" >/dev/null 2>&1 \
    && gh release upload asr-models --repo "$REPO" --clobber vela-asr-whisper-tiny.tar.gz asr-manifest.json \
    || gh release create asr-models --repo "$REPO" --prerelease \
         --title "ASR models (on-device voice search)" \
         --notes "On-device speech-to-text (Whisper tiny int8 + Silero VAD) Vela downloads for voice search. Infrastructure release." \
         vela-asr-whisper-tiny.tar.gz asr-manifest.json
fi
echo "==> done"
