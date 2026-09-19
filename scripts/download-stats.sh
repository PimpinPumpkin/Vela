#!/usr/bin/env bash
# Snapshot what GitHub will tell us about reach, before the prune eats it.
#
#   scripts/download-stats.sh [out-dir]        (default: docs/stats)
#
# There is no such thing as a unique downloader in GitHub's API - release assets carry a counter
# and nothing else. What there IS, and what disappears for good if nobody writes it down:
#
#   * a release's download counter, which the nightly prune deletes along with the release. The
#     in-app updater and Obtainium each pull an APK once per device per release, so the newest
#     stable's counter is the closest thing to a floor on active installs.
#   * the traffic API, which is a ROLLING 14 DAYS. Miss a fortnight and that fortnight is gone.
#   * which REGIONS people download, per asset, across the data catalogs. Aggregate, anonymous,
#     and the only signal there is about where Vela is actually used - it decides which bakes are
#     worth the runner time.
#
# Nothing here is telemetry: every number is a byproduct of hosting files, GitHub counts it whether
# we look or not, and none of it is attributable to a person.
#
# TWO TRAPS in reading the output, both of which make a number look smaller than the truth:
#   * REPLACING AN ASSET RESETS ITS COUNTER. Every re-bake does that, so a data release's figure is
#     "since this file was last rebuilt", never a total. The nightly places bake resets a seventh of
#     that catalog every night; the canary APK resets on every push, which is why canary is skipped
#     entirely rather than recorded as a suspiciously small number.
#   * The F-Droid channel is served from GitHub Pages, which publishes no counters at all. Those
#     installs are invisible here and always will be.
set -euo pipefail

REPO="${REPO:-PimpinPumpkin/Vela}"
OUT="${1:-docs/stats}"
DAY="$(date -u +%Y-%m-%d)"
mkdir -p "$OUT"

api() { gh api -H "Accept: application/vnd.github+json" "$@"; }

# Every release, paginated - the catalog releases push this past 400 and an unpaginated list
# silently truncates (it has cost this project a wrong damage report before).
api --paginate "repos/$REPO/releases?per_page=100" \
  -q '.[] | {tag: .tag_name, pre: .prerelease, at: .published_at,
             assets: [.assets[] | {name: .name, dl: .download_count}]} | @json' > /tmp/vela-rel.jsonl

python3 - "$OUT" "$DAY" <<'PY'
import csv, json, os, sys

out, day = sys.argv[1], sys.argv[2]
rows = [json.loads(l) for l in open('/tmp/vela-rel.jsonl')]

def append(path, header, lines):
    """Append today's rows, replacing any this day already wrote - a re-run (a retry, a manual
    dispatch beside the cron) must not double-count the day."""
    kept = []
    if os.path.exists(path):
        with open(path, newline='') as f:
            rows = list(csv.reader(f))
        kept = [r for r in rows[1:] if r and r[0] != day]
    with open(path, 'w', newline='') as f:
        w = csv.writer(f)
        w.writerow(header)
        w.writerows(kept)
        w.writerows(lines)

# 1. APP releases, one row per asset. `v0.*` is the app line; everything else is file hosting.
app = []
for r in rows:
    if not r['tag'].startswith('v0.'):
        continue
    for a in r['assets']:
        app.append([day, r['tag'], 'prerelease' if r['pre'] else 'stable',
                    r['at'][:10], a['name'], a['dl']])
app.sort(key=lambda x: x[1])
append(os.path.join(out, 'downloads.csv'),
       ['snapshot', 'tag', 'channel', 'published', 'asset', 'downloads'], app)

# 2. DATA releases, one row each. Per asset would be 2,000 rows a week; the total is the trend.
# The overlays that MapLibre range-reads (places, buildings, addresses, maxspeed) count a read as
# a download, so their totals measure map panning, not installs - marked so nobody reads them as
# people.
STREAMED = {'places-overlays', 'building-overlays', 'address-overlays', 'maxspeed-overlays'}
# Fetched by CI at build time, never by a phone. Counting these as reach would be counting our
# own runners.
CI = {'tts-runtime', 'obf-runtime', 'obf-tools'}
data = []
for r in rows:
    if r['tag'].startswith('v0.') or r['tag'] == 'canary':
        continue
    total = sum(a['dl'] for a in r['assets'])
    kind = 'ci' if r['tag'] in CI else 'streamed' if r['tag'] in STREAMED else 'downloaded'
    data.append([day, r['tag'], len(r['assets']), total, kind])
data.sort(key=lambda x: x[1])
append(os.path.join(out, 'data-releases.csv'),
       ['snapshot', 'tag', 'assets', 'downloads', 'kind'], data)

# 3. WHERE, current picture only (overwritten). Per-region counters for the catalogs a phone
# downloads WHOLE, so the numbers mean installs rather than tile reads.
WHERE = ('obf-regions', 'poi-packs', 'basemap-tiles', 'road-features')
where = []
for r in rows:
    if r['tag'] not in WHERE:
        continue
    for a in r['assets']:
        if a['dl'] > 0 and not a['name'].endswith('.json'):
            where.append([r['tag'], a['name'], a['dl']])
where.sort(key=lambda x: -x[2])
with open(os.path.join(out, 'regions.csv'), 'w', newline='') as f:
    w = csv.writer(f)
    w.writerow(['snapshot', 'tag', 'asset', 'downloads'])
    for row in where:
        w.writerow([day] + row)
print(f"app rows {len(app)}, data rows {len(data)}, region rows {len(where)}")
PY

# 4. Traffic and the repo counters. The traffic endpoints need push access and answer nothing
# useful without it, so a failure here is recorded as blank rather than failing the snapshot.
views=$(api "repos/$REPO/traffic/views" -q '"\(.count),\(.uniques)"' 2>/dev/null || echo ",")
clones=$(api "repos/$REPO/traffic/clones" -q '"\(.count),\(.uniques)"' 2>/dev/null || echo ",")
repo=$(api "repos/$REPO" -q '"\(.stargazers_count),\(.forks_count),\(.open_issues_count)"')
TRAFFIC="$OUT/traffic.csv"
[ -f "$TRAFFIC" ] || echo "snapshot,views,unique_views,clones,unique_clones,stars,forks,open_issues" > "$TRAFFIC"
grep -v "^$DAY," "$TRAFFIC" > "$TRAFFIC.tmp" && mv "$TRAFFIC.tmp" "$TRAFFIC"
echo "$DAY,$views,$clones,$repo" >> "$TRAFFIC"

# 5. Where the visitors came from, also a rolling 14 days.
REFS="$OUT/referrers.csv"
[ -f "$REFS" ] || echo "snapshot,referrer,views,unique_views" > "$REFS"
grep -v "^$DAY," "$REFS" > "$REFS.tmp" && mv "$REFS.tmp" "$REFS"
api "repos/$REPO/traffic/popular/referrers" \
  -q ".[] | \"$DAY,\(.referrer),\(.count),\(.uniques)\"" 2>/dev/null >> "$REFS" || true

echo "wrote $OUT"
