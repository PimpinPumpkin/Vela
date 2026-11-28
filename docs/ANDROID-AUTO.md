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

## Which way fits your phone

| Your phone | What works today |
|---|---|
| GrapheneOS, or any phone without the Play Store signed in | Gearslip or myCar, in the next section. No install trick has passed a real car's check on these phones. |
| Stock Android, signed in to the Play Store | King Installer ("Google installer" or Shizuku method) with Unknown sources on, in "Where the real gate is" below. Gearslip and myCar work too. |
| Rooted, any ROM | PlayInstaller, or Gearslip and myCar. |

## Gearslip and myCar: no Android Auto app in the path

Gearslip and myCar are apps on your phone that talk to the car's screen themselves, in place
of Google's Android Auto app. Google's app never runs, so the check on who installed Vela
never runs either. They work on GrapheneOS with no Play services at all, and Vela draws its
own car screen inside them: the map, turn cards, search and route choices.

- [Gearslip](https://github.com/twinksoftware/Gearslip), by Twink Software.
- [myCar](https://github.com/SirioNero/MyCar), by SirioNero, a fork of Gearslip with its own
  changes.

Both are separate projects, not part of Vela. Both are alpha software under the AGPL-3.0, and
both install from GitHub or Obtainium.

To set one up:

1. Install Vela and either Gearslip or myCar. You do not need the Android Auto app, Play
   services or a Google account.
2. Open Gearslip or myCar and go through its setup, parked. It asks about the car's
   certificate. Most cars work with what it offers, and some cars from about 2020 on need one
   of your own; its own docs explain that step. Vela does not ship a certificate and cannot.
3. On GrapheneOS, check that USB-C port control is not set to **Charging-only**, or the car
   never sees the phone.
4. Plug the phone into the car's USB port, or pair it over Bluetooth for a car with wireless
   Android Auto. Pick Vela on the car screen.

Reported to work:

| Car | App | Notes |
|---|---|---|
| Volkswagen Golf 8, 2023 | myCar on GrapheneOS | Works. The link drops now and then when start/stop restarts the engine (GrapheneOS forum, November 2026). |

[Gearslip's car list](https://github.com/twinksoftware/Gearslip/blob/main/CARS.md) has the
cars its drivers have confirmed.

Where to report a problem: if the car will not connect, or the car screen freezes outside Vela,
that goes to Gearslip or myCar. If Vela's map, turns or search are wrong on the car screen,
that goes to [Vela's issues](https://github.com/PimpinPumpkin/Vela/issues/new/choose).

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

What has been seen to work, from reports on issue #179 (October 2026):

| Car | Phone | How Vela was installed |
|---|---|---|
| Kia Carnival, newest model | Sony Xperia 1 VII | King Installer, Unknown sources on |
| Toyota Yaris hatchback 2020, by cable | Sony Xperia 1 VII | the same |
| Toyota Corolla hatchback 2022 | Sony Xperia 1 VII | the same |
| Skoda Fabia 2017, built-in screen | Samsung Galaxy A16 | King Installer, "Google installer" method, Unknown sources on |
| Renault Zoe, built-in screen | Samsung Galaxy A53, Android 16 | King Installer, "Shizuku" method, Unknown sources on |
| A car with an instrument-cluster display, over AAWireless | not stated | Shizuku, Unknown sources on; needs 0.5.2426 or newer, where the cluster screen is handled |
| A motorcycle aftermarket unit | Sony Xperia 1 VII, Android 16 | King Installer, ADB method, Unknown sources on |

None of these phones runs GrapheneOS. A Mitsubishi Mirage 2023 did not connect to Android Auto
at all, with any app; that is the car, not Vela. Some head units take Android Auto over a cable
only.

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

## Gearslip speaks the turns with its own voice

Gearslip, an app that takes Android Auto's place on the phone, reads each turn aloud in the car
itself. Vela's mute, on the car screen or the phone, does not reach that voice. To silence it,
turn off Sound and voice > Spoken directions in Gearslip's car settings.

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
answer it. Gearslip and myCar step around it by replacing the Android Auto app on the phone
(see "Gearslip and myCar" above). A listing on Google Play, a head unit running full Android,
or a car running Android natively are the other ways around it.
