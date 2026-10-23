package ru.igrolov.app

import org.junit.Assert.*
import org.junit.Test

class SteamWishlistTest {
    @Test fun acceptsSteamIdAndNumericProfileUrl() {
        val id = "76561198000000000"
        assertEquals(id, SteamWishlist.profileId(id))
        assertEquals(id, SteamWishlist.profileId("https://steamcommunity.com/profiles/$id/"))
        assertNull(SteamWishlist.profileId("https://example.com/profiles/$id"))
        assertNull(SteamWishlist.profileId("not-a-steam-id"))
    }

    @Test fun resolvesVanityProfileXmlAndKeepsVanityAsSeparateInput() {
        assertEquals("Gamer_123", SteamWishlist.vanityName("https://steamcommunity.com/id/Gamer_123/"))
        val profile = SteamWishlist.parsePublicProfile("<profile><steamID>Alex &amp; Sam</steamID><steamID64>76561198000000000</steamID64></profile>")
        assertEquals("76561198000000000", profile.first)
        assertEquals("Alex & Sam", profile.second)
        val cdataProfile = SteamWishlist.parsePublicProfile("<profile><steamID><![CDATA[Namtar]]></steamID><steamID64>76561198000000000</steamID64></profile>")
        assertEquals("Namtar", cdataProfile.second)
        val cdataWithMarkup = SteamWishlist.parsePublicProfile("<profile><steamID><![CDATA[A &amp; B]]></steamID><steamID64>76561198000000000</steamID64></profile>")
        assertEquals("A &amp; B", cdataWithMarkup.second)
        assertNull(SteamWishlist.parsePublicProfileId("<profile><steamID64>123</steamID64></profile>"))
    }

    @Test fun parsesAndDeduplicatesIdsWithoutInventingTitles() {
        val result = SteamWishlist.parseWishlist("""{"response":{"items":[{"appid":281990},{"app_id":289070,"name":"Civilization VI"},{"appid":281990}]}}""")
        assertEquals(listOf(281990, 289070), result.map { it.appId })
        assertEquals("Civilization VI", result[1].title)
        assertEquals("", result[0].title)
    }

    @Test fun enrichesWishlistTitlesFromSteamAppDetailsAndKeepsFallbacks() {
        val games = listOf(WishlistGame(730), WishlistGame(281990, "Steam #281990"))
        val response = """{"730":{"success":true,"data":{"name":"Counter-Strike 2","price_overview":{"currency":"RUB","initial":199900,"final":99900,"discount_percent":50,"initial_formatted":"1 999 руб.","final_formatted":"999 руб."}}},"281990":{"success":false}}"""
        val titled = SteamWishlist.applyTitles(games, response)
        assertEquals("Counter-Strike 2", titled[0].title)
        assertEquals("999 руб.", titled[0].steamPrice)
        assertEquals("1 999 руб.", titled[0].steamInitialPrice)
        assertEquals(50, titled[0].discountPercent)
        assertEquals("Steam #281990", titled[1].title)
    }

    @Test fun enrichesWishlistFeaturesAndPersistsThemInCache() {
        val game = WishlistGame(281990)
        val enriched = SteamWishlist.applyTitles(listOf(game), """
            {"281990":{"success":true,"data":{"name":"Stellaris",
              "supported_languages":"English, Russian <strong>*</strong>","controller_support":"full"}}}
        """.trimIndent()).single()
        assertEquals(SteamRussianLocalization.SUPPORTED, enriched.features.russianLocalization)
        assertEquals(SteamControllerSupport.FULL, enriched.features.controllerSupport)
        assertEquals(enriched, SteamWishlist.decode(SteamWishlist.encode(listOf(enriched))).single())
    }

    @Test fun storesMultipleNamedProfilesAndWishlistPrices() {
        val profiles = listOf(
            WishlistProfile("76561198000000000", "Alex", "https://steamcommunity.com/profiles/76561198000000000", listOf(
                WishlistGame(730, "Counter-Strike 2", "999 руб.", "1 999 руб.", 50, 750.0)
            ), 1234L),
            WishlistProfile("76561198000000001", "Sam", "76561198000000001")
        )
        assertEquals(profiles, SteamWishlist.decodeProfiles(SteamWishlist.encodeProfiles(profiles)))
    }

    @Test fun cachedWishlistSurvivesRoundTripAndDropsInvalidIds() {
        val games = listOf(WishlistGame(730, "Counter-Strike 2"), WishlistGame(281990))
        assertEquals(games, SteamWishlist.decode(SteamWishlist.encode(games)))
        assertTrue(SteamWishlist.parseWishlist("""{"response":{"items":[]}}""").isEmpty())
        try {
            SteamWishlist.parseWishlist("{}")
            fail("Malformed responses must not be treated as an empty wishlist")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun persistsIndependentConfirmedMinimumPricesForPlatiAndGgsel() {
        val game = WishlistGame(281990, "Stellaris", lowestPlatiPrice = 499.0, lowestGgselPrice = 475.0)
        val decoded = SteamWishlist.decode(SteamWishlist.encode(listOf(game))).single()
        assertEquals(499.0, decoded.lowestPlatiPrice!!, 0.0)
        assertEquals(475.0, decoded.lowestGgselPrice!!, 0.0)
    }

    @Test fun oldWishlistCacheKeepsPlatiPriceAndDefaultsGgselToUnknown() {
        val decoded = SteamWishlist.decode("""[{"appId":281990,"title":"Stellaris","lowestPlatiPrice":499.0}]""").single()
        assertEquals(499.0, decoded.lowestPlatiPrice!!, 0.0)
        assertNull(decoded.lowestGgselPrice)
    }
}

