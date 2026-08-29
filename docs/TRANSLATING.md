# Translating Vela

Vela ships in 15 languages (the canonical layer-by-layer table is in
[LANGUAGES.md](LANGUAGES.md)). Translations are community-maintained, and today
they come in as ordinary pull requests.

> **Weblate is not up yet.** Hosted Weblate is free for open-source projects, but
> a project has to be at least three months old to qualify and Vela is not there
> yet. Until it is, there is no Weblate project to sign in to, so please use the
> pull-request flow below. This page gets rewritten the day it is running.

## Translate by pull request

1. Find your language file: `app/src/main/res/values-<lang>/strings.xml`
   (for example `values-de` for German, `values-zh-rTW` for Traditional
   Chinese). The English original is `app/src/main/res/values/strings.xml`.
2. Edit or add the strings you want to fix. You can do this entirely in the
   GitHub web editor: open the file, press the pencil, and commit to a new
   branch. No git client and no Android toolchain needed.
3. Open a pull request. A maintainer reviews it against the rules below and
   merges. You keep commit credit for your strings.

Anything you do not translate simply falls back to English, so a partial
contribution is genuinely useful and never breaks the app.

Missing your language entirely? Open an issue and say which one, or copy
`values/strings.xml` to a new `values-<lang>/` folder and translate what you
can. A new language needs the UI strings first; spoken directions and the
open/closed keyword table are separate layers a maintainer wires up afterwards
(see below).

## What lives where

The UI strings above are the layer that is open to everyone. The rest is code
or config and changes through pull requests too, but needs a maintainer:

| Layer | Where | How to change |
|---|---|---|
| App UI strings (~350) | `app/src/main/res/values-<lang>/strings.xml` | PR (the flow above) |
| Spoken turn-by-turn | `core/src/main/java/app/vela/core/i18n/` (a `NavStrings` table per language) | PR, needs native review |
| Open/closed status keywords | `calibration.json` (`statusClosedWords`/`statusOpenWords`) + compiled tables in `SearchParser` | PR or a signed calibration push |
| Neural voice | Piper voice catalog (`PiperCatalog`) | depends on an upstream Piper voice existing |

## Rules that keep translations shippable

- **Placeholders must match the English type.** `%1$s` stays a string,
  `%1$d` stays a number, in the same order. A mismatch makes Android fall
  back to English for that one string (never a crash), so your translation
  silently doesn't show.
- **Plurals need the right CLDR categories for your language.** Russian,
  Ukrainian and Polish need `one`/`few`/`many`/`other`; Hebrew needs
  `one`/`two`/`many`/`other`; Chinese and Japanese only `other`. Copy the
  category set from an existing file in your language if you are unsure.
- **No em dashes.** Use a comma, a colon, or rephrase. The one legitimate
  dash is a numeric range. (House style across the whole repo.)
- **Escape apostrophes** as `\'` in strings.xml. A raw one fails the release
  build even when a debug build passes.
- **Never translate data.** Place names, street names, reviews and anything
  else that comes from the map or from Google is shown as-is.
- **Keep it short.** These strings live on phone-width chips, rows and
  buttons; when in doubt, prefer the shorter phrasing.

Some English literals are deliberately NOT translatable: strings that double
as logic keys (the category chips are also the search query, "Open"/"Closed"
feed the status parser). They stay inline in code until display text is
split from the key, so don't be surprised if you can't find one on Weblate.

## For maintainers: the Weblate component

One component covers the app:

- Repo: `https://github.com/PimpinPumpkin/Vela`, branch `main`
- File mask: `app/src/main/res/values-*/strings.xml`
- Monolingual base: `app/src/main/res/values/strings.xml`
- Format: Android string resources; license GPL-3.0
- Contribution flow: Weblate pushes to its fork and opens PRs (review each
  one like any other PR; the em-dash and placeholder rules above are the
  review checklist)
- Language-code note: the repo uses Android's legacy `values-iw` for Hebrew
  and `values-zh-rTW` for Traditional Chinese; Weblate understands both, but
  check the mapping reads `iw -> he` and `zh-rTW -> zh_Hant` when the
  component is first created.

Adding a new string to the app: add it to the English base
(`values/strings.xml`) only, in the same commit as the feature. Weblate
picks it up on the next push and translators fill the locales; untranslated
strings fall back to English in the meantime. Hand-editing a
`values-<lang>` file directly is still fine (it merges like any other
change), just expect Weblate to own those files over time.
