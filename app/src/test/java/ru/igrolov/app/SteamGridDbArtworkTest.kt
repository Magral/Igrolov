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

    @Test fun extractsCurrentSteamGridDbDownloadLinkFromNestedAssetCard() {
        val html = """
            <div data-type="grid" class="asset-container compact">
              <div class="asset" aria-expanded="false">
                <div class="preview"><div class="btn-download">
                  <a href="https://cdn2.steamgriddb.com/grid/a592d9fe5ade10553913fb1b94f1175c.png">download</a>
                </div></div>
                <div class="img-container" style="padding-bottom: 150%;">
                  <img data-src="https://cdn2.steamgriddb.com/thumb/a592d9fe5ade10553913fb1b94f1175c.jpg">
                </div>
                <div class="info"><div class="metadata">Alternate • <span>600×900</span></div></div>
              </div>
            </div>
        """.trimIndent()

        assertEquals(
            listOf("https://cdn2.steamgriddb.com/grid/a592d9fe5ade10553913fb1b94f1175c.png"),
            SteamGridDbArtwork.parsePortraitUrls(html)
        )
    }

    @Test fun stillFindsVerticalCoversWhenPublicPageChangesCardClassAndUsesLazyImageSource() {
        val html = """
            <section class="grid-card"><span>600 × 900</span>
              <img data-src="https://cdn2.steamgriddb.com/grid/abcdef0123456789abcdef0123456789.webp"></section>
            <section class="grid-card"><span>920 × 430</span>
              <img src="https://cdn2.steamgriddb.com/grid/0123456789abcdef0123456789abcdef.jpg"></section>
        """.trimIndent()

        assertEquals(
            listOf("https://cdn2.steamgriddb.com/grid/abcdef0123456789abcdef0123456789.webp"),
            SteamGridDbArtwork.parsePortraitUrls(html)
        )
    }
}
