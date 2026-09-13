package app.vela.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The shapes people said to the mic in discussion #365, in English and French, plus the ones
 *  that must stay plain searches so a business name never turns into a command. */
class QueryIntentTest {
    private fun en(s: String) = QueryIntents.parse(s, "en")
    private fun fr(s: String) = QueryIntents.parse(s, "fr")

    @Test fun `home and work, with or without a verb`() {
        assertEquals(QueryIntent.Home, en("Bring me home"))
        assertEquals(QueryIntent.Home, en("take me home please"))
        assertEquals(QueryIntent.Home, en("home"))
        assertEquals(QueryIntent.Work, en("Get me to work"))
        assertEquals(QueryIntent.Work, en("navigate to my office"))
        assertEquals(QueryIntent.Home, fr("Emmène-moi à la maison"))
        assertEquals(QueryIntent.Work, fr("va au travail"))
        assertEquals(QueryIntent.Home, fr("chez moi"))
    }

    @Test fun `navigate to a place`() {
        assertEquals(QueryIntent.NavigateTo("555 xyz street, example city"), en("Go to 555 XYZ street, example city"))
        assertEquals(QueryIntent.NavigateTo("the eiffel tower"), en("find the fastest route to the Eiffel Tower"))
        assertEquals(QueryIntent.NavigateTo("la tour eiffel"), fr("Itinéraire vers la tour Eiffel"))
        assertEquals(QueryIntent.NavigateTo("la gare"), fr("emmène-moi à la gare"))
    }

    @Test fun `nearby searches lose their filler`() {
        assertEquals(QueryIntent.Search("italian restaurant"), en("Where is the nearest Italian restaurant near me?"))
        assertEquals(QueryIntent.Search("gas station"), en("gas station near me"))
        assertEquals(QueryIntent.Search("pharmacy"), en("find a pharmacy"))
        assertEquals(QueryIntent.Search("boulangerie"), fr("où est la boulangerie la plus proche"))
        assertEquals(QueryIntent.Search("pharmacie"), fr("pharmacie près de moi"))
    }

    @Test fun `eta questions`() {
        assertEquals(QueryIntent.Eta, en("What is my ETA?"))
        assertEquals(QueryIntent.Eta, en("how much longer"))
        assertEquals(QueryIntent.Eta, fr("Dans combien de temps j'arrive"))
    }

    @Test fun `a to b routes`() {
        assertEquals(QueryIntent.Route("davis", "san francisco"), en("Davis to San Francisco"))
        assertEquals(QueryIntent.Route("davis", "san francisco"), en("from Davis to San Francisco"))
        assertEquals(QueryIntent.Route("paris", "lyon"), fr("de Paris à Lyon"))
        assertEquals(QueryIntent.Route("the airport", "downtown"), en("navigate from the airport to downtown"))
    }

    @Test fun `plain queries stay plain`() {
        assertNull(en("Mikuni"))
        assertNull(en("coffee"))
        assertNull(en("1451 W Covell Blvd"))
        assertNull(en("where to eat"))
        assertNull(en("to go"))
        assertNull(en("Home Depot"))
        assertNull(fr("Boulangerie Paul"))
    }

    @Test fun `english works as a fallback in any language`() {
        assertEquals(QueryIntent.Home, QueryIntents.parse("take me home", "ja"))
        assertEquals(QueryIntent.NavigateTo("the station"), QueryIntents.parse("navigate to the station", "zh-TW"))
        assertNull(QueryIntents.parse("駅", "ja"))
    }
}
