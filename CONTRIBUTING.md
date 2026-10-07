# Contributing to Vela

This page says what a good contribution looks like, so a pull request lands on the first try.
How the app is built is in [SPEC.md](SPEC.md) and [the book](docs/book/README.md). The rules
and traps for changing the code are in [CLAUDE.md](CLAUDE.md), which is written for people too.

## Ground rules

1. **No backend, no shared keys.** Every install talks to Google like one logged-out browser,
   from the user's own connection. Never embed a Google API key and never add a Vela server.
   This is the project's legal footing.
2. **Do not let your own location in by accident.** Working on a maps app, your test
   coordinates, screenshots, sample addresses and commit messages all come from where you are,
   and together they put you on a map in public git history for good. Naming a business
   because its data is broken is fine. Fixtures use the Davis and Sacramento area in
   California, screenshots use the built-in location simulator, and a commit message names a
   place only when the place is the point. The full list is in CLAUDE.md under "Location
   hygiene". It applies to AI assistants too; give one that section before it writes a patch.
3. **No Google libraries.** Android's own `LocationManager` and `TextToSpeech`, no Play
   services, no Firebase, no Play Integrity. The app must work on GrapheneOS with no Google
   services installed.
4. **Two modules.** `:core` has no interface code. `:app` is the Compose interface. MapLibre
   and Android UI types stay out of `:core`. `core/data/MapDataSource` is the seam.
5. **Docs go in the same commit.** A change to behavior updates `SPEC.md`, the book chapter for
   that subsystem, and `FEATURES.md` as needed. When something on `ROADMAP.md` ships or turns
   out to be impossible, move its entry to `docs/ROADMAP-HISTORY.md`. If a change needs no doc
   edit, say why in the commit message. CLAUDE.md says how the docs are written.
6. **Every string a user sees is translatable.** Add new strings to the English file,
   `res/values/strings.xml`. Translations arrive through Weblate
   ([docs/TRANSLATING.md](docs/TRANSLATING.md)), and an untranslated string shows in English
   until then. Match placeholder types to their arguments. Place names, addresses and reviews
   are data and are never translated.

## Practical rules

- **Test on a release build.** A debug build drops frames on the map, and conclusions drawn
  from it are wrong. `./gradlew :app:assembleRelease`, then install it.
- **Logic that has no interface goes in `:core` with unit tests** (`./gradlew :core:test`).
- **Large downloads never use the shared OkHttp client.** Its 12 second call timeout cuts a
  big download off without an error. Use a client with `callTimeout(0)`, as the existing
  downloaders do.
- **Never trust a remembered Google response.** Field numbers and array positions move. If
  you touch a parser, check it against a real response.
- **Commit subjects are the changelog users read.** Write them as plain sentences. A subject
  that starts with "Docs:" is left out of the in-app notes.
- **Install the hooks once:** `bash scripts/install-hooks.sh`. CI fails a push whose added text
  has an em dash, a British spelling, or an AI attribution line. The hook runs the same check
  before anything is public.

## Bug reports and feature requests

The tracker is a work queue for one maintainer. The rules keep every open issue something that
can be acted on.

- **One problem or one request per issue.** A report that bundles several is closed and you
  are asked to split it.
- **A bug report must be reproducible from what is written in it:** the steps in order, the
  version (Settings > About), and for anything about routes, places or the map, the start, the
  destination or the place. If you would rather not say where you were, use the location
  simulator (Settings > Diagnostics > Simulate my location) in Davis, California and say so.
  A report that cannot be reproduced from its text is closed.
- **Diagnostics beat descriptions.** In Settings > Diagnostics turn on "Share diagnostics",
  make the problem happen again, then tap "Export debug session". The log exists only while
  that switch is on, so switch it on first. Turn on "Redact places in exports" if the file
  must be safe to post.
- **Heat, battery and lag reports need a number:** the battery share Vela used over a stated
  time, how long the drive ran, the phone's temperature, or a screen recording of the lag.
  Add the diagnostics export and the version. A report on a build older than the current
  stable is closed, so update first.
- **Discussions are for questions only.** A bug or a request posted as a discussion is closed
  with a pointer to the issue form.
- **Feature requests are read, not voted on.** The maintainer decides. A request that does not
  fit is closed as not planned and stays closed.
- **A feature request says where the data would come from.** Vela has no server and no API
  keys and never signs in to Google. Each phone asks Google for what a logged-out browser
  would see, and everything else comes from open data. Things that need a Google account (your
  saved lists, Timeline, live busyness), a paid API, or a server of our own cannot be built.
  If you do not know the source, say so and name what you checked. [ROADMAP.md](ROADMAP.md)
  lists what will not be built.
- **A new issue gets an automatic first look.** A workflow compares it with earlier issues and
  comments with the closest few by shared wording. If one is the same thing, add your detail
  there. A bug report naming a build older than the current stable is labeled `incomplete`.
  This is plain text matching (`scripts/issue-triage.py`), and it never closes anything.
- **Incomplete issues are closed without further explanation** and labeled `incomplete`. Fill
  in the template and it will be read.

## Using an AI assistant

AI help is welcome, for code and for reports, as long as a person stands behind every word.

- **Have it read the project first:** [CLAUDE.md](CLAUDE.md), this page,
  [SPEC.md](SPEC.md) and [ROADMAP.md](ROADMAP.md), plus
  [docs/ROADMAP-HISTORY.md](docs/ROADMAP-HISTORY.md) for what was already tried and dropped.
  An assistant that has not read them will describe a different app with confidence.
- **You answer for every claim.** If the text says the code does something, check the code. If
  it cites a source, open the source. Invented citations and claims about features Vela does
  not have get the issue closed.
- **Do not paste AI output as a reply or an argument.** If you disagree with a decision, say
  why in your own words, briefly, or open a pull request that builds it.
- **The best use is a pull request.** An assistant that has read the docs can write a focused,
  tested change.

## Pull requests

- Small and focused: one change each.
- Say what changed and why. If it touches the interface or navigation, say which device you
  checked it on.
- Anything merged reaches nightly users within a day and everyone else within about a week.

## Conduct

Keep it about the code. Contributions are judged on technical merit and nothing else. Be civil
in reviews and issues, and argue about approaches, not people. Politics, in every direction,
is off topic here. That is the whole policy.
