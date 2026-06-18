#!/usr/bin/env bash
# Sign calibration.json so the app (which pins the matching public key in
# CalibrationStore) will accept it. Run this after EVERY edit to calibration.json,
# then commit calibration.json + calibration.json.sig together.
#
#   ./scripts/sign-calibration.sh
#
# The private key lives outside the repo (never commit it). Override its path with
# VELA_CALIBRATION_KEY if needed.
set -euo pipefail

KEY="${VELA_CALIBRATION_KEY:-$HOME/.vela-signing/vela-calibration.key}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JSON="$ROOT/calibration.json"
SIG="$ROOT/calibration.json.sig"

if [ ! -f "$KEY" ]; then
  echo "error: signing key not found at $KEY" >&2
  echo "generate it once with:" >&2
  echo "  openssl ecparam -name prime256v1 -genkey -noout -out $KEY" >&2
  exit 1
fi

# Detached ECDSA-P256/SHA-256 signature, base64 (matches BundleSignature.verify).
openssl dgst -sha256 -sign "$KEY" "$JSON" | base64 | tr -d '\n' > "$SIG"

ver="$(grep -oE '"version"[[:space:]]*:[[:space:]]*[0-9]+' "$JSON" | grep -oE '[0-9]+')"
echo "signed calibration.json (version $ver) -> calibration.json.sig"

# Self-check: verify with the public half before anyone ships it.
PUB="$(mktemp)"; trap 'rm -f "$PUB"' EXIT
openssl ec -in "$KEY" -pubout -out "$PUB" 2>/dev/null
if openssl dgst -sha256 -verify "$PUB" -signature <(base64 -d < "$SIG") "$JSON" >/dev/null 2>&1; then
  echo "verify OK"
else
  echo "error: self-verify FAILED — do not commit" >&2
  exit 1
fi
