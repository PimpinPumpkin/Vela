# Settings and page motion

## What you see

Settings opens over the map. A category opens its own page; Back returns to the category list,
then the map. On supported Android devices, swiping back previews the previous page. Releasing
commits the move; canceling keeps the current page. Settings > Appearance > Page transitions
turns the animations off without changing the back actions. The preference defaults to on.

## Where the data comes from

The motion preference is stored locally as `page_transitions` in `vela_settings`. No network
request is involved. Settings search includes the new row and opens Appearance at that row.

## How it is decided

Navigation Compose owns the back stack and gesture progress. The map uses `main/map`; Settings
uses `settings/<section>`. Distinct paths prevent case-insensitive matching from opening Map
settings on startup. Pages slide over 250 ms with
quarter-width outgoing motion; back reverses it, and RTL mirrors the direction. The map stays
composed under a transparent destination so its camera is retained. Every page receives the
same root map view model. The voice-library shortcut stacks the hub before Voice, and opening
Voice from Offline replaces Offline so Back still reaches the hub. Search highlights belong to
the destination entry. Keypad autofocus waits until the destination is RESUMED.

## Limits

Android 13/14 need the predictive-back developer option for gesture testing. The toggle controls
Settings page transitions and map sheet entry/exit. Place details, both directions pickers, trip
editors, results, arrivals, stop offers, transit guidance, and transit route details keep their outgoing snapshot
while sliding down over 200 ms; entry slides up over 250 ms. Stable overlay keys prevent data
refreshes from replaying the entrance. The driving steps sheet keeps its existing bar/list motion
and animates Back before clearing its state. Map camera motion, direct sheet dragging and detent
settling, photo viewers, and system back-to-home animations keep their own behavior.


## Predictive sheet back and navigation mode

A back swipe moves the open sheet down with the finger. Finishing settles it offscreen over
160 ms and then closes it; canceling returns it over 180 ms without clearing the place or route.
With Page transitions off, the same actions work without motion. Search, choosing a map point,
and route alternatives retain their back priority, and ending a drive still asks for confirmation.

Sheet shadows use 4 dp elevation, with the driving steps card retaining 6 dp. Route share and
close actions reuse Place details' 36 dp circles and 18 dp icons. Switching between browsing and
navigation fades/slides the top controls (220 ms in, 160 ms out) and animates the bottom bar;
the map and active guidance are never recreated for the transition.
