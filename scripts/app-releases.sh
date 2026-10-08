#!/usr/bin/env bash
# Lists the app's releases (tags that start with v0.), newest first by run number, one a line:
#
#   <tag> TAB <true|false, is it a prerelease> TAB <created at>
#
# Asked through GraphQL, which returns these three fields and nothing else. The REST listing
# returns every asset of every release: among about 500 releases a page of 100 is 25 MB and
# takes 9 s, at the edge of GitHub's 10 s limit, and a promotion failed on HTTP 504. The whole
# list takes 2.5 s this way.
#
# Usage: bash scripts/app-releases.sh [owner/repo]   (default $GITHUB_REPOSITORY; needs GH_TOKEN)
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/gh-retry.sh
source "$here/gh-retry.sh"
repo="${1:-${GITHUB_REPOSITORY:?no repository given}}"
query='query($owner:String!,$name:String!,$endCursor:String){repository(owner:$owner,name:$name){releases(first:100,after:$endCursor,orderBy:{field:CREATED_AT,direction:DESC}){nodes{tagName isPrerelease isDraft createdAt} pageInfo{hasNextPage endCursor}}}}'
list() {
  gh api graphql --paginate -f owner="${repo%%/*}" -f name="${repo##*/}" -f query="$query" \
    --jq '.data.repository.releases.nodes[] | select(.isDraft | not) | select(.tagName | test("^v0\\.")) | "\(.tagName)\t\(.isPrerelease)\t\(.createdAt)"'
}
out="$(gh_retry list)"
# By run number, the tag's last dotted field. A tag without one sorts last, by creation time.
printf '%s\n' "$out" \
  | awk -F'\t' 'NF { n = $1; sub(/^.*[.]/, "", n); if (n !~ /^[0-9]+$/) n = 0; print n "\t" $0 }' \
  | sort -t"$(printf '\t')" -k1,1nr -k4,4r \
  | cut -f2-
