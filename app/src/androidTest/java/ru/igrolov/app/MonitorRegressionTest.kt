package ru.igrolov.app

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit

/** Exercises the actual WorkManager worker, transport, verifier, persistence and notifications. */
@RunWith(AndroidJUnit4::class)
class MonitorRegressionTest {
    @Test fun manualChecksQueueBehindAnExistingCheck() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val manager=androidx.work.WorkManager.getInstance(context)
        manager.cancelUniqueWork("plati_manual_price_check").result.get()
        val first=OneTimeWorkRequestBuilder<PriceWatchWorker>().setInitialDelay(1,TimeUnit.DAYS).build()
        val second=OneTimeWorkRequestBuilder<PriceWatchWorker>().setInitialDelay(1,TimeUnit.DAYS).build()
        WatchScheduler.enqueueManualCheck(context,first).result.get()
        WatchScheduler.enqueueManualCheck(context,second).result.get()
        val queued=manager.getWorkInfosForUniqueWork("plati_manual_price_check").get()
        assertEquals("A second refresh must remain queued behind the active refresh",2,queued.size)
        manager.cancelUniqueWork("plati_manual_price_check").result.get()
    }

    @Test fun verifiedPricesFurtherDropsRetriesAndConcurrentWatchEdits() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val mode=AtomicInteger(0)
        val calls=AtomicInteger(0)
        val changed=AtomicBoolean(false)
        val ggselEnabled=AtomicBoolean(false)
        val ggselOffline=AtomicBoolean(false)
        URL.setURLStreamHandlerFactory { protocol -> if(protocol!="https") null else object:URLStreamHandler() {
            override fun openConnection(url:URL):URLConnection=object:HttpURLConnection(url) {
                override fun connect(){}
                override fun disconnect(){}
                override fun usingProxy()=false
                override fun getResponseCode()=if(mode.get()==1 || (mode.get()==2 && url.path.contains("/10006/")) || (ggselOffline.get() && url.host in setOf("api.ggsel.com","api4.ggsel.com")))503 else 200
                override fun getOutputStream()=ByteArrayOutputStream()
                override fun getInputStream():ByteArrayInputStream {
                    if(getResponseCode()!=200)throw IOException("HTTP ${getResponseCode()}")
                    val json=when {
                        url.host=="api.ggsel.com" && url.path.endsWith("query-categories") -> if(ggselEnabled.get())
                            JSONObject("""{"data":{"categories":[{"digi_catalog":77,"name":"Cyberpunk 2077","url":"cyberpunk-2077"}]}}""")
                            else JSONObject("""{"data":{"categories":[]}}""")
                        url.host=="api4.ggsel.com" && url.path.endsWith("/categories") -> JSONObject("""{"items":[
                            {"id_goods":77777,"url":"cyberpunk-steam-key-ru","name":"Cyberpunk 2077 Steam Key РФ","forbidden_type":0,
                             "price_for_one_wmr":"50","is_preorder":false,"options_check":false,"options":[]}] }""")
                        url.path.contains("appdetails") -> JSONObject("""{"1091500":{"success":true,"data":{"name":"Cyberpunk 2077","type":"game","dlc":[2138330]}}}""")
                        url.path.endsWith("search2") -> {
                            val items=JSONArray()
                            for(i in 0..7)items.put(JSONObject().put("id",10000+i).put("name","Cyberpunk 2077 Steam Key РФ").put("price_rur",1+i))
                            JSONObject().put("items",JSONObject().put("item",items))
                        }
                        else -> {
                            calls.incrementAndGet()
                            if(mode.get() in setOf(7,8,9) && changed.compareAndSet(false,true)) {
                                val edit=Thread {
                                    if(mode.get()==7)WatchStore.remove(context,0)
                                    else if(mode.get()==8) WatchStore.add(context,"Cyberpunk 2077","Key",1.0)
                                    else IgnoredStore.add(context,"10005","Cyberpunk 2077 Steam Key РФ")
                                }
                                edit.start();edit.join(1500)
                                check(!edit.isAlive) {"Watch edits blocked by network lock"}
                            }
                            val id=url.path.split('/')[3].toInt()
                            val price=if(id==10005 && mode.get()==9)90 else if(mode.get()==4 && id==10005)95 else 100+id-10000
                            val name=if(mode.get()==6 && id==10005)"Cyberpunk 2077 Phantom Liberty Steam Key РФ" else "Cyberpunk 2077 Steam Key РФ"
                            JSONObject().put("retval",0).put("product",JSONObject().put("id",id).put("name",name)
                                .put("is_available",if(mode.get()!=3 && id>=10005)1 else 0)
                                .put("prices",JSONObject().put("default",JSONObject().put("RUB",price))))
                        }
                    }
                    return ByteArrayInputStream(json.toString().toByteArray())
                }
            }
        }}
        fun worker()=TestListenableWorkerBuilder<PriceWatchWorker>(context).build()
        androidx.work.WorkManager.getInstance(context).cancelAllWork().result.get()
        IgnoredStore.remove(context,"10005")
        WatchStore.write(context,JSONArray());AlertJournal.clear(context)
        WatchStore.add(context,"Cyberpunk 2077","Key",1000.0)
        CatalogPageSettings.set(context,100)
        val firstResult=worker().doWork()
        assertEquals(WatchStore.read(context).toString(),ListenableWorker.Result.success(),firstResult)
        assertEquals(8,calls.get())
        var watch=WatchStore.read(context).getJSONObject(0)
        assertEquals("10005",watch.getString("bestId"));assertEquals(105.0,watch.getDouble("last"),0.001)
        assertEquals(1091500,watch.getInt("steamAppId"));assertEquals(3,watch.getInt("verifiedCount"))
        assertEquals(3,AlertJournal.read(context).length())
        val manager=context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertTrue(manager.activeNotifications.isNotEmpty())
        assertEquals(ListenableWorker.Result.success(),worker().doWork());assertEquals(3,AlertJournal.read(context).length())
        val oldHistory=WatchStore.read(context).getJSONObject(0).getJSONArray("history").length()
        for(failure in listOf(1,2)) {
            mode.set(failure)
            assertEquals(ListenableWorker.Result.retry(),worker().doWork())
            watch=WatchStore.read(context).getJSONObject(0)
            assertEquals(oldHistory,watch.getJSONArray("history").length())
            assertEquals(105.0,watch.getDouble("last"),0.001);assertTrue(watch.has("error"))
            assertEquals(3,AlertJournal.read(context).length())
        }
        mode.set(4)
        assertEquals(ListenableWorker.Result.success(),worker().doWork());assertEquals(4,AlertJournal.read(context).length())
        assertEquals(95.0,AlertJournal.read(context).getJSONObject(0).getDouble("price"),0.001)
        assertEquals(ListenableWorker.Result.success(),worker().doWork());assertEquals(4,AlertJournal.read(context).length())
        mode.set(0)
        worker().doWork();assertEquals(4,AlertJournal.read(context).length())
        mode.set(6)
        worker().doWork();assertEquals("10006",WatchStore.read(context).getJSONObject(0).getString("bestId"))
        assertEquals(4,AlertJournal.read(context).length())
        mode.set(9);changed.set(false)
        assertEquals(ListenableWorker.Result.success(),worker().doWork())
        assertEquals("10006",WatchStore.read(context).getJSONObject(0).getString("bestId"))
        IgnoredStore.remove(context,"10005")
        mode.set(3)
        assertEquals(ListenableWorker.Result.success(),worker().doWork())
        watch=WatchStore.read(context).getJSONObject(0)
        assertEquals(-1.0,watch.getDouble("last"),0.0)
        val history=watch.getJSONArray("history")
        assertFalse(history.getJSONObject(history.length()-1).has("price"));assertFalse(watch.has("error"))
        mode.set(8);changed.set(false)
        assertEquals(ListenableWorker.Result.success(),worker().doWork())
        watch=WatchStore.read(context).getJSONObject(0)
        assertEquals(1.0,watch.getDouble("limit"),0.0);assertEquals(-1.0,watch.getDouble("last"),0.0)
        mode.set(7);changed.set(false)
        assertEquals(ListenableWorker.Result.success(),worker().doWork())
        assertEquals(0,WatchStore.read(context).length());assertEquals(4,AlertJournal.read(context).length())

        // A complete monitor snapshot now merges verified Plati and active GGSEL catalog prices.
        mode.set(0);changed.set(false)
        ggselEnabled.set(true)
        AlertJournal.clear(context)
        WatchStore.add(context,"Cyberpunk 2077","Key",100.0,appId=1091500)
        assertEquals(ListenableWorker.Result.success(),worker().doWork())
        watch=WatchStore.read(context).getJSONObject(0)
        assertEquals("ggsel:77777",watch.getString("bestId"))
        assertEquals(50.0,watch.getDouble("last"),0.001)
        assertEquals(3,watch.getJSONObject("sourceCounts").getInt("Plati"))
        assertEquals(1,watch.getJSONObject("sourceCounts").getInt("GGSEL"))
        val top=watch.getJSONArray("topOffers").getJSONObject(0)
        assertEquals("GGSEL",top.getString("marketplace"))
        assertEquals("https://ggsel.net/en/catalog/product/cyberpunk-steam-key-ru",top.getString("url"))
        val ggselAlert=AlertJournal.read(context).getJSONObject(0)
        assertEquals("GGSEL",ggselAlert.getString("marketplace"))
        assertTrue(ggselAlert.getString("url").contains("ggsel.net"))

        // An unavailable provider cannot turn its previous confirmed price into zero or a history gap.
        val savedHistory=watch.getJSONArray("history").length()
        ggselOffline.set(true)
        assertEquals(ListenableWorker.Result.retry(),worker().doWork())
        watch=WatchStore.read(context).getJSONObject(0)
        assertEquals(50.0,watch.getDouble("last"),0.001)
        assertEquals(savedHistory,watch.getJSONArray("history").length())
        assertTrue(watch.getString("error").contains("GGSEL HTTP 503"))
        ggselOffline.set(false)
        manager.cancelAll()
    }
}
