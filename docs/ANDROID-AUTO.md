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
   under **Customize vehicle launcher**.

That is the same step OsmAnd, Organic Maps, and every other sideloaded nav app
needs. On many phones it is not enough on its own: see "Where the real gate is" below. Media apps (music, podcasts) do **not** need it, which is why a sideloaded
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


## Where the real gate is

On a real car the Unknown sources toggle is not the whole story. A car log captured in
September 2026 (a Pixel 9 on GrapheneOS with sandboxed Play, Android Auto 17.4, Unknown sources
on) shows what Android Auto does when the phone connects: it asks the Play Store who owns each
template app, gets `PlayGearheadService app.vela, app owners empty`, and then logs
`CAR.VALIDATOR: Package DENIED; failed all other checks [app.vela]`. CoMaps and Organic Maps
were denied the same way. The check reads Play's own install record, so setting the installer
fields on the phone does not pass it, and Unknown sources did not cover it on that phone.

What has been seen to work:

- **A stock Pixel with a Google account signed in to Play**, with Vela installed through King
  Installer's "Google installer" method (Google's own package installer, which GrapheneOS does
  not ship). Vela was listed and ran.
- **An aftermarket head unit** (a motorcycle unit, phone on Android 16) with Vela installed
  through King Installer and Unknown sources on. Aftermarket units often run their own receiver,
  which can be more lenient than the one built into cars.

The Desktop Head Unit (Google's car simulator) does not run the ownership check at all: on a
stock phone with no Google account, a plain sideloaded Vela was listed and ran, and the log
shows no ownership lookup. It is useful for trying the car screens, and proves nothing about
whether a car will list Vela.

## If it still does not appear

1. **Check how Vela was installed.** Settings > About shows "Installed by" and the package
   that installed Vela. An installer that records the Play Store is what the workarounds above
   rely on, and an in-app update reinstalls through the system installer and undoes it. When
   the install source is the Play Store, Vela holds the update back and offers the APK as a file
   so you can reinstall it the same way.
2. **Confirm the toggle stuck.** Some phones have more than one Android Auto surface (the
   built-in one under Connected devices and a standalone app). Make sure Unknown sources is on
   for the one your car actually uses, then force stop Android Auto and reconnect.
3. **Let Android Auto rescan.** Force stop Android Auto (and Google Play services on ROMs that
   run it sandboxed), then reopen. A plain phone reboot does not always trigger a fresh scan of
   installed template apps.
4. **Grab a log while connecting.** This is the one step that tells us why. With the phone
   plugged in and USB debugging on, start a log that survives the drive, connect to the car,
   then pull it:

   ```
   adb shell 'nohup logcat -f /data/local/tmp/aa.txt -r 32768 -n 6 &'
   ```

   ```
   adb pull /data/local/tmp/aa.txt
   ```

   Search it for `CAR.VALIDATOR` and `PlayGearheadService`. A reboot stops the capture. Scrub
   any coordinates before attaching it to an issue.

## Why Vela will not bundle the old Android Auto SDK

Some sideloaded car apps (Fermata, and projects built on it) appear on Android
Auto without any of the above, because they bundle `aauto.aar`, Google's
pre-template Android Auto SDK from the projection era. It is worth saying plainly
why Vela does not and will not go that route.

**It is proprietary Google code.** The AAR carries
`com.google.android.apps.auto.sdk` and `com.google.android.gms.car`, partly
obfuscated. It went to early-access partners, was never published and never
open-sourced. Shipping it inside Vela would breach Vela's own GPLv3, not just
Google's terms, and that is not a call one contributor can make for everyone
else.

**There is no newer version to find.** Every circulating copy is stamped January
2019. Google did not supersede that SDK; it removed the entire "draw your own
surface" API and replaced it with the open `androidx.car.app` templates Vela
already uses. The 2019 build is the last one that exists, which is why every
project doing this ships the identical file.

**It is decaying, and the decay is on the car side.** Tested with one phone
across two cars: a 2018 head unit accepts it, a newer one does not. The variable
is the head unit's protocol negotiation, not anything on the phone, so no newer
blob, install trick or app-side change can recover it, and the set of cars where
it works shrinks every model year. Upstream shows the same rot from the other
direction (Fermata disabled full-screen mirroring when Android Auto 17.3 broke
it).

The routes above have the opposite shape: an install that Play records as its own, a
lenient aftermarket unit, or a Play listing all need no proprietary code at all.

## Known rough edge: de-Googled and sandboxed-Play ROMs

Android Auto is part of Google Play services, so it works on a de-Googled ROM only where
sandboxed Google Play is set up and Android Auto is talking to it. The ownership check above is
what stops sideloaded navigation apps there, Vela included, and nothing inside the app can
answer it. A listing on Google Play, a head unit running full Android, or a car running Android
natively are the ways around it.
