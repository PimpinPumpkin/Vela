# Puck jitter: what it actually was

Issue #251 was open from July to September 2026 and was re-diagnosed from scratch at least
five times. Each pass found a real cause, fixed it, and the report came back. This is the
record of what was wrong, in the order it was found, with the measurement that found it, so
that nobody has to derive it a sixth time.

The one-line version: **there were eight independent causes, and the last one, the one a
driver's eye was actually on, was not physics at all.** The puck was a map symbol whose
per-frame update could land one frame after the camera move, so the arrow vibrated by one
frame of travel against a calm map.

## The rule that came out of it

A jitter report gets **measured before anything is touched**, in this order:

1. `scripts/jitter/puck_track.py` on a screen recording: does the ARROW move on screen?
2. `scripts/jitter/map_motion.py` and `rotation_fit.py`: does the MAP move unevenly, or turn
   unevenly through a bend?
3. `scripts/jitter/gl_frames.py` on a Perfetto trace: are map renders over budget, and are
   the over-budget ones evenly spaced (a periodic culprit) or random (the device's floor)?
4. Settings > Diagnostics: is Compatibility rendering on? It should not be on a healthy phone.

A demo drive (Settings > Navigation > demo mode) has zero GPS noise. If it jitters, the
cause is not GPS and no filter will fix it.

## The eight causes

| # | What | How it showed | Found by | Fix |
|---|------|---------------|----------|-----|
| 1 | The along-route POSITION was never filtered; the snapped fix went straight into the drawn progress | every metre of GPS noise was a metre the arrow travelled, once a second | replaying real trips | `AlongRouteFilter`, a 1-D Kalman on metres-along (#305) |
| 2 | The progress rule stalled and surged: ease-toward-target plus a monotonic clamp fought each other at the fix cadence | stalled frames then lurches | replay + frame log | rate-domain correction, bounded catch-up (#305) |
| 3 | The camera bearing followed digitization wiggle | the map swung under a steady arrow | screen-space band analysis | camera bearing eases with a steady/turn time constant |
| 4 | The smoothing window's own width rippled with the Kalman speed | sideways drift on curves | analysis | eased window width |
| 5 | Demo drives ran the puck clocks at 3x | stalls on 54% of frames in demo mode | frame log | gate the replay speedup on real replays only |
| 6 | The UI thread stalled 60 ms once per fix: `SpokenScript.forDisplay` sorted the whole road-name dictionary twice per fix for English text that could never match, and `NavEngine.update` re-projected every maneuver over the route per fix | one 65 to 84 ms frame every 1.02 s, then a 3.5x catch-up step | screen recording timestamps + Perfetto (`Compose:recompose` 50 to 74 ms at 1 Hz) + trace markers bisecting the arguments of `ManeuverBanner` | fast path + digest cache; per-route geometry cache (#314) |
| 7 | The route line's driven/ahead split re-uploaded a 3 km LineString every 150 ms | over-budget map renders spaced 133 to 167 ms, 46 in 8 s: a 6.7 Hz vibration of the whole map | `gl_frames.py`, then an A/B build with the throttle at 2 s (spacing went random, count 18 to 22) | the moving cut is a `line-gradient` PAINT update on a short 400 m piece; geometry slides every ~300 m (#316) |
| 8 | **The puck was a GeoJSON symbol.** `setMeSource` goes through MapLibre's async worker tiling; `moveCamera` is synchronous. The symbol landed on time or one frame late at random | the white chevron moved 1 to 2 px on 95% of frames on a dead-straight highway with the map calm, in a saw-tooth | `puck_track.py` | in follow mode the puck is a Compose overlay at the projection of the same point through the camera state just set (#318) |

And one that was not code: the test phone had been silently switched into **Compatibility
rendering** (a TextureView map) by the crash sentinel, which counted any process death during
map init, including force-stops during testing. The renderer sat at 89% of a core and every road
juddered. The sentinel now counts only native crashes and says on the settings row when it
engaged (#317).

## After the fix: the puck was too sticky

Once the vibration was gone, the next report was the opposite: the arrow followed the drawn
road more faithfully than the car, running straight for a moment after a corner the driver had
cut. From the shared trip: the along-route measurement jumps about 30 m in one fix at such a
corner, and the catch-up cap of 0.5x speed + 1 m/s needed about 7 s to drain that at city speed.
The Kalman gain was not the limiter; the cap was. It is now 1.5x speed + 2 m/s, which drains the
jump in about 2.5 s with no measurable change to ordinary-driving smoothness.

## Things that were tried and are worse

- A per-frame **LineString** source for the moving route cut. A line re-tiles on every worker
  thread on every update; only a point source is cheap per frame. Worker CPU went from 0.6 s to
  1.4 s per thread per 8 s.
- A whole-route **line-gradient** for the cut. 256 texels over the route smears the cut into a
  routeLength/256 metre ramp.
- Smoothing the **route geometry** for the arrow. Measured on the reporter's own roads: the
  camera's turn rate through bends was already smooth (0.02 degrees per frame of jerk) and the
  modelled arrow sat within a fraction of a pixel of the camera. The kinks were not what the eye
  saw; cause 8 was.

## What it cost and saved

Total app CPU during a demo drive on a Pixel 4a, from Perfetto, same road: 173% of a core in
the morning (compatibility rendering plus causes 6, 7, 8), 122% after. Main thread per fix:
about 80 ms to about 1 ms.

## Where the code is

- `core/location/AlongRouteFilter.kt` (1, 2), `core/voice/SpokenScript.kt` and
  `core/nav/NavEngine.kt` (6), `app/ui/map/VelaMapView.kt` (3, 4, 5, 7, 8 and the sentinel).
- The longer notes, with the numbers, live in `CLAUDE.md` under the puck-jitter heading.
