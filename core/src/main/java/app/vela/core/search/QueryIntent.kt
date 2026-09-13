package app.vela.core.search

/**
 * What a typed or spoken query MEANS, before it is handed to search (discussion #365, 2026-09-13).
 *
 * Voice search is dictation: the model turns speech into text and the text used to go straight
 * to the search box, so "take me home" searched for a place called "take me home". This parser
 * reads the handful of shapes people actually say and turns them into actions; anything it does
 * not recognise stays a plain search (null), so it can never make a query worse. Rule-based and
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

    private val TABLES = mapOf("en" to EN, "fr" to FR, "de" to DE, "es" to ES, "it" to IT, "pt" to PT, "nl" to NL)

    /** The languages with an intent vocabulary; any other language gets plain search. */
    val supportedLanguages: Set<String> get() = TABLES.keys

    /**
     * Parse [text] in language [lang] (ISO 639-1; a tag like "zh-TW" is reduced to its language).
     * Null = no recognised shape, run it as a plain search. English is tried as a fallback for
     * any language, because people mix ("navigate to" in a French phone is common).
     */
    fun parse(text: String, lang: String): QueryIntent? {
        val t = normalise(text)
        if (t.isBlank()) return null
        val code = lang.lowercase().substringBefore('-').substringBefore('_')
        val primary = TABLES[code]
        if (primary != null) parseWith(t, primary)?.let { return it }
        if (primary !== EN) parseWith(t, EN)?.let { return it }
        return null
    }

    private fun normalise(s: String): String =
        s.trim().lowercase()
            .replace(Regex("[\"“”«»]"), "")
            .replace(Regex("[!?.,;:]+$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun parseWith(t0: String, w: Words): QueryIntent? {
        var t = t0
        // Trailing politeness ("... please", "... s'il te plaît").
        for (p in w.please.sortedByDescending { it.length }) {
            if (t.endsWith(" $p")) t = t.removeSuffix(" $p").trim()
        }
        if (t.isBlank()) return null
        // ETA questions are whole phrases.
        if (w.eta.any { it == t }) return QueryIntent.Eta
        // "go home" / "take me to work" / bare "home".
        val afterGo = stripGo(t, w)
        val afterBare = if (afterGo == null) stripBare(t, w) else null
        val dest = afterGo ?: afterBare ?: t
        if (w.home.any { it == dest }) return QueryIntent.Home
        if (w.work.any { it == dest }) return QueryIntent.Work
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
        for (p in w.nearPrefix.sortedByDescending { it.length }) {
            if (q.startsWith("$p ")) { q = q.removePrefix("$p ").trim(); changed = true; break }
        }
        for (sfx in w.nearSuffix.sortedByDescending { it.length }) {
            if (q.endsWith(" $sfx")) { q = q.removeSuffix(" $sfx").trim(); changed = true; break }
        }
        if (changed && q.isNotBlank() && q != t) return QueryIntent.Search(q)
        return null
    }

    /** The text after a "navigate to" verb phrase, or null when the query has none. The phrase may
     *  sit behind up to four filler words ("can you please take me to", "find the fastest route to"). */
    private fun stripGo(t: String, w: Words): String? {
        for (g in w.go.sortedByDescending { it.length }) {
            if (t == g) return ""
            if (t.startsWith("$g ")) return t.removePrefix("$g ").trim()
            val idx = t.indexOf(" $g ")
            if (idx > 0 && t.substring(0, idx).split(' ').size <= 4) return t.substring(idx + g.length + 2).trim()
        }
        return null
    }

    private fun stripBare(t: String, w: Words): String? {
        for (g in w.goBare.sortedByDescending { it.length }) {
            if (t.startsWith("$g ")) return t.removePrefix("$g ").trim()
        }
        return null
    }

    /** "from A to B" always; "A to B" only when A is not a question word or a verb. */
    private fun splitRoute(t: String, w: Words): QueryIntent.Route? {
        val explicit = w.from.sortedByDescending { it.length }.firstOrNull { t.startsWith("$it ") }
        val body = if (explicit != null) t.removePrefix("$explicit ").trim() else t
        for (to in w.to.sortedByDescending { it.length }) {
            val idx = body.indexOf(" $to ")
            if (idx <= 0) continue
            val a = body.substring(0, idx).trim()
            val b = body.substring(idx + to.length + 2).trim()
            if (a.isBlank() || b.isBlank()) continue
            if (explicit == null) {
                // A bare "X to Y" is a route only when X is a place-shaped thing, not a question.
                val firstWord = a.substringBefore(' ')
                if (firstWord in w.notFrom) continue
                if (a.length < 3 || b.length < 3) continue
            }
            return QueryIntent.Route(a, b)
        }
        return null
    }
}
