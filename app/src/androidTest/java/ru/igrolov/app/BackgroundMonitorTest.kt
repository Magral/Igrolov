package ru.igrolov.app

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Runs with no MainActivity: the actual periodic worker must persist a price on its own. */
@RunWith(AndroidJUnit4::class)
class BackgroundMonitorTest {
    @Test fun retryTokenIsStableButNextPeriodicCycleGetsNewToken() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val id=UUID.randomUUID().toString()
        val first=MonitorRunToken.get(context,id,0)
        assertEquals(first,MonitorRunToken.get(context,id,1))
        assertEquals(first,MonitorRunToken.get(context,id,2))
        val next=MonitorRunToken.get(context,id,0)
        assertNotEquals(first,next)
        assertEquals(next,MonitorRunToken.get(context,id,1))
    }
    private fun await(message:String,condition:()->Boolean) {
        val until=System.currentTimeMillis()+30000
        while(!condition() && System.currentTimeMillis()<until)Thread.sleep(100)
        assertTrue(message,condition())
    }

    @Test fun repeatedRegistrationKeepsExistingBackgroundRequest() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val manager=WorkManager.getInstance(context)
        manager.cancelAllWork().result.get()
        WatchStore.write(context,JSONArray())
        MonitorBackgroundStore.preferences(context).edit().clear().commit()
        WatchScheduler.start(context)
        await("Periodic work was not registered") {manager.getWorkInfosForUniqueWork("plati_game_price_watch").get().any {!it.state.isFinished}}
        val first=manager.getWorkInfosForUniqueWork("plati_game_price_watch").get().single {!it.state.isFinished}.id
        WatchScheduler.start(context)
        Thread.sleep(200)
        val active=manager.getWorkInfosForUniqueWork("plati_game_price_watch").get().filterNot {it.state.isFinished}
        assertEquals(listOf(first),active.map {it.id})
        assertEquals("Registering a schedule is not a background check",BackgroundMonitorReport(),MonitorBackgroundStore.read(context))
        manager.cancelAllWork().result.get()
    }

    @Test fun periodicWorkerUpdatesWithoutActivityAndPercentageAlertDoesNotRepeat()=runBlocking {
        val amount=AtomicInteger(1000)
        val requests=AtomicInteger()
        val offline=java.util.concurrent.atomic.AtomicBoolean(false)
        URL.setURLStreamHandlerFactory {protocol->if(protocol!="https")null else object:URLStreamHandler() {
            override fun openConnection(url:URL):URLConnection=object:HttpURLConnection(url) {
                override fun connect(){}
                override fun disconnect(){}
                override fun usingProxy()=false
                override fun getResponseCode()=if(offline.get() && url.host.contains("ggsel"))503 else 200
                override fun getOutputStream()=ByteArrayOutputStream()
                override fun getInputStream():ByteArrayInputStream {
                    val json=when {
                        url.path.contains("appdetails") -> """{"281990":{"success":true,"data":{"name":"Stellaris","type":"game","dlc":[]}}}"""
                        url.path.endsWith("search2") -> {requests.incrementAndGet();"""{"items":{"item":[{"id":12345,"name":"Stellaris Steam Key РФ","price_rur":1}]}}"""}
                        url.path.contains("/api/products/") -> """{"retval":0,"product":{"id":12345,"name":"Stellaris Steam Key РФ","is_available":1,"prices":{"default":{"RUB":${amount.get()}}}}}"""
                        else -> """{"data":{"categories":[]},"items":[]}"""
                    }
                    return ByteArrayInputStream(json.toByteArray())
                }
            }
        }}
        val context=ApplicationProvider.getApplicationContext<Context>()
        val manager=WorkManager.getInstance(context)
        val notifications=context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancelAllWork().result.get()
        notifications.cancelAll()
        MonitorBackgroundStore.preferences(context).edit().clear().commit()
        WatchStore.write(context,JSONArray())
        WatchStore.add(context,"Stellaris","Key",100.0,appId=281990)
        val watchId=WatchStore.identity(WatchStore.read(context).getJSONObject(0))
        WatchStore.updateTarget(context,watchId,100.0,10)
        try {
            WatchScheduler.start(context,delayed=false)
            await("Background periodic worker did not update the price without an activity") {
                WatchStore.read(context).optJSONObject(0)?.optDouble("last")==1000.0
            }
            await("Background worker did not finish") {
                manager.getWorkInfosForUniqueWork("plati_game_price_watch").get().none {it.state==WorkInfo.State.RUNNING}
            }
            assertTrue(requests.get()>0)
            val background=MonitorBackgroundStore.read(context)
            assertEquals("success",background.status)
            assertTrue(background.lastSuccessAt>0)
            assertTrue(notifications.activeNotifications.isEmpty())
            amount.set(900)
            val requestId=UUID.randomUUID()
            assertEquals(ListenableWorker.Result.success(),TestListenableWorkerBuilder<PriceWatchWorker>(context).setId(requestId).build().doWork())
            val sent=WatchStore.read(context).getJSONObject(0)
            assertEquals(900.0,sent.getDouble("notificationPrice"),0.0)
            assertEquals(1,notifications.activeNotifications.size)
            assertTrue(notifications.activeNotifications.single().notification.extras.getCharSequence("android.text").toString().contains("10%"))
            val postedAt=notifications.activeNotifications.single().postTime
            val beforeRetry=requests.get()
            assertEquals(ListenableWorker.Result.success(),TestListenableWorkerBuilder<PriceWatchWorker>(context).setId(requestId).setRunAttemptCount(1).build().doWork())
            assertEquals("Retry must not recheck an already completed watch",beforeRetry,requests.get())
            assertEquals(ListenableWorker.Result.success(),TestListenableWorkerBuilder<PriceWatchWorker>(context).build().doWork())
            assertEquals(postedAt,notifications.activeNotifications.single().postTime)
            assertEquals("Manual checks must not change the actual background report",background,MonitorBackgroundStore.read(context))
            offline.set(true)
            manager.cancelUniqueWork("plati_game_price_watch").result.get()
            WatchScheduler.start(context,delayed=false)
            await("A failed background source must be recorded as partial") {MonitorBackgroundStore.read(context).status=="partial"}
            assertEquals(background.lastSuccessAt,MonitorBackgroundStore.read(context).lastSuccessAt)
        } finally {WatchStore.write(context,JSONArray());manager.cancelAllWork().result.get();notifications.cancelAll()}
    }
}

