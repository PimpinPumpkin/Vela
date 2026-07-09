# Vela's F-Droid repository

Vela publishes its own F-Droid repository, so you can install and update it from
any F-Droid client (F-Droid, Droid-ify, Neo Store) without sideloading.

## Add the repo

1. In your F-Droid client, open **Settings → Repositories → Add repository**.
2. Enter the repo address:

   ```
   https://pimpinpumpkin.github.io/Vela/repo
   ```

3. When asked for the fingerprint (or to confirm it), check it matches:

   ```
   F374920F2F5F38D7508D0B042125B8EAF23CF0F06FA7490280FB77115BB091DE
   ```

4. Refresh, search for **Vela Maps**, install.

Or use the one-line form some clients accept directly:

```
https://pimpinpumpkin.github.io/Vela/repo?fingerprint=F374920F2F5F38D7508D0B042125B8EAF23CF0F06FA7490280FB77115BB091DE
```

## What the repo serves

- The latest **stable** release (promoted weekly from the nightly line).
- The newest **nightly** build when it is ahead of stable, so you can opt into
  the fresh one by picking the higher version in your client.

The repo index is rebuilt automatically by CI on every release
(`.github/workflows/fdroid-repo.yml`) and hosted on GitHub Pages. The index is
signed with a dedicated repo key; the APKs carry the same Vela signing key as
the GitHub releases and the in-app updater, so switching install sources never
forces a reinstall.

## Why not the official f-droid.org catalog?

The main F-Droid catalog builds every app from source on their own servers,
which requires all dependencies to be free of prebuilt binaries. Vela bundles
the sherpa-onnx voice runtime and downloads voice models and routing graphs at
runtime, which does not fit that pipeline today. A self-hosted repo has no such
constraints and updates the moment a release is cut.
