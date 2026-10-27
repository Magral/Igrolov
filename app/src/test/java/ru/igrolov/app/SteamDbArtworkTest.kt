package ru.igrolov.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SteamDbArtworkTest {
    @Test fun extractsParentAppIdFromSteamDbRelationshipRow() {
        val html = """
            <table>
              <tr><td>Developer</td><td>Example Studio</td></tr>
              <tr>
                <td>Parent App</td>
                <td><a href="https://steamdb.info/app/1938090/" itemprop="isPartOf"></a>
                    <a class="app" data-appid="1938090">Call of Duty</a> <i>(1938090)</i>
                </td>
              </tr>
            </table>
        """.trimIndent()

        assertEquals(1938090, SteamDbArtwork.parseParentAppId(html, 4435490))
        assertNull(SteamDbArtwork.parseParentAppId(html, 1938090))
    }

    @Test fun ignoresUnrelatedAppIdsOutsideParentRow() {
        val html = """
            <tr><td>Parent App</td><td>Unknown</td></tr>
            <a data-appid="1938090">Call of Duty</a>
        """.trimIndent()

        assertNull(SteamDbArtwork.parseParentAppId(html, 4435490))
    }
}
