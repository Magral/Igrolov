package ru.igrolov.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TrackedVariantRegressionTest {
    private val title="Stellaris +ВЫБОР STEAM Gift РФ"
    private fun product()=JSONObject("""{"id":"123456","name":"$title","is_available":1,"prices":{"default":{"RUB":1}},"options":[
        {"id":11,"label":"Выберите издание","type":"radio","variants":[{"value":21,"text":"Stellaris","visible":1},{"value":22,"text":"Stellaris Utopia","visible":1}]},
        {"id":12,"label":"Страна","type":"radio","variants":[{"value":31,"text":"Украина","visible":1},{"value":32,"text":"Россия","visible":1}]}]}""")
    @Test fun reproducesWhyFullProductTitleCannotReplaceCanonicalGameName() {
        assertFalse(ManualPurchaseProof.plan(product(),title).valid)
        val plan=ManualPurchaseProof.plan(product(),"Stellaris")
        assertTrue(plan.valid);assertEquals(listOf("11:21","12:32"),plan.pairs)
    }
    @Test fun searchToTrackingKeepsVerifiedPriceAndExactContextAcrossRestartAndRefresh() {
        val offer=Offer("123456",title,4914.0,"Gift","РФ выбрана в параметрах","Игра",detailsChecked=true,available=true,priceVerified=true,canonical="Stellaris",steamAppId=281990)
        val tracked=TrackedPriceCache.fromOffer(offer)
        assertEquals(4914.0,tracked.price!!,0.0)
        val restored=TrackedPriceCache.decode(tracked.id,TrackedPriceCache.encode(tracked))
        val identity=TrackedGameIdentity.resolve(restored,title,emptyList())
        assertEquals("Stellaris",identity.canonical);assertEquals(281990,identity.appId)
        val updated=ManualPurchaseProof.inspect(product(),identity.canonical) {path->
            assertTrue(path.contains("11%3A21"));assertTrue(path.contains("12%3A32"))
            JSONObject("""{"retval":0,"data":{"amount":5200,"commission":0,"count":1,"currency":"RUB"}}""")
        }
        assertEquals(5200.0,updated.price!!,0.0)
    }
    @Test fun oldBrokenTrackedCardCanRecoverFromKnownGamesWithoutBeingReadded() {
        val old=TrackedPriceCache.decode("123456","""{"proofSchema":106,"name":"$title","price":null}""")
        val identity=TrackedGameIdentity.resolve(old,title,GameSearchResolver.seeds)
        assertEquals("Stellaris",identity.canonical);assertTrue(ManualPurchaseProof.plan(product(),identity.canonical).valid)
        val sequel=TrackedGameIdentity.resolve(old.copy(name="Cities Skylines II Steam Key"),"Cities Skylines II Steam Key",GameSearchResolver.seeds)
        assertEquals(949230,sequel.appId)
    }
    @Test fun ambiguousOrUnknownLegacyIdentityIsNotGuessedAndCanBeRecoveredLater() {
        val tracked=Tracked("123456","Game",null,"","https://plati.market/itm/123456")
        val ambiguous=TrackedGameIdentity.resolve(tracked,"Game Steam Key",listOf(GameSearchResolver.Candidate(1,"Game"),GameSearchResolver.Candidate(2,"Game")))
        assertFalse(ambiguous.confirmed)
        assertFalse(TrackedGameIdentity.resolve(tracked,"Cyberpunk 2077 + Stellaris Steam Key",GameSearchResolver.seeds).confirmed)
        assertFalse(TrackedGameIdentity.resolve(tracked,"Completely unknown Steam Key",GameSearchResolver.seeds).confirmed)
    }
    @Test fun networkFailureDoesNotErasePriceJustVerifiedInSearch() {
        val previous=Tracked("123456",title,4914.0,"","https://plati.market/itm/123456","Stellaris",281990)
        val failed=previous.copy(price=null,status="Ошибка загрузки: HTTP 503")
        val merged=TrackedPriceCache.merge(previous,failed)
        assertEquals(4914.0,merged.price!!,0.0);assertTrue(merged.status.startsWith("Нет связи"))
        assertEquals("Stellaris",merged.canonical)
        assertNull(TrackedPriceCache.merge(previous,previous.copy(price=null,status="Нет в наличии")).price)
    }
}
