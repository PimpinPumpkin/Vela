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
| Chinese (Simplified) | `zh` | ✅ | ✅ | ✅ (Mandarin voice) | ✅ |
| Chinese (Traditional) | `zh-TW` | ✅ | ✅ | ✅ (shares the Mandarin voice) | ✅ |
| Japanese | `ja` | ✅ | ✅ | ❌ system TTS* | ✅ |
| Hebrew | `he` | ✅ (first RTL locale) | ✅ | ❌ system TTS* | ✅ |

\* Piper/espeak-ng has no Japanese or Hebrew phonemizer, so there's no downloadable Vela
voice for those two. Spoken directions route to the phone's own system TTS in that
language instead; if the system has no such voice either, nav stays silent rather than
mangling it, and a hint points at the voice settings.

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
- **Dictation** - the search-bar mic transcribes on-device with a multilingual Whisper
  model, pinned to the app language for all fifteen.

The language picker is Settings → Language ("Follow system" by default, or any of the
fifteen by endonym).

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
5. Optional: a Piper voice for the language in `PiperCatalog` if one exists upstream.

A handful of literals are deliberately still English-only because they double as logic
keys (the review sort/tab labels, the search-along-route chip queries); those localize
when display text gets decoupled from the key.

## Weblate

The plan is to move translation upkeep to Weblate so native speakers can maintain their
languages without touching Kotlin. The `values-<code>/strings.xml` layout, the plurals
usage, and the placeholder-parity CI check above are the prerequisites and are already in
place; what remains is standing up the Weblate project and pointing it at the repo. Until
then, string additions ship with translations for all fifteen languages in the same PR
(see CONTRIBUTING).
