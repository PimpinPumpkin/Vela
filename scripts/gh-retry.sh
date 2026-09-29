# Source this: gh_retry CMD... runs CMD (a command or a shell function) and retries it with a
# growing wait (60 s doubling to 15 min, GH_RETRY_TRIES attempts, default 6) when it fails. The
# Actions token has 1,000 GitHub API requests an hour for the WHOLE repository, and a long data
# bake can spend them all: on 2026-09-29 the canary release and the F-Droid index both failed with
# HTTP 403 while the world cells bake was uploading. On success the command's stdout is printed
# (captured first, so a pipe after gh_retry cannot cut the command short); stderr goes to stderr.
gh_retry() {
  local try=1 wait=60 out err
  err="$(mktemp)"
  while :; do
    if out="$("$@" 2>"$err")"; then
      cat "$err" >&2; rm -f "$err"
      [ -n "$out" ] && printf '%s\n' "$out"
      return 0
    fi
    tail -3 "$err" >&2
    if [ "$try" -ge "${GH_RETRY_TRIES:-6}" ]; then rm -f "$err"; return 1; fi
    echo "attempt $try of '$1' failed; retrying in ${wait}s" >&2
    sleep "$wait"
    try=$((try + 1)); wait=$((wait * 2)); [ "$wait" -gt 900 ] && wait=900
  done
}
