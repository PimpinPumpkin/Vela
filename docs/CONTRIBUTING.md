# Contributing to Vela

Thanks for wanting to help. Vela is a degoogled maps client with a small surface and
strong opinions; this page tells you what a good contribution looks like so your PR
lands on the first try. The deeper background lives in [SPEC.md](SPEC.md) (how it's
built and why) and [AGENTS.md](../AGENTS.md) (build rules and gotchas, useful to humans
too).

## Ground rules, in order of importance

1. **No backend, no shared keys.** Every install talks to Google like one logged-out
   browser, from the user's own IP. Never embed a static Google API key, never add a
   Vela server. This is the project's legal footing and it is not negotiable.
2. **Degoogled at runtime.** AOSP `LocationManager` only (never Fused), AOSP
   `TextToSpeech`, no GMS, no Firebase, no Play Integrity. The app must work fully on
   GrapheneOS with no Google services installed.
3. **The module boundary is real.** `:core` is a UI-agnostic extractor (the
   NewPipeExtractor pattern); `:app` is the Compose UI. MapLibre and Android UI types
   never leak into `:core`. The one seam between them is `core/data/MapDataSource`.
4. **Docs move with code, in the same commit.** When behaviour changes, update
   `README.md`, `docs/FEATURES.md`, `docs/SPEC.md` and `AGENTS.md` as the change needs. Stale
   docs are treated as a bug. If a change genuinely needs no doc edit, say why in the
   commit message.
5. **Every user-facing string ships in all 11 languages.** Add it to
   `res/values/strings.xml` and each `res/values-<lang>/strings.xml` (de es fr it nl
   pl pt ru sv uk). Match placeholder types to the arguments (an Int needs `%d`; a
   `%d` fed a String crashes). Place names, addresses and reviews are data and are
   never translated.

## Practical rules you will hit quickly

- **The `debug` build is smooth enough to test on.** It's now R8-minified *and*
  debuggable (see AGENTS.md "Build variants"), so it no longer lags the map the way a
  stock debug build did - the old "always ship release" caveat is gone. Use the
  non-debuggable `staging` variant only when you need true release-perf numbers.
- **Pure logic gets unit tests in `:core`.** The nav engine, parsers, polyline codec
  and ranking logic are all plain JVM code with tests (`./gradlew :core:test`). If
  you add logic that can live there, put it there and test it.
- **Large downloads never use the shared OkHttp client.** Its 12 second call timeout
  (which keeps scrapes bounded) silently aborts big bodies mid-read. Derive a client
  with `callTimeout(0)` like every existing downloader does.
- **Never trust a remembered Google response shape.** Field numbers and array indices
  drift; they are marked `CALIBRATE:` and pinned from live captures. If you touch the
  scraper, verify against a real response, not memory or docs.
- **Commit subjects are the user-facing changelog.** Releases publish the commit
  subjects since the last tag as release notes. Write plain-language subjects a user
  can read, not terse internals.

## Pull requests

- Keep them small and focused; one change per PR.
- Say what changed and why in the description. If it touches UI or navigation, note
  what device you verified on.
- CI builds, tests and publishes a signed release from every push to `main`, so
  anything merged ships to real phones within minutes. Treat merges accordingly.

## Conduct

Keep it about the code. Contributions are judged on technical merit and nothing
else: not who you are, not what you believe, not where you're from. Be civil in
reviews and issues; argue about approaches, not people. Politics, in every
direction, is off-topic in this repo. That is the whole policy, and there is no
separate code of conduct document.
