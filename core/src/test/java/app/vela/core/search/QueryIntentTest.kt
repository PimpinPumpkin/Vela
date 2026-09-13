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

    private fun p(s: String, l: String) = QueryIntents.parse(s, l)

    @Test fun `every app language has a command vocabulary`() {
        val app = setOf("en", "fr", "de", "es", "it", "pt", "nl", "ru", "pl", "sv", "uk", "hu", "zh", "ja", "he")
        assertEquals(emptySet<String>(), app - QueryIntents.supportedLanguages)
    }

    @Test fun `russian ukrainian polish swedish hungarian`() {
        assertEquals(QueryIntent.Home, p("отвези меня домой", "ru"))
        assertEquals(QueryIntent.NavigateTo("вокзала"), p("проложи маршрут до вокзала", "ru"))
        assertEquals(QueryIntent.Search("аптека"), p("где ближайшая аптека", "ru"))
        assertEquals(QueryIntent.Eta, p("сколько осталось", "ru"))
        assertNull(p("кафе в центре", "ru"))
        assertEquals(QueryIntent.Route("москвы", "казань"), p("из Москвы в Казань", "ru"))
        assertEquals(QueryIntent.Work, p("на роботу", "uk"))
        assertEquals(QueryIntent.Search("аптека"), p("де найближча аптека", "uk"))
        assertEquals(QueryIntent.Home, p("zawieź mnie do domu", "pl"))
        assertEquals(QueryIntent.NavigateTo("dworca"), p("nawiguj do dworca", "pl"))
        assertEquals(QueryIntent.Eta, p("ile jeszcze", "pl"))
        assertEquals(QueryIntent.Home, p("ta mig hem", "sv"))
        assertEquals(QueryIntent.Route("stockholm", "uppsala"), p("från Stockholm till Uppsala", "sv"))
        assertEquals(QueryIntent.Home, p("vigyél haza", "hu"))
        assertEquals(QueryIntent.Search("gyógyszertár"), p("hol van a legközelebbi gyógyszertár", "hu"))
        assertEquals(QueryIntent.Eta, p("mikor érkezem", "hu"))
    }

    @Test fun `hebrew`() {
        assertEquals(QueryIntent.Home, p("קח אותי הביתה", "he"))
        assertEquals(QueryIntent.Work, p("לעבודה", "he"))
        assertEquals(QueryIntent.NavigateTo("תחנה המרכזית"), p("נווט ל תחנה המרכזית", "he"))
        assertEquals(QueryIntent.Search("בית מרקחת"), p("איפה יש בית מרקחת קרוב אליי", "he"))
        assertEquals(QueryIntent.Eta, p("מתי אגיע", "he"))
        assertEquals(QueryIntent.Home, p("take me home", "iw"))
    }

    @Test fun `chinese and japanese without spaces`() {
        assertEquals(QueryIntent.Home, p("带我回家", "zh"))
        assertEquals(QueryIntent.Home, p("回家", "zh-TW"))
        assertEquals(QueryIntent.Work, p("去公司", "zh"))
        assertEquals(QueryIntent.NavigateTo("火车站"), p("导航到火车站", "zh"))
        assertEquals(QueryIntent.NavigateTo("台北車站"), p("帶我去台北車站", "zh-TW"))
        assertEquals(QueryIntent.Search("加油站"), p("附近的加油站", "zh"))
        assertEquals(QueryIntent.Search("药店"), p("最近的药店在哪里", "zh"))
        assertEquals(QueryIntent.Eta, p("还有多久", "zh"))
        assertEquals(QueryIntent.Route("北京", "上海"), p("从北京到上海", "zh"))
        assertEquals(QueryIntent.Route("北京", "上海"), p("北京到上海", "zh"))
        assertEquals(QueryIntent.Home, p("家に帰る", "ja"))
        assertEquals(QueryIntent.Work, p("会社に行く", "ja"))
        assertEquals(QueryIntent.NavigateTo("東京駅"), p("東京駅へ行きたい", "ja"))
        assertEquals(QueryIntent.NavigateTo("東京駅"), p("東京駅まで案内して", "ja"))
        assertEquals(QueryIntent.Search("コンビニ"), p("近くのコンビニ", "ja"))
        assertEquals(QueryIntent.Search("薬局"), p("薬局はどこですか", "ja"))
        assertEquals(QueryIntent.Eta, p("あとどのくらい", "ja"))
        assertEquals(QueryIntent.Route("東京", "大阪"), p("東京から大阪まで", "ja"))
        assertNull(p("ラーメン", "ja"))
        assertNull(p("咖啡", "zh"))
    }

    @Test fun `dictation slips still land`() {
        assertEquals(QueryIntent.NavigateTo("the station"), en("navigat to the station"))
        assertEquals(QueryIntent.Search("pharmacy"), en("where is the nearst pharmacy"))
        assertEquals(QueryIntent.Work, en("take me to my ofice"))
        assertEquals(QueryIntent.Eta, en("what's my E.T.A."))
        assertEquals(QueryIntent.Eta, en("whats my e t a"))
        assertEquals(QueryIntent.Home, en("can you please take me home"))
        assertEquals(QueryIntent.NavigateTo("la gare"), fr("emmene moi a la gare"))
    }

    @Test fun `fuzziness never rewrites a short word or the destination`() {
        assertEquals(QueryIntent.NavigateTo("hope"), en("take me to hope"))
        assertNull(en("home depot"))
        assertNull(en("hone"))
        assertEquals(QueryIntent.NavigateTo("the stashun"), en("navigate to the stashun"))
        assertNull(en("fine dining"))
        assertNull(en("finds"))
    }
}
