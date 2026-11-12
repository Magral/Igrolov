package ru.igrolov.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SteamRuQuoteTest {
    private val title="Kingdom Come: Deliverance II"
    private val royal="$title Royal Edition"
    private fun app(extra:String)="""{"1":{"success":true,"data":{"type":"game",$extra}}}"""
    private val prices=""""price_overview":{"currency":"RUB","final":150000},"package_groups":[{"subs":[{"packageid":10,"price_in_cents_with_discount":150000},{"packageid":20,"price_in_cents_with_discount":220000}]}]"""
    @Test fun selectedEditionUsesItsOwnPriceAndRuRegionOnly() {
        val quote=SteamRuQuotes.fetch(1,title,20,royal,100) {url->
            assertTrue(url.contains("cc=ru"));assertTrue(url.contains("appdetails"));JSONObject(app(prices))
        }
        assertEquals(2200.0,quote.price!!,0.0)
        assertEquals(OfferComparison.scope(1,title,royal),quote.scope)
        assertEquals(quote,SteamRuQuotes.decode(SteamRuQuotes.encode(quote)))
    }
    @Test fun unavailableRuAndMissingEditionNeverSubstituteBaseOrAnotherCountry() {
        val unavailable=SteamRuQuotes.fetch(1,title,20,royal,100) {JSONObject("""{"1":{"success":false}}""")}
        assertEquals(false,unavailable.available);assertNull(unavailable.price)
        assertEquals(royal,unavailable.edition)
        val missing=SteamRuQuotes.fetch(1,title,30,royal,100) {JSONObject(app(prices))}
        assertEquals(false,missing.available);assertNull(missing.price)
    }
    @Test fun appOverviewCanDisplayWithoutClaimingEditionProof() {
        val quote=SteamRuQuotes.fetch(1,title,now=100) {JSONObject(app(""""price_overview":{"currency":"RUB","final":150000}"""))}
        assertEquals(1500.0,quote.price!!,0.0);assertEquals("",quote.scope)
    }
    @Test fun basePackageMustContainTheGameAndHaveItsExactName() {
        fun quote(name:String,appId:Int)=SteamRuQuotes.fetch(1,title,now=100) {url->
            JSONObject(if(url.contains("appdetails"))app(prices) else """{"10":{"success":true,"data":{"name":"$name","apps":[{"id":$appId}],"price":{"currency":"RUB","final":150000}}}}""")
        }
        assertEquals("base:1",quote(title,1).scope)
        assertEquals("",quote(royal,1).scope)
        assertEquals("",quote(title,2).scope)
    }
    @Test fun selectedPackageDetailsMustContainTheSelectedGame() {
        val quote=SteamRuQuotes.fetch(1,title,20,royal,100) {url->JSONObject(
            if(url.contains("appdetails"))app(""""package_groups":[{"subs":[{"packageid":20}]}]""")
            else """{"20":{"success":true,"data":{"name":"$royal","apps":[{"id":1}],"price":{"currency":"RUB","final":220000}}}}""")}
        assertEquals(2200.0,quote.price!!,0.0)
    }
    @Test(expected=IllegalStateException::class) fun wrongCurrencyCannotBeShownAsRubles() {
        SteamRuQuotes.fetch(1,title,now=100) {JSONObject(app(""""price_overview":{"currency":"USD","final":150000}"""))}
    }
    @Test fun corruptCacheIsSafe() {
        assertNull(SteamRuQuotes.decode("bad"))
        assertNull(SteamRuQuotes.decode("""{"price":-5}""")!!.price)
        assertEquals(0L,SteamRuQuotes.decode("""{"checked":-5}""")!!.checkedAt)
    }
}
