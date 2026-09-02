# Vela's F-Droid repository

Vela publishes its own F-Droid repository, so you can install and update it from
any F-Droid client (F-Droid, Droid-ify, Neo Store) without sideloading.

## Add the repo

Paste this one line as the repository address - the official F-Droid client,
Droid-ify and Neo Store all accept it and pick up the fingerprint automatically:

```
https://pimpinpumpkin.github.io/Vela/repo?fingerprint=F374920F2F5F38D7508D0B042125B8EAF23CF0F06FA7490280FB77115BB091DE
```

(In your client: **Settings → Repositories → Add repository**, paste, refresh,
search for **Vela Maps**, install.)

If your client wants the address and fingerprint separately, use:

- Address:

  ```
  https://pimpinpumpkin.github.io/Vela/repo
  ```

- Fingerprint:

  ```
  F374920F2F5F38D7508D0B042125B8EAF23CF0F06FA7490280FB77115BB091DE
  ```

## What the repo serves

- The latest **stable** release (promoted weekly from the nightly line). This is
  what the repo suggests, so a normal install only ever updates on stables.
- The newest **nightly** build when it is ahead of stable. Nightlies sit above
  the suggested version, so clients treat them as unstable: to get them, enable
  unstable/beta updates for Vela in your client (in the official F-Droid client
  that's Settings -> Expert -> Unstable updates; Droid-ify and Neo Store have a
  per-app release-channel toggle). Or track nightlies straight from GitHub with
  Obtainium's "include prereleases".

The repo index is rebuilt automatically after every successful CI run and
weekly stable promotion (`.github/workflows/fdroid-repo.yml`) and hosted on
GitHub Pages. The index is
signed with a dedicated repo key; the APKs carry the same Vela signing key as
the GitHub releases and the in-app updater, so switching install sources never
forces a reinstall.

**Two different fingerprints, do not mix them up.** The one in the repo URL
above signs the repo INDEX, which is what tells your F-Droid client the listing
is genuine. The APKs themselves are signed with the Vela app key, whose
certificate fingerprint is:

```
SHA-256  29:93:8B:48:58:06:3E:42:E6:77:FF:95:C9:01:CD:48:24:8A:7F:03:2A:3A:E8:5F:9B:9E:56:17:56:8B:0D:36
```

That is the one to check an APK against (`apksigner verify --print-certs`), and
it is identical on every channel, which is why moving between install sources
never asks you to uninstall first.

## Why not the official f-droid.org catalog?

The main F-Droid catalog builds every app from source on their own servers,
which requires all dependencies to be free of prebuilt binaries. Vela bundles
the sherpa-onnx voice runtime and downloads voice models and routing graphs at
runtime, which does not fit that pipeline today. A self-hosted repo has no such
constraints and updates the moment a release is cut.
