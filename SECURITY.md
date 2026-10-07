# Security policy

## Reporting a vulnerability

Use GitHub's private vulnerability reporting on this repository (Security tab,
"Report a vulnerability"). That opens a private thread with the maintainer; please
do not open a public issue for anything exploitable.

Worth reporting privately: anything that lets a third party push code or config to
installed apps (the calibration channel is ECDSA-signed against a pinned key, the
updater installs only same-signature APKs through the system installer; a way around
either is exactly what we want to hear about), leaks of user location or history off
the device, or a way to make the hidden WebViews run attacker-controlled script.

Not security bugs: Google changing a response shape (that's a calibration drift,
open a normal issue), the community OSRM server being down, or OSM data being wrong.

## What updates look like

There is no backend. A fix that lands on `main` ships in the next nightly (cut daily,
or at once for an urgent fix) and reaches the weekly stable at the next promotion, or
sooner through an early one. Every build is signed with the same key. Scraper-shape
fixes can also ship to every install within minutes through the signed remote
calibration file, without an app update.

## Which versions get fixes

The current stable, the nightlies and the canary. There are no maintenance branches: a fix
goes to the newest build on each channel and older builds are updated by updating.

## What is checked automatically

On every push to `main` and `canary`, on every pull request, and weekly
(`.github/workflows/security.yml`):

- A static scan of the app's source for known insecure Android patterns (mobsfscan). Results
  go to the repository's code scanning page; the rules switched off, and why, are in `.mobsf`.
- The libraries the build actually resolves are sent to GitHub's dependency graph. Dependabot
  raises an alert when one of them has a published vulnerability, and a pull request that adds
  a library with a known high-severity one fails its check.
- The OpenSSF Scorecard (`scorecard.yml`) scores how the repository is run. The result is
  public at https://scorecard.dev/viewer/?uri=github.com/PimpinPumpkin/Vela

Every GitHub Action the workflows use is pinned to an exact commit, and workflows get only
the token permissions they need. Secret scanning with push protection is on. The job that
signs the F-Droid index installs its tools at fixed versions, and the build checks that the
Gradle wrapper in the repository is Gradle's own file.

The switches that open the app up for inspection (the WebView inspector, the network log,
saving raw replies) can only be turned on over adb, on the phone in hand. The remote settings
file cannot turn them on.

These are tools, and tools miss things. The project is written with heavy use of AI
assistants and reviewed by one maintainer; if you read the code and something looks wrong,
that report is worth more than any scanner.

## Bill of materials

The dependency graph is exportable as an SPDX file from Insights, Dependency graph, "Export
SBOM", and each run of the security workflow on `main` attaches the same file as the
`vela-sbom` artifact. Four prebuilt libraries are not Maven artifacts and are fetched from
this repository's own releases at build time: the sherpa-onnx speech runtime, Chromium's
Cronet (packed from Chromium's public build by `scripts/build-cronet-aar.sh`), and OsmAnd's
routing jars. `docs/BUILDING.md` says where each comes from.

## What the app is built to limit

- No account, no API key and no server of its own. The only code that can change an
  installed app is a signed update or the signed calibration file described above.
- All traffic is HTTPS; cleartext is allowed to localhost only, for local testing.
- The hidden web views load Google's pages and nothing else: navigation off google.com is
  blocked, file and content access are off, and the bridge objects carry random names.
- Files leave the app only through a non-exported file provider, by your own share action.
- A device backup carries your settings, saved places and lists, saved routes, history,
  recorded trips and your font. It does not carry the Google web session, the last known
  position, diagnostics, caches or downloads (`app/src/main/res/xml/backup_rules.xml`).

## Verifying a download

Every release on every channel is signed with one key. Its SHA-256 certificate fingerprint
is in the README under "Check you got the real thing"; `apksigner verify --print-certs`
prints it for any APK.

