package ru.igrolov.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MonitorSourcePricesTest {
    @Test fun legacyMinimumsKeepTheirOriginalSuccessfulTime() {
        val old=MonitorSourcePrices.read(JSONObject().put("checked",100L).put("sourceMinimums",JSONObject().put("Plati",120).put("GGSEL",100)))
        assertEquals(120.0,old.plati.price!!,0.0)
        assertEquals(100L,old.plati.checkedAt)
        assertTrue(old.plati.productUrl.isEmpty())
    }
    @Test fun successfulStoreUpdatesAndFailedStoreKeepsPriceDateAndLink() {
        val old=CachedGamePrices(SourcePriceCheck(120.0,100),SourcePriceCheck(100.0,100,productUrl="https://ggsel.net/catalog/product/game"))
        val updated=OfferPriceCache.merge(old,mapOf("Plati" to 110.0),setOf("GGSEL"),200,300,"Unavailable",productUrls=mapOf("Plati" to "https://plati.market/itm/12345"))
        val stored=MonitorSourcePrices.read(JSONObject().put("sourceChecks",OfferPriceCache.encode(updated)))
        assertEquals(110.0,stored.plati.price!!,0.0)
        assertEquals(300L,stored.plati.checkedAt)
        assertEquals(100.0,stored.ggsel.price!!,0.0)
        assertEquals(100L,stored.ggsel.checkedAt)
        assertEquals(300L,stored.ggsel.failedAt)
        assertEquals(old.ggsel.productUrl,stored.ggsel.productUrl)
    }
}
