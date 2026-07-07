package app.vela.core.data.google

import app.vela.core.data.google.parse.SearchParser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The open/closed boolean drives the status COLOUR (RatingStars.placeStatusColor paints
 * openNow==true green before it even looks at the text), so a false "open" literally paints a
 * closed place green — the field bug: a closed Starbucks rendered green ("the starbucks i am
 * looking at is closed but it shows green"). Two dead mechanisms, both pinned here:
 *
 * 1. `startsWith("Open")` swallowed "Opens 5 AM" (a CLOSED place's bare status) → open.
 * 2. The numeric "status codes" pinned from a French capture (6/5/13) turned out NOT to be
 *    open/closed codes at all — a live EN capture (2026-07-04) showed closed pharmacies carrying
 *    6 ("open") and an Open-24-hours business carrying 13/4 ("closed"). The code path is gone;
 *    the DISPLAYED TEXT, matched per request language, is authoritative.
 *
 * Invariant: open can only come from an AFFIRMATIVE match — no match → null, never open.
 */
class PlaceStatusTest {

    @Test fun `english - Opens-prefixed statuses are CLOSED, not swallowed by the Open prefix`() {
        assertEquals(false, SearchParser.parseOpenNow("Opens 5 AM", "en"))
        assertEquals(false, SearchParser.parseOpenNow("Opens soon ⋅ 5 AM", "en"))
        assertEquals(false, SearchParser.parseOpenNow("Opening soon", "en"))
    }

    @Test fun `english - genuinely-open statuses still read open`() {
        assertEquals(true, SearchParser.parseOpenNow("Open", "en"))
        assertEquals(true, SearchParser.parseOpenNow("Open 24 hours", "en"))
        assertEquals(true, SearchParser.parseOpenNow("Open ⋅ Closes 7 PM", "en"))
        assertEquals(true, SearchParser.parseOpenNow("Closes 9 PM", "en"))
        assertEquals(true, SearchParser.parseOpenNow("Closing soon", "en")) // closing LATER = open NOW
    }

    @Test fun `english - closed statuses and unknowns`() {
        assertEquals(false, SearchParser.parseOpenNow("Closed ⋅ Opens 7 AM", "en"))
        assertEquals(false, SearchParser.parseOpenNow("Closed", "en"))
        assertEquals(false, SearchParser.parseOpenNow("Temporarily closed", "en"))
        assertEquals(false, SearchParser.parseOpenNow("Permanently closed", "en"))
        assertEquals(null, SearchParser.parseOpenNow(null, "en"))
        assertEquals(null, SearchParser.parseOpenNow("  ", "en"))
        assertEquals(null, SearchParser.parseOpenNow("Hours may vary", "en")) // no affirmative signal → null
    }

    /** Owner-set temporary closure → first-class flag (the place-sheet banner + hours suppression;
     *  the "Tee Sud" resilience ask — when the owner DOES tell Google, Vela must be loud about it).
     *  Multilingual CONTAINS matching: several languages put the closed word first. */
    @Test fun `temporary closure is detected across languages, and only when present`() {
        assertEquals(true, SearchParser.isTemporarilyClosed("Temporarily closed"))
        assertEquals(true, SearchParser.isTemporarilyClosed(null, "Temporarily closed")) // any status slot
        assertEquals(true, SearchParser.isTemporarilyClosed("Fermé temporairement"))
        assertEquals(true, SearchParser.isTemporarilyClosed("Vorübergehend geschlossen"))
        assertEquals(true, SearchParser.isTemporarilyClosed("Временно закрыто"))
        assertEquals(false, SearchParser.isTemporarilyClosed("Closed ⋅ Opens 7 AM"))
        assertEquals(false, SearchParser.isTemporarilyClosed("Open ⋅ Closes 9 PM"))
        assertEquals(false, SearchParser.isTemporarilyClosed("Permanently closed"))
        assertEquals(false, SearchParser.isTemporarilyClosed(null, null))
    }

    @Test fun `localized - closed-first ordering disarms every prefix-cousin collision`() {
        // pt: "Fechado" (closed) is a PREFIX-EXTENSION of "Fecha" (closes → open) — closed wins.
        assertEquals(false, SearchParser.parseOpenNow("Fechado ⋅ Abre às 9:00", "pt"))
        assertEquals(true, SearchParser.parseOpenNow("Fecha às 19:00", "pt"))
        assertEquals(true, SearchParser.parseOpenNow("Aberto ⋅ Fecha às 19:00", "pt"))
        // nl: "Opent om 09:00" (opens later → closed) vs plain "Open".
        assertEquals(false, SearchParser.parseOpenNow("Opent om 09:00", "nl"))
        assertEquals(true, SearchParser.parseOpenNow("Open ⋅ Sluit om 19:00", "nl"))
        assertEquals(true, SearchParser.parseOpenNow("Geopend ⋅ Sluit om 19:00", "nl"))
        // fr: "Ouvre à 07:00" (closed) must not be swallowed by "Ouvert" (open) — and the accent
        // keeps "Fermé" (closed) distinct from "Ferme à" (closes → open).
        assertEquals(false, SearchParser.parseOpenNow("Ouvre à 07:00", "fr"))
        assertEquals(false, SearchParser.parseOpenNow("Fermé ⋅ Ouvre à 07:00", "fr"))
        assertEquals(true, SearchParser.parseOpenNow("Ouvert ⋅ Ferme à 19:00", "fr"))
        assertEquals(true, SearchParser.parseOpenNow("Ferme bientôt", "fr")) // closing soon = open now
    }

    @Test fun `localized - each supported language classifies its canonical forms`() {
        assertEquals(true, SearchParser.parseOpenNow("Geöffnet ⋅ Schließt um 19:00", "de"))
        assertEquals(false, SearchParser.parseOpenNow("Geschlossen ⋅ Öffnet um 09:00", "de"))
        assertEquals(true, SearchParser.parseOpenNow("Abierto ⋅ Cierra a las 19:00", "es"))
        assertEquals(false, SearchParser.parseOpenNow("Cerrado ⋅ Abre a las 9:00", "es"))
        assertEquals(true, SearchParser.parseOpenNow("Aperto ⋅ Chiude alle 19:00", "it"))
        assertEquals(false, SearchParser.parseOpenNow("Chiuso ⋅ Apre alle 9:00", "it"))
        assertEquals(true, SearchParser.parseOpenNow("Открыто ⋅ Закроется в 19:00", "ru"))
        assertEquals(false, SearchParser.parseOpenNow("Закрыто ⋅ Откроется в 09:00", "ru"))
        assertEquals(true, SearchParser.parseOpenNow("Otwarte ⋅ Zamknięcie o 19:00", "pl"))
        assertEquals(false, SearchParser.parseOpenNow("Zamknięte ⋅ Otwarcie o 09:00", "pl"))
        assertEquals(true, SearchParser.parseOpenNow("Öppet ⋅ Stänger 19:00", "sv"))
        assertEquals(false, SearchParser.parseOpenNow("Stängt ⋅ Öppnar 09:00", "sv"))
        assertEquals(true, SearchParser.parseOpenNow("Відчинено ⋅ Зачиняється о 19:00", "uk"))
        assertEquals(false, SearchParser.parseOpenNow("Зачинено ⋅ Відчиниться о 09:00", "uk"))
    }

    @Test fun `unknown language falls back to the english table, never to open`() {
        assertEquals(true, SearchParser.parseOpenNow("Open ⋅ Closes 7 PM", "ja"))
        assertEquals(null, SearchParser.parseOpenNow("営業中", "ja")) // untranslated language → null, not a guess
    }

    /** STATUS_LANGS gates GoogleMapsDataSource.localized()'s hl= rewrite: the scrape may only ask
     *  Google for status text in a language parseOpenNow can read, else openNow is always null and
     *  the UI can't colour open/closed. It MUST equal the shipped keyword-table languages (11). */
    @Test fun `STATUS_LANGS covers exactly the shipped status-table languages`() {
        val expected = setOf("en", "fr", "de", "es", "it", "pt", "nl", "ru", "pl", "sv", "uk")
        assertEquals(expected, SearchParser.STATUS_LANGS)
    }
}
