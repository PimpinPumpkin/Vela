# Translating Vela

Vela ships in 15 languages (the canonical layer-by-layer table is in
[LANGUAGES.md](LANGUAGES.md)). Translations are community-maintained, and today
they come in as ordinary pull requests.

> **Weblate is not up yet.** Hosted Weblate is free for open-source projects once a
> project is at least three months old. Vela passed that mark on 2026-09-15, but the
> application has not been made, so there is no Weblate project to sign in to. Please
> use the pull-request flow below. This page gets rewritten the day Weblate is running.

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
(see below, and the full checklist under "Adding a language" in
[LANGUAGES.md](LANGUAGES.md)).

## What lives where

The UI strings above are the layer that is open to everyone. The rest is code
or config and changes through pull requests too, but needs a maintainer:

| Layer | Where | How to change |
|---|---|---|
| App UI strings (about 990) | `app/src/main/res/values-<lang>/strings.xml` | PR (the flow above) |
| Spoken turn-by-turn | `core/src/main/java/app/vela/core/i18n/NavStrings.kt` (one table per language) | PR, needs native review |
| Open/closed status keywords | compiled tables in `SearchParser`; `calibration.json` can override them (`statusClosedWords`/`statusOpenWords`) | PR, or a signed calibration push for a hot fix |
| Transit-category words | `calibration.json` (`transitCategoryWords`, `transitExcludeWords`), with a compiled fallback | PR plus a signed calibration push |
| Voice commands ("take me home") | `core/.../search/QueryIntent.kt`, with the phrases Settings shows in `VoiceCommandExamples.kt` | PR, needs native review |
| Review page labels | `core/.../data/ReviewWords.kt` (captured from Google's own page in that language) | PR |
| Neural voice | Piper voice catalog (`PiperCatalog`) | depends on an upstream Piper voice existing |

## Rules that keep translations shippable

- **Placeholders must match the English set.** `%1$s` stays a string and
  `%1$d` stays a number; you may move them around the sentence, but keep every
  one. A `%d` handed a word crashes the app the moment that string is shown, so
  CI (`tools/check-translations.py`) fails any pull request whose placeholders
  differ from English.
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
as logic keys (the "Open"/"Closed" word on a status line Vela works out from
the hours itself feeds the status coloring). They stay inline in code until
display text is split from the key, so don't be surprised if one is missing
from strings.xml. The category chips used to be in this group; their labels
are translatable now.

## For maintainers: the Weblate component (once the project is accepted)

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
(`values/strings.xml`) only, in the same commit as the feature. Translators
fill the locales by pull request today (by Weblate once it runs);
untranslated strings fall back to English in the meantime, and
`python3 tools/check-translations.py` prints what each language is missing.
Hand-editing a `values-<lang>` file directly is still fine (it merges like
any other change), just expect Weblate to own those files once it is set up.
