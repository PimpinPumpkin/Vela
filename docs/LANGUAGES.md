# Languages

What Vela speaks, layer by layer. The UI, the spoken turn-by-turn, the neural voice, and
on-device dictation are separate systems, so support differs per language - this table is
the canonical list. Update it when a language lands or gains a layer.

| Language | Code | App UI | Spoken directions | Vela voice (neural TTS) | Dictation (voice search) |
|---|---|:-:|:-:|:-:|:-:|
| English | `en` | ✅ | ✅ | ✅ (US + British voices) | ✅ |
| French | `fr` | ✅ | ✅ | ✅ | ✅ |
| German | `de` | ✅ | ✅ | ✅ | ✅ |
| Spanish | `es` | ✅ | ✅ | ✅ (Spain + Mexico voices) | ✅ |
| Italian | `it` | ✅ | ✅ | ✅ | ✅ |
| Portuguese | `pt` | ✅ | ✅ | ✅ (Brazilian voice) | ✅ |
| Dutch | `nl` | ✅ | ✅ | ✅ | ✅ |
| Russian | `ru` | ✅ | ✅ | ✅ | ✅ |
| Polish | `pl` | ✅ | ✅ | ✅ | ✅ |
| Swedish | `sv` | ✅ | ✅ | ✅ | ✅ |
| Ukrainian | `uk` | ✅ | ✅ | ✅ | ✅ |
| Hungarian | `hu` | ✅ (contributed by Zsolt Laszlo Kaiser, 2026-09-13) | ✅ | ✅ (Anna) | ✅ |
| Chinese (Simplified) | `zh` | ✅ | ✅ | ✅ (Mandarin voice) | ✅ |
| Chinese (Traditional) | `zh-TW` | ✅ | ✅ | ✅ (shares the Mandarin voice) | ✅ |
| Japanese | `ja` | ✅ | ✅ | ❌ system TTS* | ✅ |
| Hebrew | `he` (resources in `values-iw`) | ✅ (first RTL locale) | ✅ | ❌ system TTS* | ✅ |

\* Piper/espeak-ng has no Japanese or Hebrew phonemizer, so there's no downloadable Vela
voice for those two. Spoken directions route to the phone's own system TTS in that
language instead; if the system has no such voice either, nav stays silent rather than
mangling it, and a hint points at the voice settings.

The App UI column means the language has its own string file, not that every string in it is
translated. New features land in English first and show in English until someone fills them in; all
fifteen files were brought fully up to date with English on 2026-09-25 (about 115 strings each,
translated per language to match each file's own register and terms). `python3 tools/check-translations.py` lists the missing
keys per language (and fails only on placeholder drift).

Some context on the columns:

- **App UI** - every user-facing string, from Settings to the nav banner to the
  foreground-service notification. Google place content (categories, hours, open/closed)
  also arrives localized, since the scrape's `hl=` follows the app language. Place NAMES,
  street names and reviews are data and are never translated.
- **Spoken directions** - generated from per-language grammar templates (`NavStrings` in
  `:core`), not word-swapped, so cases and word order are right. Distances follow the
  imperial/metric setting.
- **Vela voice** - the downloadable on-device neural voice (Piper). The voice library
  pairs the app language to a matching voice and nudges a download if you switch to a
  language whose voice isn't installed.
- **Dictation** - the search-bar mic transcribes on-device. The default is a multilingual Whisper
  model, pinned to the app language for every language in the table. Settings → Search also
  offers two alternative engines you can download and switch to: **SenseVoice** (English,
  Chinese, Japanese, Korean, Cantonese) and **Moonshine** (English only) - faster/more accurate
  for those languages, but Whisper stays the default so nothing regresses.

The language setting is in Settings → Appearance: "Follow system language" is on by default,
and turning it off shows a picker with English and the fifteen translations, each under its own
name (Deutsch, 日本語, עברית and so on).

## Adding a language

The moving parts, in the order they matter:

1. `app/src/main/res/values-<code>/strings.xml` - the full UI string set (translated from
   `values/strings.xml`, the English source). Placeholder types must match English
   exactly; CI validates this. Count-bearing strings are `<plurals>` - give the language
   the plural forms it actually needs.
2. `AppLocale.SUPPORTED` + its endonym map (`app/ui/AppLocale.kt`) - registers the
   language in the picker and everywhere the app language flows (dictation pinning, the
   `hl=` scrape parameter, voice pairing).
3. A `NavStrings` table in `core/i18n/` - the spoken-direction grammar templates.
4. The open/closed status keyword table (`SearchParser`) and the transit-category words
   (calibration) - the two spots that match localized Google TEXT to make a decision.
5. The other per-language word tables, each a plain map in `:core`: the voice-command
   vocabulary (`search/QueryIntent`) and its example phrases for Settings > Search
   (`search/VoiceCommandExamples`), the review page's labels (`data/ReviewWords`), and the
   generic business words the same-business rule ignores (`util/PlaceNames`, whose union is
   mirrored in `tools/place-generic-words.txt` for the places bake).
6. Optional: a Piper voice for the language in `PiperCatalog` if one exists upstream.

`QueryIntentTest` compares the voice-command tables with the app's language list and fails
when one is missing. `PlaceStatusTest` (the status-table languages) and `SpokenRoadNamesTest`
(every nav table) pin their own lists, so add the new code there too, then run
`./gradlew :core:test`.

One literal is deliberately still English-only because it doubles as a logic key: the
"Open"/"Closed" word on a status line Vela computes itself from the hours (used only when
Google sent no status text), which the status coloring parses. It localizes once display
text is split from that key. The category chips and the review sort and tab labels used to
be in this list; their labels are translated now and only the query or click key behind
them stays English.

## Weblate

Not live yet, and there is no Weblate project to sign in to. Hosted Weblate is free for open
source once a project is three months old; Vela passed that mark on 2026-09-15, so the only
thing missing is the application itself, which has not been made. Until a project is accepted,
translations come in as ordinary pull requests: see [TRANSLATING.md](TRANSLATING.md). New
strings go into the English base file only, and a missing translation falls back to English.
The `values-<code>/strings.xml` layout, the plurals and the placeholder check in CI are what
Weblate needs, and all three are already in place.
