package ru.igrolov.app

import org.junit.Assert.*
import org.junit.Test

class GameDealsTest {
    private val now=100_000_000L
    private val quote=SteamRuQuote(2000.0,true,"base:1","Game",now)
    private fun source(price:Double,scope:String="base:1")=SourcePriceCheck(price,now,scope=scope,deliveryType="Key")
    @Test fun comparesOnlyFreshIdenticalEditionsAndNamesTheWinningSource() {
        val prices=CachedGamePrices(source(1500.0),source(100.0,"edition:1:royal"))
        assertEquals(GameSaving(500.0,25,"Plati"),GameDeals.saving(quote,prices,now))
        assertNull(GameDeals.saving(quote.copy(scope=""),prices,now))
        assertNull(GameDeals.saving(quote,prices.copy(plati=source(1500.0).copy(failedAt=now+1)),now+1))
        assertNull(GameDeals.saving(quote.copy(checkedAt=1),prices,now))
        assertNull(GameDeals.saving(quote,prices.copy(plati=source(1500.0).copy(checkedAt=1)),now))
        assertNull(GameDeals.saving(quote.copy(available=false),prices,now))
        assertNull(GameDeals.saving(quote,CachedGamePrices(source(2200.0),source(2000.0)),now))
    }
    @Test fun oldDataAndConfirmedEmptyResultsCannotInventSavingsOrRestoreLegacyPrices() {
        assertNull(GameDeals.saving(quote,CachedGamePrices(SourcePriceCheck(100.0,now)),now))
        assertNull(GameDeals.price(SourcePriceCheck(checkedAt=now),100.0))
        assertEquals(100.0,GameDeals.price(null,100.0)!!,0.0)
    }
    @Test fun sortsStablyWithUnknownPricesAtTheEnd() {
        val items=listOf("unknown","medium","cheap","tie")
        val prices=mapOf("medium" to 200.0,"cheap" to 100.0,"tie" to 100.0)
        assertEquals(listOf("cheap","tie","medium","unknown"),GameDeals.sort(items,DealSort.PRICE,{prices[it]},{null}))
        assertEquals(listOf("medium","cheap","tie","unknown"),GameDeals.sort(items,DealSort.SAVING,{null},{prices[it]?.let {v->GameSaving(v,10)}}))
        assertEquals(items,GameDeals.sort(items,DealSort.DEFAULT,{null},{null}))
    }
    @Test fun baseEditionProofRejectsUnknownAndPremiumSuffixes() {
        val canonical="Kingdom Come: Deliverance II"
        assertTrue(OfferComparison.baseTitle("$canonical Steam Key RU",canonical))
        assertTrue(OfferComparison.baseTitle("$canonical Standard Edition",canonical))
        listOf("Royal Edition","Gold Edition","Founder's Pack","DLC","Season Pass").forEach {
            assertFalse(it,OfferComparison.baseTitle("$canonical $it Steam",canonical))
        }
        val offer=Offer("1","$canonical Steam Key RU",100.0,"Key","РФ","Игра",available=true,priceVerified=true)
        assertEquals(OfferComparisonProof("base:1","Key"),OfferComparison.proof(offer,1,canonical))
        assertEquals("",OfferComparison.proof(offer.copy(available=false),1,canonical).scope)
        assertEquals("",OfferComparison.proof(offer.copy(priceVerified=false),1,canonical).scope)
        val royal="$canonical Royal Edition"
        assertEquals(OfferComparison.scope(1,canonical,royal),OfferComparison.proof(offer.copy(name="$royal Steam Key RU"),1,canonical,royal).scope)
    }
    @Test fun cacheKeepsProofOnlyForThePriceItActuallyVerified() {
        val old=CachedGamePrices(source(100.0))
        assertEquals(old.plati,OfferPriceCache.decode(OfferPriceCache.encode(old))!!.plati)
        val failed=OfferPriceCache.merge(old,emptyMap(),setOf("Plati"),now+1,now+2,"offline")
        assertEquals(old.plati.scope,failed.plati.scope)
        val changed=OfferPriceCache.merge(failed,mapOf("Plati" to 150.0),emptySet(),now+3,now+4,"")
        assertEquals("",changed.plati.scope)
        assertEquals("",changed.plati.deliveryType)
        val proof=OfferComparisonProof("edition:1:royal","Gift")
        val premium=OfferPriceCache.merge(changed,mapOf("Plati" to 120.0),emptySet(),now+5,now+6,"",proofs=mapOf("Plati" to proof))
        assertEquals(proof.scope,premium.plati.scope)
        assertEquals("Gift",premium.plati.deliveryType)
    }
    @Test fun sortPreferencesRoundTripAndOldSettingsKeepDefaultOrder() {
        val input=SteamLeaderFilterInput(titleQuery="Game")
        val snapshot=CatalogFilterSettings.decode(CatalogFilterSettings.encode(input,sort=DealSort.SAVING))
        assertEquals(DealSort.SAVING,snapshot.sort)
        assertEquals(input,snapshot.input)
        assertEquals(DealSort.DEFAULT,CatalogFilterSettings.decode("{}").sort)
    }
}
