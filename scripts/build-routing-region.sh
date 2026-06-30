#!/usr/bin/env bash
# Build + publish ONE region's offline routing graph to the `routing-graphs` GitHub release, and
# merge it into routing-manifest.json. Runnable locally or from CI (.github/workflows/routing-graphs.yml).
#
#   scripts/build-routing-region.sh <id> "<Display name>" <geofabrik .osm.pbf URL>
#   e.g. scripts/build-routing-region.sh oregon "Oregon (state)" \
#          https://download.geofabrik.de/north-america/us/oregon-latest.osm.pbf
#
# Needs: gh (authenticated), osmium-tool, jq, zip, a JDK 17 (the graph builder). The graph is built
# with the SAME profile + Contraction Hierarchies the app's GraphHopperRouteEngine loads.
set -euo pipefail

ID="${1:?region id}"; NAME="${2:?display name}"; URL="${3:?geofabrik pbf url}"
REPO="${VELA_REPO:-PimpinPumpkin/Vela}"
TAG="routing-graphs"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

echo "→ downloading $URL"
curl -fsSL "$URL" -o "$WORK/region.osm.pbf"

echo "→ building CH graph"
( cd "$ROOT" && ./gradlew :tools:graphbuilder:run --args="$WORK/region.osm.pbf $WORK/graph" --no-daemon -q )

( cd "$WORK/graph" && zip -qr "$WORK/$ID.zip" . )
SIZE=$(( ( $(stat -f%z "$WORK/$ID.zip" 2>/dev/null || stat -c%s "$WORK/$ID.zip") + 1048575 ) / 1048576 ))

# bbox [S,W,N,E] from the extract's data extent: osmium prints (minlon,minlat,maxlon,maxlat)
read -r MINLON MINLAT MAXLON MAXLAT < <(osmium fileinfo -e -g data.bbox "$WORK/region.osm.pbf" | tr -d '()' | tr ',' ' ')
BBOX="[$MINLAT,$MINLON,$MAXLAT,$MAXLON]"
ASSET_URL="https://github.com/$REPO/releases/download/$TAG/$ID.zip"
echo "→ $ID: ${SIZE} MB, bbox $BBOX"

# ensure the catalog release exists (prerelease so it never becomes the "Latest" the APK tracks)
gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1 || \
  gh release create "$TAG" --repo "$REPO" --prerelease --title "Offline routing graphs" \
    --notes "Prebuilt GraphHopper CH graphs for Vela offline routing. Data assets, not a code release."

gh release upload "$TAG" "$WORK/$ID.zip" --clobber --repo "$REPO"

# merge this region into routing-manifest.json (replace any existing entry with the same id)
gh release download "$TAG" --repo "$REPO" -p routing-manifest.json -O "$WORK/manifest.json" 2>/dev/null \
  || echo '{"regions":[]}' > "$WORK/manifest.json"
jq --arg id "$ID" --arg name "$NAME" --arg url "$ASSET_URL" --argjson size "$SIZE" --argjson bbox "$BBOX" \
  '.regions = ([.regions[] | select(.id != $id)] + [{id:$id,name:$name,url:$url,sizeMb:$size,bbox:$bbox}])' \
  "$WORK/manifest.json" > "$WORK/routing-manifest.json"
gh release upload "$TAG" "$WORK/routing-manifest.json" --clobber --repo "$REPO"

echo "✓ published $ID — the app's Settings → Offline routing will list it"
