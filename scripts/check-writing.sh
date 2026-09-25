#!/usr/bin/env bash
# The two bans that are about how this repo READS, checked instead of remembered.
#
#   scripts/check-writing.sh [<git range>]     default: origin/main..HEAD
#
# 1. NO AI ATTRIBUTION. No commit carries a Co-Authored-By: Claude trailer or a "generated with"
#    line. The assistant's own harness can be told to add one; that instruction is wrong here, and
#    it has slipped through before (2026-09-18, six commits and two squash merges).
# 2. US ENGLISH. The repo is written in US English (CLAUDE.md lists the three data exceptions), and
#    that includes commit messages, which are the user-facing changelog. Slipped on 2026-09-18 in a
#    commit message and twice in drafted issue replies, all three by the assistant.
# 3. NO EM DASHES. They are the clearest machine tell in English prose, and this text is public.
#    The rule covers commit messages, and the same rule applies by hand to issue and PR comments,
#    release notes and docs.
set -euo pipefail
RANGE="${1:-origin/main..HEAD}"
FAIL=0
BRITISH='\b(colour|centre|behaviour|neighbour|metres?|labelled|travelled|licence|defence|grey|organis|recognis|utilis|memoise|apologise)\b'
MSGS="$(git log "$RANGE" --format='%H%n%B' 2>/dev/null || true)"
if [ -n "$MSGS" ]; then
  if grep -inE "co-authored-by:.*(claude|anthropic)|generated with \[?claude|🤖" <<<"$MSGS"; then
    echo "FAIL: a commit message carries AI attribution" >&2; FAIL=1
  fi
  if grep -n "—" <<<"$MSGS"; then
    echo "FAIL: a commit message contains an em dash" >&2; FAIL=1
  fi
  if grep -inE "$BRITISH" <<<"$MSGS"; then
    echo "FAIL: a commit message uses a British spelling" >&2; FAIL=1
  fi
fi
# Em dashes in what this change ADDS. Only added lines: the repo carries plenty of older ones and
# a whole-file test would fail every change that touches those files without preventing a thing.
# This script is excluded from its own scan: it has to contain the words it looks for.
ADDED="$(git diff "$RANGE" -U0 -- '*.md' '*.kt' '*.xml' '*.sh' '*.yml' ':(exclude)scripts/check-writing.sh' 2>/dev/null | grep '^+' | grep -v '^+++' || true)"
# The spelling check reads English text only: a translation is another language ("utilisé" is
# French, not a British "utilise"), and the en-GB file exists to be British. Em dashes are still
# checked in every file, translations included.
ADDED_EN="$(git diff "$RANGE" -U0 -- '*.md' '*.kt' '*.xml' '*.sh' '*.yml' ':(exclude)scripts/check-writing.sh' ':(exclude)app/src/main/res/values-*/*' 2>/dev/null | grep '^+' | grep -v '^+++' || true)"
# The keyword lists that must carry BOTH spellings, and the en-GB strings file, are the exceptions
# CLAUDE.md names; they are data, not prose, so a line that keeps both forms is left alone.
if grep -inE "$BRITISH" <<<"$ADDED_EN" | grep -viE "values-en-rGB|centre\"|centre'|fitness_centre|arts_centre|neighbourhood|cancelled\(\)|isCancelled" >/dev/null 2>&1; then
  echo "FAIL: this change adds a British spelling:" >&2
  grep -inE "$BRITISH" <<<"$ADDED_EN" | grep -viE "values-en-rGB|centre\"|centre'|fitness_centre|arts_centre|neighbourhood|cancelled\(\)|isCancelled" | head -5 >&2
  FAIL=1
fi
if grep -n "—" <<<"$ADDED" >/dev/null 2>&1; then
  echo "FAIL: this change adds an em dash:" >&2
  grep -n "—" <<<"$ADDED" | head -5 >&2
  FAIL=1
fi
[ "$FAIL" -eq 0 ] && echo "writing checks passed"
exit "$FAIL"
