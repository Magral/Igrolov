package ru.igrolov.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OfferProductLinkTest {
    private fun offer(id:String,price:Double,url:String="",marketplace:String="Plati")=
        Offer(id,"Stellaris Steam Key РФ",price,"Key","РФ указана в названии","Игра",
            detailsChecked=true,available=true,priceVerified=true,marketplace=marketplace,productUrl=url)

    @Test fun cheapestEligibleProductSuppliesBothPriceAndUrl() {
        val rows=listOf(offer("11111",150.0),offer("22222",100.0),
            offer("33333",50.0).copy(available=false),
            offer("ggsel:1",90.0,"https://ggsel.net/en/catalog/product/stellaris-key","GGSEL"))
        val best=OfferPriceCache.bestOffers(rows,ManualSearchFilters(),JSONObject(),emptySet())
        assertEquals(100.0,best.getValue("Plati").price,0.0)
        assertEquals("https://plati.market/itm/22222",OfferProductLink.forOffer(best.getValue("Plati")))
        assertEquals("https://ggsel.net/en/catalog/product/stellaris-key",OfferProductLink.forOffer(best.getValue("GGSEL")))
    }
    @Test fun persistenceAndPartialFailuresKeepMatchingPriceAndLink() {
        val first=OfferPriceCache.merge(null,mapOf("Plati" to 100.0,"GGSEL" to 90.0),emptySet(),1,2,"",
            productUrls=mapOf("Plati" to "https://plati.market/itm/12345","GGSEL" to "https://ggsel.net/en/catalog/product/key"))
        val failed=OfferPriceCache.merge(first,mapOf("Plati" to 1.0,"GGSEL" to 80.0),setOf("Plati"),3,4,"offline",
            productUrls=mapOf("Plati" to "https://plati.market/itm/99999","GGSEL" to "https://ggsel.net/en/catalog/product/cheaper-key"))
        assertEquals(100.0,failed.plati.price!!,0.0)
        assertEquals(first.plati.productUrl,failed.plati.productUrl)
        assertEquals("https://ggsel.net/en/catalog/product/cheaper-key",failed.ggsel.productUrl)
        assertEquals(failed,OfferPriceCache.decode(OfferPriceCache.encode(failed)))
        assertEquals(failed,OfferPriceCache.merge(failed,mapOf("Plati" to 10.0),emptySet(),1,9,"",
            productUrls=mapOf("Plati" to "https://plati.market/itm/88888")))
        val changedWithoutLink=OfferPriceCache.merge(failed,mapOf("Plati" to 120.0),emptySet(),5,6,"")
        assertEquals("",changedWithoutLink.plati.productUrl)
        assertNull(changedWithoutLink.ggsel.price);assertEquals("",changedWithoutLink.ggsel.productUrl)
    }
    @Test fun legacyPricesAreReadableWithoutInventingTheirProduct() {
        val old=OfferPriceCache.decode("""{"schema":1,"Plati":{"price":100,"checked":1}}""")!!
        assertEquals(100.0,old.plati.price!!,0.0);assertEquals("",old.plati.productUrl)
        assertEquals("",OfferProductLink.forProduct("GGSEL","ggsel:42","https://ggsel.net/"))
        assertEquals("",OfferProductLink.validated("Plati","javascript:alert(1)"))
        assertEquals("",OfferProductLink.validated("Plati","https://plati.market.example/itm/12345"))
    }
    @Test fun monitorMinimumUsesWinningIdAndExactPrice() {
        val watch=JSONObject().put("last",90.5).put("bestId","ggsel:42").put("topOffers",JSONArray()
            .put(JSONObject().put("id","12345").put("price",90.5).put("marketplace","Plati"))
            .put(JSONObject().put("id","ggsel:42").put("price",90.5).put("marketplace","GGSEL")
                .put("url","https://ggsel.net/en/catalog/product/right-edition")))
        assertEquals("https://ggsel.net/en/catalog/product/right-edition",OfferProductLink.monitorMinimum(watch))
        watch.put("last",80)
        assertEquals("",OfferProductLink.monitorMinimum(watch))
    }
}
