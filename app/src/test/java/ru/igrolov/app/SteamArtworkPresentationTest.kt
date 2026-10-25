package ru.igrolov.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamArtworkPresentationTest {
    @Test fun portraitCoverRequestsUseCropAndOwnTheirFallbackCache() {
        val cover = "https://cdn.akamai.steamstatic.com/steam/apps/123/library_600x900.jpg"
        assertTrue(SteamArtworkPresentation.isPortrait(cover))
        assertEquals("portrait_123", SteamArtworkPresentation.cacheKey(123, true))
        assertFalse(SteamArtworkPresentation.isPortrait("https://cdn.akamai.steamstatic.com/steam/apps/123/header.jpg"))
        assertEquals("landscape_123", SteamArtworkPresentation.cacheKey(123, false))
    }
}
