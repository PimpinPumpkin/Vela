#!/usr/bin/env bash
# The two bans that are about how this repo READS, checked instead of remembered.
#
#   scripts/check-writing.sh [<git range>]     default: origin/main..HEAD
#
# 1. NO AI ATTRIBUTION. No commit carries a Co-Authored-By: Claude trailer or a "generated with"
#    line. The assistant's own harness can be told to add one; that instruction is wrong here, and
#    it has slipped through before (2026-09-18, six commits and two squash merges).
# 2. NO EM DASHES. They are the clearest machine tell in English prose, and this text is public.
#    The rule covers commit messages, and the same rule applies by hand to issue and PR comments,
#    release notes and docs.
set -euo pipefail
RANGE="${1:-origin/main..HEAD}"
FAIL=0
MSGS="$(git log "$RANGE" --format='%H%n%B' 2>/dev/null || true)"
if [ -n "$MSGS" ]; then
  if grep -inE "co-authored-by:.*(claude|anthropic)|generated with \[?claude|🤖" <<<"$MSGS"; then
    echo "FAIL: a commit message carries AI attribution" >&2; FAIL=1
  fi
  if grep -n "—" <<<"$MSGS"; then
    echo "FAIL: a commit message contains an em dash" >&2; FAIL=1
  fi
fi
# Em dashes in what this change ADDS. Only added lines: the repo carries plenty of older ones and
# a whole-file test would fail every change that touches those files without preventing a thing.
ADDED="$(git diff "$RANGE" -U0 -- '*.md' '*.kt' '*.xml' 2>/dev/null | grep '^+' | grep -v '^+++' || true)"
if grep -n "—" <<<"$ADDED" >/dev/null 2>&1; then
  echo "FAIL: this change adds an em dash:" >&2
  grep -n "—" <<<"$ADDED" | head -5 >&2
  FAIL=1
fi
[ "$FAIL" -eq 0 ] && echo "writing checks passed"
exit "$FAIL"
