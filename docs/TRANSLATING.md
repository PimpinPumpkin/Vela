# Languages and translating

Vela's interface is offered in 18 languages. Translations are done by the community on
Weblate:

**<https://hosted.weblate.org/engage/vela-maps/>**

## What works in each language

The interface, the spoken directions, the downloadable neural voice and on-device dictation
are separate systems, so support differs by language.

| Language | Code | App text | Spoken directions | Vela voice | Dictation |
|---|---|:-:|:-:|:-:|:-:|
| English | `en` | yes | yes | yes (US and British) | yes |
| French | `fr` | yes | yes | yes | yes |
| German | `de` | yes | yes | yes | yes |
| Spanish | `es` | yes | yes | yes (Spain and Mexico) | yes |
| Italian | `it` | yes | yes | yes | yes |
| Portuguese (Portugal) | `pt` | yes | yes | yes (Brazilian voice) | yes |
| Portuguese (Brazil) | `pt-BR` | yes | yes (the Portuguese table) | yes | yes |
| Dutch | `nl` | yes | yes | yes | yes |
| Russian | `ru` | yes | yes | yes | yes |
| Polish | `pl` | yes | yes | yes | yes |
| Swedish | `sv` | yes | yes | yes | yes |
| Ukrainian | `uk` | yes | yes | yes | yes |
| Hungarian | `hu` | yes | yes | yes | yes |
| Estonian | `et` | in progress | English | system voice | yes |
| Chinese (Simplified) | `zh` | yes | yes | yes (Mandarin) | yes |
| Chinese (Traditional) | `zh-TW` | yes | yes | yes (the Mandarin voice) | yes |
| Japanese | `ja` | yes | yes | system voice | yes |
| Hebrew | `he` | yes | yes | system voice | yes |

- **App text** means the language has its own string file. A string nobody has translated yet
  shows in English. Categories, hours and open or closed lines from Google arrive in the app
  language too. Place names, street names and reviews are data and are never translated.
- **Spoken directions** are built from grammar templates per language (`NavStrings` in
  `:core`), so cases and word order are right.
- **Vela voice** is the downloadable neural voice (Piper). There is no Piper voice for
  Japanese, Hebrew or Estonian, so those use the phone's own text-to-speech in that language.
  If the phone has none, navigation stays silent and a hint points at the voice settings.
- **Dictation** runs on the phone. The default model is multilingual Whisper, set to the app
  language. Settings > Search offers SenseVoice (English, Chinese, Japanese, Korean,
  Cantonese) and Moonshine (English only) as alternatives.

The language setting is in Settings > Appearance. "Follow system language" is on by default.

Hungarian was contributed by Zsolt Laszlo Kaiser, Brazilian Portuguese by Netocon, and
Estonian was started by Priit Jõerüüt.

## Translate on Weblate

1. Open the link above and sign in (email or a GitHub login).
2. Pick your language and translate in the browser. Weblate shows the English next to your
   text and warns about a missing placeholder as you type.
3. Save. Weblate sends saved translations to the repo as a pull request under your name. A
   pull request that only changes translations, keeps every placeholder and passes the build
   merges by itself. Anything else waits for the maintainer.

A partial translation is useful: what is not translated falls back to English.

Missing your language? Start it on Weblate, or open an issue. A new language needs the
interface strings first. Spoken directions and the word tables below are added by a
maintainer afterwards.

## Or by pull request

For a one-line fix: edit `app/src/main/res/values-<lang>/strings.xml` (for example
`values-de`, or `values-zh-rTW` for Traditional Chinese) in GitHub's web editor and open a
pull request. The English original is `app/src/main/res/values/strings.xml`.

## Rules

- **Placeholders match the English set.** `%1$s` stays a string and `%1$d` a number. Move them
  around the sentence, but keep every one. A `%d` handed a word crashes the app, so
  `tools/check-translations.py` fails a pull request whose placeholders differ.
- **Plurals need your language's categories.** Russian, Ukrainian and Polish need `one`,
  `few`, `many`, `other`; Hebrew `one`, `two`, `many`, `other`; Chinese and Japanese only
  `other`. Weblate shows the right set.
- **No em dashes.** Use a comma or a colon, or rephrase. A numeric range is the one exception.
- **Escape apostrophes** as `\'` when editing the file by hand. On Weblate, type a plain one.
- **Never translate data:** place names, street names, reviews.
- **Keep it short.** These strings sit on chips, rows and buttons.

One word is not translatable yet: the "Open" / "Closed" on a status line Vela computes from
the hours itself, which the status coloring reads.

## What lives where

| Layer | Where | How to change |
|---|---|---|
| App text | `app/src/main/res/values-<lang>/strings.xml` | Weblate, or a pull request |
| Spoken directions | `core/src/main/java/app/vela/core/i18n/NavStrings.kt`, one table per language | Pull request, needs a native reviewer |
| Open and closed keywords | tables in `SearchParser`; `calibration.json` can override (`statusClosedWords`, `statusOpenWords`) | Pull request, or a signed calibration push |
| Transit category words | `calibration.json` (`transitCategoryWords`, `transitExcludeWords`), with a compiled fallback | Pull request plus a calibration push |
| Voice commands | `core/.../search/QueryIntent.kt`, examples in `VoiceCommandExamples.kt` | Pull request, needs a native reviewer |
| Review page labels | `core/.../data/ReviewWords.kt`, captured from Google's page in that language | Pull request |
| Generic business words | `core/.../util/PlaceNames.kt`, mirrored in `tools/place-generic-words.txt` | Pull request |
| Neural voice | `PiperCatalog` | Needs a Piper voice to exist upstream |

## Adding a language (maintainers)

1. `values-<code>/strings.xml`, translated from the English file.
2. `AppLocale.SUPPORTED` and its name map (`app/ui/AppLocale.kt`). This puts the language in
   the picker and everywhere the app language flows.
3. A `NavStrings` table in `core/i18n/`.
4. The open and closed keyword table, and the transit category words.
5. The other word tables in the list above.
6. A Piper voice in `PiperCatalog`, if one exists.

`QueryIntentTest`, `PlaceStatusTest` and `SpokenRoadNamesTest` each hold a list of languages.
Add the new code to each and run `./gradlew :core:test`.

## The Weblate component (maintainers)

The project is <https://hosted.weblate.org/projects/vela-maps/>, on hosted Weblate's free plan
for open source. One component, `app`:

- Repo `https://github.com/PimpinPumpkin/Vela`, branch `main`
- File mask `app/src/main/res/values-*/strings.xml`, base file `values/strings.xml`
- Format: Android string resources. License GPL-3.0-or-later.
- The repo uses Android's `values-iw` for Hebrew and `values-zh-rTW` for Traditional Chinese.
  Weblate reads them as `he` and `zh_Hant` and writes back to the same folders.

A GitHub webhook (push events, to `https://hosted.weblate.org/hooks/github/`) tells Weblate
about new strings. Going the other way, Weblate commits translations to its own copy of the
repo, pushes them to a fork under its own GitHub account and opens a pull request against
`main`. It has no write access to this repo. Translators cannot edit the English file or add
and remove keys.

`weblate-automerge.yml` merges such a pull request after CI passes, when
`scripts/weblate-automerge-check.py` finds that it comes from Weblate's account and fork,
touches only `values-<lang>/strings.xml`, uses only keys that exist in English, keeps every
placeholder, and adds no web address, link or em dash. The repository variable
`WEBLATE_AUTOMERGE=off` switches it off. It cannot judge what a translation says.

New strings go into the English file only, in the same commit as the feature.
`python3 tools/check-translations.py` prints what each language is missing. Before editing a
`values-<lang>` file by hand, merge Weblate's open pull request if one is waiting.
