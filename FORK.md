# Fork: on-device routing via the sorting-barrier SSSP algorithm

Upstream: https://github.com/PimpinPumpkin/Vela. This fork: https://github.com/catalinb13/Vela, branch `feat/sorting-barrier-router`.

## What this fork does

Vela's on-device routing already worked. This fork adds a second offline engine whose core
shortest-path step is the 2025 breakthrough algorithm from:

> Ran Duan, Hongyuan Mao, Chen Mao, Yixin Shu, Shubham Yin.
> **Breaking the Sorting Barrier for Directed Single-Source Shortest Paths.**
> arXiv:2504.17033 (STOC 2025; best-paper award).

It is the first comparison-based algorithm for directed single-source shortest paths with
non-negative weights that beats `O(m log n)`: it runs in `O(m log^(2/3) n)` expected time
(a worst-case variant exists at `O(m log n / log^(1/4) n)`). The algorithm's core idea: a
priority queue that extracts *batches* of elements below a selected pivot instead of one minimum
per `log n` comparison, recursively, so the total comparison count undercuts the sorting
lower bound.

## What runs, where

| File | Role |
|---|---|
| `core/src/main/java/app/vela/core/routing/Bmssp.kt` | Algorithms 1-3 of the paper, ported from the authors' MIT reference `bmssp-expected.hpp` (github.com/lcs147/bmssp) |
| `core/src/main/java/app/vela/core/routing/BatchPQ.kt` | the batched priority queue (D0/D1 block lists, intrusive entry lists, quickselect, reference-faithful `pull`/`split`/`batchPrepend`; entries held in flat epoch-stamped primitive arrays instead of per-entry objects) |
| `core/src/main/java/app/vela/core/routing/SsspGraph.kt` | directed millisecond-weighted graph, the distinct-distance key `UDist` (paper Assumption 2.1), and the Dijkstra baseline used as correctness oracle |
| `core/src/main/java/app/vela/core/routing/RoadGraphBuilder.kt` | OsmAnd-style polylines welded into the graph by exact coordinate snapping, per-direction ms weights from OSM maxspeed, oneway handling |
| `core/src/main/java/app/vela/core/data/ObfBmsspRouteEngine.kt` | the new `RouteEngine`: builds the road graph from `.obf` regions (the same files the shipping engine reads), snaps origin/destination (500 m, never onto a lone node), runs BMSSP, converts the path to a Vela `Route` (polyline + DEPART/ARRIVE) |
| `core/src/main/java/app/vela/core/data/SortingBarrierEngine.kt` | calibration-gated composite: BMSSP first for plain drives, shipping `ObfRouteEngine` for everything else and for every BMSSP refusal |
| `core/src/test/java/app/vela/core/routing/SsspCorrectnessTest.kt` | BMSSP vs Dijkstra on 30 seeded random digraphs, both transform modes, path reconstruction checked edge-by-edge |
| `core/src/test/java/app/vela/core/routing/RoadGraphBuilderTest.kt` | welding, near-miss non-welding, oneway, directional weights, degenerate polylines |
| `core/src/test/java/app/vela/core/routing/SsspBenchmarkTest.kt` | opt-in (`-DvelaBench=true`) synthetic head-to-head |
| `core/src/test/java/app/vela/core/data/ObfBmsspProbeTest.kt` | real-road probe over the fork's committed `delaware.obf` test region (`-DvelaObf=<dir>`), including the head-to-head probe line |

The engine is opt-in: the `sortingBarrierRouter` calibration flag (default **false**) makes the
composite `RouteEngine` in `di/CoreModule.kt` prefer the BMSSP engine for driving requests;
everything else (walking, cycling, transit, tolls/highways/ferries avoidance, any BMSSP refusal
or unreachable answer) keeps the existing engine, which remains the default everywhere.

## Honest performance

`O(m log^(2/3) n)` is an asymptotic statement. The authors' own implementation study
(arXiv:2511.03007, "Implementation and Brief Experimental Analysis of the Duan et al. (2025)
Algorithm") measures this exact reference algorithm **slower than a tuned binary-heap Dijkstra
by roughly 7-9x in wall-clock** at sizes up to millions of vertices; their fitted crossovers
against heap Dijkstra sit far beyond any phone workload (n > 10^8 at a constant ratio of 3;
n > 10^65 at a constant ratio of 6).

This fork's own measurements on an M1 Max (JVM 27, Kotlin 2.4.20, integer ms weights,
constant-degree transform on, single-threaded): BMSSP vs the Dijkstra baseline **on the same
graph**:

| Workload | nodes | Dijkstra | BMSSP | ratio |
|---|---:|---:|---:|---:|
| random sparse digraph | 4,096 | 0 ms | 8 ms | 8.0x |
| random sparse digraph | 16,384 | 4 ms | 42 ms | 10.5x |
| random sparse digraph | 65,536 | 18 ms | 253 ms | 14.1x |
| road lattice (grid streets) | 8,100 | 1 ms | 20 ms | 20.0x |
| road lattice | 40,000 | 6 ms | 118 ms | 19.7x |
| road lattice | 102,400 | 18 ms | 328 ms | 18.2x |
| **real roads**: Delaware `.obf`, Wilmington → Rehoboth Beach (145.8 km route, 99 min) | 745,495 | 142 ms | 1,631 ms | 11.5x |

(The real-road row is the head-to-head line the probe itself prints: the Dijkstra baseline and a
fresh BMSSP prepare + execute (transform build included) on the same welded graph. On-device
reroutes inside one coverage box reuse the cached prepared transform and pay only the search.
Every row ran inside Gradle's default 512 MB test heap after the BatchPQ rewrite. Distances from
both algorithms match bit-exactly on every run; `agree=true` is asserted in the probe.)

So the fork reproduces the published result on real roads: **correct, and roughly an order of
magnitude slower than heap Dijkstra at phone-workload sizes** (8-20x on the synthetic workloads,
about 11x on the Delaware road network).

**Do not enable `sortingBarrierRouter` expecting faster navigation.** It exists because a
road-network router is exactly the workload the paper is about, and because a working
implementation is the only way the community can see where the constants actually land.
Dijkstra stays the default.

On-device policy: a graph build or search failure refuses to the shipped engine instead of
crashing (the app already runs with `largeHeap`, i.e. a 512 MB heap on this class of phone).
After the BatchPQ rewrite the whole-state Delaware search fits inside that heap: the Delaware
probe passes end-to-end in the default 512 MB test JVM with no heap bump, both through the
engine's `route()` and through the head-to-head harness. Whole-state coverage boxes therefore
run BMSSP on the phone as well, and refusal stays policy only for graphs that genuinely exceed
the heap. The engine also drops the previous cached prepared transform before building a new
coverage box, so two whole-state prepared graphs never coexist in memory.

## Measuring it yourself

With the repo toolchain (needs the Android SDK like everything else here):

```
./gradlew :core:test --tests "app.vela.core.routing.*"                          # correctness
./gradlew :core:test --tests "app.vela.core.routing.SsspBenchmarkTest" -DvelaBench=true
./gradlew :core:test --tests "app.vela.core.data.ObfBmsspProbeTest" -DvelaObf=<dir with delaware.obf + index.json>
```

Without an Android SDK, the pure-Kotlin core compiles on a plain JVM: `kotlinc` the
`routing/` package plus `data/{RouteEngine,OfflinePhrases,ObfBmsspRouteEngine,SortingBarrierEngine}.kt`,
`i18n/NavStrings.kt`, `voice/SpeechText.kt`, `model/{Geo,Route}.kt` and the test files against
`core/libs/*.jar` + junit (stubbing `nav/SpokenRoadNames`), then run
`java -DvelaBench=true ... org.junit.runner.JUnitCore app.vela.core.routing.SsspCorrectnessTest ...`.

The tables above come from a clean run of the gradle commands on an otherwise idle machine
(`agree=true`, distances bit-exact every time); expect a few points of ratio drift run to run
under machine load.

## Deviations from the C++ reference (all documented in code comments)

1. Integer millisecond weights instead of sanitized doubles (exactness; road times are ms-granular anyway).
2. `int` (not `short`) for tree sizes and level bookkeeping (16 bits overflow at city scale).
3. Quickselect instead of Floyd-Rivest (both expected-linear selection).
4. Block-id tie-break in the UB index instead of node-address tie-break (both total orders).
5. `k`/`t` clamped to >= 1 for degenerate tiny graphs (the reference divides by zero at n < 8).
6. `execute()` special-cases a source with no out-edges: the reference's constant-degree transform
   maps every zero-out-degree vertex (including such a source) onto one shared sentinel, and
   anchoring BMSSP at that sentinel hands distance 0 to every isolated vertex. The fork returns
   the trivially correct answer there (only the source is reachable, at 0).

7. De-duplication and the constant-degree transform store the graph in flat CSR arrays, and
   number custom nodes by sorted undirected pair (2q / 2q+1) instead of the reference's
   per-vertex `std::map` first-encounter ids. This is an isomorphic relabeling: cycle
   membership still follows the reference's symmetric map entries (a pair's two edge-nodes
   join the zero cycles of the two endpoints), cycles still walk neighbors by ascending
   target id, and distances and predecessor trees are identical. The flat form uses roughly
   a tenth of the memory: the map/ArrayList form OOMed a 512 MB heap building the Delaware
   transform; the CSR form builds it inside that heap.

8. `BatchPQ` stores its entries in flat per-node arrays instead of the reference's per-vertex
   hash tables: each level queue keeps parallel primitive lanes indexed by transformed node id
   (the key unpacked, the intrusive links as node indices, the blocks in an int pool) with one
   generation stamp carrying queued-ness and the D0/D1 side. Lookups stay O(1) by node id and
   the block/UB/split semantics are unchanged. The reference preallocates its hash arrays; this
   port sizes its lanes once per prepared graph (only for levels actually used) and resets by
   generation, so a reset costs no scan. The boxed-HashMap shape OOMed a 512 MB heap on the
   whole-state Delaware search; the flat shape fits inside that heap.

## What is and is not verified

- Verified by execution: all routing/builder/probe tests above on the JVM, including the
  Delaware end-to-end route (145.8 km / 99 min / 1854 points) and the head-to-head timings.
- Verified with a local Android SDK install (cmdline-tools, platform-tools, platforms;android-36,
  build-tools 36.0.0, JDK 17 for the `:osmand-shaded` toolchain): `./gradlew :core:test
  --no-daemon` green (934 tests, 22 skipped, 0 failures; the two gated new classes self-skip),
  `./gradlew :app:assembleDebug` builds the APK (first full Android compile of the
  `MapViewModel` call sites, `CoreModule`, `Calibration` and `CalibrationStore`), and
  `./gradlew :core:testDebugUnitTest --tests '*ObfBmsspProbeTest' --tests '*SsspBenchmarkTest'
  -DvelaObf=<dir> -DvelaBench=true` runs both green.
- The whole-state probe runs inside Gradle's default 512 MB test heap: the `velaObf` forwarder no
  longer raises the fork heap. The raise existed for the pre-rewrite batched priority queue and
  was measured as a removal experiment (build passed, search OOMed); after the queue rewrite the
  build and the whole-state search both pass with no raise.
- Tested on a device: the flag-on release build installs and runs on a Pixel 3 XL (Android 15,
  map screen, location fix). CI still owns `:app:assembleRelease` as the authoritative build,
  and the maintainer owns on-device review per CONTRIBUTING.
- Interface change in this fork: `RouteEngine.shutdown()` is a default no-op. `ObfRouteEngine`
  overrides it (it was a plain class method before). The six call sites in `MapViewModel` that
  used to write `(routeEngine as? ObfRouteEngine)?.shutdown()` now call `routeEngine.shutdown()`,
  because those casts would silently stop firing once DI injects the composite engine (region
  install/delete, storage move, cell change all rely on it to drop cached readers).

## License note

`Bmssp.kt`/`BatchPQ.kt` are derivative works of the MIT-licensed reference implementation and
carry attribution in their headers. Vela is GPL-3.0; an MIT-licensed derivative is compatible
with GPL-3.0 distribution, and the attribution headers carry the MIT notice through.
