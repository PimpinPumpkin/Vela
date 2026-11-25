# Vela Maps: fact sheet

Everything here may be quoted or reused in coverage of Vela.

## One sentence

Vela is a free, open-source maps and navigation app for Android that works without Google Play
Services or a Google account.

## One paragraph

Vela is a free, open-source maps and navigation app for Android, built for phones without Google
Play Services. The map and the places on it come from open data such as OpenStreetMap and Overture
Maps. For the things open data does not have, like reviews, opening hours and traffic-aware
routes, the phone looks them up on Google's public website the way a logged-out browser would,
with no account and no API key. Nothing you save leaves the device. It is in beta and maintained
by one developer.

## Facts

| | |
| --- | --- |
| Name | Vela Maps, or Vela. Named for the constellation "the Sails". |
| Platform | Android 8 and newer. No iOS version. |
| Price | Free. No ads, no account, no paid tier. |
| License | GPLv3 |
| Status | Beta |
| Started | June 2026 |
| Who makes it | One developer, who goes by PimpinPumpkin. Outside contributors send fixes and volunteers translate it. |
| Languages | 19 |
| Where to get it | GitHub releases, Obtainium, or Vela's own F-Droid repository. Not on Google Play. |
| Source | https://github.com/PimpinPumpkin/Vela |
| Website | https://velamaps.org/ |

## Accurate ways to describe it

- An open-source maps app that works without Google services
- Looks things up on Google like a logged-out browser
- The map and the places on it are open data
- What NewPipe is to YouTube, for maps
- In beta, made by one developer

## Not accurate

- A Google Maps mod, crack or patched app
- A wrapper around the Google Maps website
- Free access to the Google Maps API
- A way to use your Google account privately
- Made by, approved by or connected to Google
- Made by a team or a company

## What Google receives, by default

| When you | Google gets |
| --- | --- |
| Pan and zoom the map | Nothing |
| Type a search | Your text, anonymously |
| Tap a place | An anonymous lookup of that place |
| Ask for driving directions | Where the trip starts and ends, anonymously |
| Drive with navigation on | A traffic check from your position every couple of minutes (can be turned off) |
| Save a place or a list | Nothing, ever |

Google sees an IP address and the question. It does not see an account, a device ID or an app key.
One setting, "Use Vela without Google", turns every Google request off, and downloaded regions
work fully offline. Full detail: https://github.com/PimpinPumpkin/Vela/blob/main/PRIVACY.md

## Surveillance cameras

Vela shows mapped license-plate readers (Flock and others) from the community DeFlock project,
and fixed speed cameras from OpenStreetMap. It can warn when one is ahead and can prefer a route
that passes fewer of them when the detour is small. It shows cameras people have mapped; it cannot
know about ones nobody has.

## Requests

- If you can, say you are covering it before it goes out. A day of notice helps one person handle
  the bug reports that follow.
- Link the GitHub page for downloads. Copies on other APK sites are not checked by anyone.
- Send bug reports to the GitHub issue form, not to comments.

## Contact

@PimpinPumpkin on GitHub: https://github.com/PimpinPumpkin

Security problems: the private report form at
https://github.com/PimpinPumpkin/Vela/security/advisories/new

## In this folder

- `logo/` the logo as SVG and the app icon as PNG. Please do not recolor it.
- `screenshots/` eleven full-size screenshots, dark and light. They show the project's test area.
- `not-google-maps.png` the project's joke image.
