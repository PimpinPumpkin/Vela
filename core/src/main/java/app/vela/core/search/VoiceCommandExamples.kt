package app.vela.core.search

/**
 * Example phrases for the voice command list in Settings > Search and on the listening screen
 * (discussion #365). Hand-written per language rather than generated from [QueryIntents]'s
 * tables, because gluing a verb to a place word makes broken grammar in most languages.
 * QueryIntentExamplesTest parses every line and checks it lands on its [Kind], so the list
 * cannot promise a phrase the parser does not take.
 */
object VoiceCommandExamples {
    enum class Kind { HOME, WORK, GO, ROUTE, NEARBY, ETA }

    class Example(val kind: Kind, val phrase: String)

    private fun ex(vararg p: Pair<Kind, String>) = p.map { Example(it.first, it.second) }

    private val EN = ex(
        Kind.HOME to "Take me home", Kind.WORK to "Get me to work",
        Kind.GO to "Navigate to the train station", Kind.GO to "Fastest route to the airport",
        Kind.ROUTE to "From the library to the airport",
        Kind.NEARBY to "Where is the nearest gas station", Kind.NEARBY to "Coffee near me",
        Kind.ETA to "What's my ETA",
    )
    private val FR = ex(
        Kind.HOME to "Ramène-moi à la maison", Kind.WORK to "Emmène-moi au travail",
        Kind.GO to "Emmène-moi à la gare", Kind.GO to "Itinéraire vers l'aéroport",
        Kind.ROUTE to "De Lyon à Paris",
        Kind.NEARBY to "Trouve-moi une pharmacie", Kind.NEARBY to "Boulangerie près de moi",
        Kind.ETA to "Quand est-ce que j'arrive",
    )
    private val DE = ex(
        Kind.HOME to "Bring mich nach Hause", Kind.WORK to "Fahr mich zur Arbeit",
        Kind.GO to "Navigiere zum Bahnhof", Kind.GO to "Schnellste Route nach Berlin",
        Kind.ROUTE to "Von Köln nach Bonn",
        Kind.NEARBY to "Wo ist die nächste Tankstelle", Kind.NEARBY to "Café in der Nähe",
        Kind.ETA to "Wann komme ich an",
    )
    private val ES = ex(
        Kind.HOME to "Llévame a casa", Kind.WORK to "Llévame al trabajo",
        Kind.GO to "Llévame a la estación de tren", Kind.GO to "Ruta hacia el aeropuerto",
        Kind.ROUTE to "De Madrid a Toledo",
        Kind.NEARBY to "Busca una farmacia", Kind.NEARBY to "Café cerca de mí",
        Kind.ETA to "Cuánto falta",
    )
    private val IT = ex(
        Kind.HOME to "Portami a casa", Kind.WORK to "Portami al lavoro",
        Kind.GO to "Portami alla stazione", Kind.GO to "Percorso per l'aeroporto",
        Kind.ROUTE to "Da Milano a Torino",
        Kind.NEARBY to "Trovami una farmacia", Kind.NEARBY to "Farmacia qui vicino",
        Kind.ETA to "Quanto manca",
    )
    private val PT = ex(
        Kind.HOME to "Leva-me para casa", Kind.WORK to "Leva-me para o trabalho",
        Kind.GO to "Leva-me para a estação", Kind.GO to "Rota para o aeroporto",
        Kind.ROUTE to "De Lisboa para Sintra",
        Kind.NEARBY to "Procura uma farmácia", Kind.NEARBY to "Café perto de mim",
        Kind.ETA to "Quanto falta",
    )
    private val NL = ex(
        Kind.HOME to "Breng me naar huis", Kind.WORK to "Breng me naar mijn werk",
        Kind.GO to "Navigeer naar het station", Kind.GO to "Snelste route naar Schiphol",
        Kind.ROUTE to "Van Utrecht naar Amsterdam",
        Kind.NEARBY to "Waar is de dichtstbijzijnde apotheek", Kind.NEARBY to "Koffie in de buurt",
        Kind.ETA to "Wanneer kom ik aan",
    )
    private val RU = ex(
        Kind.HOME to "Отвези меня домой", Kind.WORK to "Отвези меня на работу",
        Kind.GO to "Отвези меня на вокзал", Kind.GO to "Поехали в аэропорт",
        Kind.ROUTE to "Из Москвы до Твери",
        Kind.NEARBY to "Найди аптеку", Kind.NEARBY to "Кафе рядом",
        Kind.ETA to "Когда я приеду",
    )
    private val UK = ex(
        Kind.HOME to "Відвези мене додому", Kind.WORK to "Відвези мене на роботу",
        Kind.GO to "Відвези мене на вокзал", Kind.GO to "Поїхали до аеропорту",
        Kind.ROUTE to "З Києва до Львова",
        Kind.NEARBY to "Знайди аптеку", Kind.NEARBY to "Кафе поруч",
        Kind.ETA to "Коли я приїду",
    )
    private val PL = ex(
        Kind.HOME to "Zawieź mnie do domu", Kind.WORK to "Zawieź mnie do pracy",
        Kind.GO to "Zawieź mnie na dworzec", Kind.GO to "Nawiguj do lotniska",
        Kind.ROUTE to "Z Krakowa do Warszawy",
        Kind.NEARBY to "Znajdź aptekę", Kind.NEARBY to "Kawiarnia w pobliżu",
        Kind.ETA to "Kiedy dojadę",
    )
    private val SV = ex(
        Kind.HOME to "Ta mig hem", Kind.WORK to "Kör mig till jobbet",
        Kind.GO to "Navigera till centralstationen", Kind.GO to "Snabbaste vägen till Arlanda",
        Kind.ROUTE to "Från Uppsala till Stockholm",
        Kind.NEARBY to "Var är närmaste apotek", Kind.NEARBY to "Kafé i närheten",
        Kind.ETA to "När är jag framme",
    )
    private val HU = ex(
        Kind.HOME to "Vigyél haza", Kind.WORK to "Vigyél munkába",
        Kind.GO to "Navigálj a pályaudvarra", Kind.GO to "Menjünk a repülőtérre",
        Kind.NEARBY to "Hol van a legközelebbi gyógyszertár", Kind.NEARBY to "Kávézó a közelben",
        Kind.ETA to "Mikor érkezem",
    )
    private val HE = ex(
        Kind.HOME to "קח אותי הביתה", Kind.WORK to "קח אותי לעבודה",
        Kind.GO to "נווט לתחנת הרכבת", Kind.GO to "נווט אל שדה התעופה",
        Kind.NEARBY to "איפה יש בית מרקחת", Kind.NEARBY to "בית קפה בסביבה",
        Kind.ETA to "מתי אגיע",
    )
    private val ZH = ex(
        Kind.HOME to "带我回家", Kind.WORK to "去公司",
        Kind.GO to "导航到火车站", Kind.GO to "带我去机场",
        Kind.ROUTE to "从北京到天津",
        Kind.NEARBY to "最近的药店", Kind.NEARBY to "附近的咖啡店",
        Kind.ETA to "还有多久",
    )
    private val JA = ex(
        Kind.HOME to "家に帰る", Kind.WORK to "会社に行く",
        Kind.GO to "駅へ行きたい", Kind.GO to "空港まで案内して",
        Kind.ROUTE to "東京から横浜まで",
        Kind.NEARBY to "一番近いコンビニ", Kind.NEARBY to "カフェはどこ",
        Kind.ETA to "あとどのくらい",
    )

    private val TABLES = mapOf(
        "en" to EN, "fr" to FR, "de" to DE, "es" to ES, "it" to IT, "pt" to PT, "nl" to NL,
        "ru" to RU, "uk" to UK, "pl" to PL, "sv" to SV, "hu" to HU, "he" to HE, "iw" to HE,
        "zh" to ZH, "ja" to JA,
    )

    /** The phrases for [lang] (a tag like "zh-TW" reduces to its language); English otherwise. */
    fun forLanguage(lang: String): List<Example> =
        TABLES[lang.lowercase().substringBefore('-').substringBefore('_')] ?: EN

    internal val languages: Set<String> get() = TABLES.keys
}
