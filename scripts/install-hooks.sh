#!/usr/bin/env bash
# Install the git hooks that enforce the repo's writing rules locally, so a slip is caught BEFORE
# it is public rather than by CI afterwards.
#
#   bash scripts/install-hooks.sh
#
# Why this exists: the rules are checked in CI, which is the backstop, but a commit message cannot
# be amended once it is pushed. Twice on 2026-09-18 a British spelling reached a public commit
# message because the check was run by hand against the wrong range before committing. A hook runs
# it against the right range, every time, with no one deciding to. The hook also runs the LOCATION
# check against a private term list kept outside the repo, which is the one that matters most: a
# commit message or a comment naming a street near the maintainer cannot be taken back once pushed.
set -euo pipefail
HOOKS="$(git rev-parse --git-common-dir)/hooks"
mkdir -p "$HOOKS"
cat > "$HOOKS/pre-push" <<'HOOK'
#!/usr/bin/env bash
# Vela: no AI attribution, US English, no em dashes. Installed by scripts/install-hooks.sh.
set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"
[ -x "$ROOT/scripts/check-writing.sh" ] || exit 0
FAIL=0
while read -r _local_ref local_sha _remote_ref remote_sha; do
  [ "$local_sha" = "0000000000000000000000000000000000000000" ] && continue
  if [ "$remote_sha" = "0000000000000000000000000000000000000000" ] || ! git cat-file -e "$remote_sha^{commit}" 2>/dev/null; then
    RANGE="$local_sha~1..$local_sha"   # a new branch: check the tip rather than all of history
  else
    RANGE="$remote_sha..$local_sha"
  fi
  bash "$ROOT/scripts/check-writing.sh" "$RANGE" || FAIL=1
  [ -x "$ROOT/scripts/check-location.sh" ] && { bash "$ROOT/scripts/check-location.sh" "$RANGE" || FAIL=1; }
done
if [ "$FAIL" -ne 0 ]; then
  echo "push blocked by the writing rules. A commit message cannot be fixed after it is pushed:" >&2
  echo "  git commit --amend    (or git rebase -i) and try again." >&2
  exit 1
fi
HOOK
chmod +x "$HOOKS/pre-push"
echo "installed $HOOKS/pre-push"
