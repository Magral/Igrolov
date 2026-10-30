package ru.igrolov.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ManualCatalogTest {
    @Test fun onlyVerifiedAvailableOffersArePublishedBeforeSlowCardsFinish() {
        val slowStarted=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        val partial=java.util.concurrent.CountDownLatch(1)
        val executor=java.util.concurrent.Executors.newSingleThreadExecutor()
        val snapshots=java.util.Collections.synchronizedList(mutableListOf<List<Offer>>())
        val client=ManualCatalogEnricher {path->
            val id=path.substringAfter("/products/").substringBefore("/").toInt()
            if(id==10000) {slowStarted.countDown();check(release.await(3,java.util.concurrent.TimeUnit.SECONDS))}
            JSONObject("""{"retval":0,"product":{"id":$id,"name":"Cyberpunk 2077 Steam Key РФ","is_available":${if(id==10002)0 else 1},"prices":{"default":{"RUB":${id-9900}}}}}""")
        }
        try {
            val result=executor.submit<List<Offer>> {
                client.enrich((10000..10002).map {offer(it.toString(),"Key")},emptyMap(),{true},
                    onOffers={snapshots.add(it);partial.countDown()})
            }
            assertTrue(slowStarted.await(3,java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(partial.await(3,java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(result.isDone)
            assertTrue(snapshots.flatten().all {it.id=="10001" && it.priceVerified && it.available==true})
            release.countDown()
            assertEquals(3,result.get(3,java.util.concurrent.TimeUnit.SECONDS).size)
        } finally {release.countDown();executor.shutdownNow()}
    }
    private fun offer(id: String = "123456", type: String = "Не указан") = Offer(id, "Cyberpunk 2077 Steam", 900.0, type, "Регион РФ не подтвержден", "Игра")
    @Test fun descriptionDistinguishesGiftFromAccessToAccount() {
        assertEquals("Gift", ManualOfferClassifier.refine(offer(), JSONObject().put("info", "Метод доставки: Steam Gift — подарком на ваш аккаунт"), null)?.type)
        assertNull(ManualOfferClassifier.refine(offer(), JSONObject().put("info", "После оплаты вы получаете общий аккаунт Steam для игры в оффлайн режиме"), null))
        assertNull(ManualOfferClassifier.refine(offer(), JSONObject().put("info", "Вы получаете аккаунт с игрой"), null))
        assertNull(ManualOfferClassifier.refine(offer(), JSONObject().put("info", "Покупая товар вы получаете общий STEAM аккаунт. LOGIN:PASSWORD"), null))
        assertEquals("Gift", ManualOfferClassifier.refine(offer(), JSONObject().put("info", "Это не аккаунт. Игра в виде подарка на ваш аккаунт Steam"), null)?.type)
    }
    @Test fun freshTitleOverridesObsoleteDeliveryAndRegionFromSearch() {
        val old=offer(type="Key").copy(rfStatus="РФ указана в названии")
        val changed=ManualOfferClassifier.refine(old,JSONObject().put("name","Cyberpunk 2077 Steam Gift"),null)!!
        assertEquals("Gift",changed.type)
        assertEquals("Регион РФ не подтвержден",changed.rfStatus)
        assertEquals("Cyberpunk 2077 Steam Gift",changed.name)
        assertNull(ManualOfferClassifier.refine(old,JSONObject().put("name","Cyberpunk 2077 Xbox Key РФ"),null))
    }
    @Test fun conflictsNeverBecomeGiftOrKey() {
        assertEquals("Не указан", ManualOfferClassifier.refine(offer(type="Key"), JSONObject().put("info", "Steam Gift"), "Steam Keys")?.type)
        assertEquals("Key", ManualOfferClassifier.refine(offer(), JSONObject().put("info", "Цифровой ключ. Активация Steam"), null)?.type)
        assertEquals("Не указан", ManualOfferClassifier.refine(offer(), JSONObject(), "Steam")?.type)
        assertEquals("Gift", ManualOfferClassifier.refine(offer(), JSONObject(), "Steam Gifts")?.type)
        assertNull(ManualOfferClassifier.refine(offer(type="Gift"), JSONObject(), "Аккаунты Steam"))
    }
    @Test fun rentalProfileAndFalseSearchMatchesRejected() {
        for (title in listOf("Cyberpunk 2077 Steam профиль", "Cyberpunk 2077 Steam аренда", "Cyberpunk 2077 Steam аккаунта", "Cyberpunk 2077 Steam Offline"))
            assertNull(ManualOfferClassifier.titleType(title, "Cyberpunk 2077"))
        assertNull(ManualOfferClassifier.titleType("Cyberpunk 2077 Steam Key", "Cyberpunk 207"))
        assertEquals("Gift", ManualOfferClassifier.titleType("Cyberpunk 2077 Steam Gift", "Cyberpunk 2077"))
    }
    @Test fun variantsAndExcludedRussiaAreExplicit() {
        val card = JSONObject("""{"info":"Недоступно для России и Беларуси", "options":[{"name":"Регион"}],"is_available":0}""")
        val result = ManualOfferClassifier.refine(offer(type="Gift"), card, null)!!
        assertEquals("РФ исключена", result.rfStatus)
        assertTrue(result.variablePrice)
        assertEquals(false, result.available)
        assertNull(ManualOfferClassifier.refine(offer(type="Gift"), JSONObject().put("info", "Россия со сменой региона"), null))
    }
    @Test fun allCandidatesBeyondTwelveCheckedWithBoundedConcurrencyAndSellerCache() {
        val inFlight=AtomicInteger(); val peak=AtomicInteger(); val detailCount=AtomicInteger(); val categoryCount=AtomicInteger()
        val client=ManualCatalogEnricher { path ->
            val now=inFlight.incrementAndGet(); peak.updateAndGet { maxOf(it,now) }
            try {
                if (path.startsWith("/api/categories")) {
                    categoryCount.incrementAndGet()
                    JSONObject("""{"retval":0,"category":[{"id":7,"name":"Steam Gifts"}]}""")
                } else {
                    detailCount.incrementAndGet()
                    val id=path.substringAfter("/products/").substringBefore("/")
                    JSONObject().put("retval",0).put("product",JSONObject().put("id",id).put("seller",JSONObject().put("id","101")).put("category_id",7))
                }
            } finally { inFlight.decrementAndGet() }
        }
        val offers=(10000..10039).map { offer(it.toString()) }
        val result=client.enrich(offers,offers.associate { it.id to "101" },{true})
        assertEquals(40,result.size); assertEquals(40,detailCount.get()); assertEquals(1,categoryCount.get())
        assertTrue(peak.get() <= 4); assertTrue(result.all { it.type == "Gift" && it.detailsChecked })
        assertEquals(offers.map { it.id },result.map { it.id })
    }
    @Test fun apiFailureAndSellerMismatchDoNotInventCategory() {
        val offers=listOf(offer())
        val mismatch=ManualCatalogEnricher { path ->
            assertFalse(path.startsWith("/api/categories"))
            JSONObject("""{"retval":0,"product":{"id":"123456","seller":{"id":"202"},"category_id":7}}""")
        }.enrich(offers,mapOf("123456" to "101"),{true})
        assertEquals("Не указан",mismatch.single().type)
        val offline=ManualCatalogEnricher { throw java.io.IOException("offline") }.enrich(offers,emptyMap(),{true})
        assertFalse(offline.single().detailsChecked)
        assertEquals("Не указан",offline.single().type)
        val wrongProduct=ManualCatalogEnricher { JSONObject("""{"retval":0,"product":{"id":"999999","info":"Steam Gift"}}""") }
            .enrich(offers,emptyMap(),{true})
        assertFalse(wrongProduct.single().detailsChecked)
    }
    @Test fun cancelledSearchStopsTakingCandidatesAndDropsResults() {
        val calls=AtomicInteger()
        val client=ManualCatalogEnricher { calls.incrementAndGet(); JSONObject("""{"retval":0}""") }
        val result=client.enrich((10000..10499).map { offer(it.toString()) },emptyMap(),{calls.get()<4})
        assertTrue(result.isEmpty()); assertTrue(calls.get() <= 7)
    }
    @Test fun recordedPublicApiCardsClassifyGiftKeyAndDisguisedAccounts() {
        val json = javaClass.getResourceAsStream("/digiseller-live-2026-10-20.json")!!.bufferedReader().use { it.readText() }
        val fixtures = org.json.JSONArray(json)
        assertEquals(7, fixtures.length())
        for (i in 0 until fixtures.length()) {
            val sample = fixtures.getJSONObject(i)
            val title = sample.getString("name")
            val initial = ManualOfferClassifier.titleType(title, sample.getString("query"))
            val result = initial?.let { ManualOfferClassifier.refine(offer(type=it).copy(name=title),
                JSONObject().put("name",title).put("info",sample.getString("info")), null) }
            if (sample.getString("expected") == "Excluded") assertNull(title, result)
            else assertEquals(title, sample.getString("expected"), result?.type)
        }
    }

    @Test fun royalEditionIsKeptAsBaseGameWhileStandaloneDlcIsRejected() {
        val game = ManualGameReference.Game("Kingdom Come: Deliverance II", 1771300)
        assertEquals("Игра", ManualContentRules.classify(
            "Kingdom Come: Deliverance II Royal Edition Steam Key РФ",
            "Ключ Steam, активация в РФ", game
        ))
        assertEquals("DLC", ManualContentRules.classify(
            "Kingdom Come: Deliverance II Mysteria Ecclesiae DLC Steam Key РФ",
            "Для запуска требуется базовая игра", game
        ))
    }
}

