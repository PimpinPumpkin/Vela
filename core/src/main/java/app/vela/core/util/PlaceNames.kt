package app.vela.core.util

import java.text.Normalizer

/**
 * Place-name comparison for "is this the same business", shared by the tap resolve (which Google
 * listing did the user tap), the Both-mode twin hiding (which open feature is Google's copy) and,
 * as a SQL mirror in `tools/build-places-region.sh`, the bake's own dedupe. One rule set, because
 * three private copies drifted apart and every drift was a duplicate icon or a wrong tap.
 *
 * Derived from a side by side of Google's answers and the open archive over the Davis fixture
 * (2026-09-21, 495 Google places): 290 matched by exact name after normalization, and the rest of
 * the real twins fell into three families that the rules below cover:
 *
 *  - DESCRIPTOR TAILS. Google says "Circle K | Gas Station", "U.S. Bank Branch", "Wells Fargo
 *    Bank", "Golden 1 Credit Union - Davis", "Hilton Garden Inn Davis Downtown", "Bank of
 *    America (with Drive-thru ATM)"; the archive says "CVS Pharmacy" where Google says "CVS".
 *    The extra words are generic (a category, a city, a branch word), so a name that is the
 *    other plus generic words is the same business ([Match.VARIANT]).
 *  - SPELLING. "&" against "and", "St" against "Street", "NY" against "New York", accents,
 *    possessives, legal suffixes ("James W. Childress, DDS Inc."), chain tails ("by Wyndham",
 *    "an Ascend Collection Hotel"), parentheticals ("(formerly Yakitori Yuchan)", "(aka Aggie
 *    Dental Care)"), a leading "The" or "Dr.". All folded by [normalized].
 *  - DISTINCTIVE OVERLAP. "Davis Dental Creations -Dr. Harsimran Bains" against "Davis Dental
 *    Creations -dr. Simran Bains", "The Local by Dunloe Brewing" against "Dunloe Brewing - The
 *    Local": the words that actually identify the business agree ([Match.OVERLAP]).
 *
 * And the false positives the old two-shared-words rule produced, which the generic list exists
 * to refuse: "Russell Park Apartments" is not "Orchard Park Apartments", "Havana Mini Mart" is not
 * "Kobe Mini Mart", "Davis Senior High School" is not "Davis Adult & Community Education School",
 * "Ergash Dental" is not "Davis Dental", and a brand's fuel station, pharmacy or counter is not
 * the store ("Shop Fuel Station" against "Shop" is a VARIANT of it, which callers that need the
 * store itself keep out with [same]).
 */
object PlaceNames {
    enum class Match { EXACT, VARIANT, OVERLAP, NONE }

    private val PAREN = Regex("\\([^)]*\\)|\\[[^]]*]")
    private val POSSESSIVE = Regex("[’']s\\b")
    private val PUNCT = Regex("[^\\p{L}\\p{N} ]")
    private val SPACES = Regex("\\s+")
    private val TRAILING_NUMBER = Regex("( (no|num|store|unit|#) ?\\p{N}{1,6}| \\p{N}{2,6})$")
    private val COMBINING = Regex("\\p{M}+")

    private val CONNECTORS = setOf("and", "of", "at", "by", "for", "with")
    private val LEGAL = setOf(
        "llc", "inc", "corp", "co", "ltd", "company", "incorporated", "corporation", "pc", "apc", "llp", "pllc", "pty", "plc",
        // the same suffixes in the app's other languages (a legal form is never a name)
        "gmbh", "ag", "kg", "ohg", "ug", "ev", "mbh",                 // de
        "sarl", "sas", "sa", "eurl", "sci", "snc",                    // fr
        "srl", "spa", "snc", "sas", "sapa",                           // it
        "sl", "slu", "sau", "cb", "scp",                              // es
        "lda", "ltda", "eireli", "sa", "me", "epp", "cia",            // pt
        "bv", "nv", "vof", "cv",                                      // nl
        "ab", "hb", "kb",                                             // sv
        "zoo", "sp", "spj", "ska",                                    // pl (sp. z o.o.)
        "ооо", "зао", "оао", "пао", "ип", "ао", "тов", "фоп", "пп",   // ru, uk
        "kft", "bt", "zrt", "nyrt",                                   // hu
        "בעמ",                                                        // he
    )
    private val CHAIN_TAILS = listOf(
        "by wyndham", "by marriott", "by hilton", "by ihg", "by choice hotels", "by best western", "by radisson",
        "an ascend collection hotel", "a tribute portfolio hotel", "a marriott hotel", "a hilton hotel",
    )
    private val ABBR = mapOf(
        "st" to "street", "ave" to "avenue", "blvd" to "boulevard", "rd" to "road", "ctr" to "center", "centre" to "center",
        "ny" to "new york", "nyc" to "new york", "univ" to "university", "mt" to "mount", "ft" to "fort", "hwy" to "highway",
        "pkwy" to "parkway", "sq" to "square", "jr" to "junior", "intl" to "international", "natl" to "national",
        "dr" to "doctor", "drs" to "doctors", "ln" to "lane", "ct" to "court", "ter" to "terrace", "pl" to "place",
        "str" to "strasse", "av" to "avenida", "avda" to "avenida", "bd" to "boulevard", "bvd" to "boulevard", "pza" to "plaza",
        "ул" to "улица", "пр" to "проспект", "просп" to "проспект", "пл" to "площадь", "бул" to "бульвар", "вул" to "вулиця",
    )

    /**
     * Words that describe a business rather than name it. A name made only of these matches
     * nothing by overlap, and a name that is another plus some of these is that business. Kept
     * as one list on purpose: it is the IDF of a places corpus written down, and it has to be the
     * same list in every caller.
     */
    val GENERIC: Set<String> get() = GENERIC_ALL
    private val GENERIC_EN: Set<String> = setOf(
        "the", "of", "and", "at", "in", "on", "a", "an", "for", "by", "with", "to",
        "store", "stores", "shop", "shoppe", "shops", "station", "center", "centers", "services", "service", "group", "office", "offices",
        "company", "branch", "bank", "atm", "atms", "pharmacy", "drug", "drugs", "grooming", "fuel", "gas", "market", "markets", "mart", "mini",
        "grocery", "restaurant", "cafe", "coffee", "inn", "hotel", "hotels", "suites", "motel", "apartments", "apartment", "clinic", "medical",
        "dental", "dentistry", "hospital", "church", "school", "university", "college", "salon", "studio", "bar", "grill", "kitchen",
        "bakery", "baking", "deli", "express", "downtown", "plaza", "mall", "building", "hall", "department", "dept", "emergency", "room",
        "outlet", "supply", "supplies", "food", "foods", "drinks", "liquor", "wine", "beer", "auto", "automotive", "repair", "car", "cars",
        "care", "health", "healthcare", "wellness", "fitness", "gym", "realty", "real", "estate", "agency", "agent", "agents", "realtor",
        "insurance", "law", "legal", "attorney", "attorneys", "financial", "tax", "consulting", "home", "homes", "self", "drive", "thru",
        "mobile", "pet", "pets", "animal", "veterinary", "vet", "spa", "nails", "nail", "hair", "beauty", "pizza", "sushi", "taqueria",
        "cuisine", "catering", "team", "associates", "partners", "properties", "management", "rental", "rentals", "storage", "cleaners",
        "laundry", "wash", "tire", "tires", "smog", "oil", "change", "lube", "glass", "body", "collision", "parts", "hardware", "lumber",
        "paint", "garden", "nursery", "florist", "flowers", "gifts", "gift", "books", "bookstore", "toys", "thrift", "resale",
        "boutique", "jewelry", "jewelers", "optical", "vision", "eye", "eyecare", "chiropractic", "physical", "therapy", "massage", "yoga",
        "pilates", "martial", "arts", "dance", "music", "lessons", "academy", "learning", "preschool", "daycare", "child", "childcare",
        "kids", "senior", "living", "community", "county", "city", "public", "library", "park", "pool", "recreation", "sports", "club",
        "lounge", "tavern", "pub", "brewing", "brewery", "roasters", "tea", "bagels", "donuts", "ice", "cream", "yogurt", "juice",
        "smoothie", "burgers", "chicken", "bbq", "mexican", "chinese", "japanese", "thai", "indian", "italian", "greek", "mediterranean",
        "vietnamese", "korean", "american", "cantina", "bistro", "eatery", "diner", "house", "place", "spot", "corner", "village",
        "square", "commons", "crossing", "ranch", "farm", "farms", "credit", "union", "federal", "mortgage", "lending", "loan", "loans",
        "wholesale", "retail", "convenience", "general", "family", "practice", "physician", "physicians", "doctor", "doctors", "dds",
        "dmd", "md", "dr", "professional", "professionals", "solutions", "systems", "technologies", "international", "national",
        "north", "south", "east", "west", "inc", "co",
        // Street types: "38th st grocery deli" and "Rsvp 38th Street Venture" share "38th street"
        // and are not one business; the ordinal alone must not carry it.
        "street", "avenue", "boulevard", "road", "lane", "court", "way", "highway", "parkway", "terrace", "alley", "route",
        // Trade and category words that a dense archive shares across unrelated businesses (Chelsea,
        // 2026-09-22: "Richard Phibbs Fine Art" and "Priska C. Juschka Fine Art" are two galleries,
        // "Murdock Young Architects" and "Murdock Solon Architects" two firms, one Amazon locker is
        // not the next one).
        "art", "fine", "gallery", "galleries", "contemporary", "modern", "design", "designs", "designer", "photography", "photo",
        "custom", "creative", "media", "productions", "entertainment", "events", "event", "advisor", "advisors", "capital",
        "investments", "wealth", "planning", "construction", "contractor", "contractors", "plumbing", "electric", "electrical",
        "roofing", "painting", "cleaning", "maintenance", "installation", "landscaping", "lawn", "moving", "movers", "transport",
        "logistics", "trucking", "printing", "signs", "sign", "locker", "lockers", "vending", "kiosk", "garage", "lot", "tours",
        "tour", "cruises", "ministries", "ministry", "fellowship", "temple", "mosque", "synagogue", "institute", "foundation",
        "association", "society", "council", "authority", "district", "state", "global", "worldwide", "united", "enterprises",
        "industries", "distributors", "distribution", "imports", "trading", "depot", "warehouse", "factory", "works", "labs", "lab",
        "laboratory", "research", "technology", "tech", "software", "digital", "online", "wireless", "communications", "network",
        "networks", "security", "protection", "safety", "surgery", "surgical", "surgeon", "surgeons", "pediatric", "pediatrics",
        "dermatology", "cardiology", "orthopedic", "orthopedics", "urgent", "primary", "specialists", "specialist", "lawyer",
        "lawyers", "firm", "cpa", "accounting", "accountants", "residences", "residence", "towers", "tower", "lofts", "loft",
        "condos", "condominiums", "estates", "villas", "manor", "gardens", "architects", "architect", "engineering", "engineers",
        "management", "marketing", "staffing", "recruiting", "consultants", "studios", "cafe", "cafes", "grille", "eats", "kitchens",
        "market", "shoppe", "emporium", "collective", "co-op", "coop", "gourmet", "organic", "natural", "fresh", "healthy", "urban",
        "downtown", "uptown", "midtown", "central", "metro", "metropolitan", "heights", "hills", "valley", "lake", "river", "bay",
        "harbor", "beach", "coast", "mountain", "creek", "grove", "oaks", "pines", "meadow", "meadows", "springs", "falls", "point",
        "landing", "station", "junction", "terminal", "campus", "annex", "wing", "suite", "floor", "level", "unit", "bldg",
    )

    /**
     * The same words in the app's other languages, one table each, all folded through
     * [normalized] (so no accents). The comparison uses the UNION rather than picking a language:
     * the names on a map belong to the region, not to the phone, and a user in Berlin with an
     * English phone still needs "Tankstelle" and "Apotheke" read as descriptors. A word that is a
     * descriptor in one language and a name in another is rare enough to accept.
     */
    private val GENERIC_FR = setOf(
        "le", "la", "les", "de", "du", "des", "et", "au", "aux", "chez", "sur", "sous", "en",
        "restaurant", "cafe", "bar", "brasserie", "bistro", "bistrot", "boulangerie", "patisserie", "pizzeria", "creperie", "traiteur",
        "boucherie", "charcuterie", "fromagerie", "epicerie", "supermarche", "hypermarche", "marche", "magasin", "boutique", "librairie",
        "papeterie", "tabac", "presse", "pharmacie", "parapharmacie", "clinique", "cabinet", "medical", "dentaire", "veterinaire",
        "hopital", "laboratoire", "banque", "agence", "assurance", "assurances", "immobilier", "immobiliere", "notaire", "avocat", "avocats",
        "coiffeur", "coiffure", "salon", "institut", "beaute", "hotel", "auberge", "gite", "camping", "garage", "carrosserie",
        "station", "service", "parking", "ecole", "college", "lycee", "universite", "eglise", "chapelle", "temple", "mairie", "poste",
        "bureau", "centre", "commercial", "galerie", "parc", "jardin", "place", "rue", "avenue", "boulevard", "chemin", "route",
        "impasse", "allee", "quai", "pont", "gare", "port", "plage", "maison", "village", "ville", "nord", "sud", "est", "ouest",
        "societe", "compagnie", "groupe", "entreprise", "services", "conseil", "gestion", "location", "vente", "achat", "reparation",
        "nettoyage", "pressing", "laverie", "fleuriste", "fleurs", "opticien", "optique", "bijouterie", "chaussures", "vetements", "mode",
    )
    private val GENERIC_DE = setOf(
        "der", "die", "das", "und", "am", "im", "an", "auf", "bei", "zum", "zur", "von", "vom", "mit",
        "restaurant", "gasthaus", "gasthof", "gaststatte", "wirtshaus", "kneipe", "bar", "cafe", "kaffee", "backerei", "konditorei",
        "metzgerei", "fleischerei", "pizzeria", "imbiss", "doner", "kebap", "biergarten", "brauerei", "weinstube", "eisdiele", "eiscafe",
        "supermarkt", "markt", "laden", "geschaft", "kaufhaus", "einkaufszentrum", "center", "zentrum", "apotheke", "drogerie",
        "praxis", "zahnarzt", "zahnarztpraxis", "arzt", "arztpraxis", "klinik", "krankenhaus", "tierarzt", "physiotherapie", "bank",
        "sparkasse", "volksbank", "raiffeisenbank", "versicherung", "versicherungen", "immobilien", "makler", "notar", "rechtsanwalt",
        "rechtsanwalte", "anwalt", "kanzlei", "steuerberater", "friseur", "frisor", "salon", "kosmetik", "hotel", "pension", "gastehaus",
        "ferienwohnung", "tankstelle", "autohaus", "werkstatt", "kfz", "waschanlage", "parkhaus", "parkplatz", "schule", "grundschule",
        "gymnasium", "kindergarten", "kita", "universitat", "hochschule", "kirche", "kapelle", "gemeinde", "rathaus", "post", "buro",
        "strasse", "platz", "weg", "allee", "gasse", "ring", "damm", "ufer", "bahnhof", "haltestelle", "hafen", "brucke", "park",
        "garten", "haus", "hof", "stadt", "dorf", "nord", "sud", "ost", "west", "gesellschaft", "gruppe", "firma", "betrieb",
        "service", "dienst", "dienste", "beratung", "verwaltung", "handel", "vertrieb", "reparatur", "reinigung", "blumen", "optik",
        "optiker", "juwelier", "schuhe", "mode", "bekleidung", "fitness", "studio", "sport", "schwimmbad", "bad", "bibliothek", "museum",
    )
    private val GENERIC_ES = setOf(
        "el", "la", "los", "las", "de", "del", "y", "al", "en", "con", "por", "para",
        "restaurante", "restaurant", "cafe", "cafeteria", "bar", "taberna", "cerveceria", "bodega", "panaderia", "pasteleria", "pizzeria",
        "taqueria", "marisqueria", "asador", "carniceria", "pescaderia", "fruteria", "supermercado", "mercado", "tienda", "almacen",
        "libreria", "papeleria", "farmacia", "clinica", "consultorio", "dental", "veterinaria", "veterinario", "hospital", "laboratorio",
        "banco", "caja", "agencia", "seguros", "inmobiliaria", "notaria", "abogado", "abogados", "peluqueria", "barberia", "salon",
        "estetica", "belleza", "hotel", "hostal", "pension", "posada", "gasolinera", "estacion", "servicio", "taller", "mecanico",
        "lavadero", "parking", "aparcamiento", "estacionamiento", "escuela", "colegio", "instituto", "universidad", "iglesia", "capilla",
        "parroquia", "ayuntamiento", "correos", "oficina", "centro", "comercial", "galeria", "parque", "jardin", "plaza", "calle",
        "avenida", "paseo", "camino", "carretera", "ronda", "puente", "playa", "puerto", "casa", "pueblo", "ciudad", "norte", "sur",
        "este", "oeste", "sociedad", "compania", "grupo", "empresa", "servicios", "asesoria", "gestion", "alquiler", "venta", "reparacion",
        "limpieza", "lavanderia", "floristeria", "flores", "optica", "joyeria", "zapateria", "ropa", "moda", "gimnasio", "deportes",
        "piscina", "biblioteca", "museo", "teatro", "cine", "san", "santa", "santo", "nuestra", "senora",
    )
    private val GENERIC_IT = setOf(
        "il", "lo", "la", "i", "gli", "le", "di", "del", "della", "dei", "delle", "e", "al", "alla", "da", "in", "con", "per",
        "ristorante", "trattoria", "osteria", "pizzeria", "bar", "caffe", "caffetteria", "pasticceria", "gelateria", "panificio",
        "panetteria", "forno", "macelleria", "pescheria", "salumeria", "enoteca", "birreria", "supermercato", "mercato", "negozio",
        "bottega", "libreria", "cartoleria", "tabacchi", "edicola", "farmacia", "parafarmacia", "clinica", "studio", "medico", "dentistico",
        "veterinario", "ospedale", "laboratorio", "banca", "agenzia", "assicurazioni", "immobiliare", "notaio", "avvocato", "avvocati",
        "parrucchiere", "barbiere", "salone", "estetica", "bellezza", "hotel", "albergo", "pensione", "locanda", "agriturismo", "benzinaio",
        "distributore", "stazione", "servizio", "officina", "carrozzeria", "autolavaggio", "parcheggio", "scuola", "liceo", "istituto",
        "universita", "chiesa", "cappella", "parrocchia", "comune", "municipio", "poste", "ufficio", "centro", "commerciale", "galleria",
        "parco", "giardino", "piazza", "via", "viale", "corso", "strada", "vicolo", "ponte", "spiaggia", "porto", "casa", "villa",
        "paese", "citta", "nord", "sud", "est", "ovest", "societa", "compagnia", "gruppo", "impresa", "servizi", "consulenza",
        "gestione", "noleggio", "vendita", "riparazione", "pulizie", "lavanderia", "fioraio", "fiori", "ottica", "gioielleria",
        "calzature", "abbigliamento", "moda", "palestra", "sport", "piscina", "biblioteca", "museo", "teatro", "cinema", "san", "santa",
    )
    private val GENERIC_PT = setOf(
        "o", "a", "os", "as", "de", "do", "da", "dos", "das", "e", "ao", "no", "na", "em", "com", "por", "para",
        "restaurante", "cafe", "cafeteria", "bar", "lanchonete", "padaria", "confeitaria", "pastelaria", "pizzaria", "churrascaria",
        "acougue", "peixaria", "mercearia", "supermercado", "mercado", "loja", "armazem", "livraria", "papelaria", "farmacia", "drogaria",
        "clinica", "consultorio", "odontologia", "veterinaria", "hospital", "laboratorio", "banco", "caixa", "agencia", "seguros",
        "imobiliaria", "cartorio", "advogado", "advogados", "cabeleireiro", "barbearia", "salao", "estetica", "beleza", "hotel", "pousada",
        "posto", "gasolina", "combustivel", "estacao", "servico", "oficina", "mecanica", "lavagem", "estacionamento", "escola", "colegio",
        "faculdade", "universidade", "igreja", "capela", "paroquia", "prefeitura", "camara", "correios", "escritorio", "centro",
        "comercial", "galeria", "parque", "jardim", "praca", "rua", "avenida", "alameda", "estrada", "rodovia", "travessa", "ponte",
        "praia", "porto", "casa", "vila", "cidade", "bairro", "norte", "sul", "leste", "oeste", "sociedade", "companhia", "grupo",
        "empresa", "servicos", "consultoria", "gestao", "aluguel", "locacao", "venda", "vendas", "reparo", "conserto", "limpeza",
        "lavanderia", "floricultura", "flores", "otica", "joalheria", "calcados", "roupas", "moda", "academia", "esportes", "piscina",
        "biblioteca", "museu", "teatro", "cinema", "sao", "santa", "santo", "nossa", "senhora",
    )
    private val GENERIC_NL = setOf(
        "de", "het", "een", "en", "van", "der", "den", "te", "bij", "aan", "op", "in", "met",
        "restaurant", "cafe", "eetcafe", "bar", "brasserie", "bakkerij", "banketbakkerij", "slagerij", "pizzeria", "snackbar", "cafetaria",
        "supermarkt", "markt", "winkel", "warenhuis", "winkelcentrum", "boekhandel", "apotheek", "drogisterij", "praktijk", "tandarts",
        "huisarts", "huisartsen", "kliniek", "ziekenhuis", "dierenarts", "fysiotherapie", "bank", "verzekeringen", "makelaar",
        "makelaardij", "notaris", "advocaat", "advocaten", "kapper", "kapsalon", "salon", "schoonheid", "hotel", "pension", "tankstation",
        "garage", "autobedrijf", "wasstraat", "parkeergarage", "parkeerplaats", "school", "basisschool", "college", "universiteit",
        "kerk", "kapel", "gemeente", "gemeentehuis", "stadhuis", "postkantoor", "kantoor", "centrum", "galerie", "park", "tuin",
        "plein", "straat", "laan", "weg", "steeg", "gracht", "kade", "dijk", "singel", "brug", "station", "haven", "strand", "huis",
        "hof", "dorp", "stad", "noord", "zuid", "oost", "west", "groep", "bedrijf", "diensten", "advies", "beheer", "verhuur",
        "verkoop", "reparatie", "schoonmaak", "wasserij", "stomerij", "bloemist", "bloemen", "optiek", "opticien", "juwelier",
        "schoenen", "kleding", "mode", "sportschool", "fitness", "sport", "zwembad", "bibliotheek", "museum", "theater", "bioscoop",
    )
    private val GENERIC_SV = setOf(
        "och", "i", "pa", "vid", "av", "till", "for", "med",
        "restaurang", "krog", "cafe", "kafe", "bar", "pub", "bageri", "konditori", "pizzeria", "grill", "kiosk", "livs", "livsmedel",
        "butik", "affar", "handel", "varuhus", "galleria", "kopcentrum", "bokhandel", "apotek", "klinik", "mottagning", "vardcentral",
        "tandlakare", "tandvard", "veterinar", "sjukhus", "bank", "forsakring", "forsakringar", "maklare", "maklarna", "advokat",
        "advokatbyra", "frisor", "frisersalong", "salong", "skonhet", "hotell", "vandrarhem", "pensionat", "bensinstation", "mack",
        "bilverkstad", "verkstad", "biltvatt", "parkering", "parkeringshus", "skola", "forskola", "gymnasium", "universitet", "hogskola",
        "kyrka", "kapell", "forsamling", "kommun", "kommunhus", "stadshus", "posten", "kontor", "centrum", "center", "park", "tradgard",
        "torg", "gatan", "gata", "vagen", "vag", "grand", "allen", "kajen", "bron", "station", "hamn", "strand", "hus", "gard", "by",
        "stad", "norra", "sodra", "ostra", "vastra", "bolag", "grupp", "foretag", "tjanster", "service", "radgivning", "forvaltning",
        "uthyrning", "forsaljning", "reparation", "stadning", "tvatt", "blomster", "blommor", "optik", "optiker", "guldsmed", "skor",
        "klader", "mode", "gym", "sport", "badhus", "simhall", "bibliotek", "museum", "teater", "bio",
    )
    private val GENERIC_PL = setOf(
        "i", "w", "we", "na", "pod", "przy", "u", "z", "ze", "do", "od",
        "restauracja", "kawiarnia", "bar", "pub", "piekarnia", "cukiernia", "pizzeria", "kebab", "bistro", "sklep", "market",
        "supermarket", "delikatesy", "hurtownia", "centrum", "handlowe", "galeria", "ksiegarnia", "apteka", "drogeria", "przychodnia",
        "gabinet", "stomatologiczny", "stomatolog", "dentysta", "lekarz", "lekarski", "klinika", "szpital", "weterynarz", "laboratorium",
        "bank", "ubezpieczenia", "nieruchomosci", "notariusz", "kancelaria", "adwokat", "radca", "prawny", "fryzjer", "salon",
        "kosmetyczny", "uroda", "hotel", "pensjonat", "hostel", "stacja", "paliw", "benzynowa", "warsztat", "mechanika", "myjnia",
        "parking", "szkola", "podstawowa", "liceum", "przedszkole", "uniwersytet", "kosciol", "kaplica", "parafia", "urzad", "gminy",
        "miasta", "poczta", "biuro", "osrodek", "park", "ogrod", "plac", "ulica", "aleja", "aleje", "droga", "rynek", "most",
        "dworzec", "przystanek", "port", "plaza", "dom", "wies", "miasto", "polnoc", "poludnie", "wschod", "zachod", "spolka",
        "grupa", "firma", "przedsiebiorstwo", "uslugi", "doradztwo", "zarzad", "wynajem", "sprzedaz", "naprawa", "serwis", "sprzatanie",
        "pralnia", "kwiaciarnia", "kwiaty", "optyk", "jubiler", "obuwie", "odziez", "moda", "silownia", "fitness", "sport", "basen",
        "biblioteka", "muzeum", "teatr", "kino", "sw", "swietego", "swietej",
    )
    private val GENERIC_RU = setOf(
        "и", "в", "во", "на", "у", "при", "с", "со", "от", "до", "к", "по", "для", "им", "имени",
        "ресторан", "кафе", "кофейня", "бар", "паб", "пекарня", "кондитерская", "пиццерия", "столовая", "бистро", "шаурма", "магазин",
        "супермаркет", "гипермаркет", "универсам", "рынок", "торговый", "центр", "тц", "трц", "галерея", "книжный", "аптека", "клиника",
        "поликлиника", "стоматология", "стоматологическая", "медицинский", "медцентр", "больница", "ветеринарная", "ветклиника",
        "лаборатория", "банк", "отделение", "банкомат", "страхование", "страховая", "недвижимость", "агентство", "нотариус", "адвокат",
        "юридическая", "парикмахерская", "барбершоп", "салон", "красоты", "гостиница", "отель", "хостел", "азс", "заправка",
        "автосервис", "сто", "шиномонтаж", "автомойка", "мойка", "парковка", "стоянка", "школа", "гимназия", "лицей", "детский",
        "сад", "университет", "институт", "колледж", "церковь", "храм", "собор", "часовня", "мечеть", "администрация", "почта",
        "офис", "бизнес", "парк", "сквер", "площадь", "улица", "проспект", "переулок", "бульвар", "шоссе", "набережная", "мост",
        "вокзал", "станция", "остановка", "порт", "пляж", "дом", "село", "город", "северный", "южный", "восточный", "западный",
        "компания", "группа", "фирма", "предприятие", "услуги", "сервис", "консалтинг", "управление", "аренда", "продажа", "ремонт",
        "уборка", "прачечная", "химчистка", "цветы", "оптика", "ювелирный", "обувь", "одежда", "мода", "фитнес", "спорт", "бассейн",
        "библиотека", "музей", "театр", "кинотеатр", "святого", "святой",
    )
    private val GENERIC_UK = setOf(
        "і", "та", "й", "у", "в", "на", "при", "з", "із", "від", "до", "по", "для", "ім", "імені",
        "ресторан", "кафе", "кав'ярня", "кавярня", "бар", "паб", "пекарня", "кондитерська", "піцерія", "їдальня", "бістро", "магазин",
        "супермаркет", "гіпермаркет", "ринок", "торговий", "центр", "тц", "трц", "галерея", "книгарня", "аптека", "клініка", "поліклініка",
        "стоматологія", "стоматологічна", "медичний", "медцентр", "лікарня", "ветеринарна", "ветклініка", "лабораторія", "банк",
        "відділення", "банкомат", "страхування", "страхова", "нерухомість", "агентство", "нотаріус", "адвокат", "юридична",
        "перукарня", "барбершоп", "салон", "краси", "готель", "хостел", "азс", "заправка", "автосервіс", "сто", "шиномонтаж",
        "автомийка", "мийка", "парковка", "стоянка", "школа", "гімназія", "ліцей", "дитячий", "садок", "університет", "інститут",
        "коледж", "церква", "храм", "собор", "каплиця", "мечеть", "адміністрація", "пошта", "офіс", "бізнес", "парк", "сквер",
        "площа", "вулиця", "проспект", "провулок", "бульвар", "шосе", "набережна", "міст", "вокзал", "станція", "зупинка", "порт",
        "пляж", "дім", "будинок", "село", "місто", "північний", "південний", "східний", "західний", "компанія", "група", "фірма",
        "підприємство", "послуги", "сервіс", "консалтинг", "управління", "оренда", "продаж", "ремонт", "прибирання", "пральня",
        "хімчистка", "квіти", "оптика", "ювелірний", "взуття", "одяг", "мода", "фітнес", "спорт", "басейн", "бібліотека", "музей",
        "театр", "кінотеатр", "святого", "святої",
    )
    private val GENERIC_HU = setOf(
        "a", "az", "es", "utcai", "teri",
        "etterem", "vendeglo", "csarda", "kavezo", "kavehaz", "bar", "kocsma", "sorozo", "pekseg", "cukraszda", "pizzeria", "bufe",
        "gyorsetterem", "bolt", "uzlet", "abc", "elelmiszer", "szupermarket", "hipermarket", "piac", "bevasarlokozpont", "kozpont",
        "plaza", "konyvesbolt", "gyogyszertar", "patika", "drogeria", "rendelo", "fogaszat", "fogorvos", "orvosi", "klinika", "korhaz",
        "allatorvos", "allatorvosi", "labor", "bank", "fiok", "biztosito", "ingatlan", "ingatlaniroda", "kozjegyzo", "ugyved",
        "ugyvedi", "iroda", "fodrasz", "fodraszat", "szalon", "szepsegszalon", "hotel", "szallo", "szalloda", "panzio", "benzinkut",
        "toltoallomas", "autoszerviz", "szerviz", "gumiszerviz", "automoso", "parkolo", "parkolohaz", "iskola", "altalanos",
        "gimnazium", "ovoda", "bolcsode", "egyetem", "foiskola", "templom", "kapolna", "plebania", "onkormanyzat", "polgarmesteri",
        "hivatal", "posta", "park", "kert", "ter", "utca", "ut", "korut", "sugarut", "koz", "sor", "hid", "palyaudvar", "allomas",
        "megallo", "kikoto", "strand", "haz", "falu", "varos", "eszak", "eszaki", "del", "deli", "kelet", "keleti", "nyugat", "nyugati",
        "tarsasag", "csoport", "ceg", "vallalat", "szolgaltatas", "szolgaltatasok", "tanacsadas", "kezeles", "berles", "kolcsonzo",
        "eladas", "javitas", "takaritas", "mosoda", "patyolat", "viragbolt", "virag", "optika", "ekszer", "ekszeresz", "cipo", "ruha",
        "divat", "edzoterem", "fitnesz", "sport", "uszoda", "konyvtar", "muzeum", "szinhaz", "mozi", "szent",
    )
    private val GENERIC_HE = setOf(
        "ה", "ו", "של", "על", "ב", "ל", "עם", "בית", "בת",
        "מסעדה", "מסעדת", "קפה", "בר", "פאב", "מאפייה", "קונדיטוריה", "פיצריה", "פיצה", "שווארמה", "פלאפל", "חומוס", "סושי", "מכולת",
        "סופר", "סופרמרקט", "שוק", "חנות", "קניון", "מרכז", "מסחרי", "בית מרקחת", "מרקחת", "קליניקה", "מרפאה", "מרפאת", "שיניים",
        "רופא", "וטרינר", "וטרינרית", "חולים", "מעבדה", "בנק", "סניף", "כספומט", "ביטוח", "נדלן", "תיווך", "נוטריון", "עורך", "עורכי",
        "דין", "מספרה", "ספר", "סלון", "יופי", "מלון", "אכסניה", "צימר", "תחנת", "דלק", "מוסך", "פנצריה", "שטיפת", "רכב", "חניון",
        "חניה", "בית ספר", "ספר", "גן", "ילדים", "תיכון", "אוניברסיטה", "מכללה", "כנסת", "כנסייה", "מסגד", "עירייה", "מועצה", "דואר",
        "משרד", "משרדי", "פארק", "גינה", "כיכר", "רחוב", "שדרות", "שדרת", "דרך", "סמטת", "גשר", "תחנה", "רכבת", "נמל", "חוף",
        "כפר", "עיר", "צפון", "דרום", "מזרח", "מערב", "חברה", "קבוצה", "חברת", "שירותים", "שירות", "ייעוץ", "ניהול", "השכרה",
        "השכרת", "מכירה", "תיקון", "תיקוני", "ניקיון", "מכבסה", "פרחים", "אופטיקה", "תכשיטים", "נעליים", "בגדים", "אופנה", "חדר",
        "כושר", "ספורט", "בריכה", "ספרייה", "מוזיאון", "תיאטרון", "קולנוע",
    )
    private val GENERIC_ALL: Set<String> = GENERIC_EN + GENERIC_FR + GENERIC_DE + GENERIC_ES + GENERIC_IT + GENERIC_PT + GENERIC_NL +
        GENERIC_SV + GENERIC_PL + GENERIC_RU + GENERIC_UK + GENERIC_HU + GENERIC_HE

    /**
     * Scripts written without spaces (Han, kana, Hangul mostly, Thai) get no tokens, so a CJK
     * name is compared as a STRING: the descriptor suffixes are stripped from both ends and the
     * shorter has to be the whole of, or sit inside, the longer. "スターバックス 渋谷店" is
     * "スターバックス" (VARIANT), "星巴克咖啡" is "星巴克", "セブン-イレブン渋谷駅前店" contains
     * "セブン-イレブン" (OVERLAP, the extra is a branch name).
     */
    private val CJK = Regex("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}\\p{IsThai}]")
    private val CJK_SUFFIXES = listOf(
        // ja
        "駅前店", "本店", "支店", "分店", "店舗", "店", "薬局", "銀行", "支行", "病院", "医院", "診療所", "歯科", "学校", "公園", "駅", "駐車場",
        "営業所", "事務所", "株式会社", "有限会社", "合同会社", "商店", "商会", "食堂", "教室", "支社", "本社", "工場", "倉庫",
        // zh
        "餐厅", "餐廳", "饭店", "飯店", "酒店", "咖啡厅", "咖啡館", "咖啡", "超市", "便利店", "药店", "药房", "藥局", "藥房", "银行", "分行",
        "医院", "醫院", "诊所", "診所", "学校", "學校", "公园", "公園", "停车场", "停車場", "有限公司", "公司", "商场", "商場", "购物中心",
        "購物中心", "大厦", "大廈", "中心", "总店", "總店", "旗舰店", "旗艦店", "专卖店", "專賣店", "分店",
        // ko
        "지점", "본점", "점", "약국", "은행", "병원", "의원", "학교", "공원", "주차장", "주식회사", "카페", "식당", "마트", "편의점",
        // th
        "สาขา", "ธนาคาร", "โรงพยาบาล", "โรงเรียน", "ร้าน",
    )

    private fun cjkCore(s: String): String {
        var t = s.replace(" ", "")
        var changed = true
        while (changed) {
            changed = false
            for (x in CJK_SUFFIXES) {
                if (t.length > x.length && t.endsWith(x)) { t = t.removeSuffix(x); changed = true }
                if (t.length > x.length && t.startsWith(x)) { t = t.removePrefix(x); changed = true }
            }
        }
        return t
    }

    private fun cjkMatch(na: String, nb: String): Match {
        val a = cjkCore(na); val b = cjkCore(nb)
        if (a.isEmpty() || b.isEmpty()) return Match.NONE
        if (a == b) return if (na.replace(" ", "") == nb.replace(" ", "")) Match.EXACT else Match.VARIANT
        val (short, long) = if (a.length <= b.length) a to b else b to a
        if (short.length < 2) return Match.NONE
        return if (long.contains(short)) Match.OVERLAP else Match.NONE
    }

    /** Accents folded, case and punctuation gone, "&" read as "and", legal suffixes, chain tails,
     *  parentheticals, a leading "the"/"dr" and a trailing store number dropped, common street
     *  abbreviations expanded, and runs of single letters joined ("u s bank" is "us bank"). */
    fun normalized(name: String?): String {
        if (name.isNullOrBlank()) return ""
        var s = Normalizer.normalize(name, Normalizer.Form.NFKD).replace(COMBINING, "").lowercase()
        // The letters NFKD does not decompose: German, Nordic, Polish, and the Cyrillic yo, which
        // the two sources write both ways.
        s = s.replace("ß", "ss").replace("æ", "ae").replace("ø", "o").replace("œ", "oe").replace("ł", "l").replace("đ", "d").replace("ð", "d").replace("þ", "th").replace("ё", "е")
        s = s.replace(PAREN, " ")
        s = s.replace("&", " and ").replace("+", " and ")
        s = s.replace(POSSESSIVE, "s")
        s = s.replace(PUNCT, " ").replace(SPACES, " ").trim()
        for (tail in CHAIN_TAILS) if (s.endsWith(" $tail")) s = s.removeSuffix(" $tail").trim()
        s = s.replace(TRAILING_NUMBER, "").trim()
        val words = ArrayList<String>()
        for (w in s.split(' ')) {
            if (w.isEmpty() || w in LEGAL) continue
            val exp = ABBR[w]
            if (exp != null) words.addAll(exp.split(' ')) else words.add(w)
        }
        // "u s bank" -> "us bank": abbreviations written with dots come through as single letters.
        val joined = ArrayList<String>(words.size)
        var run = StringBuilder()
        fun flush() { if (run.isNotEmpty()) { joined.add(run.toString()); run = StringBuilder() } }
        for (w in words) {
            if (w.length == 1 && w[0].isLetter()) run.append(w) else { flush(); joined.add(w) }
        }
        flush()
        // A lone "s" after a word is a possessive the punctuation pass split off ("JOE S DINER").
        val glued = ArrayList<String>(joined.size)
        for (w in joined) {
            if (w == "s" && glued.isNotEmpty()) glued[glued.lastIndex] = glued.last() + "s" else glued.add(w)
        }
        joined.clear(); joined.addAll(glued)
        if (joined.isNotEmpty() && joined[0] == "the") joined.removeAt(0)
        // A connector left dangling by a dropped suffix ("Avid & Co." is "avid and" without this).
        while (joined.isNotEmpty() && joined.last() in CONNECTORS) joined.removeAt(joined.lastIndex)
        while (joined.isNotEmpty() && joined.first() in CONNECTORS) joined.removeAt(0)
        return joined.joinToString(" ")
    }

    fun tokens(name: String?): List<String> = normalized(name).split(' ').filter { it.isNotEmpty() }

    /** The words that identify a business: not generic, not a number, at least two letters
     *  ("US Bank" is named by "us"; a single letter never names anything on its own). */
    fun distinctive(name: String?, extraGeneric: Set<String> = emptySet()): Set<String> =
        tokens(name).filter { isDistinctive(it, extraGeneric) }.toSet()

    // A number that survived the trailing-store-number strip IS the name ("Thai 5", "Pho 175",
    // "Studio 54"): without it those names were all generic words and matched nothing.
    /** [words] with each plural replaced by its singular WHEN the other name has that singular. */
    private fun foldPlurals(words: List<String>, other: Set<String>): List<String> = words.map { w ->
        if (w.length > 3 && w.endsWith("s") && !w.endsWith("ss") && w.dropLast(1) in other) w.dropLast(1) else w
    }

    /** Two identifying words, or one of at least five letters: "speedee", "nordstrom", "laurenzos"
     *  carry a name on their own; "finn", "bayou", "main" do not (Midtown and Houston, 2026-09-22:
     *  "Bayou Place" against "Bunnies On The Bayou", "Bryant Health Clinic" against an osteria
     *  in Bryant Park). */
    private fun strongCore(words: List<String>): Boolean =
        words.size >= 2 || (words.size == 1 && words[0].length >= 5 && !isOrdinal(words[0]))

    private val ORDINAL = Regex("\\d+(st|nd|rd|th)?")
    private fun isOrdinal(w: String) = ORDINAL.matches(w)

    /**
     * Words that are generic IN THIS POOL: a token carried by [minNames] or more of [names] names
     * a neighborhood or a mall rather than a business ("Memorial Heights", "NoMad", "Flatiron"),
     * and a caller passes the result as `extraGeneric` so two businesses that merely share the
     * neighborhood are not one. The pool is whatever the caller is comparing against (the places
     * on screen, a search's results), so it costs nothing to compute.
     */
    fun localGeneric(names: Collection<String?>, minNames: Int = 3): Set<String> {
        val counts = HashMap<String, Int>()
        for (n in names) for (t in tokens(n).toSet()) counts[t] = (counts[t] ?: 0) + 1
        return counts.filterValues { it >= minNames }.keys
    }

    private fun isDistinctive(w: String, extraGeneric: Set<String>): Boolean =
        (w !in GENERIC && w !in extraGeneric && !pluralGeneric(w) && w.length >= 2) || (w.length == 1 && w[0].isDigit())

    /** "studios", "salons", "cleaners" are as generic as their singulars. */
    private fun pluralGeneric(w: String): Boolean = w.length > 3 && w.endsWith("s") && w.dropLast(1) in GENERIC

    /** True when two names identify the same business under [normalized] (the strict test). */
    fun same(a: String?, b: String?): Boolean {
        val na = normalized(a)
        return na.isNotEmpty() && na == normalized(b)
    }

    /**
     * How [a] and [b] relate. [extraGeneric] adds words that are generic in THIS comparison, such as
     * the town's name out of a listing's address ("FIT House Davis" is "FIT House" in Davis, and a
     * different gym elsewhere). Distance is the caller's business: a VARIANT is the same business
     * on the same lot, an OVERLAP wants the two within tens of meters.
     */
    fun match(a: String?, b: String?, extraGeneric: Set<String> = emptySet()): Match {
        val na = normalized(a); val nb = normalized(b)
        if (na.isEmpty() || nb.isEmpty()) return Match.NONE
        if (na == nb) return Match.EXACT
        if (CJK.containsMatchIn(na) || CJK.containsMatchIn(nb)) return cjkMatch(na, nb)
        // Plurals fold PAIRWISE ("Sola Salons" against "Sola Salon Studios"): a word loses its "s"
        // only when the other name carries the singular, so "Davis" and "Wells" stay themselves.
        val ra = na.split(' '); val rb = nb.split(' ')
        val la = foldPlurals(ra, rb.toSet()); val lb = foldPlurals(rb, ra.toSet())
        if (la == lb) return Match.EXACT
        val ta = la.toSet(); val tb = lb.toSet()
        val da = la.filter { isDistinctive(it, extraGeneric) }.toSet(); val db = lb.filter { isDistinctive(it, extraGeneric) }.toSet()
        val nested = ta.containsAll(tb) || tb.containsAll(ta)
        if (nested) {
            val extra = if (ta.size >= tb.size) ta - tb else tb - ta
            val core = if (ta.size >= tb.size) tb else ta
            val coreDistinct = core.filter { isDistinctive(it, extraGeneric) }
            // The shorter name must still NAME something: "Hair" inside "Hair Studio" is two
            // descriptions, not a business.
            if (coreDistinct.isEmpty()) return Match.NONE
            if (extra.none { isDistinctive(it, extraGeneric) }) return Match.VARIANT
            // Extra words that are not generic ("SpeeDee-Midas" over "SpeeDee", "Sam's
            // Mediterranean Cuisine" over "Sam's Cuisine"): the same business when what the
            // shorter name identifies is all there. A short lone word is not enough of an
            // identity to claim a longer name ("The Finn" is not "Dish Society at Finn Hall").
            return if (strongCore(coreDistinct)) Match.OVERLAP else Match.NONE
        }
        // Not nested: two identifying words in common ("Davis Dental Creations" plus a dentist's
        // surname, "Dunloe" and "Local"). ONE shared word is not enough: "Arroyo Park" is not
        // "Arroyo Pool", "Avid & Co." is not "The Avid Reader Bookstore".
        if ((da intersect db).size >= 2) return Match.OVERLAP
        // Three more families from the Midtown and Houston side by sides (2026-09-22):
        // - a BRAND PREFIX of two or more words with an identifying one among them: "Bank of
        //   America Financial Center" and "Bank of America ATM";
        val prefix = la.zip(lb).takeWhile { (x, y) -> x == y }.size
        if (prefix >= 2 && la.take(prefix).any { isDistinctive(it, extraGeneric) }) return Match.OVERLAP
        // - the shorter name, less generic words at its ends, is a PHRASE inside the longer ("23rd
        //   Street Dental" of "23rd Street Dental Associates" inside "My NYC Dentist - 23rd Street
        //   Dental"). Three words carry it even when the only identifying one is a street number
        //   (that is how New York names things); two words need an identifying word that is not.
        val (short, long) = if (la.size <= lb.size) la to lb else lb to la
        val lead = short.dropWhile { !isDistinctive(it, extraGeneric) }
        for (k in lead.size downTo 2) {
            val phrase = lead.take(k)
            val named = phrase.any { isDistinctive(it, extraGeneric) && !isOrdinal(it) } || (k >= 3 && phrase.any { isDistinctive(it, extraGeneric) })
            if (named && long.windowed(k).any { it == phrase }) return Match.OVERLAP
        }
        // - the shorter name's identifying words all sit in the longer's, which has more of them
        //   ("Laurenzo's Restaurant" against "Laurenzo's Prime Rib"), when the shorter is more than
        //   one word and its identity is not just a street number. A one-word name inside a longer
        //   one stays out ("Avid & Co." is still not "The Avid Reader"), and so does "Arroyo Park"
        //   against "Arroyo Pool" (the same one identifying word on both sides).
        // With ONE identifying word in the shorter name, the longer has to LEAD with it (a brand
        // in front: "Laurenzo's Prime Rib", "Walgreens Photo", "Chase Home Lending"); a word that
        // merely appears inside the longer name is a neighborhood or a landmark far more often
        // than a business ("Bayou Place" against "Bunnies On The Bayou").
        val (dShort, dLong) = if (short === la) da to db else db to da
        if (short.size >= 2 && dShort.isNotEmpty() && dLong.size > dShort.size && dLong.containsAll(dShort) &&
            dShort.any { !isOrdinal(it) } && (dShort.size >= 2 || long.first() == dShort.single())
        ) return Match.OVERLAP
        return Match.NONE
    }

    /** The loose test: the same business by [match], any kind but NONE. */
    fun agree(a: String?, b: String?, extraGeneric: Set<String> = emptySet()): Boolean =
        match(a, b, extraGeneric) != Match.NONE

    /**
     * [agree] with the two places' KINDS (the icon group, "fuel", "food", "shop"...; null or
     * "default" = unknown) in hand: an OVERLAP between two known, different kinds is not a match.
     * "Cathcart Station Alfy's" (a fuel station) and "Cathcart Station LLC" (a pizza place) share
     * their identifying words and are two businesses on one lot; a VARIANT or EXACT still counts
     * across kinds, because "Safeway Pharmacy" and "Safeway" ARE one business in two listings.
     */
    fun sameBusiness(a: String?, kindA: String?, b: String?, kindB: String?, extraGeneric: Set<String> = emptySet()): Boolean {
        val m = match(a, b, extraGeneric)
        if (m == Match.NONE) return false
        if (m == Match.OVERLAP && knownKind(kindA) && knownKind(kindB) && kindA != kindB) return false
        return true
    }

    /** Two fuel stations within [FUEL_LOT_M] are one station: a forecourt is one per lot, and the
     *  sources name it after different things (the brand, the operator, the shop inside). Two
     *  stations facing each other across a road are the case to refuse: when both sides carry a
     *  house number and the numbers differ they are two lots whatever the distance, and the
     *  distance itself is short of a road's width plus two setbacks (user 2026-09-22). */
    fun sameFuelLot(kindA: String?, kindB: String?, distanceM: Double, numberA: String? = null, numberB: String? = null): Boolean {
        if (kindA != FUEL_KIND || kindB != FUEL_KIND) return false
        if (!numberA.isNullOrBlank() && !numberB.isNullOrBlank() && numberA != numberB) return false
        return distanceM < FUEL_LOT_M
    }

    /** The house number a street address starts with ("16315 State Route 9 SE" -> "16315"). */
    fun houseNumber(address: String?): String? =
        address?.trimStart()?.takeWhile { it.isDigit() }?.takeIf { it.isNotEmpty() }

    const val FUEL_KIND = "fuel"
    const val FUEL_LOT_M = 30.0
    private fun knownKind(k: String?) = !k.isNullOrBlank() && k != "default"

    /** The words of a town out of a listing's address ("239 G St, Davis, CA 95616" -> davis, ca),
     *  to pass as [extraGeneric]: a name that ends in its own town is the name. */
    fun cityWords(address: String?): Set<String> {
        if (address.isNullOrBlank()) return emptySet()
        val parts = address.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return emptySet()
        return parts.drop(1).flatMap { tokens(it) }.filter { it.length >= 2 && !it.all { c -> c.isDigit() } }.toSet()
    }
}
