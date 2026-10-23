package ru.igrolov.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class SteamTopSellerTest {
    @Test fun searchPagesExposeSteamTotalAndNextOffset() {
        val page = SteamTopSellerParser.parseSearchPage("""{
            "total_count":80,
            "results_html":"<a class=\"search_result_row\" data-ds-appid=\"281990\"><span class=\"title\">Stellaris</span></a>"
        }""", 30)
        assertEquals(listOf(281990), page.games.map { it.appId })
        assertEquals(31, page.nextStart)
        assertEquals(80, page.totalCount)
        assertTrue(page.hasMore)
    }

    @Test fun freeGamesAreMarkedAndExcludedFromDiscountedLeaders() {
        val free = SteamTopSellerParser.applyRuDetails(SteamTopSeller(730, "Counter-Strike 2"),
            """{"730":{"success":true,"data":{"type":"game","name":"Counter-Strike 2","is_free":true,"price_overview":{"discount_percent":100,"final_formatted":"Бесплатно"}}}}""", null)
        assertTrue(free.isFree)
        assertEquals("Бесплатно", free.steamPrice)
        assertEquals(0, free.discountPercent)
        assertTrue(SteamLeaderSection.TOP_SELLERS.filter(listOf(free)).isEmpty())
        assertTrue(SteamLeaderSection.DISCOUNTED_TOP_SELLERS.filter(listOf(free)).isEmpty())
    }

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

    @Test fun leaderFiltersUseRegionalPriceDiscountAndAvailableReviewPercent() {
        val games = listOf(
            SteamTopSeller(1, "Affordable", steamPrice = "599 руб.", discountPercent = 25,
                reviewSummary = SteamReviewSummary(92, 100)),
            SteamTopSeller(2, "Expensive", steamPrice = "1 599 руб.", discountPercent = 50,
                reviewSummary = SteamReviewSummary(95, 100)),
            SteamTopSeller(3, "No regional price", steamPrice = "", discountPercent = 80,
                reviewSummary = SteamReviewSummary(99, 100)),
            SteamTopSeller(4, "No reviews", steamPrice = "499 руб.", discountPercent = 30)
        )

        val filtered = SteamLeaderFilter.apply(games, SteamLeaderFilters(
            maxPriceRub = 700.0, minimumDiscountPercent = 20, minimumReviewPercent = 90
        ))

        assertEquals(listOf(1), filtered.map { it.appId })
    }

    @Test fun minimumReviewCountAvoidsHighlyRatedGamesWithTooFewReviews() {
        val games = listOf(
            SteamTopSeller(1, "Established", reviewSummary = SteamReviewSummary(920, 1000)),
            SteamTopSeller(2, "Small sample", reviewSummary = SteamReviewSummary(99, 100)),
            SteamTopSeller(3, "No review data")
        )
        val filtered = SteamLeaderFilter.apply(games, SteamLeaderFilters(
            minimumReviewPercent = 90, minimumReviewCount = 500
        ))
        assertEquals(listOf(1), filtered.map { it.appId })
    }

    @Test fun leaderReviewFiltersParseAndCountReviewThreshold() {
        val input = SteamLeaderFilterInput(minimumReviewText = "85", minimumReviewCountText = "1000")
        assertEquals(2, input.activeFilterCount)
        assertFalse(input.hasInvalidInput)
        assertEquals(1000L, input.toFilters().minimumReviewCount)
        assertTrue(SteamLeaderFilterInput(minimumReviewCountText = "0").hasInvalidInput)
        assertTrue(SteamLeaderFilterInput(minimumReviewCountText = "many").hasInvalidInput)
    }

    @Test fun reviewSummaryShowsCompactTotalReviewVolume() {
        assertEquals("950", SteamReviewSummary(900, 950).compactTotal)
        assertEquals("1,2 тыс.", SteamReviewSummary(900, 1200).compactTotal)
        assertEquals("24 тыс.", SteamReviewSummary(24000, 25000).compactTotal)
        assertEquals("1,2 млн", SteamReviewSummary(1_200_000, 1_250_000).compactTotal)
    }

    @Test fun russianSteamPriceParserHandlesGroupedAndDecimalValues() {
        assertEquals(1599.0, SteamLeaderFilter.parsePriceRub("1 599 руб.")!!, 0.0)
        assertEquals(1299.0, SteamLeaderFilter.parsePriceRub("1 299 ₽")!!, 0.0)
        assertEquals(99.99, SteamLeaderFilter.parsePriceRub("99,99 руб.")!!, 0.0)
        assertEquals(null, SteamLeaderFilter.parsePriceRub("Цена не указана"))
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
              "final_formatted":"399 руб.","initial_formatted":"1 599 руб.","discount_percent":75},"header_image":"https://cdn.example/header.jpg","short_description":"A strategy game."}}}
        """.trimIndent(), null)
        assertEquals(SteamRuAvailability.AVAILABLE, result.availability)
        assertEquals("399 руб.", result.steamPrice)
        assertEquals("1 599 руб.", result.steamInitialPrice)
        assertEquals(75, result.discountPercent)
        assertEquals("https://cdn.example/header.jpg", result.headerImageUrl)
        assertEquals("A strategy game.", result.description)
    }

    @Test fun regionalDetailsPopulateLanguageAndControllerBadges() {
        val result = SteamTopSellerParser.applyRuDetails(SteamTopSeller(281990, "Stellaris"), """
            {"281990":{"success":true,"data":{"type":"game","name":"Stellaris",
              "supported_languages":"English, Russian <strong>*</strong>","controller_support":"partial"}}}
        """.trimIndent(), null)
        assertEquals(SteamRussianLocalization.SUPPORTED, result.features.russianLocalization)
        assertEquals(SteamControllerSupport.PARTIAL, result.features.controllerSupport)
    }

    @Test fun globalMetadataFillsArtworkAndDescriptionWithoutChangingRuEvidence() {
        val game = SteamTopSeller(
            123456, "Original title", steamPrice = "", availability = SteamRuAvailability.UNAVAILABLE
        )
        val result = SteamTopSellerParser.applyGlobalMetadata(game, """
            {"123456":{"success":true,"data":{"type":"game","name":"Global title",
              "header_image":"https://cdn.example/header.jpg","short_description":"Global description.",
              "supported_languages":"English","controller_support":"full",
              "price_overview":{"final_formatted":"$19.99"}}}}
        """.trimIndent())

        assertEquals("Original title", result.title)
        assertEquals("https://cdn.example/header.jpg", result.headerImageUrl)
        assertEquals("Global description.", result.description)
        assertEquals(SteamRussianLocalization.NOT_SUPPORTED, result.features.russianLocalization)
        assertEquals(SteamControllerSupport.FULL, result.features.controllerSupport)
        assertEquals("", result.steamPrice)
        assertEquals(SteamRuAvailability.UNAVAILABLE, result.availability)
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
