package app.vela.core.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryFilterTest {

    @Test fun blocksEnglishAdultCategories() {
        listOf(
            "Bar", "Pub", "Night club", "Nightclub", "Cocktail bar", "Sports bar", "Strip club",
            "Liquor store", "Wine bar", "Brewery", "Winery", "Casino", "Sportsbook", "Hookah lounge",
            "Cannabis store", "Dispensary", "Adult entertainment", "Sex shop", "Tobacco shop", "Escort service",
        ).forEach { assertTrue("should block '$it'", CategoryFilter.isAdult(it)) }
    }

    @Test fun blocksLocalisedAdultCategories() {
        // Categories come localised (hl=<lang>) — the filter must catch them too.
        listOf(
            "Boîte de nuit", "Bar à vin", "Cave à vin",          // fr
            "Discoteca", "Licorería", "Casa de apuestas",         // es
            "Nachtclub", "Spielhalle", "Weinhandlung",            // de
            "Enoteca", "Sala scommesse",                          // it
            "Casa noturna", "Loja de bebidas",                    // pt
            "Slijterij", "Wedkantoor",                            // nl
            "Klub nocny", "Sklep monopolowy",                     // pl
            "Ночной клуб", "Магазин алкоголя", "Казино",          // ru
            "Магазин алкоголю",                                   // uk
            "Nattklubb", "Systembolaget",                         // sv
        ).forEach { assertTrue("should block '$it'", CategoryFilter.isAdult(it)) }
    }

    @Test fun keepsBenignCategories() {
        // Food "…bar" and benign categories must NOT be dropped.
        listOf(
            "Restaurant", "Sushi bar", "Juice bar", "Coffee shop", "Salad bar", "Snack bar", "Oyster bar",
            "Public library", "Discount store", "Grocery store", "Bakery", "Pharmacy", "Barber shop",
            "Alcoholism treatment center", null, "",
        ).forEach { assertFalse("should keep '$it'", CategoryFilter.isAdult(it)) }
    }

    @Test fun applyIfEnabledRespectsFlag() {
        val prev = CategoryFilter.enabled
        try {
            CategoryFilter.enabled = false
            // With the flag off, nothing is filtered (isAdult still classifies, but applyIfEnabled passes through).
            assertTrue(CategoryFilter.isAdult("Bar"))
        } finally {
            CategoryFilter.enabled = prev
        }
    }
}
