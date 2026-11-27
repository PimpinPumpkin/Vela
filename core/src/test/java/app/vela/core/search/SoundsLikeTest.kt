package app.vela.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SoundsLikeTest {
    // Streets around central Berlin.
    private val berlin = listOf(
        "Kurfürstendamm", "Friedrichstraße", "Unter den Linden", "Karl-Marx-Allee", "Kottbusser Damm", "Alexanderplatz",
    )

    private fun de(heard: String, streets: List<String> = berlin) = SoundsLike.rewrite(heard, "de", streets)

    @Test fun `codes follow Koelner Phonetik`() {
        // The published examples of the algorithm.
        assertEquals("65752682", SoundsLike.code("Müller-Lüdenscheidt"))
        assertEquals("3412", SoundsLike.code("Wikipedia"))
        assertEquals("17863", SoundsLike.code("Breschnew"))
        // d and t before s sound like s; the vowels drop out.
        assertEquals(SoundsLike.code("Leopoldstraße"), SoundsLike.code("Leo polt strasse"))
        assertEquals(SoundsLike.code("Kottbusser Damm"), SoundsLike.code("Kotbüsser dam"))
    }

    @Test fun `a compound heard as separate words is the one street`() {
        assertEquals("Kurfürstendamm", de("Kur fürsten damm"))
        assertEquals("Kurfürstendamm", de("Kurfürsten Damm"))
        assertEquals("Friedrichstraße", de("Friedrich strasse"))
        assertEquals("Unter den Linden", de("Unter denn Linden"))
    }

    @Test fun `a name spelled by sound matches`() {
        assertEquals("Kottbusser Damm", de("Kotbüsser dam"))
        assertEquals("Leopoldstraße", de("Leo polt strasse", listOf("Leopoldstraße", "Ludwigstraße")))
        assertEquals("Karl-Marx-Allee", de("Karl Marks Allee"))
    }

    @Test fun `a name heard with an umlaut the street does not have is put right`() {
        assertEquals("Horsterdamm", de("Hörsterdamm", listOf("Horsterdamm", "Hauptstraße")))
        assertEquals("Horsterdamm", de("Hoerstär damm", listOf("Horsterdamm", "Hauptstraße")))
        assertEquals(null, de("Horsterdamm", listOf("Horsterdamm", "Hauptstraße")))
    }

    @Test fun `the whole transcript is tried when it is longer than a run`() {
        assertEquals("Kurfürstendamm", de("Kur für sten damm"))
    }

    @Test fun `words outside the street stay where they were`() {
        assertEquals("Kurfürstendamm 21", de("Kurfürsten Damm 21"))
        assertEquals("Friedrichstraße in Berlin", de("Friedrich strasse in Berlin"))
        assertEquals("navigiere zur Friedrichstraße", de("navigiere zur Friedrich strasse"))
    }

    @Test fun `a street with a different code is left alone`() {
        assertNull(de("Potsdamer Platz"))
        assertNull(de("Karl Marks Platz"))
    }

    @Test fun `a short word or a short street never matches`() {
        assertNull(de("Dam", listOf("Damm", "Dom")))
        assertNull(de("Tam"))
        assertNull(de("Am See", listOf("Amsee")))
    }

    @Test fun `two streets that sound the same and are spelled as close give nothing`() {
        assertNull(de("Bech strasse", listOf("Bachstraße", "Buchstraße")))
        // Each of two runs names a different street equally well.
        assertNull(de("Bach strasse Buch strasse", listOf("Bachstraße", "Buchstraße")))
    }

    @Test fun `the heard letters exactly win over a near spelling`() {
        assertEquals("Bachstraße", de("Bach strasse", listOf("Bachstraße", "Buchstraße")))
    }

    @Test fun `words that already name a street are not rewritten`() {
        assertNull(de("Bachstraße", listOf("Bachstraße", "Buchstraße")))
        assertNull(de("Kurfürstendamm"))
    }

    @Test fun `other Latin-script languages use the same code`() {
        val sf = listOf("Geary Boulevard", "Market Street", "Mission Street", "Valencia Street")
        assertEquals("Geary Boulevard", SoundsLike.rewrite("Gary Boulevard", "en", sf))
        // Joined, a t before an s codes as s, so a vowel heard between them changes the code.
        assertNull(SoundsLike.rewrite("Marquette Street", "en", sf))
    }

    @Test fun `a language in another script gets no rewrite`() {
        assertNull(SoundsLike.rewrite("Kur fürsten damm", "ru", berlin))
        assertNull(de("Курфюрстендамм"))
    }

    @Test fun `the whole reading takes a street said alone, with a house number`() {
        val near = listOf("Kurfürstendamm", "Friedrichstraße", "Unter den Linden")
        assertEquals("Kurfürstendamm", SoundsLike.rewrite("Kur fürsten damm", "de", near, whole = true))
        assertEquals("Kurfürstendamm 21", SoundsLike.rewrite("Kur fürsten damm 21", "de", near, whole = true))
    }

    @Test fun `the whole reading leaves a street inside a longer query alone`() {
        val near = listOf("Kurfürstendamm", "Friedrichstraße")
        assertNull(SoundsLike.rewrite("Pizza am Kur fürsten damm", "de", near, whole = true))
        assertNull(SoundsLike.rewrite("navigiere zur Friedrich strasse", "de", near, whole = true))
    }
}
