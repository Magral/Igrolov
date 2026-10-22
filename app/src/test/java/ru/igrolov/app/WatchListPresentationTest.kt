package ru.igrolov.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchListPresentationTest {
    private fun entry(index: Int, name: String, price: Double, limit: Double, error: String? = null) =
        WatchListPresentation.Entry(index, JSONObject().put("name", name).put("last", price).put("limit", limit)
            .apply { if (error != null) put("error", error) })

    @Test fun belowTargetFilterKeepsOnlyPositivePricesAtOrBelowLimit() {
        val entries = listOf(entry(0, "Above", 120.0, 100.0), entry(1, "At target", 100.0, 100.0),
            entry(2, "Not checked", -1.0, 100.0))
        assertEquals(listOf(1), WatchListPresentation.arrange(entries,
            WatchListPresentation.Filter.BELOW_TARGET, WatchListPresentation.Sort.NAME).map { it.originalIndex })
    }

    @Test fun errorsAndAttentionSortPutActionableGamesFirst() {
        val entries = listOf(entry(0, "Normal", 300.0, 100.0), entry(1, "Below", 80.0, 100.0),
            entry(2, "Error", 300.0, 100.0, "timeout"), entry(3, "Unchecked", -1.0, 100.0))
        assertEquals(listOf(2), WatchListPresentation.arrange(entries,
            WatchListPresentation.Filter.ERRORS, WatchListPresentation.Sort.NAME).map { it.originalIndex })
        assertEquals(listOf(2, 1, 3, 0), WatchListPresentation.arrange(entries,
            WatchListPresentation.Filter.ALL, WatchListPresentation.Sort.ATTENTION).map { it.originalIndex })
    }

    @Test fun lowPriceSortKeepsUnknownPricesAtTheEndAndPreservesOriginalIndex() {
        val entries = listOf(entry(0, "Unknown", -1.0, 100.0), entry(1, "Higher", 250.0, 100.0),
            entry(2, "Lower", 75.0, 100.0))
        assertEquals(listOf(2, 1, 0), WatchListPresentation.arrange(entries,
            WatchListPresentation.Filter.ALL, WatchListPresentation.Sort.LOWEST_PRICE).map { it.originalIndex })
    }
}
