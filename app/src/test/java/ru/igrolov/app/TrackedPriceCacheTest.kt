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

    @Test fun marketplaceKeysKeepSameOfferIdsSeparateAndRestoreGgselUrl() {
        val url="https://ggsel.net/catalog/stellaris/123456"
        val offer=Offer("123456","Stellaris Steam Gift",450.0,"Gift","РФ","Игра",
            available=true,priceVerified=true,marketplace="GGSEL",productUrl=url)
        val ggsel=TrackedPriceCache.fromOffer(offer)
        val plati=Tracked("123456","Stellaris",450.0,"В продаже","https://plati.market/itm/123456")
        assertEquals("GGSEL:123456",ggsel.storageKey)
        assertNotEquals(plati.storageKey,ggsel.storageKey)
        val restored=TrackedPriceCache.decode(ggsel.storageKey,TrackedPriceCache.encode(ggsel))
        assertEquals("GGSEL",restored.marketplace)
        assertEquals(url,restored.url)
        assertEquals(450.0,restored.price!!,0.0)
    }

    @Test fun legacyNumericTrackedIdStillDecodesAsPlati() {
        val restored=TrackedPriceCache.decode("123456","""{"proofSchema":106,"name":"Stellaris","price":450.0}""")
        assertEquals("Plati",restored.marketplace)
        assertEquals("123456",restored.id)
        assertEquals("https://plati.market/itm/123456",restored.url)
        assertEquals(450.0,restored.price!!,0.0)
    }
}
