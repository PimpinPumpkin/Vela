# 3. Surveillance cameras

## What you see

Mapped license-plate readers (ALPR, of which Flock is the best known brand) draw on the map out
of the box. With **Settings > Navigation > Avoid surveillance cameras** on, each route in the
picker shows how many cameras it passes, and Vela will quietly pick a lower-camera route when the
detour is small. Two further opt-ins, both off by default, warn you while driving: a heads-up
card, and a spoken "License plate camera ahead".

Speed cameras are a separate dataset with the same warning machinery.

## Where the data comes from

The camera positions are OpenStreetMap data, largely surveyed by the community
[DeFlock](https://deflock.me) project, which maps ALPR installations and pushes them to OSM.
Vela ships a bundled snapshot in the APK so the layer works on first launch with no downloads,
and refreshes it from the `flock-cameras` release **weekly** (Mondays 08:17 UTC, see
[chapter 2](02-data-and-rebakes.md)). Speed cameras ride along in the per-region `road-features`
bake, monthly.

No request is made to any camera service while you drive. The dataset is on the phone.

## How it is decided

### What counts as a camera "on your route"

A camera tagged with a direction only counts when it faces along your travel axis:

```
CameraFacing.MAX_AXIS_DIFF_DEG = 50
```

The comparison is axis-based (a bearing and its reverse are the same axis), because a reader
pointed at the oncoming lanes of your road is still reading your road. A camera at a junction
you cross, pointed down the cross street, does not count. A camera with no direction tag counts,
since there is nothing to rule it out.

### The avoid rule

With avoid-cameras on, every route in the answer is counted, and then:

- the candidate is the route with the **fewest cameras**, ties broken by the faster one;
- it must beat the current fastest route on camera count;
- its extra time must be at most **25% of the fastest route's ETA, capped at 10 minutes**.

If it qualifies, that route leads the list and becomes the active one; the "Fastest" tag stays on
the genuinely fastest row, so the trade is legible rather than hidden. If nothing qualifies, the
fastest route stands and the counts are still shown, so the choice remains yours. A long-press
"route through here" is the manual override for the case where you know a road the router does
not.

The count badge is per route in the picker, and with the alternates pane open the route with the
fewest cameras is named, so a pick that is not the fastest explains itself.

### The warnings

Both warnings fire on the same timing, shared with the speed-camera warning:

```
CameraAlerts.LEAD_SECONDS   = 12       // aim to warn twelve seconds out
CameraAlerts.MIN_LEAD_M     = 150      // floor, so a slow road still gets a useful warning
CameraAlerts.MAX_LEAD_M     = 600      // cap, so a motorway warning is not forgotten before it arrives
CameraAlerts.MOVING_FLOOR_MPS = 2.0    // below this you are parked or crawling; say nothing
```

Lead distance is `speed * 12 s`, clamped to that 150 to 600 m band. Distance alone would be
wrong: 200 m is ample in town and about two seconds on a motorway.

The spoken warning follows the normal spoken-directions setting, so muting directions mutes it.
The card and the voice are independent toggles, and neither depends on the map layer being on.

## Limits

- **Coverage is what volunteers have mapped.** An unmapped camera is invisible to Vela, and a
  camera that has been removed stays until someone edits OSM. Contributing through DeFlock or OSM
  is the only way that improves.
- **Direction tags are optional.** An untagged camera counts on every pass, which over-counts
  rather than under-counts, deliberately.
- **Avoidance is route choice, not evasion.** Vela picks among the routes the router already
  offers; it will not invent a back-street detour, and the 25% / 10 minute cap means a heavily
  covered corridor often has no acceptable alternative.
- **Nothing here is legal advice** and nothing here defeats a camera you drive past. The feature
  tells you where they are and prefers a road with fewer of them.
