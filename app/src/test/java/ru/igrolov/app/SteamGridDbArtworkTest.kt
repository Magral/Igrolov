package ru.igrolov.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SteamGridDbArtworkTest {
    @Test fun extractsSteamGridDbGameId() {
        assertEquals(25316, SteamGridDbArtwork.parseSteamGridDbGameId("""{"success":true,"data":{"id":25316,"name":"Call of Duty 4"}}"""))
        assertNull(SteamGridDbArtwork.parseSteamGridDbGameId("""{"success":false,"data":{"id":25316}}"""))
    }

    @Test fun returnsHighestScoredSafeGridCdnPortraitCandidates() {
        val response = """{"success":true,"data":[
            {"score":1,"url":"https://cdn2.steamgriddb.com/grid/low.jpg"},
            {"score":9,"url":"https://cdn2.steamgriddb.com/grid/high.png"},
            {"score":99,"url":"https://example.com/grid/foreign.jpg"},
            {"score":100,"url":"http://cdn2.steamgriddb.com/grid/insecure.jpg"},
            {"score":90,"url":"https://cdn2.steamgriddb.com/hero/horizontal.jpg"}
        ]}"""

        assertEquals(
            listOf(
                "https://cdn2.steamgriddb.com/grid/high.png",
                "https://cdn2.steamgriddb.com/grid/low.jpg"
            ),
            SteamGridDbArtwork.parsePortraitUrls(response)
        )
    }
}
