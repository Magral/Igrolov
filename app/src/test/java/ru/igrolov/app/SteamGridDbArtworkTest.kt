package ru.igrolov.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SteamGridDbArtworkTest {
    @Test fun publicPageUrlUsesSteamAppIdWithoutApiRoute() {
        assertEquals("https://www.steamgriddb.com/steam/7940/grids", SteamGridDbArtwork.publicGridPageUrl(7940))
    }

    @Test fun extractsOnlyPortraitImagesFromPublicHtmlAssetCards() {
        val html = """
            <div class="asset"><div class="metadata">Alternate • <span>600×900</span></div>
              <a href="https://cdn2.steamgriddb.com/grid/a1b2c3d4e5f60718293a4b5c6d7e8f90.jpg">download</a></div>
            <div class="asset"><div class="metadata">Alternate • <span>920×430</span></div>
              <a href="https://cdn2.steamgriddb.com/grid/landscape.png">download</a></div>
            <div class="asset"><div class="metadata">Alternate • <span>600x900</span></div>
              <a href="https://example.com/grid/foreign.jpg">download</a></div>
            <div class="asset"><div class="metadata">Alternate • <span>600×900</span></div>
              <a href="http://cdn2.steamgriddb.com/grid/c1b2c3d4e5f60718293a4b5c6d7e8f90.jpg">download</a></div>
            <div class="asset"><div class="metadata">Alternate • <span>600×900</span></div>
              <a href="https://cdn2.steamgriddb.com/grid/b1b2c3d4e5f60718293a4b5c6d7e8f90.webp">download</a></div>
        """.trimIndent()
        assertEquals(
            listOf(
                "https://cdn2.steamgriddb.com/grid/a1b2c3d4e5f60718293a4b5c6d7e8f90.jpg",
                "https://cdn2.steamgriddb.com/grid/b1b2c3d4e5f60718293a4b5c6d7e8f90.webp"
            ),
            SteamGridDbArtwork.parsePortraitUrls(html)
        )
    }
}
