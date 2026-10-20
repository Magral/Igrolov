package ru.igrolov.app

import org.junit.Assert.*
import org.junit.Test

class TrackedPriceCacheTest {
    @Test fun upgradingKeepsTitleButDoesNotPresentLegacyTeaserAsVerifiedPrice() {
        val decoded=TrackedPriceCache.decode("123456","""{"name":"Stellaris","price":1}""")
        assertEquals("Stellaris",decoded.name);assertNull(decoded.price)
        assertNull(TrackedPriceCache.decode("123456","broken").price)
    }
    @Test fun newVerifiedPricesSurviveRestartButFailuresDoNotOverwriteCache() {
        val item=Tracked("123456","Stellaris",450.0,"В продаже","https://plati.market/itm/123456")
        assertEquals(450.0,TrackedPriceCache.decode(item.id,TrackedPriceCache.encode(item)).price!!,0.0)
        assertNull(TrackedPriceCache.encode(item.copy(status="Ошибка загрузки: HTTP 503")))
        assertNull(TrackedPriceCache.decode(item.id,TrackedPriceCache.encode(item.copy(price=null))).price)
    }
}
