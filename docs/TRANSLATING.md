# Translating Vela

Vela ships in 15 languages (the canonical layer-by-layer table is in
[LANGUAGES.md](LANGUAGES.md)). Translations are community-maintained through
**Weblate**, a web editor where you can translate without touching git at all.

## Translate on Weblate

Project: https://hosted.weblate.org/projects/vela-maps/

1. Make a free account (GitHub sign-in works).
2. Pick your language and start suggesting or translating strings.
3. Weblate batches the work and opens a pull request against this repo; a
   maintainer reviews and merges it. You get commit credit for your strings.

Missing your language entirely? Open the project page and hit "Start new
translation", or open an issue here. A new language needs the UI strings
first; spoken directions and the open/closed keyword table are separate
layers a maintainer wires up afterwards (see below).

## What lives where

Only the first layer is on Weblate. The rest is code or config and changes
through normal pull requests:

| Layer | Where | How to change |
|---|---|---|
| App UI strings (~350) | `app/src/main/res/values-<lang>/strings.xml` | Weblate |
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
  `one`/`two`/`many`/`other`; Chinese and Japanese only `other`. Weblate
  shows the right set automatically.
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
