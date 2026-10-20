package ru.igrolov.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** A recorded complete search page, not a hand-picked collection of only successful offers. */
class StellarisRegressionTest {
    @Test fun actualHundredResultPageDoesNotLeakDlcUnavailableOrTeaserPrices() {
        val root=JSONObject(javaClass.getResourceAsStream("/stellaris-2026-10-20.json")!!.bufferedReader().use {it.readText()})
        val array=root.getJSONArray("samples")
        assertEquals(100,array.length())
        val samples=(0 until array.length()).map {array.getJSONObject(it)}.associateBy {it.getString("id")}
        val dlc=root.getJSONArray("dlc_ids")
        val game=ManualGameReference.Game("Stellaris",281990,(0 until dlc.length()).map {dlc.getInt(it)}.toSet())
        val offers=samples.values.mapNotNull { sample ->
            val title=sample.getString("name")
            val type=ManualOfferClassifier.titleType(title,"Stellaris") ?: return@mapNotNull null
            val price=sample.optString("catalog_price").toDoubleOrNull() ?: return@mapNotNull null
            Offer(sample.getString("id"),title,price,type,OfferRules.rfStatus(title),OfferRules.editionType(title,"Stellaris"))
        }
        val client=ManualCatalogEnricher { path ->
            when {
                path.startsWith("/api/categories") -> JSONObject("""{"retval":0,"category":[]}""")
                path.startsWith("/api/products/price/calc") -> {
                    val id=path.substringAfter("product_id=").substringBefore('&')
                    samples[id]?.optJSONObject("calculation") ?: JSONObject("""{"retval":33}""")
                }
                else -> {
                    val id=path.substringAfter("/products/").substringBefore('/')
                    JSONObject().put("retval",0).put("product",samples[id]?.optJSONObject("product"))
                }
            }
        }
        val enriched=client.enrich(offers,samples.mapValues {it.value.optString("seller")},{true},reference=game)
        val visible=enriched.filter { ManualContentRules.visible(it,"Игра","Все") }
        assertTrue("The regression must preserve genuine base-game offers",visible.size>=4)
        assertTrue("The available base Steam Key must remain",visible.any {it.id=="5581236"})
        assertTrue("Fresh price of base game must remain",visible.any {it.id=="1412961" && it.price==1499.0})
        for(offer in visible) {
            val sample=samples.getValue(offer.id)
            assertTrue("DLC leaked: ${offer.name}",sample.getBoolean("base_game"))
            assertEquals("No unavailable cards: ${offer.name}",1,sample.getJSONObject("product").getInt("is_available"))
            assertTrue(offer.priceVerified)
            assertFalse(offer.variablePrice)
            val calculated=sample.optJSONObject("calculation")?.optJSONObject("data")
            val price=if(calculated==null) sample.getJSONObject("product").getJSONObject("prices").getJSONObject("default").getDouble("RUB")
                else calculated.getDouble("amount")+calculated.getDouble("commission")
            assertEquals(offer.name,price,offer.price,0.001)
        }
        assertFalse(visible.any {it.id in setOf("3347666","2851969","3658460","2118012","3482888")})
        val selected=enriched.single {it.id=="3535868"}
        assertTrue(selected.priceVerified)
        assertEquals(4914.0,selected.price,0.001)
        assertTrue("Variant selection must identify the base game",ManualContentRules.visible(selected,"Игра","Все"))
        assertEquals(visible.map {it.price}.sorted(),visible.map {it.price})
    }
}
