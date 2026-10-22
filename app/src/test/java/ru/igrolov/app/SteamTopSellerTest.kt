package ru.igrolov.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class SteamTopSellerTest {
    @Test fun featuredNewReleasesKeepExactAppIdsAndIgnoreDuplicates() {
        val games = SteamTopSellerParser.parseFeaturedCategory("""{
            "new_releases":{"items":[
                {"id":730,"name":"Counter-Strike 2"},
                {"id":281990,"name":"Stellaris"},
                {"id":730,"name":"Duplicate"},
                {"id":0,"name":"Invalid"},
                {"id":440,"name":""}
            ]}
        }""")
        assertEquals(listOf(730, 281990), games.map { it.appId })
        assertEquals("Counter-Strike 2", games.first().title)
    }

    @Test fun discountedLeaderSectionKeepsOnlyGamesWithRegionalDiscounts() {
        val games = listOf(
            SteamTopSeller(1, "Discounted", discountPercent = 25),
            SteamTopSeller(2, "Full price"),
            SteamTopSeller(3, "Free", discountPercent = 0)
        )
        assertEquals(listOf(1), SteamLeaderSection.DISCOUNTED_TOP_SELLERS.filter(games).map { it.appId })
        assertEquals(games, SteamLeaderSection.TOP_SELLERS.filter(games))
    }

    @Test fun rankingRowsKeepSteamAppIdsAndTitles() {
        val rows = SteamTopSellerParser.parseRankingHtml("""
            <a class="search_result_row" data-ds-appid="281990"><span class="title">Stellaris</span></a>
            <a class="search_result_row" data-ds-appid="281990"><span class="title">Duplicate</span></a>
            <a class="search_result_row" data-ds-appid="289070"><span class="title">Civilization &amp; Beyond</span></a>
        """.trimIndent())
        assertEquals(listOf(281990, 289070), rows.map { it.appId })
        assertEquals("Stellaris", rows.first().title)
        assertEquals("Civilization & Beyond", rows.last().title)
    }

    @Test fun russianPriceAndDiscountComeFromRegionalAppDetails() {
        val game = SteamTopSeller(281990, "Stellaris")
        val result = SteamTopSellerParser.applyRuDetails(game, """
            {"281990":{"success":true,"data":{"type":"game","name":"Stellaris","price_overview":{
              "final_formatted":"399 руб.","initial_formatted":"1 599 руб.","discount_percent":75}}}}
        """.trimIndent(), null)
        assertEquals(SteamRuAvailability.AVAILABLE, result.availability)
        assertEquals("399 руб.", result.steamPrice)
        assertEquals("1 599 руб.", result.steamInitialPrice)
        assertEquals(75, result.discountPercent)
    }

    @Test fun regionalSearchPresenceDoesNotProvePurchaseAvailability() {
        val game = SteamTopSeller(281990, "Stellaris")
        val json = """{"281990":{"success":false}}"""
        assertEquals(SteamRuAvailability.UNKNOWN,
            SteamTopSellerParser.applyRuDetails(game, json, null).availability)
        assertEquals(SteamRuAvailability.UNKNOWN,
            SteamTopSellerParser.applyRuDetails(game, json, true).availability)
        assertEquals(SteamRuAvailability.UNAVAILABLE,
            SteamTopSellerParser.applyRuDetails(game, json, false).availability)
    }

    @Test fun successfulDetailsWithoutRegionalPriceDoNotProveAvailability() {
        val game = SteamTopSeller(281990, "Stellaris")
        val result = SteamTopSellerParser.applyRuDetails(game,
            """{"281990":{"success":true,"data":{"type":"game","name":"Stellaris"}}}""", true)
        assertEquals(SteamRuAvailability.UNKNOWN, result.availability)
        assertTrue(result.steamPrice.isBlank())
    }

    @Test fun successfulDetailsWithEmptyPriceOverviewDoNotProveAvailability() {
        val game = SteamTopSeller(281990, "Stellaris")
        val result = SteamTopSellerParser.applyRuDetails(game,
            """{"281990":{"success":true,"data":{"type":"game","name":"Stellaris","price_overview":{"final_formatted":""}}}}""", null)
        assertEquals(SteamRuAvailability.UNKNOWN, result.availability)
        assertTrue(result.steamPrice.isBlank())
    }

    @Test fun dlcTypeIsMarkedForExclusionFromLeaderRows() {
        val game = SteamTopSeller(1234, "An expansion")
        val result = SteamTopSellerParser.applyRuDetails(game,
            """{"1234":{"success":true,"data":{"type":"dlc"}}}""", null)
        assertEquals(false, result.isGame)
        assertEquals(SteamRuAvailability.UNKNOWN, result.availability)
    }

    @Test fun malformedResponseDoesNotClassifyGameAsUnavailable() {
        val game = SteamTopSeller(281990, "Stellaris")
        val result = SteamTopSellerParser.applyRuDetails(game, "not-json", false)
        assertEquals(SteamRuAvailability.UNKNOWN, result.availability)
        assertTrue(result.steamPrice.isBlank())
    }

    @Test fun marketplaceMinimumRequiresConfirmedRuGiftOrKeyBaseGame() {
        fun offer(id: String, price: Double, type: String = "Gift", region: String = "РФ указана в названии",
                  edition: String = "Игра", marketplace: String = "Plati") = Offer(
            id, "Stellaris Steam $type RU", price, type, region, edition,
            detailsChecked = true, available = true, priceVerified = true, marketplace = marketplace
        )
        val offers = listOf(
            offer("p1", 500.0), offer("p2", 350.0),
            offer("g1", 400.0, type = "Key", marketplace = "GGSEL"),
            offer("foreign", 100.0, region = "Регион не подтверждён"),
            offer("dlc", 50.0, edition = "DLC"),
            offer("unknown", 10.0, type = "Не указан")
        )

        val minima = LeaderOfferSelector.minimumConfirmedByMarketplace(
            offers, ManualSearchFilters(), JSONObject(), emptySet()
        )

        assertEquals(mapOf("Plati" to 350.0, "GGSEL" to 400.0), minima)
    }
}
