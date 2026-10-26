package ru.igrolov.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamThemedSalesTest {
    @Test fun featuredFeedExposesOnlyCurrentThemedSalePages() {
        val events = SteamThemedSales.parseFeaturedCategories("""{
            "0":{"name":"Spotlights","items":[
                {"name":"Weekend Deal","url":"https://store.steampowered.com/app/10/Game/"},
                {"name":"Steam Scream V Fest","url":"https://store.steampowered.com/sale/steamscream"},
                {"name":"Publisher Sale","url":"https://store.steampowered.com/sale/publisher2026"}
            ]},
            "specials":{"items":[{"name":"Sale","url":"https://store.steampowered.com/sale/ignored"}]}
        }""")

        assertEquals(listOf("Steam Scream V Fest", "Publisher Sale"), events.map { it.name })
        assertEquals("https://store.steampowered.com/sale/steamscream", events.first().url)
    }

    @Test fun discountsAndEventsFeedKeepsOnlySalePageEntries() {
        val events = SteamThemedSales.parseDiscountsAndEvents("""{
            "items":[
                {"title":"Publisher Sale","url":"https://store.steampowered.com/sale/publisher"},
                {"title":"Weekend Deal","url":"https://store.steampowered.com/app/10/Game/"},
                {"title":"Not Steam","url":"https://example.com/sale"}
            ]
        }""")

        assertEquals(listOf("Publisher Sale"), events.map { it.name })
    }

    @Test fun salePageGamesSupportRankingAndGenericSteamCards() {
        val html = """
            <a class="search_result_row" data-ds-appid="281990">
              <span class="title">Stellaris</span>
            </a>
            <div class="salepreview_item" data-ds-appid="440">
              <div class="salepreview_item_name">Team Fortress 2</div>
            </div>
            <div data-ds-appid="730"><img alt="Counter-Strike 2"></div>
        """.trimIndent()

        val games = SteamThemedSales.parseSalePageGames(html, "Strategy Fest")

        assertEquals(listOf(281990, 440, 730), games.map { it.appId })
        assertTrue(games.all { it.saleEventName == "Strategy Fest" })
    }
}
