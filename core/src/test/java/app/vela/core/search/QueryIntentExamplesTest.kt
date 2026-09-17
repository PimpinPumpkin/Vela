package app.vela.core.search

import app.vela.core.search.VoiceCommandExamples.Kind
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every phrase the in-app voice command list shows must parse to the command it is listed under. */
class QueryIntentExamplesTest {
    @Test fun `every listed phrase is understood`() {
        val bad = mutableListOf<String>()
        for (lang in VoiceCommandExamples.languages) {
            for (e in VoiceCommandExamples.forLanguage(lang)) {
                val got = QueryIntents.parse(e.phrase, lang)
                val ok = when (e.kind) {
                    Kind.HOME -> got == QueryIntent.Home
                    Kind.WORK -> got == QueryIntent.Work
                    Kind.GO -> got is QueryIntent.NavigateTo
                    Kind.ROUTE -> got is QueryIntent.Route
                    // A nearby search must have lost its filler, or it only "works" as plain text.
                    Kind.NEARBY -> got is QueryIntent.Search && got.query.length < e.phrase.length - 1
                    Kind.ETA -> got == QueryIntent.Eta
                }
                if (!ok) bad += "$lang ${e.kind} \"${e.phrase}\" -> $got"
            }
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun `every language lists every command it has`() {
        for (lang in VoiceCommandExamples.languages) {
            val kinds = VoiceCommandExamples.forLanguage(lang).map { it.kind }.toSet()
            assertTrue(lang, kinds.containsAll(listOf(Kind.HOME, Kind.WORK, Kind.GO, Kind.NEARBY, Kind.ETA)))
        }
    }
}
