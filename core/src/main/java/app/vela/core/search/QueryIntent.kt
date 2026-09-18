package app.vela.core.search

/**
 * What a typed or spoken query MEANS, before it is handed to search (discussion #365, 2026-09-13).
 *
 * Voice search is dictation: the model turns speech into text and the text used to go straight
 * to the search box, so "take me home" searched for a place called "take me home". This parser
 * reads the handful of shapes people actually say and turns them into actions; anything it does
 * not recognize stays a plain search (null), so it can never make a query worse. Rule-based and
 * on-device, per app language, no server: the tables below are the whole vocabulary.
 */
sealed class QueryIntent {
    /** Directions to the saved Home. */
    object Home : QueryIntent()
    /** Directions to the saved Work. */
    object Work : QueryIntent()
    /** Directions to a place found by [query]. */
    data class NavigateTo(val query: String) : QueryIntent()
    /** Directions from a place found by [from] to one found by [to]. */
    data class Route(val from: String, val to: String) : QueryIntent()
    /** A plain search with the filler removed ("nearest gas station" -> "gas station"). */
    data class Search(val query: String) : QueryIntent()
    /** "What is my ETA": answer out loud while navigating. */
    object Eta : QueryIntent()
}

object QueryIntents {
    private class Words(
        /** Verb phrases that mean "navigate to", each a regex fragment, matched at the start. */
        val go: List<String>,
        val home: List<String>,
        val work: List<String>,
        /** Filler around a place search: "nearest", "near me", "find", "where is". */
        val nearPrefix: List<String>,
        val nearSuffix: List<String>,
        val eta: List<String>,
        /** "from A to B" / "A to B" connectors. */
        val from: List<String>,
        val to: List<String>,
        /** Trailing politeness to strip. */
        val please: List<String>,
        /** Left-hand words that make "X to Y" a question, not a route ("where to eat"). */
        val notFrom: List<String> = emptyList(),
        /** Verbs WITHOUT a preposition ("take me", "go"): only meaningful before home/work or an
         *  explicit "from A to B", never a free destination ("go karts" is a search). */
        val goBare: List<String> = emptyList(),
        /** Languages that put the verb AFTER the place ("Xへ行く", "Xまでナビ"). */
        val goSuffix: List<String> = emptyList(),
        /** Connectors allowed for a BARE "A to B" (no explicit from); null = the [to] list. Languages
         *  whose "to" is also an everyday preposition ("в", "na") keep only the unambiguous one here. */
        val toBare: List<String>? = null,
        /** Chinese and Japanese: no spaces, phrases attach directly. */
        val noSpaces: Boolean = false,
        /** Japanese "AからBまで": the from-marker is a SUFFIX on A, not a prefix. */
        val fromIsSuffix: Boolean = false,
    )

    private val EN = Words(
        go = listOf("take me to", "bring me to", "get me to", "drive me to", "navigate to", "navigate me to", "directions to", "direction to", "route to", "fastest route to", "quickest route to", "best route to", "route me to", "go to", "drive to", "head to", "let's go to", "i want to go to", "i need to go to", "i need to get to"),
        home = listOf("home", "my home", "my house", "the house"),
        work = listOf("work", "my work", "my office", "the office", "my job"),
        nearPrefix = listOf("where is the nearest", "where is the closest", "where's the nearest", "where's the closest", "where is a", "where is", "find me a", "find me the nearest", "find me", "find a", "find the nearest", "find the closest", "find", "search for", "search", "look for", "look up", "show me the nearest", "show me", "the nearest", "the closest", "nearest", "closest", "any"),
        nearSuffix = listOf("near me", "nearby", "around me", "close to me", "around here", "near here", "close by"),
        eta = listOf("what is my eta", "what's my eta", "my eta", "eta", "how long until i arrive", "how long till i arrive", "when will i arrive", "when do i arrive", "how much longer", "how far is it", "how long is left", "arrival time", "time to destination"),
        from = listOf("from"),
        to = listOf("to"),
        please = listOf("please", "now"),
        notFrom = listOf("where", "how", "what", "when", "who", "why", "go", "get", "take", "want", "need", "back"),
        goBare = listOf("take me", "bring me", "get me", "drive me", "navigate", "go", "directions", "route", "let's go", "i want to go", "i need to go", "i need to get"),
    )
    private val FR = Words(
        go = listOf("emmène-moi à", "emmène-moi au", "emmène-moi aux", "emmène-moi chez", "emmene-moi à", "emmene moi à", "emmène moi à", "conduis-moi à", "conduis-moi au", "conduis moi à", "amène-moi à", "amène-moi au", "amène moi à", "va à", "va au", "vas à", "aller à", "aller au", "aller vers", "je veux aller à", "je veux aller au", "je dois aller à", "itinéraire vers", "itinéraire pour", "itinéraire jusqu'à", "itineraire vers", "itineraire pour", "route vers", "route pour", "direction", "trajet vers", "trajet pour", "le chemin le plus rapide vers", "le chemin le plus rapide pour", "la route la plus rapide vers", "la route la plus rapide pour", "navigue vers", "naviguer vers"),
        home = listOf("la maison", "maison", "chez moi", "à la maison", "mon domicile", "domicile"),
        work = listOf("travail", "le travail", "au travail", "mon travail", "bureau", "le bureau", "au bureau", "mon bureau", "boulot", "le boulot", "au boulot"),
        nearPrefix = listOf("où est le plus proche", "où est la plus proche", "où est le", "où est la", "où est un", "où est une", "où est", "ou est", "où sont les", "où se trouve le", "où se trouve la", "où se trouve", "trouve-moi un", "trouve-moi une", "trouve-moi le", "trouve-moi la", "trouve-moi", "trouve un", "trouve une", "trouve", "cherche un", "cherche une", "cherche", "recherche", "montre-moi", "le plus proche", "la plus proche", "un", "une"),
        nearSuffix = listOf("près de moi", "pres de moi", "près d'ici", "pres d'ici", "autour de moi", "à proximité", "a proximité", "le plus proche", "la plus proche", "les plus proches", "à côté"),
        eta = listOf("quelle est mon heure d'arrivée", "quelle est mon eta", "mon eta", "eta", "heure d'arrivée", "quand est-ce que j'arrive", "quand j'arrive", "dans combien de temps j'arrive", "combien de temps il reste", "combien de temps reste-t-il", "il reste combien de temps", "temps restant", "c'est encore loin"),
        from = listOf("de", "depuis"),
        to = listOf("à", "a", "vers", "jusqu'à"),
        please = listOf("s'il te plaît", "s'il vous plaît", "stp", "svp", "maintenant"),
        notFrom = listOf("où", "ou", "comment", "quoi", "quand", "qui", "pourquoi", "va", "aller", "prêt", "prête"),
        goBare = listOf("emmène-moi", "emmène moi", "emmene-moi", "conduis-moi", "amène-moi", "ramène-moi", "ramène moi", "va", "vas", "aller", "allons", "je veux aller", "je dois aller", "rentrer", "rentre", "itinéraire", "navigue", "naviguer", "direction"),
    )
    private val DE = Words(
        go = listOf("bring mich zu", "bring mich zum", "bring mich zur", "bring mich nach", "fahr mich zu", "fahr mich zum", "fahr mich zur", "fahr mich nach", "fahre mich zu", "navigiere zu", "navigiere zum", "navigiere zur", "navigiere nach", "navigation zu", "navigation nach", "route zu", "route zum", "route zur", "route nach", "schnellste route zu", "schnellste route nach", "schnellster weg zu", "schnellster weg nach", "wegbeschreibung zu", "wegbeschreibung nach", "fahr zu", "fahr zum", "fahr zur", "fahr nach", "geh zu", "geh zum", "geh zur", "gehe zu", "ich will zu", "ich will zum", "ich will zur", "ich will nach", "ich möchte zu", "ich möchte zum", "ich möchte zur", "ich möchte nach", "ich muss zu", "ich muss zum", "ich muss zur", "ich muss nach"),
        home = listOf("nach hause", "hause", "zuhause", "zu hause", "heim", "nachhause"),
        work = listOf("arbeit", "zur arbeit", "die arbeit", "ins büro", "büro", "das büro", "zum büro"),
        nearPrefix = listOf("wo ist der nächste", "wo ist die nächste", "wo ist das nächste", "wo ist der", "wo ist die", "wo ist das", "wo ist ein", "wo ist eine", "wo ist", "wo gibt es", "wo gibt's", "wo finde ich", "such mir", "suche", "such", "finde", "find", "zeig mir", "zeige mir", "der nächste", "die nächste", "das nächste", "nächster", "nächste", "nächstes"),
        nearSuffix = listOf("in der nähe", "in meiner nähe", "hier in der nähe", "um mich herum", "in der umgebung"),
        eta = listOf("wann komme ich an", "wann kommen wir an", "wie lange noch", "wie lange dauert es noch", "ankunftszeit", "eta", "meine eta", "wie weit ist es noch", "verbleibende zeit", "restzeit"),
        from = listOf("von"),
        to = listOf("nach", "zu", "zum", "zur", "bis"),
        please = listOf("bitte", "jetzt"),
        notFrom = listOf("wo", "wie", "was", "wann", "wer", "warum", "fahr", "geh", "will", "möchte", "muss"),
        goBare = listOf("bring mich", "fahr mich", "fahre mich", "fahr", "fahre", "geh", "gehe", "navigiere", "navigation", "route", "ich will", "ich möchte", "ich muss"),
    )
    private val ES = Words(
        go = listOf("llévame a", "llévame al", "llevame a", "llevame al", "llévame hasta", "vamos a", "vamos al", "ir a", "ir al", "quiero ir a", "quiero ir al", "necesito ir a", "necesito ir al", "cómo llego a", "cómo llego al", "como llego a", "como llego al", "ruta a", "ruta al", "ruta hacia", "ruta hasta", "la ruta más rápida a", "la ruta más rápida al", "la ruta más rápida hacia", "ruta más rápida a", "indicaciones a", "indicaciones para", "direcciones a", "navega a", "navegar a", "navega hasta", "ve a", "ve al"),
        home = listOf("casa", "a casa", "mi casa", "la casa"),
        work = listOf("trabajo", "al trabajo", "el trabajo", "mi trabajo", "la oficina", "oficina", "mi oficina"),
        nearPrefix = listOf("dónde está el más cercano", "dónde está la más cercana", "dónde está el", "dónde está la", "dónde está un", "dónde está una", "dónde está", "donde está", "donde esta", "dónde hay", "donde hay", "busca un", "busca una", "busca", "buscar", "encuentra un", "encuentra una", "encuentra", "muéstrame", "muestrame", "el más cercano", "la más cercana", "más cercano", "más cercana"),
        nearSuffix = listOf("cerca de mí", "cerca de mi", "cerca de aquí", "cerca de aqui", "cerca", "más cercano", "más cercana", "a mi alrededor", "por aquí"),
        eta = listOf("cuál es mi hora de llegada", "cuál es mi eta", "mi eta", "eta", "cuándo llego", "cuando llego", "cuánto falta", "cuanto falta", "cuánto tiempo falta", "cuanto tiempo falta", "hora de llegada", "tiempo restante", "cuánto queda"),
        from = listOf("de", "desde"),
        to = listOf("a", "al", "hacia", "hasta"),
        please = listOf("por favor", "ahora"),
        notFrom = listOf("dónde", "donde", "cómo", "como", "qué", "que", "cuándo", "cuando", "quién", "ir", "vamos", "ve", "quiero", "necesito"),
        goBare = listOf("llévame", "llevame", "vamos", "ir", "quiero ir", "necesito ir", "navega", "navegar", "ruta", "ve"),
    )
    private val IT = Words(
        go = listOf("portami a", "portami al", "portami alla", "portami in", "portami da", "andiamo a", "andiamo al", "andiamo alla", "andiamo in", "andare a", "andare al", "andare in", "voglio andare a", "voglio andare al", "voglio andare in", "devo andare a", "devo andare al", "devo andare in", "vai a", "vai al", "vai alla", "vai in", "naviga verso", "naviga a", "naviga fino a", "percorso per", "percorso verso", "percorso fino a", "indicazioni per", "indicazioni verso", "la strada più veloce per", "strada più veloce per", "il percorso più veloce per", "percorso più veloce per", "come arrivo a", "come arrivo al", "come arrivo in"),
        home = listOf("casa", "a casa", "casa mia", "la mia casa"),
        work = listOf("lavoro", "al lavoro", "il lavoro", "in ufficio", "ufficio", "l'ufficio", "mio ufficio"),
        nearPrefix = listOf("dov'è il più vicino", "dov'è la più vicina", "dov'è il", "dov'è la", "dov'è un", "dov'è una", "dov'è", "dove è", "dove si trova", "dove trovo", "trovami un", "trovami una", "trovami", "trova un", "trova una", "trova", "cerca un", "cerca una", "cerca", "mostrami", "il più vicino", "la più vicina", "più vicino", "più vicina"),
        nearSuffix = listOf("vicino a me", "vicino", "qui vicino", "nelle vicinanze", "più vicino", "più vicina", "intorno a me", "da queste parti"),
        eta = listOf("qual è il mio orario di arrivo", "qual è la mia eta", "mia eta", "eta", "quando arrivo", "quando arriviamo", "quanto manca", "quanto tempo manca", "orario di arrivo", "tempo rimanente", "quanto ci vuole ancora"),
        from = listOf("da"),
        to = listOf("a", "al", "alla", "verso", "fino a", "in"),
        please = listOf("per favore", "per piacere", "adesso", "ora"),
        notFrom = listOf("dove", "come", "cosa", "quando", "chi", "perché", "vai", "andiamo", "voglio", "devo"),
        goBare = listOf("portami", "andiamo", "andare", "voglio andare", "devo andare", "vai", "naviga", "percorso", "torna", "torniamo"),
    )
    private val PT = Words(
        go = listOf("leva-me a", "leva-me ao", "leva-me à", "leva-me para", "leve-me a", "leve-me ao", "leve-me para", "me leva para", "me leva a", "me leve para", "vamos a", "vamos ao", "vamos para", "ir a", "ir ao", "ir para", "quero ir a", "quero ir ao", "quero ir para", "preciso ir a", "preciso ir ao", "preciso ir para", "navegar para", "navega para", "navegue para", "rota para", "rota até", "a rota mais rápida para", "rota mais rápida para", "caminho mais rápido para", "direções para", "direcções para", "como chego a", "como chego ao", "como chegar a", "como chegar ao", "vai para", "vá para"),
        home = listOf("casa", "para casa", "a casa", "minha casa", "à casa"),
        work = listOf("trabalho", "para o trabalho", "o trabalho", "ao trabalho", "meu trabalho", "escritório", "o escritório", "para o escritório"),
        nearPrefix = listOf("onde fica o mais próximo", "onde fica a mais próxima", "onde fica o", "onde fica a", "onde fica um", "onde fica uma", "onde fica", "onde está o", "onde está a", "onde está", "onde tem", "onde há", "procura um", "procura uma", "procura", "procurar", "encontra um", "encontra uma", "encontra", "encontre", "mostra-me", "mostre-me", "me mostra", "o mais próximo", "a mais próxima", "mais próximo", "mais próxima"),
        nearSuffix = listOf("perto de mim", "perto daqui", "perto", "aqui perto", "mais próximo", "mais próxima", "por aqui", "nas proximidades", "à minha volta"),
        eta = listOf("qual é a minha hora de chegada", "qual é o meu eta", "meu eta", "eta", "quando chego", "quando é que chego", "quanto falta", "quanto tempo falta", "hora de chegada", "tempo restante", "falta muito"),
        from = listOf("de", "desde"),
        to = listOf("a", "ao", "à", "para", "até"),
        please = listOf("por favor", "agora"),
        notFrom = listOf("onde", "como", "o que", "que", "quando", "quem", "porque", "vai", "vamos", "quero", "preciso"),
        goBare = listOf("leva-me", "leve-me", "me leva", "me leve", "vamos", "ir", "quero ir", "preciso ir", "navegar", "navega", "rota", "vai", "vá"),
    )
    private val NL = Words(
        go = listOf("breng me naar", "breng mij naar", "rijd me naar", "rijd naar", "rij naar", "ga naar", "navigeer naar", "navigatie naar", "route naar", "snelste route naar", "snelste weg naar", "routebeschrijving naar", "ik wil naar", "ik moet naar", "we gaan naar", "laten we naar"),
        home = listOf("huis", "naar huis", "thuis", "mijn huis"),
        work = listOf("werk", "naar werk", "naar mijn werk", "mijn werk", "kantoor", "het kantoor", "naar kantoor"),
        nearPrefix = listOf("waar is de dichtstbijzijnde", "waar is het dichtstbijzijnde", "waar is de", "waar is het", "waar is een", "waar is", "waar vind ik", "waar zit", "zoek een", "zoek", "vind een", "vind", "laat me", "toon", "de dichtstbijzijnde", "het dichtstbijzijnde", "dichtstbijzijnde", "dichtstbijzijnd"),
        nearSuffix = listOf("in de buurt", "bij mij in de buurt", "hier in de buurt", "dichtbij", "vlakbij", "om me heen"),
        eta = listOf("wat is mijn aankomsttijd", "wat is mijn eta", "mijn eta", "eta", "wanneer kom ik aan", "wanneer zijn we er", "hoe lang nog", "hoe ver is het nog", "aankomsttijd", "resterende tijd"),
        from = listOf("van", "vanaf"),
        to = listOf("naar", "tot"),
        please = listOf("alsjeblieft", "alstublieft", "aub", "nu"),
        notFrom = listOf("waar", "hoe", "wat", "wanneer", "wie", "waarom", "ga", "wil", "moet"),
        goBare = listOf("breng me", "breng mij", "rijd me", "ga", "navigeer", "route", "ik wil", "ik moet"),
    )

    private val RU = Words(
        go = listOf("отвези меня в", "отвези меня на", "отвези меня к", "отвези меня до", "довези меня до", "довези меня в", "поехали в", "поехали на", "поехали к", "едем в", "едем на", "едем к", "навигация до", "навигация к", "навигация в", "проложи маршрут до", "проложи маршрут в", "построй маршрут до", "построй маршрут в", "маршрут до", "маршрут в", "маршрут к", "как доехать до", "как добраться до", "как проехать к", "хочу поехать в", "мне нужно в", "мне надо в", "поехать в", "ехать в", "доехать до", "веди меня в", "веди в"),
        home = listOf("домой", "дом", "мой дом", "к себе домой", "до дома"),
        work = listOf("на работу", "работа", "работу", "в офис", "офис", "на работе", "до работы"),
        nearPrefix = listOf("где ближайший", "где ближайшая", "где ближайшее", "где ближайшие", "где есть", "где тут", "где здесь", "где", "найди ближайший", "найди ближайшую", "найди ближайшее", "найди мне", "найди", "покажи мне", "покажи", "поищи", "ищи", "ближайший", "ближайшая", "ближайшее", "ближайшие"),
        nearSuffix = listOf("рядом со мной", "рядом", "поблизости", "недалеко", "недалеко от меня", "возле меня", "около меня", "здесь рядом"),
        eta = listOf("когда я приеду", "когда мы приедем", "когда приедем", "сколько осталось", "сколько ещё ехать", "сколько еще ехать", "сколько ещё", "сколько еще", "время прибытия", "во сколько приеду", "во сколько я приеду", "мой eta", "eta", "долго ещё", "долго еще"),
        from = listOf("из", "от", "с"),
        to = listOf("в", "до", "на", "к"),
        toBare = listOf("до"),
        please = listOf("пожалуйста", "сейчас"),
        notFrom = listOf("где", "как", "что", "когда", "кто", "почему", "хочу", "надо", "нужно"),
        goBare = listOf("отвези меня", "довези меня", "поехали", "едем", "навигация", "маршрут", "веди меня", "хочу", "мне нужно", "мне надо"),
    )
    private val UK = Words(
        go = listOf("відвези мене до", "відвези мене в", "відвези мене на", "довези мене до", "поїхали до", "поїхали в", "поїхали на", "їдемо до", "їдемо в", "навігація до", "навігація в", "прокладіть маршрут до", "проклади маршрут до", "побудуй маршрут до", "маршрут до", "маршрут в", "як доїхати до", "як дістатися до", "хочу поїхати до", "хочу поїхати в", "мені треба до", "мені потрібно до", "їхати до", "доїхати до", "веди мене до"),
        home = listOf("додому", "дім", "мій дім", "до дому", "додому будь ласка"),
        work = listOf("на роботу", "робота", "роботу", "в офіс", "офіс", "до роботи"),
        nearPrefix = listOf("де найближчий", "де найближча", "де найближче", "де найближчі", "де є", "де тут", "де", "знайди найближчий", "знайди найближчу", "знайди мені", "знайди", "покажи мені", "покажи", "пошукай", "шукай", "найближчий", "найближча", "найближче", "найближчі"),
        nearSuffix = listOf("поруч зі мною", "поруч", "поблизу", "неподалік", "біля мене", "коло мене", "тут поруч"),
        eta = listOf("коли я приїду", "коли ми приїдемо", "коли приїдемо", "скільки залишилось", "скільки залишилося", "скільки ще їхати", "скільки ще", "час прибуття", "о котрій приїду", "мій eta", "eta", "ще довго"),
        from = listOf("з", "із", "від"),
        to = listOf("до", "в", "у", "на"),
        toBare = listOf("до"),
        please = listOf("будь ласка", "зараз"),
        notFrom = listOf("де", "як", "що", "коли", "хто", "чому", "хочу", "треба", "потрібно"),
        goBare = listOf("відвези мене", "довези мене", "поїхали", "їдемо", "навігація", "маршрут", "веди мене", "хочу", "мені треба", "мені потрібно"),
    )
    private val PL = Words(
        go = listOf("zawieź mnie do", "zawieź mnie na", "zabierz mnie do", "zabierz mnie na", "jedź do", "jedź na", "jedziemy do", "jedziemy na", "nawiguj do", "nawiguj na", "nawigacja do", "trasa do", "trasa na", "wyznacz trasę do", "wyznacz trasę na", "pokaż trasę do", "jak dojechać do", "jak dojadę do", "chcę jechać do", "chcę pojechać do", "muszę jechać do", "muszę do", "prowadź do", "prowadź mnie do", "najszybsza trasa do", "najszybsza droga do"),
        home = listOf("do domu", "dom", "mój dom", "domu"),
        work = listOf("do pracy", "praca", "pracy", "do biura", "biuro", "biura"),
        nearPrefix = listOf("gdzie jest najbliższy", "gdzie jest najbliższa", "gdzie jest najbliższe", "gdzie jest", "gdzie są", "gdzie tu", "gdzie", "znajdź najbliższy", "znajdź najbliższą", "znajdź mi", "znajdź", "poszukaj", "szukaj", "wyszukaj", "pokaż mi", "pokaż", "najbliższy", "najbliższa", "najbliższe"),
        nearSuffix = listOf("w pobliżu", "blisko mnie", "blisko", "niedaleko", "obok mnie", "w okolicy", "tu w pobliżu"),
        eta = listOf("kiedy dojadę", "kiedy dojedziemy", "kiedy będę na miejscu", "ile zostało", "ile jeszcze", "ile jeszcze jechać", "ile czasu zostało", "czas przyjazdu", "o której dojadę", "mój eta", "eta", "daleko jeszcze"),
        from = listOf("z", "ze", "od"),
        to = listOf("do", "na"),
        toBare = listOf("do"),
        please = listOf("proszę", "teraz"),
        notFrom = listOf("gdzie", "jak", "co", "kiedy", "kto", "dlaczego", "chcę", "muszę", "jedź"),
        goBare = listOf("zawieź mnie", "zabierz mnie", "jedź", "jedziemy", "nawiguj", "nawigacja", "trasa", "prowadź", "chcę", "muszę", "wracam", "wróć"),
    )
    private val SV = Words(
        go = listOf("ta mig till", "kör mig till", "ta mig hem till", "åk till", "kör till", "gå till", "navigera till", "navigering till", "rutt till", "vägbeskrivning till", "snabbaste vägen till", "snabbaste rutten till", "hur kommer jag till", "jag vill åka till", "jag vill till", "jag måste till", "jag ska till", "vi åker till", "visa vägen till"),
        home = listOf("hem", "hemma", "mitt hem", "hemåt"),
        work = listOf("jobbet", "jobb", "till jobbet", "mitt jobb", "kontoret", "till kontoret", "arbetet"),
        nearPrefix = listOf("var är närmaste", "var finns närmaste", "var är", "var finns", "var ligger", "hitta närmaste", "hitta", "sök efter", "sök", "leta efter", "visa mig", "visa", "närmaste", "närmsta"),
        nearSuffix = listOf("nära mig", "i närheten", "här i närheten", "i min närhet", "nära", "runt omkring"),
        eta = listOf("när är jag framme", "när kommer jag fram", "när är vi framme", "hur lång tid kvar", "hur långt kvar", "hur länge till", "ankomsttid", "beräknad ankomst", "min eta", "eta", "tid kvar"),
        from = listOf("från"),
        to = listOf("till"),
        please = listOf("tack", "snälla", "nu"),
        notFrom = listOf("var", "hur", "vad", "när", "vem", "varför", "åk", "kör", "vill", "måste", "ska"),
        goBare = listOf("ta mig", "kör mig", "åk", "kör", "navigera", "rutt", "jag vill", "jag måste", "jag ska", "vi åker"),
    )
    private val HU = Words(
        go = listOf("vigyél el a", "vigyél el az", "vigyél el", "vigyél a", "vigyél az", "vigyél", "menjünk a", "menjünk az", "menjünk", "navigálj a", "navigálj az", "navigálj", "navigáció a", "navigáció az", "útvonal a", "útvonal az", "útvonalat a", "útvonalat az", "leggyorsabb út a", "leggyorsabb út az", "leggyorsabb útvonal a", "leggyorsabb útvonal az", "hogy jutok el a", "hogy jutok el az", "hogyan jutok el a", "hogyan jutok el az", "el akarok menni a", "el akarok menni az", "el kell mennem a", "el kell mennem az", "irány a", "irány az", "menj a", "menj az"),
        home = listOf("haza", "otthon", "otthonra", "hazafelé", "az otthonomba", "otthonomba"),
        work = listOf("munkába", "a munkába", "munkahelyre", "a munkahelyre", "munkahelyemre", "irodába", "az irodába", "munka", "munkahely"),
        nearPrefix = listOf("hol van a legközelebbi", "hol van a", "hol van az", "hol van", "hol találok", "hol vannak", "keress egy", "keress", "keresd meg a", "keresd meg", "mutasd a legközelebbi", "mutasd meg a", "mutasd", "mutass egy", "mutass", "legközelebbi", "a legközelebbi"),
        nearSuffix = listOf("a közelben", "a közelemben", "a közelemben van", "itt a közelben", "a közelben van", "közel hozzám", "errefelé"),
        eta = listOf("mikor érkezem", "mikor érkezünk", "mikor érek oda", "mikor érünk oda", "mennyi idő van hátra", "mennyi van még hátra", "mennyi van hátra", "mennyi idő még", "érkezési idő", "várható érkezés", "hány órakor érkezem", "eta", "az eta", "messze van még"),
        from = emptyList(),
        to = emptyList(),
        please = listOf("kérlek", "légy szíves", "légyszi", "most"),
        notFrom = emptyList(),
        goBare = listOf("vigyél", "menjünk", "navigálj", "navigáció", "útvonal", "irány", "menj", "el akarok menni", "el kell mennem"),
    )
    private val HE = Words(
        go = listOf("קח אותי ל", "קח אותי אל", "תיקח אותי ל", "תקח אותי ל", "סע ל", "סע אל", "לך ל", "נווט ל", "נווט אל", "נווט", "ניווט ל", "ניווט אל", "מסלול ל", "מסלול אל", "דרך ל", "איך מגיעים ל", "איך להגיע ל", "אני רוצה להגיע ל", "אני רוצה לנסוע ל", "אני צריך להגיע ל", "אני צריכה להגיע ל", "תוביל אותי ל", "הדרך המהירה ל", "המסלול המהיר ל"),
        home = listOf("הביתה", "הבית", "הבית שלי", "בית", "לבית"),
        work = listOf("לעבודה", "עבודה", "העבודה", "העבודה שלי", "למשרד", "משרד", "המשרד"),
        nearPrefix = listOf("איפה יש", "איפה ה", "איפה", "היכן", "תמצא לי", "תמצא", "מצא לי", "מצא", "חפש לי", "חפש", "תחפש", "תראה לי", "הראה לי", "הכי קרוב", "הקרוב ביותר", "הקרובה ביותר"),
        nearSuffix = listOf("קרוב אליי", "קרוב אלי", "ליד", "לידי", "בסביבה", "באזור", "הכי קרוב", "הכי קרובה", "הקרוב ביותר", "הקרובה ביותר", "פה קרוב", "כאן קרוב"),
        eta = listOf("מתי אגיע", "מתי נגיע", "מתי אני מגיע", "מתי אני מגיעה", "כמה זמן נשאר", "כמה זמן עוד", "כמה עוד", "עוד כמה זמן", "זמן הגעה", "זמן הגעה משוער", "באיזו שעה אגיע", "eta", "רחוק עוד"),
        from = emptyList(),
        to = emptyList(),
        please = listOf("בבקשה", "עכשיו"),
        notFrom = emptyList(),
        goBare = listOf("קח אותי", "תיקח אותי", "תקח אותי", "סע", "לך", "נווט", "ניווט", "מסלול", "תוביל אותי"),
    )
    private val ZH = Words(
        go = listOf("带我去", "带我到", "帶我去", "帶我到", "导航到", "导航去", "導航到", "導航去", "导航", "導航", "开车去", "開車去", "开到", "開到", "前往", "我要去", "我想去", "我要到", "我想到", "怎么去", "怎麼去", "怎么走到", "怎麼走到", "路线到", "路線到", "路线去", "路線去", "去", "到"),
        home = listOf("家", "回家", "我家", "带我回家", "帶我回家", "回家去", "家里", "家裡"),
        work = listOf("公司", "上班", "去上班", "去公司", "办公室", "辦公室", "单位", "單位", "工作"),
        nearPrefix = listOf("离我最近的", "離我最近的", "最近的", "附近的", "附近有没有", "附近有沒有", "附近有", "哪里有", "哪裡有", "哪里可以", "哪裡可以", "帮我找", "幫我找", "找一下", "找", "搜索", "搜尋", "搜", "查找", "查一下", "给我找", "給我找"),
        nearSuffix = listOf("附近", "在哪里", "在哪裡", "在哪", "在哪儿", "在哪兒", "在附近"),
        eta = listOf("预计到达时间", "預計到達時間", "预计几点到", "預計幾點到", "还有多久", "還有多久", "还要多久", "還要多久", "还有多远", "還有多遠", "什么时候到", "什麼時候到", "几点到", "幾點到", "剩余时间", "剩餘時間", "还有多长时间", "還有多長時間", "eta", "我的eta"),
        from = listOf("从", "從", "由"),
        to = listOf("到", "去", "至"),
        toBare = listOf("到"),
        please = listOf("请", "請", "谢谢", "謝謝", "吧"),
        notFrom = emptyList(),
        goBare = emptyList(),
        noSpaces = true,
    )
    private val JA = Words(
        go = listOf("ナビ", "目的地", "案内して", "行き先"),
        home = listOf("家", "自宅", "うち", "家に帰る", "家へ帰る", "家に帰りたい", "自宅に帰る", "自宅へ帰る", "うちに帰る", "帰る", "帰宅", "家まで", "自宅まで", "家に", "家へ", "自宅に", "自宅へ"),
        work = listOf("会社", "職場", "仕事", "オフィス", "会社に行く", "会社へ行く", "職場に行く", "職場へ行く", "会社まで", "職場まで", "会社に", "会社へ", "職場に", "職場へ", "仕事に", "仕事へ"),
        nearPrefix = listOf("一番近い", "最寄りの", "最寄り", "近くの", "近所の", "付近の", "この辺の", "この近くの", "探して", "検索", "見つけて"),
        nearSuffix = listOf("はどこですか", "はどこ", "はどこにありますか", "はどこにある", "を探して", "を探す", "を検索", "を見つけて", "が近くにある", "はありますか", "ある"),
        eta = listOf("あとどのくらい", "あとどれくらい", "あとどのくらいで着く", "あとどれくらいで着く", "到着時間", "到着予定時刻", "到着予定", "いつ着く", "いつ着きますか", "何時に着く", "何時に着きますか", "残り時間", "あと何分", "eta"),
        from = listOf("から"),
        to = listOf("まで", "へ", "に"),
        toBare = listOf("まで"),
        please = listOf("ください", "お願いします", "お願い", "してください", "して"),
        notFrom = emptyList(),
        goBare = emptyList(),
        goSuffix = listOf("へ連れて行って", "に連れて行って", "まで連れて行って", "へ連れてって", "に連れてって", "まで連れてって", "へ行きたい", "に行きたい", "へ行って", "に行って", "へ行く", "に行く", "へ向かう", "に向かう", "へ向かって", "に向かって", "まで案内して", "へ案内して", "に案内して", "まで案内", "へ案内", "に案内", "までナビ", "へナビ", "にナビ", "までの道", "への道", "への行き方", "までの行き方", "まで", "へ"),
        noSpaces = true,
        fromIsSuffix = true,
    )

    private val TABLES = mapOf(
        "en" to EN, "fr" to FR, "de" to DE, "es" to ES, "it" to IT, "pt" to PT, "nl" to NL,
        "ru" to RU, "uk" to UK, "pl" to PL, "sv" to SV, "hu" to HU, "he" to HE, "iw" to HE,
        "zh" to ZH, "ja" to JA,
    )

    /** The languages with an intent vocabulary; any other language gets plain search. */
    val supportedLanguages: Set<String> get() = TABLES.keys

    /**
     * Parse [text] in language [lang] (ISO 639-1; a tag like "zh-TW" is reduced to its language).
     * Null = no recognized shape, run it as a plain search. English is tried as a fallback for
     * any language, because people mix ("navigate to" in a French phone is common).
     */
    fun parse(text: String, lang: String): QueryIntent? {
        val t = normalize(text)
        if (t.isBlank()) return null
        val code = lang.lowercase().substringBefore('-').substringBefore('_')
        val primary = TABLES[code]
        if (primary != null) parseWith(t, primary, fuzzy = false)?.let { return it }
        if (primary !== EN) parseWith(t, EN, fuzzy = false)?.let { return it }
        // FUZZY pass (2026-09-13): dictation mishears ("navigat to", "nearst", "ofice") and the
        // exact phrases miss. A second pass lets each vocabulary WORD differ by one edit (two for
        // long words), never a whole phrase, and never the free text after it. Word-per-word, so
        // "home depot" cannot become "home". Spaced languages only.
        if (primary != null && !primary.noSpaces) parseWith(t, primary, fuzzy = true)?.let { return it }
        if (primary !== EN) parseWith(t, EN, fuzzy = true)?.let { return it }
        return null
    }

    // ---- word-level fuzzy matching --------------------------------------------------------
    /** Optimal-string-alignment distance (Levenshtein + adjacent transposition). */
    private fun dist(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[a.length][b.length]
    }

    /** One vocabulary word vs one query word. Short words must match exactly (a one-letter slip
     *  in "home" reaches "hope", a real town); from [minLen] one edit is allowed, from eight two. */
    private fun wordOk(q: String, p: String, fuzzy: Boolean, minLen: Int): Boolean {
        if (q == p) return true
        if (!fuzzy || q.isEmpty()) return false
        // Dictation drops accents ("emmene moi a la gare"): an accent-only difference is a match
        // at any length, so the one-letter French "à" still lines up.
        val fq = fold(q); val fp = fold(p)
        if (fq == fp) return true
        if (p.length < minLen) return false
        val allowed = if (p.length >= 8) 2 else 1
        return kotlin.math.abs(fq.length - fp.length) <= allowed && dist(fq, fp) <= allowed
    }

    private val MARKS = Regex("\\p{Mn}+")
    private fun fold(s: String): String = MARKS.replace(java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD), "")

    /** Is [a] a (possibly misheard) verb phrase such as "navigat" or "take me"? A rejection test
     *  for the bare A-to-B rule, so it is generous: single words fuzz too. */
    private fun looksLikeVerb(a: String, to: String, w: Words): Boolean {
        val aw = a.split(' ')
        val phrases = w.go.map { it.removeSuffix(" $to").removeSuffix(" to") } + w.goBare
        return phrases.any { ph ->
            val pw = ph.split(' ')
            pw.size == aw.size && pw.indices.all { wordOk(aw[it], pw[it], true, 4) }
        }
    }

    /** [t] starts with [phrase] (word by word): the remainder, or null. */
    private fun pre(t: String, phrase: String, w: Words, fuzzy: Boolean): String? {
        if (w.noSpaces) return if (t.startsWith(phrase)) t.removePrefix(phrase).trim() else null
        if (t == phrase) return ""
        if (t.startsWith("$phrase ")) return t.removePrefix("$phrase ").trim()
        if (!fuzzy) return null
        val pw = phrase.split(' '); val tw = t.split(' ')
        // Single-word fillers stay exact: "fine dining" must not read as "find dining".
        if (pw.size < 2 || tw.size <= pw.size) return null
        for (i in pw.indices) if (!wordOk(tw[i], pw[i], true, 4)) return null
        return tw.drop(pw.size).joinToString(" ")
    }

    /** [t] equals [phrase] word by word. */
    private fun eq(t: String, phrase: String, w: Words, fuzzy: Boolean): Boolean {
        if (t == phrase) return true
        if (!fuzzy || w.noSpaces) return false
        val pw = phrase.split(' '); val tw = t.split(' ')
        if (pw.size < 2 || tw.size != pw.size) return false
        for (i in pw.indices) if (!wordOk(tw[i], pw[i], true, 5)) return false
        return true
    }

    /** [t] ends with [phrase] (word by word): the head, or null. */
    private fun suf(t: String, phrase: String, w: Words, fuzzy: Boolean): String? {
        if (w.noSpaces) return if (t.length > phrase.length && t.endsWith(phrase)) t.removeSuffix(phrase).trim() else null
        if (t.endsWith(" $phrase")) return t.removeSuffix(" $phrase").trim()
        if (!fuzzy) return null
        val pw = phrase.split(' '); val tw = t.split(' ')
        if (pw.size < 2 || tw.size <= pw.size) return null
        val tail = tw.takeLast(pw.size)
        for (i in pw.indices) if (!wordOk(tail[i], pw[i], true, 4)) return null
        return tw.dropLast(pw.size).joinToString(" ")
    }

    private fun normalize(s: String): String =
        s.trim().lowercase()
            .replace(Regex("[\"“”«»'’`]"), "")
            .replace(Regex("[!?.,;:！？。、，]+$"), "")
            // "e.t.a." / "e t a" -> "eta": dictation spells acronyms out.
            .replace(Regex("\\b([a-z])[. ]([a-z])[. ]([a-z])\\b"), "$1$2$3")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun parseWith(t0: String, w: Words, fuzzy: Boolean): QueryIntent? {
        var t = t0
        val sp = if (w.noSpaces) "" else " "
        // Trailing politeness ("... please", "... s'il te plaît").
        for (p in w.please.sortedByDescending { it.length }) {
            suf(t, p, w, fuzzy)?.let { if (it.isNotBlank()) t = it }
        }
        if (t.isBlank()) return null
        // ETA questions are whole phrases.
        if (w.eta.any { eq(t, it, w, fuzzy) }) return QueryIntent.Eta
        // Japanese "AからBまで": the trailing "まで" is also a verb suffix, so the route shape is
        // tried before the suffix strip eats it.
        if (w.fromIsSuffix) splitRoute(t, w)?.let { return it }
        // "go home" / "take me to work" / bare "home"; Japanese puts the verb after the place.
        val afterGo = stripGo(t, w, fuzzy) ?: stripGoSuffix(t, w)
        val afterBare = if (afterGo == null) stripBare(t, w, fuzzy) else null
        val dest = afterGo ?: afterBare ?: t
        // Home/work are a closed vocabulary, so they take the fuzzy compare in BOTH passes: the
        // exact pass already owns "take me to my ofice" (its verb matched) and would otherwise
        // return NavigateTo before the fuzzy pass ran. Single words stay exact ("hope" is a town).
        if (w.home.any { eq(dest, it, w, true) }) return QueryIntent.Home
        if (w.work.any { eq(dest, it, w, true) }) return QueryIntent.Work
        if (afterGo != null) {
            // "navigate from A to B" reads as a route; otherwise the rest is the destination.
            splitRoute(afterGo, w)?.let { return it }
            return if (afterGo.isNotBlank()) QueryIntent.NavigateTo(afterGo) else null
        }
        if (afterBare != null) {
            // A bare verb only carries an explicit "from A to B"; anything else falls through to
            // the plain rules ("go karts near me" is a search for go karts).
            w.from.sortedByDescending { it.length }.firstOrNull { afterBare.startsWith("$it ") }?.let {
                splitRoute(afterBare, w)?.let { r -> return r }
            }
        }
        // "from A to B" / "A to B".
        splitRoute(t, w)?.let { return it }
        // "where is the nearest X" / "X near me" / "find X".
        var q = t
        var changed = false
        // Fillers can chain ("where is" + "the nearest"), and a misheard second one only shows
        // in the fuzzy form, so each side strips twice: the pass's own mode, then fuzzy.
        for (mode in listOf(fuzzy, true)) {
            for (p in w.nearPrefix.sortedByDescending { it.length }) {
                val r = pre(q, p, w, mode) ?: continue
                if (r.isNotBlank()) { q = r; changed = true; break }
            }
        }
        for (mode in listOf(fuzzy, true)) {
            for (sfx in w.nearSuffix.sortedByDescending { it.length }) {
                val r = suf(q, sfx, w, mode) ?: continue
                if (r.isNotBlank()) { q = r; changed = true; break }
            }
        }
        if (changed && q.isNotBlank() && q != t) return QueryIntent.Search(q)
        return null
    }

    /** The text after a "navigate to" verb phrase, or null when the query has none. The phrase may
     *  sit behind up to four filler words ("can you please take me to", "find the fastest route to"). */
    private fun stripGo(t: String, w: Words, fuzzy: Boolean): String? {
        for (g in w.go.sortedByDescending { it.length }) {
            preSkippingFillers(t, g, w, fuzzy)?.let { return it }
        }
        return null
    }

    /** [pre], but the phrase may also sit behind up to four filler words ("can you please take
     *  me to"); a filler-skipped match must leave something after the phrase. */
    private fun preSkippingFillers(t: String, g: String, w: Words, fuzzy: Boolean): String? {
        pre(t, g, w, fuzzy)?.let { return it }
        if (w.noSpaces) return null
        val tw = t.split(' ')
        val pw = g.split(' ')
        for (skip in 1..minOf(4, tw.size - pw.size - 1)) {
            val rest = tw.drop(skip).joinToString(" ")
            pre(rest, g, w, fuzzy)?.let { if (it.isNotBlank()) return it }
        }
        return null
    }

    /** "Xへ行く" -> "X": the verb phrase trails the place (Japanese). */
    private fun stripGoSuffix(t: String, w: Words): String? {
        for (g in w.goSuffix.sortedByDescending { it.length }) {
            if (t.length > g.length && t.endsWith(g)) return t.removeSuffix(g).trim()
        }
        return null
    }

    private fun stripBare(t: String, w: Words, fuzzy: Boolean): String? {
        for (g in w.goBare.sortedByDescending { it.length }) {
            preSkippingFillers(t, g, w, fuzzy)?.let { if (it.isNotBlank()) return it }
        }
        return null
    }

    /** "from A to B" always; "A to B" only when A is not a question word or a verb. */
    private fun splitRoute(t: String, w: Words): QueryIntent.Route? {
        if (w.to.isEmpty()) return null
        val sp = if (w.noSpaces) "" else " "
        // Japanese "AからBまで": A is everything before the from-marker.
        if (w.fromIsSuffix) {
            for (f in w.from) {
                val fi = t.indexOf(f)
                if (fi <= 0) continue
                val a = t.substring(0, fi).trim()
                val rest = t.substring(fi + f.length)
                for (to in w.to.sortedByDescending { it.length }) {
                    val ti = rest.indexOf(to)
                    if (ti <= 0) continue
                    val b = rest.substring(0, ti).trim()
                    if (a.isNotBlank() && b.isNotBlank()) return QueryIntent.Route(a, b)
                }
            }
            return null
        }
        val explicit = w.from.sortedByDescending { it.length }.firstOrNull { t.startsWith("$it$sp") && t.length > it.length }
        val body = if (explicit != null) t.removePrefix("$explicit$sp").trim() else t
        val connectors = if (explicit == null) (w.toBare ?: w.to) else w.to
        for (to in connectors.sortedByDescending { it.length }) {
            val idx = body.indexOf("$sp$to$sp")
            if (idx <= 0) continue
            val a = body.substring(0, idx).trim()
            val b = body.substring(idx + to.length + 2 * sp.length).trim()
            if (a.isBlank() || b.isBlank()) continue
            if (explicit == null) {
                // A bare "X to Y" is a route only when X is a place-shaped thing, not a question.
                val firstWord = a.substringBefore(' ')
                if (firstWord in w.notFrom) continue
                // ...and not a misheard verb ("navigat to the station"): the fuzzy pass owns those.
                if (looksLikeVerb(a, to, w)) continue
                val minLen = if (w.noSpaces) 2 else 3
                if (a.length < minLen || b.length < minLen) continue
            }
            return QueryIntent.Route(a, b)
        }
        return null
    }
}
