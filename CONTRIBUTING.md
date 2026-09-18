# Contributing to Vela

Thanks for wanting to help. Vela is a degoogled maps client with a small surface and
strong opinions; this page tells you what a good contribution looks like so your PR
lands on the first try. The deeper background lives in [SPEC.md](SPEC.md) (how it's
built and why) and [CLAUDE.md](CLAUDE.md) (build rules and gotchas, useful to humans
too).

## Ground rules, in order of importance

1. **No backend, no shared keys.** Every install talks to Google like one logged-out
   browser, from the user's own IP. Never embed a static Google API key, never add a
   Vela server. This is the project's legal footing and it is not negotiable.
2. **Don't let your own location leak in by accident.** Working on a maps app means
   test coordinates, screenshots, sample addresses and commit messages all naturally
   come from wherever you are, and together they pin you on a map, permanently, in
   public git history. Deliberate is fine: naming a specific business because its
   data is broken is a good bug report. Incidental is the problem: fixtures default
   to the project's Davis / Sacramento, CA area unless there's a reason otherwise,
   screenshots default to the built-in location simulator, and commit messages name
   places only when the place is the point. The full checklist is in CLAUDE.md under
   "Location hygiene"; it applies to humans and AI assistants equally (AI agents
   with memory files are especially prone to writing down where their user lives),
   so if an AI writes your patch, hand it that section first.
3. **Degoogled at runtime.** AOSP `LocationManager` only (never Fused), AOSP
   `TextToSpeech`, no GMS, no Firebase, no Play Integrity. The app must work fully on
   GrapheneOS with no Google services installed.
4. **The module boundary is real.** `:core` is a UI-agnostic extractor (the
   NewPipeExtractor pattern); `:app` is the Compose UI. MapLibre and Android UI types
   never leak into `:core`. The one seam between them is `core/data/MapDataSource`.
5. **Docs move with code, in the same commit.** When behavior changes, update
   `README.md`, `FEATURES.md`, `SPEC.md` and `CLAUDE.md` as the change needs. Stale
   docs are treated as a bug. If a change genuinely needs no doc edit, say why in the
   commit message.
6. **Every user-facing string is translatable** (the 15-language matrix is in
   [docs/LANGUAGES.md](docs/LANGUAGES.md)). Add new strings to the English base
   `res/values/strings.xml`; translations come in as pull requests against `values-<lang>/strings.xml` (see
   [docs/TRANSLATING.md](docs/TRANSLATING.md)), and an untranslated string falls
   back to English until they do. Match placeholder types to the arguments (an Int
   needs `%d`; a `%d` fed a String crashes). Place names, addresses and reviews are
   data and are never translated. Want to translate rather than code? That guide
   is the place to start; a one-file edit in the GitHub web editor is enough.

## Practical rules you will hit quickly

- **Test on a release build.** Debug builds visibly lag during map scroll and
  navigation; conclusions drawn from them are wrong. `./gradlew :app:assembleRelease`
  and sideload. `assembleDebug` is fine as a compile check only.
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

## Bug reports and feature requests

The tracker is a work queue for one maintainer, not a forum. The rules exist so that
every open issue is something that can actually be acted on.

- **One problem or one request per issue.** A report that bundles several things is
  closed and you are asked to split it.
- **A bug report must be reproducible from what is written in it.** Steps in order,
  the version, and for anything about routes, places or the map, the start, the
  destination or the place. If you would rather not name where you were, use the
  built-in location simulator (Settings, Navigation) with the project's Davis, CA
  test area and say so. A report the maintainer cannot reproduce from the text is
  closed, not investigated.
- **Diagnostics beat descriptions.** Settings, Diagnostics, Export debug session.
  Turn on "Redact places in exports" if the file must be safe to post.
- **Feature requests are read, not voted on.** The maintainer decides. A request that
  does not fit the project is closed as not planned, without a debate, and stays
  closed; reopening it or filing it again under another title is not a discussion.
- **Feature requests need a keyless reality check.** Vela has no server and no API keys,
  and it never signs in to Google. Every phone asks Google for exactly what a logged-out
  browser would see, from its own connection, and everything else comes from open data
  (OpenStreetMap, Overture, AllThePlaces, Transitous and similar). So before you file,
  answer one question in the request: where would the data come from? Things that need a
  Google account (your saved lists, Timeline, live busyness), a paid API (flight search,
  most live traffic incident feeds) or a server of our own (sharing your live location)
  cannot be built. If you do not know the source, say that and name what you checked. The
  list of things that will not be built is in [ROADMAP.md](ROADMAP.md) under "Not going
  to happen".
- **Incomplete issues are closed without further explanation**, the same way NewPipe
  and most small projects handle them, and they are labeled `incomplete` so the reason
  is on the record. Fill in the template and it will be read.

## Using an AI assistant

AI help is welcome, for code and for reports, as long as a person stands behind every word.

- **Have it read the project first.** Before it writes a patch, a bug report or a feature
  request, point it at [CLAUDE.md](CLAUDE.md), [CONTRIBUTING.md](CONTRIBUTING.md),
  [FEATURES.md](FEATURES.md), [SPEC.md](SPEC.md) and [ROADMAP.md](ROADMAP.md). CLAUDE.md
  in particular records how Vela actually works today and which ideas were already tried
  and dropped. An assistant that has not read it will confidently describe a different app.
- **You are responsible for every claim.** If the text says the code does something, check
  the code. If it cites a source, open the source. Invented citations and claims about
  features Vela does not have get the issue closed.
- **Do not paste AI output as a reply or an argument.** A decision on an issue is not
  reopened by a generated essay about why it should be. If you disagree, say why in your
  own words, briefly, or better, open a pull request that builds it.
- **The best use is a pull request.** An assistant that has read the docs can write a
  focused, tested change. That moves the project; a long comment does not.

## Pull requests

- Keep them small and focused; one change per PR.
- Say what changed and why in the description. If it touches UI or navigation, note
  what device you verified on.
- CI builds and tests every push to `main`. A nightly release is cut from `main` once a
  day and promoted to stable weekly, so anything merged reaches real phones within a day.
  Treat merges accordingly.

## Conduct

Keep it about the code. Contributions are judged on technical merit and nothing
else: not who you are, not what you believe, not where you're from. Be civil in
reviews and issues; argue about approaches, not people. Politics, in every
direction, is off-topic in this repo. That is the whole policy, and there is no
separate code of conduct document.
