#!/usr/bin/env bash
# Usage: scripts/app-changed.sh <from> <to>
# Exits 0 when a file that can reach the app changed between the two commits, and 1 when every
# change is docs, the website or repository metadata (or nothing changed). The cron nightly uses
# it so a stats or docs commit on main cuts no release. The list is ci.yml's paths-ignore; keep
# the two in step.
set -euo pipefail
changed="$(git diff --name-only "$1" "$2")"
[ -n "$changed" ] || exit 1
grep -qvE '\.md$|^docs/|^LICENSE$|^\.gitignore$|^fdroid/metadata/|^\.github/ISSUE_TEMPLATE/|^site/' <<<"$changed"
