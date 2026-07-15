# graphbuilder

Builds a per-region GraphHopper routing graph for Vela's **offline routing** - the off-device half
of the on-device engine (`core/.../GraphHopperRouteEngine`). Standalone JVM tool; **not** an app
dependency (it pulls GraphHopper's heavy OSM-import deps, which never ship in the APK).

## Why it exists

The app can't import OSM `.pbf` on-device (the import deps are Android-hostile, and import is heavy).
So graphs are built **here**, off-device, and the app just downloads + loads the result. The build
config is kept byte-for-byte compatible with the engine that loads it:

- encoded values `car_access, car_average_speed, road_access, max_speed`, profile `car`
  (byte-identical to `GraphHopperRouteEngine`'s string - a mismatch fails graph load; `max_speed`
  is a passive stored column for the speed-limit badge, not part of the weighting/CH);
- a **Janino-free `SpeedWeighting` + access block** (ART can't run GraphHopper's Janino-compiled
  custom-model weighting);
- **Contraction Hierarchies prepared on that same weighting** - mandatory: CH bakes the build-time
  weighting into its shortcuts, and it's what makes on-device routing ~tens of ms instead of the
  ~7 s a flexible A* took on a Pixel 5a (measured: a 21-mi metro route went 7639 ms → **188 ms**).

## Use

```bash
# 1. crop a region from a state/country extract (Geofabrik) - metro-sized, NOT whole states
#    (a state graph is huge + I/O-bound on-device; a metro is ~50 MB and routes instantly)
osmium extract -b <W,S,E,N> washington-latest.osm.pbf -o sacramento.osm.pbf

# 2. build the CH graph (run from the repo root)
./gradlew :tools:graphbuilder:run --args="sacramento.osm.pbf sacramento-graph"

# 3. zip the output folder -> ship as a release asset; the app downloads + unzips it to internal storage
(cd sacramento-graph && zip -qr ../sacramento-graph.zip .)
```

Sizing (measured): a mid-size metro = 96 MB `.pbf` → ~53 MB CH graph (~21 MB zipped). Load on the
phone ≈ 170 ms from internal storage.

## Shipped pipeline

CI builds these at scale: `.github/workflows/routing-graphs.yml` fans a matrix over
`tools/routing-regions.json` (135 regions), uploads each graph to the `routing-graphs` release, and
merges a `routing-manifest.json`. The app downloads + loads per-region graphs via
`app/offline/RoutingGraphStore.kt` (into `filesDir/graphs/<id>/`). This README covers building one
region by hand; the automation mirrors `scripts/build-routing-region.sh`.
