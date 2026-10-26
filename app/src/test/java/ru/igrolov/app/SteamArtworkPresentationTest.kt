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
        assertTrue(SteamArtworkPresentation.isPortrait("https://cdn2.steamgriddb.com/grid/poster.png"))
        assertEquals("landscape_123", SteamArtworkPresentation.cacheKey(123, false))
        assertFalse(SteamArtworkPresentation.matchesAspect("https://cdn.akamai.steamstatic.com/steam/apps/123/header.jpg", true))
    }

    @Test fun directFallbacksKeepPortraitAndLandscapeArtworkSeparate() {
        val portrait = SteamArtworkPresentation.directFallbackUrls(123, portrait = true)
        val landscape = SteamArtworkPresentation.directFallbackUrls(123, portrait = false)

        assertTrue(portrait.isNotEmpty())
        assertTrue(portrait.all(SteamArtworkPresentation::isPortrait))
        assertTrue(portrait.any { it.startsWith("https://steamcdn-a.akamaihd.net/steam/apps/123/library_600x900") })
        assertTrue(landscape.isNotEmpty())
        assertTrue(landscape.none(SteamArtworkPresentation::isPortrait))
        assertTrue(SteamArtworkPresentation.directFallbackUrls(0, portrait = true).isEmpty())
    }

    @Test fun portraitCardsNeverReceiveLandscapeSteamHeaders() {
        val portrait = "https://cdn.akamai.steamstatic.com/steam/apps/7940/library_600x900.jpg"
        val header = "https://cdn.akamai.steamstatic.com/steam/apps/7940/header.jpg"
        val sgdb = "https://cdn2.steamgriddb.com/grid/call-of-duty-poster.png"

        assertEquals(listOf(portrait, sgdb), SteamArtworkPresentation.filterForAspect(listOf(header, portrait, sgdb), portrait = true))
        assertEquals(listOf(header), SteamArtworkPresentation.filterForAspect(listOf(portrait, header, sgdb), portrait = false))
    }

    @Test fun resolvesModernHashBasedLibraryCoversBeforeUsingLandscapeFallbacks() {
        val filenameToken = "$" + "{FILENAME}"
        val response = """{"response":{"store_items":[{"appid":123,"success":1,"assets":{
            "asset_url_format":"steam/apps/123/$filenameToken?t=1234",
            "library_capsule_2x":"a1b2c3/library_600x900_2x.jpg",
            "library_capsule":"a1b2c3/library_600x900.jpg"
        }}]}}"""
        val urls = SteamArtworkPresentation.libraryCapsuleUrls(response, 123)
        assertEquals(6, urls.size)
        assertEquals(
            "https://shared.steamstatic.com/store_item_assets/steam/apps/123/a1b2c3/library_600x900_2x.jpg?t=1234",
            urls.first()
        )
        assertTrue(urls.all { it.contains("library_600x900") })
    }

    @Test fun ignoresAssetsForOtherAppsOrWithoutPortraitCovers() {
        val filenameToken = "$" + "{FILENAME}"
        val otherApp = """{"response":{"store_items":[{"appid":456,"success":1,"assets":{
            "asset_url_format":"steam/apps/456/$filenameToken",
            "library_capsule_2x":"hash/library_600x900_2x.jpg"
        }}]}}"""
        assertTrue(SteamArtworkPresentation.libraryCapsuleUrls(otherApp, 123).isEmpty())
        val missingCover = """{"response":{"store_items":[{"appid":123,"success":1,"assets":{
            "asset_url_format":"steam/apps/123/$filenameToken",
            "header":"hash/header.jpg"
        }}]}}"""
        assertTrue(SteamArtworkPresentation.libraryCapsuleUrls(missingCover, 123).isEmpty())
    }
}
