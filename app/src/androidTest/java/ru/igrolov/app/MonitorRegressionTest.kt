package ru.igrolov.app

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.util.concurrent.atomic.AtomicInteger

/** Runs the existing worker unchanged, with deterministic HTTPS responses instead of live prices. */
@RunWith(AndroidJUnit4::class)
class MonitorRegressionTest {
    @Test fun sixthAvailableOfferNotificationsAndNetworkRetry() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val mode=AtomicInteger(0)
        val detailCalls=AtomicInteger(0)
        URL.setURLStreamHandlerFactory { protocol ->
            if (protocol != "https") null else object : URLStreamHandler() {
                override fun openConnection(url: URL): URLConnection = object : HttpURLConnection(url) {
                    override fun connect() {}
                    override fun disconnect() {}
                    override fun usingProxy()=false
                    override fun getResponseCode()=if(mode.get()==1) 503 else 200
                    override fun getInputStream(): ByteArrayInputStream {
                        val response = if (url.path.endsWith("search2")) {
                            val items=JSONArray()
                            for(i in 0..7) items.put(JSONObject().put("id",10000+i).put("name","Cyberpunk 2077 Steam Key РФ").put("price_rur",100+i))
                            JSONObject().put("items",JSONObject().put("item",items))
                        } else {
                            detailCalls.incrementAndGet()
                            val id=url.path.split("/")[3].toInt()
                            JSONObject().put("retval",0).put("product",JSONObject().put("is_available",if(id>=10005) 1 else 0)
                                .put("prices",JSONObject().put("default",JSONObject().put("RUB",id-10000+100))))
                        }
                        return ByteArrayInputStream(response.toString().toByteArray())
                    }
                }
            }
        }
        context.getSharedPreferences("steam_game_reference",0).edit().putString("cyberpunk 2077",
            JSONObject().put("at",System.currentTimeMillis()).put("schema",3).put("appId",1091500).put("name","Cyberpunk 2077").put("dlc",JSONArray()).toString()).commit()
        WatchStore.write(context,JSONArray())
        WatchStore.add(context,"Cyberpunk 2077","Key",1000.0)
        CatalogPageSettings.set(context,100)
        AlertJournal.clear(context)
        val result=TestListenableWorkerBuilder<PriceWatchWorker>(context).build().doWork()
        assertEquals(ListenableWorker.Result.success(),result)
        assertEquals(8,detailCalls.get())
        val watch=WatchStore.read(context).getJSONObject(0)
        assertEquals("10005",watch.getString("bestId"))
        assertEquals(105.0,watch.getDouble("last"),0.001)
        assertEquals(3,watch.getInt("verifiedCount"))
        assertEquals(3,AlertJournal.read(context).length())
        val manager=context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertTrue(manager.activeNotifications.isNotEmpty())
        TestListenableWorkerBuilder<PriceWatchWorker>(context).build().doWork()
        assertEquals(3,AlertJournal.read(context).length())
        mode.set(1)
        assertEquals(ListenableWorker.Result.retry(),TestListenableWorkerBuilder<PriceWatchWorker>(context).build().doWork())
        assertTrue(WatchStore.read(context).getJSONObject(0).has("error"))
        WatchStore.write(context,JSONArray())
        manager.cancelAll()
    }
}
