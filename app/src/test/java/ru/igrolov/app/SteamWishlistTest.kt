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
        assertEquals("76561198000000000", SteamWishlist.parsePublicProfileId("<profile><steamID64>76561198000000000</steamID64></profile>"))
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
        val response = """{"730":{"success":true,"data":{"name":"Counter-Strike 2"}},"281990":{"success":false}}"""
        val titled = SteamWishlist.applyTitles(games, response)
        assertEquals("Counter-Strike 2", titled[0].title)
        assertEquals("Steam #281990", titled[1].title)
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
}
