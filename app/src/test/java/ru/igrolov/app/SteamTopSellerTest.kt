package ru.igrolov.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamTopSellerTest {
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

    @Test fun unsuccessfulAppDetailsNeedIndependentRuSearchEvidence() {
        val game = SteamTopSeller(281990, "Stellaris")
        val json = """{"281990":{"success":false}}"""
        assertEquals(SteamRuAvailability.UNKNOWN,
            SteamTopSellerParser.applyRuDetails(game, json, null).availability)
        assertEquals(SteamRuAvailability.AVAILABLE,
            SteamTopSellerParser.applyRuDetails(game, json, true).availability)
        assertEquals(SteamRuAvailability.UNAVAILABLE,
            SteamTopSellerParser.applyRuDetails(game, json, false).availability)
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
}
