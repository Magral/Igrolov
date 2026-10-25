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

    @Test fun resolvesModernHashBasedLibraryCoversBeforeUsingLandscapeFallbacks() {
        val response = """{"response":{"store_items":[{"appid":123,"success":1,"assets":{
            "asset_url_format":"steam/apps/123/${'?t=1234",
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
        val otherApp = """{"response":{"store_items":[{"appid":456,"success":1,"assets":{
            "asset_url_format":"steam/apps/456/${FILENAME}",
            "library_capsule_2x":"hash/library_600x900_2x.jpg"
        }}]}}"""
        assertTrue(SteamArtworkPresentation.libraryCapsuleUrls(otherApp, 123).isEmpty())
        val missingCover = """{"response":{"store_items":[{"appid":123,"success":1,"assets":{
            "asset_url_format":"steam/apps/123/${FILENAME}",
            "header":"hash/header.jpg"
        }}]}}"""
        assertTrue(SteamArtworkPresentation.libraryCapsuleUrls(missingCover, 123).isEmpty())
    }
}}{FILENAME}?t=1234",
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
        val otherApp = """{"response":{"store_items":[{"appid":456,"success":1,"assets":{
            "asset_url_format":"steam/apps/456/${'",
            "library_capsule_2x":"hash/library_600x900_2x.jpg"
        }}]}}"""
        assertTrue(SteamArtworkPresentation.libraryCapsuleUrls(otherApp, 123).isEmpty())
        val missingCover = """{"response":{"store_items":[{"appid":123,"success":1,"assets":{
            "asset_url_format":"steam/apps/123/${FILENAME}",
            "header":"hash/header.jpg"
        }}]}}"""
        assertTrue(SteamArtworkPresentation.libraryCapsuleUrls(missingCover, 123).isEmpty())
    }
}}{FILENAME}",
            "library_capsule_2x":"hash/library_600x900_2x.jpg"
        }}]}}"""
        assertTrue(SteamArtworkPresentation.libraryCapsuleUrls(otherApp, 123).isEmpty())
        val missingCover = """{"response":{"store_items":[{"appid":123,"success":1,"assets":{
            "asset_url_format":"steam/apps/123/${'",
            "header":"hash/header.jpg"
        }}]}}"""
        assertTrue(SteamArtworkPresentation.libraryCapsuleUrls(missingCover, 123).isEmpty())
    }
}}{FILENAME}",
            "header":"hash/header.jpg"
        }}]}}"""
        assertTrue(SteamArtworkPresentation.libraryCapsuleUrls(missingCover, 123).isEmpty())
    }
}