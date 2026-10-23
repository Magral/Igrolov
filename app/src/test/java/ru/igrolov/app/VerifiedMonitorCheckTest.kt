package ru.igrolov.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class VerifiedMonitorCheckTest {
    private val reference=ManualGameReference.Game("Stellaris",281990)
    private fun offer(id:Int)=Offer(id.toString(),"Stellaris Steam Key РФ",(id-10000).toDouble()+1,"Key","РФ указана в названии","Игра")
    @Test fun checksPastTwentyCandidatesAndSortsFreshCalculatedPricesBeforeChoosingMinimum() {
        val calls=AtomicInteger()
        val offers=(10000..10031).map(::offer)
        val result=VerifiedMonitorCheck.run(CatalogCheck.Snapshot(offers,emptyMap()),reference,"Все",JSONObject(),emptySet(),{true}) {path->
            if(path.startsWith("/api/products/price/calc")) JSONObject("""{"retval":0,"data":{"amount":150,"commission":0,"currency":"RUB","count":1}}""")
            else {
                calls.incrementAndGet()
                val id=path.substringAfter("/products/").substringBefore('/').toInt()
                val p=JSONObject().put("id",if(id==10030)99999 else id).put("name",when(id) {10025->"Stellaris Utopia Steam Key РФ";10027->"Stellaris Steam Key";else->"Stellaris Steam Key РФ"})
                    .put("is_available",if(id<10024)0 else 1).put("prices",JSONObject().put("default",JSONObject().put("RUB",if(id==10029)50 else 500)))
                if(id==10026)p.put("info","Вы получаете аккаунт Steam с игрой")
                if(id==10027) return@run JSONObject().put("retval",0).put("product",p) // unknown region despite the catalog's obsolete РФ
                if(id==10028)p.put("options",JSONArray("""[{"id":1,"label":"Страна","type":"radio","variants":[{"value":2,"text":"Россия","visible":1}]}]"""))
                if(id==10031)p.put("options",JSONArray("""[{"id":1,"label":"Издание","type":"radio","variants":[{"value":2,"text":"Stellaris Utopia","visible":1}]}]"""))
                JSONObject().put("retval",0).put("product",p)
            }
        }
        assertEquals(32,calls.get())
        assertEquals(listOf("10029","10028","10024"),result.verified.map {it.id})
        assertEquals(listOf(50.0,150.0,500.0),result.verified.map {it.price})
    }
    @Test fun widespreadOutageStopsTheBatchAfterAtMostFourInFlightRequests() {
        val calls=AtomicInteger()
        try {
            VerifiedMonitorCheck.run(CatalogCheck.Snapshot((10000..10499).map(::offer),emptyMap()),reference,"Все",JSONObject(),emptySet(),{true}) {
                calls.incrementAndGet();throw IOException("HTTP 503")
            }
            fail("An incomplete check must fail")
        }catch(expected:IOException) {assertTrue(calls.get() in 1..4)}
    }
    @Test fun selectedSteamEditionOnlyReturnsThatEditionFromAutomaticMonitoring() {
        val canonical = "Kingdom Come: Deliverance II"
        val edition = "$canonical Royal Edition"
        val reference = ManualGameReference.Game(canonical, 1771300, aliases = listOf(edition))
        val names = listOf("$canonical Steam Key РФ", "$edition Steam Key РФ")
        val candidates = names.mapIndexed { index, title ->
            Offer("${11000 + index}", title, 100.0 + index, "Key", "РФ указана в названии", "Игра")
        }
        val result = VerifiedMonitorCheck.run(
            CatalogCheck.Snapshot(candidates, emptyMap()), reference, "Все", JSONObject(), emptySet(), { true },
            selectedEdition = edition,
            allEditions = listOf(canonical, edition),
            request = { path ->
                val id = path.substringAfter("/products/").substringBefore('/').toInt().toString()
                JSONObject().put("retval", 0).put("product", JSONObject()
                    .put("id", id).put("name", names[id.toInt() - 11000])
                    .put("is_available", 1)
                    .put("prices", JSONObject().put("default", JSONObject().put("RUB", 350.0))))
            }
        )
        assertEquals(listOf("11001"), result.verified.map { it.id })
    }
    @Test(expected=IOException::class) fun partialCardNetworkFailureIsNotAConfirmedEmptyMarketplace() {
        VerifiedMonitorCheck.run(CatalogCheck.Snapshot(listOf(offer(10000)),emptyMap()),reference,"Все",JSONObject(),emptySet(),{true}) {throw IOException("HTTP 503")}
    }
    @Test(expected=IllegalArgumentException::class) fun unknownSteamGameCannotProduceAutomaticAlerts() {
        VerifiedMonitorCheck.run(CatalogCheck.Snapshot(emptyList(),emptyMap()),ManualGameReference.Game("Stellaris"),"Все",JSONObject(),emptySet(),{true}) {null}
    }
}
