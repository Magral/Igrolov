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
              <a href="https://cdn2.steamgriddb.com/grid/portrait1.jpg">download</a></div>
            <div class="asset"><div class="metadata">Alternate • <span>920×430</span></div>
              <a href="https://cdn2.steamgriddb.com/grid/landscape.png">download</a></div>
            <div class="asset"><div class="metadata">Alternate • <span>600x900</span></div>
              <a href="https://example.com/grid/foreign.jpg">download</a></div>
            <div class="asset"><div class="metadata">Alternate • <span>600×900</span></div>
              <a href="http://cdn2.steamgriddb.com/grid/insecure.jpg">download</a></div>
            <div class="asset"><div class="metadata">Alternate • <span>600×900</span></div>
              <a href="https://cdn2.steamgriddb.com/grid/portrait2.webp">download</a></div>
        """.trimIndent()
        assertEquals(
            listOf("https://cdn2.steamgriddb.com/grid/portrait1.jpg", "https://cdn2.steamgriddb.com/grid/portrait2.webp"),
            SteamGridDbArtwork.parsePortraitUrls(html)
        )
    }
}
