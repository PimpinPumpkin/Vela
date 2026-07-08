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

There is no backend. Fixes ship as a new signed release within minutes of the fix
landing on `main`; scraper-shape fixes can also ship instantly through the signed
remote calibration file without an app update.
