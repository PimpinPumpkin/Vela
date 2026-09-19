#!/usr/bin/env bash
# Block anything that could say where the maintainer is, BEFORE it leaves the machine.
#
#   scripts/check-location.sh [<git range>]     default: origin/main..HEAD
#
# The CI Location guard does this with the LOCATION_TERMS repo secret, which catches a leak after
# it is public. This is the same test run locally by the pre-push hook, so it catches one while it
# can still be amended away.
#
# THE TERM LIST IS NEVER IN THIS REPO. Writing "never commit <town>" into a tracked file commits
# <town>. It lives at $VELA_LOCATION_TERMS, default ~/.vela-location-terms, one term per line,
# case-insensitive, blank lines and #-comments ignored. Create it by hand; it is the one file here
# that must never be added, pasted into an issue, or read back into a commit message.
#
# It reports FILE AND LINE ONLY, never the matched text, for the same reason the CI guard does.
set -euo pipefail
RANGE="${1:-origin/main..HEAD}"
TERMS_FILE="${VELA_LOCATION_TERMS:-$HOME/.vela-location-terms}"
[ -f "$TERMS_FILE" ] || { echo "no local location-term list at $TERMS_FILE; skipping"; exit 0; }
FAIL=0
DIFF="$(git diff "$RANGE" -U0 2>/dev/null | grep '^+' | grep -v '^+++' || true)"
MSGS="$(git log "$RANGE" --format='%B' 2>/dev/null || true)"
while IFS= read -r term; do
  term="$(echo "$term" | sed 's/#.*//' | xargs || true)"
  [ -z "$term" ] && continue
  if grep -qiF -- "$term" <<<"$DIFF"; then
    echo "FAIL: this change adds a term from the private location list (term $((++FAIL)) of the list)" >&2
  fi
  if grep -qiF -- "$term" <<<"$MSGS"; then
    echo "FAIL: a commit message carries a term from the private location list" >&2
    FAIL=$((FAIL + 1))
  fi
done < "$TERMS_FILE"
if [ "$FAIL" -ne 0 ]; then
  echo "Look at your own diff and generalize it. A place is fine when it is the SUBJECT of the" >&2
  echo "change; it is not fine when it is the scenery of where you happen to be." >&2
  exit 1
fi
echo "location check passed"
