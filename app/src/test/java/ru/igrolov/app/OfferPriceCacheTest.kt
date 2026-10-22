package ru.igrolov.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OfferPriceCacheTest {
    private val old = CachedGamePrices(SourcePriceCheck(500.0,100,requestStartedAt=90),SourcePriceCheck(800.0,100,requestStartedAt=90))
    @Test fun partialFailurePreservesOnlyFailedSourceAndItsSuccessfulTime() {
        val result=OfferPriceCache.merge(old,mapOf("Plati" to 400.0,"GGSEL" to 700.0),setOf("GGSEL"),200,210,"offline")
        assertEquals(400.0,result.plati.price!!,0.0)
        assertEquals(210L,result.plati.checkedAt)
        assertEquals(800.0,result.ggsel.price!!,0.0)
        assertEquals(100L,result.ggsel.checkedAt)
        assertEquals(210L,result.ggsel.failedAt)
        assertEquals("offline",result.ggsel.error)
    }
    @Test fun successfulEmptyResultClearsOldPriceAndPreviousFailure() {
        val failed=OfferPriceCache.merge(old,emptyMap(),setOf("Plati","GGSEL"),200,210,"offline")
        val empty=OfferPriceCache.merge(failed,emptyMap(),emptySet(),300,310,"")
        assertNull(empty.plati.price)
        assertNull(empty.ggsel.price)
        assertEquals(310L,empty.plati.checkedAt)
        assertEquals(0L,empty.plati.failedAt)
    }
    @Test fun olderRequestFinishingLaterCannotReplaceNewerEvidence() {
        val fresh=OfferPriceCache.merge(old,mapOf("Plati" to 300.0),emptySet(),300,310,"")
        val late=OfferPriceCache.merge(fresh,mapOf("Plati" to 999.0),setOf("GGSEL"),200,500,"late")
        assertEquals(fresh,late)
    }
    @Test fun persistedPartialResultRoundTripsWithoutLosingDates() {
        val failed=OfferPriceCache.merge(old,emptyMap(),setOf("GGSEL"),200,210,"offline")
        assertEquals(failed,OfferPriceCache.decode(OfferPriceCache.encode(failed)))
        assertNull(OfferPriceCache.decode("invalid"))
        assertNull(OfferPriceCache.decode("""{"schema":99}"""))
    }
    @Test fun scopeIncludesFiltersRulesIgnoredDepthAndAliasesButCanonicalizesOrder() {
        val filters=ManualSearchFilters()
        fun key(filters:ManualSearchFilters=ManualSearchFilters(),rules:JSONObject=JSONObject(),ignored:Set<String> = emptySet(),pages:Int=1,aliases:List<String> = emptyList()) =
            OfferPriceCache.key(281990,filters,rules,ignored,pages,aliases)
        val base=key()
        assertNotEquals(base,key(filters.copy(type="Gift")))
        assertNotEquals(base,key(filters.copy(confirmedRf=true)))
        assertNotEquals(base,key(rules=JSONObject().put("blocked","title")))
        assertNotEquals(base,key(ignored=setOf("12345")))
        assertNotEquals(base,key(pages=2))
        assertNotEquals(base,key(aliases=listOf("Alternative")))
        assertEquals(key(rules=JSONObject("""{"a":1,"b":2}"""),ignored=linkedSetOf("2","1")),
            key(rules=JSONObject("""{"b":2,"a":1}"""),ignored=linkedSetOf("1","2")))
    }
}
