# Android Auto

Vela ships a full navigation experience for Android Auto (phone projecting to a
car head unit): car-side search, route preview with live-traffic alternates, and
active turn-by-turn with the map drawn onto the car surface. This page covers how
to get it to show up, because sideloaded navigation apps are fussier than most
people expect.

## The short version

Vela is a **navigation template app**. Android Auto hides sideloaded navigation
and point-of-interest apps by default, so you have to turn on the developer
toggle:

1. Open the **Android Auto** settings (Settings, Connected devices, Android Auto;
   or the standalone Android Auto app on older phones).
2. Scroll to the bottom and tap **Version** ten times to unlock **Developer
   settings**.
3. Open the three-dot menu, **Developer settings**, and enable **Unknown
   sources**.
4. Reconnect to the car (or open the head-unit simulator). Vela should now appear
   under **Customise vehicle launcher**.

That is the same step OsmAnd, Organic Maps, and every other sideloaded nav app
needs. Media apps (music, podcasts) do **not** need it, which is why a sideloaded
music player shows up on Android Auto with no fuss while a nav app does not. See
below.

## Why a sideloaded music app "just works" but a nav app does not

They ride two completely different integrations:

- **Media apps** (a music or podcast player) talk to Android Auto through
  `MediaBrowserService`. That path is old, and it has always accepted any
  installed app with no allowlist and no developer toggle. So a sideloaded music
  player appears on Android Auto the moment it is installed.
- **Navigation apps** like Vela talk to Android Auto through a `CarAppService`
  using the `androidx.car.app` templates. That path is newer and gated: outside
  of an app installed from Google Play, it only surfaces when **Unknown sources**
  is enabled in Android Auto's developer settings.

So "another sideloaded app shows up, why not Vela" is usually comparing a media
app against a nav app. It is not that Vela is misconfigured; it is that the nav
path has an extra gate the media path never had.

## What Vela declares (so you can rule out a config gap)

Vela's manifest carries everything Android Auto requires for a navigation
template app:

- a `CarAppService` (`app/car/VelaCarAppService.kt`), exported, with the
  `androidx.car.app.CarAppService` action and the
  `androidx.car.app.category.NAVIGATION` category
- `res/xml/automotive_app_desc.xml` with `<uses name="template" />`, referenced
  from the `com.google.android.gms.car.application` meta-data
- `androidx.car.app.minCarApiLevel` = 1 (maximally compatible)
- the `androidx.car.app.NAVIGATION_TEMPLATES` and `androidx.car.app.ACCESS_SURFACE`
  permissions
- the `androidx.car.app` + `androidx.car.app:app-projected` libraries

If Android Auto still does not list Vela after enabling Unknown sources, it is not
because one of these is missing.

## If it still does not appear

Enabling Unknown sources is enough for the large majority of setups. When it is
not, work through these:

1. **Confirm the toggle stuck.** Some phones have more than one Android Auto
   surface (the built-in one under Connected devices and a standalone app). Make
   sure Unknown sources is on for the one your car actually uses, then force stop
   Android Auto and reconnect.
2. **Let Android Auto rescan.** Force stop Android Auto (and Google Play services
   on ROMs that run it sandboxed), then reopen. A plain phone reboot does not
   always trigger a fresh scan of installed template apps.
3. **Grab a log while opening Android Auto.** This is the one step that actually
   tells us why. With the phone plugged in and USB debugging on:

   ```
   adb logcat -c
   # now open Android Auto / connect to the car (or the Desktop Head Unit)
   adb logcat | grep -iE "carapp|CAR\.|projection|Vela"
   ```

   Android Auto logs which template apps it discovered and whether it rejected any
   (and why). If Vela is discovered but rejected, that line names the reason. If
   Vela never appears in the scan at all, that points at the Unknown-sources gate
   or the install source rather than anything in the app.
4. **Worth a try: reinstall reporting Play as the installer.** A few users have
   reported that installing with the Play Store recorded as the install source
   nudges Android Auto into listing a sideloaded nav app:

   ```
   adb install -i com.android.vending -r vela.apk
   ```

   This is not a guaranteed fix and it is not something the app can do for itself,
   but it costs nothing to try. A Shizuku- or root-based installer that sets the
   install source does the same thing.

## Known rough edge: de-Googled and sandboxed-Play ROMs

Android Auto is part of Google Play services, so it works on a de-Googled ROM only
where sandboxed Google Play is set up and Android Auto is talking to it. On those
ROMs the discovery of sideloaded template apps and the reading of the Unknown
sources setting have more moving parts than on a stock phone, and other nav apps
hit the same wall. If you are on one of these and the steps above do not surface
Vela, the logcat from step 3 is what we need to take it further. Open an issue
with that log attached (scrub any coordinates first) and we will dig in.
