package app.vela.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceNamesTest {
    @Test fun `a store number does not make a different business`() {
        assertTrue(PlaceNames.same("Shop", "Shop #1561"))
        assertTrue(PlaceNames.same("SHOP STORE 1561", "shop"))
        assertTrue(PlaceNames.same("Shop No 7", "Shop"))
    }

    @Test fun `punctuation and case do not either`() {
        assertTrue(PlaceNames.same("Joe's Diner", "JOE S DINER"))
        assertTrue(PlaceNames.same("7-Eleven", "7 Eleven"))
    }

    @Test fun `the brand's other listings are NOT the same business`() {
        // The whole point: these are what a tap on the store used to open.
        assertFalse(PlaceNames.same("Shop", "Shop Fuel Station"))
        assertFalse(PlaceNames.same("Shop", "Shop Pharmacy"))
        assertFalse(PlaceNames.same("Shop", "Coinstar"))
    }

    @Test fun `an empty name matches nothing`() {
        assertFalse(PlaceNames.same("", ""))
        assertFalse(PlaceNames.same(null, null))
        assertFalse(PlaceNames.same("   ", "Shop"))
    }

    @Test fun `a non-latin name survives normalization`() {
        assertTrue(PlaceNames.same("Кафе Уют", "кафе уют"))
        assertTrue(PlaceNames.same("東京駅", "東京駅"))
    }
}

/** Pairs from a side by side of Google and the open archive over the Davis fixture (2026-09-21). */
class PlaceNamesMatchTest {
    private fun m(a: String, b: String, city: Set<String> = setOf("davis", "ca")) = PlaceNames.match(a, b, city)

    @Test fun `normalization folds the spellings the two sources disagree on`() {
        assertEquals("university park inn and suites", PlaceNames.normalized("University Park Inn & Suites, an Ascend Collection Hotel"))
        assertEquals("g street wunderbar", PlaceNames.normalized("G St Wunderbar"))
        assertEquals("noahs new york bagels", PlaceNames.normalized("Noah's NY Bagels"))
        assertEquals("boheme clothing and gifts", PlaceNames.normalized("Bohème Clothing & Gifts"))
        assertEquals("yuchan shokudo", PlaceNames.normalized("Yuchan Shokudo (formerly Yakitori Yuchan)"))
        assertEquals("james w childress dds", PlaceNames.normalized("James W. Childress, DDS Inc."))
        assertEquals("us bank", PlaceNames.normalized("U.S. Bank"))
        assertEquals("nugget", PlaceNames.normalized("Nugget #12"))
        assertEquals("doctor keith grote dmd", PlaceNames.normalized("Dr. Keith Grote, DMD"))
        assertEquals("la quinta inn and suites", PlaceNames.normalized("La Quinta Inn & Suites by Wyndham"))
    }

    @Test fun `a descriptor tail is the same business`() {
        assertEquals(PlaceNames.Match.VARIANT, m("Circle K | Gas Station", "Circle K"))
        assertEquals(PlaceNames.Match.VARIANT, m("U.S. Bank Branch", "U.S. Bank"))
        assertEquals(PlaceNames.Match.VARIANT, m("Wells Fargo Bank", "Wells Fargo"))
        assertEquals(PlaceNames.Match.VARIANT, m("Golden 1 Credit Union - Davis", "Golden 1 Credit Union"))
        assertEquals(PlaceNames.Match.VARIANT, m("Hilton Garden Inn Davis Downtown", "Hilton Garden Inn"))
        assertEquals(PlaceNames.Match.VARIANT, m("CVS", "CVS Pharmacy"))
        assertEquals(PlaceNames.Match.VARIANT, m("FIT House Davis", "FIT House"))
        assertEquals(PlaceNames.Match.EXACT, m("Bank of America (with Drive-thru ATM)", "Bank of America"))
        assertTrue(PlaceNames.agree("Raising Cane's Chicken Fingers", "Raising Cane's"))
        assertEquals(PlaceNames.Match.EXACT, m("SPCA | Yolo County Thrift Store", "SPCA Yolo County Thrift Store"))
        assertEquals(PlaceNames.Match.EXACT, m("Activities and Recreation Center | UC Davis", "Activities and Recreation Center UC Davis"))
    }

    @Test fun `the identifying words agreeing is the same business`() {
        assertEquals(PlaceNames.Match.OVERLAP, m("Davis Dental Creations -Dr. Harsimran Bains", "Davis Dental Creations -dr. Simran Bains"))
        assertTrue(PlaceNames.agree("Dunloe Brewing - The Local", "The Local by Dunloe Brewing"))
        assertEquals(PlaceNames.Match.OVERLAP, m("SpeeDee-Midas", "SpeeDee"))
        assertTrue(PlaceNames.agree("Sam's Mediterranean Cuisine", "Sam's Cuisine")) // a cuisine word is generic: VARIANT
        assertEquals(PlaceNames.Match.OVERLAP, m("Jennifer P. Clary, M.D.", "Jennifer Papazian Clary, M.d."))
    }

    @Test fun `shared generic words are not a match`() {
        assertEquals(PlaceNames.Match.NONE, m("Russell Park Apartments", "Orchard Park Apartments"))
        assertEquals(PlaceNames.Match.NONE, m("Havana Mini Mart", "Kobe Mini Mart"))
        assertEquals(PlaceNames.Match.NONE, m("Davis Senior High School", "Davis Adult & Community Education School"))
        assertEquals(PlaceNames.Match.NONE, m("Ergash Dental - Dr Nasrin Ergash", "Davis Dental"))
        assertEquals(PlaceNames.Match.NONE, m("Hair Studio", "Hair"))
        assertEquals(PlaceNames.Match.NONE, m("Arroyo Park", "Arroyo Pool"))
        assertEquals(PlaceNames.Match.NONE, m("Avid & Co.", "The Avid Reader Bookstore"))
        assertFalse(PlaceNames.agree("Fast & Easy Mart", "Chevron"))
    }

    @Test fun `a brand's other listings are variants, never exact`() {
        assertEquals(PlaceNames.Match.VARIANT, m("Zorpmart Fuel Station", "Zorpmart"))
        assertEquals(PlaceNames.Match.VARIANT, m("Petco Grooming", "Petco"))
        assertFalse(PlaceNames.same("Zorpmart Fuel Station", "Zorpmart"))
    }

    @Test fun `brand prefixes, phrases inside longer names, plurals and titles`() {
        assertTrue(PlaceNames.agree("Bank of America Financial Center", "Bank of America ATM"))
        assertTrue(PlaceNames.agree("My NYC Dentist - 23rd Street Dental", "23rd Street Dental Associates", setOf("new", "york", "ny")))
        assertTrue(PlaceNames.agree("Sola Salons", "Sola Salon Studios"))
        assertEquals(PlaceNames.Match.EXACT, m("Dr.'s Express Urgent Care", "Doctors Express Urgent Care"))
        assertTrue(PlaceNames.agree("Laurenzo's Prime Rib", "Laurenzo's Restaurant"))
        assertEquals(PlaceNames.Match.VARIANT, m("Dr. Keith Grote, DMD", "Keith Grote"))
        // Street words are not identity.
        assertEquals(PlaceNames.Match.NONE, m("38th st grocery deli", "Rsvp 38th Street Venture Lp", setOf("new", "york", "ny")))
        assertEquals(PlaceNames.Match.NONE, m("Avid & Co.", "The Avid Reader Bookstore"))
    }

    @Test fun `a short lone word does not claim a longer name`() {
        assertEquals(PlaceNames.Match.NONE, m("The Finn", "Dish Society at Finn Hall"))
        assertEquals(PlaceNames.Match.NONE, m("Bayou Place", "Bunnies On The Bayou"))
        assertEquals(PlaceNames.Match.NONE, m("Bryant Health Clinic", "Osteria Delbianco Bryant Park"))
        assertEquals(PlaceNames.Match.OVERLAP, m("Nordstrom", "Nordstrom NYC Flagship", setOf("new", "york", "ny")))
        assertEquals(PlaceNames.Match.OVERLAP, m("Patsy's Pizzeria Flatiron", "Patsy's"))
    }

    @Test fun `the bake's copy of the generic list matches the app's`() {
        // tools/place-generic-words.txt is read by tools/build-places-region.sh for its core-key
        // fold; a word added here has to land there too, or the bake and the app disagree on what
        // a name is. Regenerate: sort the words of GENERIC into the file, one per line.
        val f = java.io.File("../tools/place-generic-words.txt").takeIf { it.exists() } ?: java.io.File("tools/place-generic-words.txt")
        assertTrue("tools/place-generic-words.txt is missing", f.exists())
        val file = f.readLines().filter { it.isNotBlank() }.toSet()
        assertEquals(PlaceNames.GENERIC, file)
    }

    @Test fun `a neighborhood shared across the pool is generic there`() {
        val pool = listOf("Memorial Heights Reflexology", "The Shops at Memorial Heights", "Memorial Heights Dental", "Joe's Pizza")
        val local = PlaceNames.localGeneric(pool)
        assertTrue(local.containsAll(setOf("memorial", "heights")))
        assertEquals(PlaceNames.Match.NONE, PlaceNames.match("The Shops at Memorial Heights", "Memorial Heights Reflexology", local))
        assertEquals(PlaceNames.Match.OVERLAP, PlaceNames.match("The Shops at Memorial Heights", "Memorial Heights Reflexology"))
    }

    @Test fun `a number can be the name`() {
        assertEquals(PlaceNames.Match.VARIANT, m("Thai 5, Thai Food Express", "Thai 5"))
        assertEquals(PlaceNames.Match.NONE, m("Thai 5", "Thai 9"))
        assertTrue(PlaceNames.agree("Salon Vintage: Le fox Hair Care - Hairstylist", "Salon Vintage"))
    }

    @Test fun `an overlap across two known kinds is two businesses on one lot`() {
        assertFalse(PlaceNames.sameBusiness("Covell Station Marco's", "fuel", "Covell Station LLC", "food"))
        assertTrue(PlaceNames.sameBusiness("Covell Station Marco's", "fuel", "Covell Station LLC", null))
        assertTrue(PlaceNames.sameBusiness("Safeway Pharmacy", "health", "Safeway", "shop")) // a VARIANT crosses kinds
        assertTrue(PlaceNames.sameFuelLot("fuel", "fuel", 11.0))
        assertTrue(PlaceNames.sameFuelLot("fuel", "fuel", 11.0, "1451", "1451"))
        assertTrue(PlaceNames.sameFuelLot("fuel", "fuel", 11.0, "1451", null))
        // Across the street: a different house number is a different lot at any distance.
        assertFalse(PlaceNames.sameFuelLot("fuel", "fuel", 11.0, "1451", "1460"))
        assertFalse(PlaceNames.sameFuelLot("fuel", "food", 11.0))
        assertFalse(PlaceNames.sameFuelLot("fuel", "fuel", 45.0))
        assertEquals("1451", PlaceNames.houseNumber("1451 W Covell Blvd"))
        assertEquals(null, PlaceNames.houseNumber("W Covell Blvd"))
    }

    @Test fun `city words come out of an address`() {
        assertEquals(setOf("davis", "ca"), PlaceNames.cityWords("239 G St, Davis, CA 95616"))
        assertTrue(PlaceNames.cityWords("239 G St").isEmpty())
    }
}

/** The same families in the app's other languages: the tables are the union, so a phone in English
 *  looking at Berlin or Madrid gets the descriptors read the same way. */
class PlaceNamesI18nTest {
    private fun m(a: String, b: String) = PlaceNames.match(a, b)

    @Test fun `descriptors in other languages are generic`() {
        assertEquals(PlaceNames.Match.VARIANT, m("Boulangerie Paul", "Paul"))
        assertEquals(PlaceNames.Match.VARIANT, m("Aral Tankstelle", "Aral"))
        assertEquals(PlaceNames.Match.VARIANT, m("Farmacia Guadalajara", "Guadalajara"))
        assertEquals(PlaceNames.Match.VARIANT, m("Ristorante Da Mario", "Da Mario"))
        assertEquals(PlaceNames.Match.VARIANT, m("Supermercado Dia", "Dia"))
        assertEquals(PlaceNames.Match.VARIANT, m("REWE Center", "REWE"))
        assertEquals(PlaceNames.Match.VARIANT, m("Аптека Ригла", "Ригла"))
        assertEquals(PlaceNames.Match.VARIANT, m("Bäckerei Müller GmbH", "Müller"))
        assertEquals(PlaceNames.Match.EXACT, m("Bäckerei Müller GmbH", "Backerei Mueller".replace("ue", "u")))
    }

    @Test fun `spelling variants across languages fold`() {
        assertEquals(PlaceNames.Match.EXACT, m("Straße des 17. Juni Apotheke", "Strasse des 17 Juni Apotheke"))
        assertEquals(PlaceNames.Match.EXACT, m("Кафе Пушкинъ", "Кафе Пушкинъ"))
        assertEquals(PlaceNames.Match.EXACT, m("Ёлки", "Елки"))
        assertEquals(PlaceNames.Match.EXACT, m("Łódź Bar", "Lodz Bar"))
    }

    @Test fun `shared descriptors are still not identity in other languages`() {
        assertEquals(PlaceNames.Match.NONE, m("Restaurant Zur Post", "Gasthaus Zur Linde"))
        assertEquals(PlaceNames.Match.NONE, m("Farmacia Central", "Farmacia Sol"))
        assertEquals(PlaceNames.Match.NONE, m("Salon de Coiffure Marie", "Salon de Coiffure Julie"))
    }

    @Test fun `Google's English descriptors meet the archive's local ones`() {
        // One identifying word on both sides: the kinds decide, and here they agree.
        assertTrue(PlaceNames.sameBusiness("torhaus - Your Dentists in Berlin", "health", "torhaus - Ihre Zahnärzte", "health", setOf("berlin")))
        assertTrue(PlaceNames.sameBusiness("Pharmacy at Mehringplatz", "health", "Apotheke am Mehringplatz", "health"))
        assertTrue(PlaceNames.sameBusiness("Sophien Church", "civic", "Sophienkirche", "civic"))
        assertFalse(PlaceNames.sameBusiness("Arroyo Park", "park", "Arroyo Pool", "sports"))
        assertTrue(PlaceNames.agree("Pharmacy at Mehringplatz", "Apotheke am Mehringplatz")) // "mehring platz" is a phrase in both
        assertTrue(PlaceNames.agree("Greenhouse Cafe", "Green House Cafe"))
        assertEquals("sophien kirche", PlaceNames.normalized("Sophienkirche"))
        assertEquals("bookstore", PlaceNames.normalized("Bookstore"))
        // A store in a mall is not the mall, and a pharmacy on a plaza is not the plaza.
        assertFalse(PlaceNames.same("VANS Store Berlin Alexa", "ALEXA Berlin"))
        assertEquals(PlaceNames.Match.OVERLAP, PlaceNames.match("VANS Store Berlin Alexa", "ALEXA Berlin", setOf("berlin")))
        assertFalse(PlaceNames.sameBusiness("Pharmacy At Strausberger Platz", "health", "Strausberger Platz", "park"))
        assertTrue(PlaceNames.sameBusiness("Safeway Pharmacy", "health", "Safeway", "shop"))
    }

    @Test fun `four-letter European brands carry a name when the other side adds little`() {
        assertTrue(PlaceNames.sameBusiness("Lidl", "grocery", "Lidl Deutschland", "grocery"))
        assertTrue(PlaceNames.agree("Kolo coffee klcf shop", "Kolo Coffee"))
        assertTrue(PlaceNames.agree("Meya Meya - ägyptisches Essen", "Meya Meya"))
        assertEquals(PlaceNames.Match.NONE, PlaceNames.match("The Finn", "Dish Society at Finn Hall"))
        assertEquals(PlaceNames.Match.NONE, PlaceNames.match("Hair", "Hair Studio"))
        assertEquals(PlaceNames.Match.NONE, PlaceNames.match("Avid & Co.", "The Avid Reader Bookstore"))
    }

    @Test fun `a name glued into one word reads as its words`() {
        assertTrue(PlaceNames.agree("greengymberlin health and fitness club", "Green Gym Berlin"))
        assertTrue(PlaceNames.agree("Green Gym Berlin", "greengymberlin"))
        assertEquals(PlaceNames.Match.NONE, PlaceNames.match("Bellboyhouse", "Bell Boy Shop")) // no run of words spells it
    }

    @Test fun `CJK names compare as strings with their suffixes stripped`() {
        assertEquals(PlaceNames.Match.OVERLAP, m("スターバックス 渋谷店", "スターバックス")) // the branch name is the extra
        assertEquals(PlaceNames.Match.VARIANT, m("星巴克咖啡", "星巴克"))
        assertEquals(PlaceNames.Match.OVERLAP, m("セブン-イレブン渋谷駅前店", "セブン-イレブン"))
        assertEquals(PlaceNames.Match.OVERLAP, m("스타벅스 강남점", "스타벅스"))
        assertEquals(PlaceNames.Match.NONE, m("松屋", "吉野家"))
        assertEquals(PlaceNames.Match.NONE, m("店", "本店"))
    }
}
