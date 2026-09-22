#!/usr/bin/env bash
# The user-facing changelog: one "- <subject>" line per commit in a range, SKIPPING commits that
# change nothing a user can see (user 2026-09-22: "repo documentation updates don't need to go in
# the Vela version notes in the app"). The in-app What's new and update dialogs show these lines
# verbatim, so a docs sweep read as a feature. A commit is skipped when:
#   - every file it touches is documentation (*.md, docs/, site/, fdroid/metadata/, LICENSE,
#     .github/ISSUE_TEMPLATE/), or
#   - every line it adds or removes in any other file is a comment or blank (a comment sweep), or
#   - its subject starts with "Docs:".
#
#   scripts/changelog.sh <rev-range>          e.g. v0.4.1200..HEAD
#   scripts/changelog.sh -n <count> <rev>     the last <count> user-facing commits up to <rev>
#   scripts/changelog.sh --latest <rev>       only the newest user-facing subject, no "- "
set -euo pipefail
MODE=range; COUNT=0
if [ "${1:-}" = "-n" ]; then MODE=count; COUNT="$2"; shift 2; fi
if [ "${1:-}" = "--latest" ]; then MODE=latest; COUNT=1; shift; fi
REV="${1:?usage: changelog.sh [-n N | --latest] <rev-or-range>}"

is_doc_path() {
  case "$1" in
    *.md|docs/*|site/*|fdroid/metadata/*|LICENSE|.github/ISSUE_TEMPLATE/*) return 0 ;;
    *) return 1 ;;
  esac
}

user_facing() {
  local sha="$1" f code=0
  git log -1 --format=%s "$sha" | grep -q '^Docs:' && return 1
  while IFS= read -r f; do
    [ -z "$f" ] && continue
    is_doc_path "$f" || { code=1; break; }
  done < <(git show --format= --name-only "$sha")
  [ "$code" = 0 ] && return 1
  # Comment-only when, after dropping comment and blank lines and trailing "//" comments, the
  # removed and added code lines are the same multiset (an edited trailing comment leaves the code
  # before it unchanged). Continuation lines of an XML comment end with "-->".
  git show --format= -U0 "$sha" -- . ':(exclude)*.md' ':(exclude)docs' ':(exclude)site' | python3 -c '
import re, sys
from collections import Counter
plus, minus = Counter(), Counter()
for line in sys.stdin:
    if line.startswith(("+++ ", "--- ")) or not line.startswith(("+", "-")):
        continue
    body = line[1:].strip()
    if not body or re.match(r"(//|/\*|\*|#|<!--)", body) or body.endswith("-->"):
        continue
    code = re.sub(r"\s+//.*$", "", body).strip()
    (plus if line[0] == "+" else minus)[code] += 1
sys.exit(1 if plus == minus else 0)
'
}

n=0
while IFS= read -r sha; do
  if user_facing "$sha"; then
    subj="$(git log -1 --format=%s "$sha")"
    if [ "$MODE" = latest ]; then echo "$subj"; exit 0; fi
    echo "- $subj"
    n=$((n + 1))
    [ "$MODE" = count ] && [ "$n" -ge "$COUNT" ] && break
  fi
done < <(git rev-list --no-merges "$REV")
exit 0
