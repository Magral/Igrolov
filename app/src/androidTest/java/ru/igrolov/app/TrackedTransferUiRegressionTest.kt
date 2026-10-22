package ru.igrolov.app

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/** Each method is invoked in its own instrumentation process by CI. */
@RunWith(AndroidJUnit4::class)
class TrackedTransferUiRegressionTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private val title="Stellaris +ВЫБОР STEAM Gift РФ"
    private fun installApi(price:AtomicInteger,offline:AtomicBoolean=AtomicBoolean(false)) {
        URL.setURLStreamHandlerFactory {protocol->if(protocol!="https")null else object:URLStreamHandler() {
            override fun openConnection(url:URL):URLConnection=object:HttpURLConnection(url) {
                override fun connect(){}
                override fun disconnect(){}
                override fun usingProxy()=false
                override fun getResponseCode()=if(offline.get())503 else 200
                override fun getInputStream():ByteArrayInputStream {
                    if(getResponseCode()!=200)throw IOException("HTTP ${getResponseCode()}")
                    val json=when {
                        url.path.contains("storesearch") -> JSONObject("""{"items":[{"id":281990,"name":"Stellaris"}]}""")
                        url.path.contains("appdetails") -> JSONObject("""{"281990":{"success":true,"data":{"type":"game","name":"Stellaris","dlc":[]}}}""")
                        url.path.endsWith("search2") -> JSONObject().put("result",JSONObject().put("total_pages",1))
                            .put("items",JSONObject().put("item",JSONArray().put(JSONObject().put("id",123456).put("name",title).put("price_rur",1))))
                        url.path.contains("price/calc") -> {
                            check(url.query.contains("11%3A21") && url.query.contains("12%3A32")) {"Wrong edition/region: ${url.query}"}
                            JSONObject().put("retval",0).put("data",JSONObject().put("amount",price.get()).put("commission",0).put("count",1).put("currency","RUB"))
                        }
                        else -> {
                            val id=url.path.substringAfter("/products/").substringBefore('/').toInt()
                            val product=JSONObject("""{"is_available":1,"prices":{"default":{"RUB":1}},"options":[
                                {"id":11,"label":"Выберите издание","type":"radio","variants":[{"value":21,"text":"Stellaris","visible":1},{"value":22,"text":"Stellaris Utopia","visible":1}]},
                                {"id":12,"label":"Страна","type":"radio","variants":[{"value":31,"text":"Украина","visible":1},{"value":32,"text":"Россия","visible":1}]}]}""").put("id",id).put("name",title)
                            JSONObject().put("retval",0).put("product",product)
                        }
                    }
                    return ByteArrayInputStream(json.toString().toByteArray())
                }
            }
        }}
    }
    private fun open():UiDevice {
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("Игролов 1.3.2")),15000))
        return device
    }
    private fun key(id:String)=TrackedPriceCache.storageKey("Plati",id)
    private fun cached(id:String)=TrackedPriceCache.decode(key(id),context.getSharedPreferences("tracked_products",0).getString("product_cache_${key(id)}",null))
    private fun cachedFromRoom(id:String)=TrackedPriceCache.decode(key(id),MonitorRoomStore.readTrackedProduct(context,key(id)))

    @Test fun addFromSearchRefreshPriceAndKeepItOnNetworkFailure() {
        val price=AtomicInteger(4914);val offline=AtomicBoolean(false)
        installApi(price,offline)
        context.getSharedPreferences("tracked_products",0).edit().putString("ids","").remove("product_cache_123456").remove("product_cache_${key("123456")}").commit()
        MonitorRoomStore.writeTrackedProduct(context,key("123456"),null)
        ManualSearchSettings.save(context,ManualSearchFilters())
        val device=open()
        device.findObject(By.clazz("android.widget.EditText")).text="Stellaris"
        assertTrue(device.wait(Until.hasObject(By.text("Stellaris · Steam #281990")),15000))
        device.findObject(By.text("Stellaris · Steam #281990")).click()
        val scroll=UiScrollable(UiSelector().className("android.widget.ScrollView").scrollable(true)).setMaxSearchSwipes(30)
        assertTrue(scroll.scrollTextIntoView("Искать"));device.findObject(By.text("Искать")).click()
        assertTrue(device.wait(Until.hasObject(By.textContains("Проверено:")),15000))
        // Compose may expose a nested scrollable (for example, the search history
        // field) before the page container. Swipe the page viewport directly so
        // this regression remains reliable as search filters grow.
        var attempts=0
        while(!device.hasObject(By.text("Отслеживать")) && attempts++<10) {
            device.swipe(device.displayWidth/2,device.displayHeight*3/4,device.displayWidth/2,device.displayHeight/3,12)
        }
        assertTrue(device.hasObject(By.text("Отслеживать")))
        assertTrue(device.hasObject(By.textContains("4914,00 ₽")))
        device.findObject(By.text("Отслеживать")).click()
        device.findObject(By.text("Товары")).click()
        assertTrue(device.wait(Until.hasObject(By.text("4914,00 ₽")),10000))
        assertEquals("Stellaris",cached("123456").canonical);assertEquals(281990,cached("123456").steamAppId)
        val roomDeadline=android.os.SystemClock.uptimeMillis()+10000
        while(cachedFromRoom("123456").price!=4914.0 && android.os.SystemClock.uptimeMillis()<roomDeadline)Thread.sleep(100)
        assertEquals(4914.0,cachedFromRoom("123456").price!!,0.001)
        price.set(5200)
        if(!device.hasObject(By.text("Обновить цены")))assertTrue(scroll.scrollTextIntoView("Обновить цены"))
        device.findObject(By.text("Обновить цены")).click()
        assertTrue(device.wait(Until.hasObject(By.text("5200,00 ₽")),10000))
        offline.set(true)
        device.findObject(By.text("Обновить цены")).click()
        assertTrue(device.wait(Until.hasObject(By.textContains("Нет связи")),10000))
        assertTrue(device.hasObject(By.text("5200,00 ₽")))
        device.takeScreenshot(File(context.getExternalFilesDir(null),"tracked-transfer.png"))
        MonitorRoomStore.writeTrackedProduct(context,"123456",null)
    }
    @Test fun restartKeepsContextAndRepairsLegacyTrackedItemWithoutReadding() {
        installApi(AtomicInteger(5300))
        assertEquals("Stellaris",cached("123456").canonical)
        assertEquals(5200.0,cached("123456").price!!,0.001)
        context.getSharedPreferences("tracked_products",0).edit().putString("ids","123456,123457")
            .putString("product_cache_123457",JSONObject().put("proofSchema",106).put("name",title).put("price",JSONObject.NULL).toString()).commit()
        val device=open();device.findObject(By.text("Товары")).click()
        assertTrue(device.wait(Until.hasObject(By.text("5300,00 ₽")),10000))
        val deadline=android.os.SystemClock.uptimeMillis()+10000
        while((cached("123457").price!=5300.0 || cached("123456").price!=5300.0) && android.os.SystemClock.uptimeMillis()<deadline)Thread.sleep(100)
        assertEquals(5300.0,cached("123456").price!!,0.001)
        assertEquals(5300.0,cached("123457").price!!,0.001)
        assertEquals("Stellaris",cached("123457").canonical)
        device.takeScreenshot(File(context.getExternalFilesDir(null),"tracked-restart.png"))
        context.getSharedPreferences("tracked_products",0).edit().putString("ids","")
            .remove("product_cache_123456").remove("product_cache_123457")
            .remove("product_cache_${key("123456")}").remove("product_cache_${key("123457")}").commit()
        MonitorRoomStore.writeTrackedProduct(context,"123456",null);MonitorRoomStore.writeTrackedProduct(context,"123457",null)
        MonitorRoomStore.writeTrackedProduct(context,key("123456"),null);MonitorRoomStore.writeTrackedProduct(context,key("123457"),null)
    }
}
