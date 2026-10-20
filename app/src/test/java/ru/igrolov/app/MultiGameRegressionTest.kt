package ru.igrolov.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.zip.GZIPInputStream

/** Three complete recorded pages. Golden IDs were reviewed by title and public card description. */
class MultiGameRegressionTest {
    @Test fun civilizationSixKeepsBaseGamesButRejectsOtherPartsDlcAndAccounts()=check(289070,2,setOf("4719600","3525803"))
    @Test fun citiesSkylinesDoesNotIncludeTheSequelOrNamedAddons()=check(255710,2,setOf("2908921","2983193","5514108"))
    @Test fun simsFourPageContainsNoVerifiedSteamGiftOrKeyBaseGame()=check(1222670,0,emptySet())
    private fun check(appId:Int,minimum:Int,required:Set<String>) {
        val encoded=javaClass.getResourceAsStream("/catalog-$appId-2026-10-20.json.gz.b64")!!.bufferedReader().use {it.readText()}
        val root=JSONObject(GZIPInputStream(Base64.getMimeDecoder().decode(encoded).inputStream()).bufferedReader().use {it.readText()})
        val rows=root.getJSONArray("samples")
        assertEquals(100,rows.length())
        val samples=(0 until rows.length()).map {rows.getJSONObject(it)}.associateBy {it.getString("id")}
        val dlc=root.getJSONArray("dlc_ids")
        val game=ManualGameReference.Game(root.getString("canonical"),appId,(0 until dlc.length()).map {dlc.getInt(it)}.toSet(),GameSearchResolver.aliases(appId,root.getString("canonical")))
        val offers=samples.values.mapNotNull {s ->
            val name=s.getString("name")
            val type=(listOf(root.getString("query"))+game.aliases).firstNotNullOfOrNull {ManualOfferClassifier.titleType(name,it)} ?: return@mapNotNull null
            val price=s.optString("catalog_price").toDoubleOrNull() ?: return@mapNotNull null
            Offer(s.getString("id"),name,price,type,OfferRules.rfStatus(name),OfferRules.editionType(name,game.canonical))
        }
        val client=ManualCatalogEnricher {path ->
            when {
                path.startsWith("/api/categories") -> JSONObject("""{"retval":0,"category":[]}""")
                path.startsWith("/api/products/price/calc") -> {
                    val sample=samples[path.substringAfter("product_id=").substringBefore('&')]
                    val expected=sample?.optJSONArray("calculation_options")
                    if(expected!=null) for(i in 0 until expected.length()) assertTrue(path.contains(java.net.URLEncoder.encode(expected.getString(i),"UTF-8")))
                    sample?.optJSONObject("calculation") ?: JSONObject("""{"retval":33}""")
                }
                else -> JSONObject().put("retval",0).put("product",samples[path.substringAfter("/products/").substringBefore('/')]?.optJSONObject("product"))
            }
        }
        val enriched=client.enrich(offers,samples.mapValues {it.value.optString("seller")},{true},reference=game)
        val visible=enriched.filter {ManualContentRules.visible(it,"Игра","Все")}
        println("Recorded ${root.getString("query")}: ${samples.size} rows, ${offers.size} candidates, ${visible.size} verified main games")
        visible.forEach {println("Kept ${it.id}: ${it.name} / ${it.price}")}
        assertTrue("Must retain real available main games",visible.size>=minimum)
        for(id in required) assertTrue("Main-game key $id disappeared",visible.any {it.id==id})
        if(appId==1222670) assertTrue("Recorded Sims page has only services/accounts/non-Steam offers",visible.isEmpty())
        for(offer in visible) {
            val s=samples.getValue(offer.id)
            assertTrue("DLC, sequel, account or service leaked: ${offer.id} ${offer.name}",s.getBoolean("base_game"))
            assertEquals(1,s.getJSONObject("product").getInt("is_available"))
            val calc=s.optJSONObject("calculation")?.optJSONObject("data")
            val actual=if(calc==null)s.getJSONObject("product").getJSONObject("prices").getJSONObject("default").getDouble("RUB") else calc.getDouble("amount")+calc.getDouble("commission")
            assertEquals("Catalog teaser must not replace actual price",actual,offer.price,0.001)
        }
        assertEquals(visible.map {it.price}.sorted(),visible.map {it.price})
    }
}
