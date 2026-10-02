# Translating Vela

Vela ships in 15 languages (the canonical layer-by-layer table is in
[LANGUAGES.md](LANGUAGES.md)). Translations are community-maintained, and they
happen on Weblate:

**<https://hosted.weblate.org/engage/vela-maps/>**

## Translate on Weblate

1. Open the link above and sign in (an email account or a GitHub login both
   work).
2. Pick your language and translate in the browser. Weblate shows the English
   original next to your text and warns about a missing placeholder as you
   type. No git client and no Android toolchain needed.
3. Save. Weblate sends saved translations to the repo as a pull request, and
   once a maintainer merges it they ship in the next build. You keep commit
   credit for your strings.

## Or translate by pull request

The older flow still works, and suits a one-line fix:

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

Missing your language entirely? Start it on Weblate, or open an issue and say
which one. A new language needs the UI strings first; spoken directions and the
open/closed keyword table are separate layers a maintainer wires up afterwards
(see below, and the full checklist under "Adding a language" in
[LANGUAGES.md](LANGUAGES.md)).

## What lives where

The UI strings above are the layer that is open to everyone. The rest is code
or config and changes through pull requests too, but needs a maintainer:

| Layer | Where | How to change |
|---|---|---|
| App UI strings (about 1,180) | `app/src/main/res/values-<lang>/strings.xml` | Weblate, or a PR (both flows above) |
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
  Weblate shows the right set for your language on its own.
- **No em dashes.** Use a comma, a colon, or rephrase. The one legitimate
  dash is a numeric range. (House style across the whole repo.)
- **Escape apostrophes** as `\'` when you edit strings.xml by hand. A raw one
  fails the release build even when a debug build passes. On Weblate, type a
  plain apostrophe: it does the escaping.
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

## For maintainers: the Weblate component

The project is <https://hosted.weblate.org/projects/vela-maps/>, on hosted
Weblate's libre plan (free for open source, subject to their approval). One
component, `app`, covers the app:

- Repo: `https://github.com/PimpinPumpkin/Vela`, branch `main`
- File mask: `app/src/main/res/values-*/strings.xml`
- Monolingual base: `app/src/main/res/values/strings.xml`
- Format: Android string resources; license GPL-3.0-or-later
- Language codes: the repo uses Android's legacy `values-iw` for Hebrew and
  `values-zh-rTW` for Traditional Chinese. Weblate reads them as `he` and
  `zh_Hant` (checked when the component was created) and writes back to the
  same folders.
- A second component, the glossary, was created by Weblate and lives only
  there.

Both directions are automatic. A GitHub webhook on the repo (push events, to
`https://hosted.weblate.org/hooks/github/`) tells Weblate about new strings.
Going the other way, the component's version control is set to GitHub pull
requests with no push URL: Weblate commits translations to its own copy of the
repo (readable at `https://hosted.weblate.org/git/vela-maps/app/`), pushes them
to a fork under its own GitHub account and opens a pull request against `main`.
It holds no write access to this repo. Review that pull request like any
other: the em-dash and placeholder rules above are the checklist, and
`tools/check-translations.py` runs in CI. Translators cannot edit the English
base file or add and remove keys from Weblate; both are switched off.

Adding a new string to the app: add it to the English base
(`values/strings.xml`) only, in the same commit as the feature. Translators
fill the locales on Weblate (or by pull request); untranslated strings fall
back to English in the meantime, and `python3 tools/check-translations.py`
prints what each language is missing. Hand-editing a `values-<lang>` file
directly is still fine, but Weblate may hold unmerged changes to the same
file, so merge its open pull request first when one is waiting.
