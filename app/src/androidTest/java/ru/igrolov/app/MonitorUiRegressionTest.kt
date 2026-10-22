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
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler

@RunWith(AndroidJUnit4::class)
class MonitorUiRegressionTest {
    @Test fun historyGapsTrackedVariantPriceAndExactWatchSelection() {
        URL.setURLStreamHandlerFactory {protocol->if(protocol!="https")null else object:URLStreamHandler() {
            override fun openConnection(url:URL):URLConnection=object:HttpURLConnection(url) {
                override fun connect(){}
                override fun disconnect(){}
                override fun usingProxy()=false
                override fun getResponseCode()=200
                override fun getInputStream():ByteArrayInputStream {
                    val json=when {
                        url.path.contains("storesearch") -> JSONObject("""{"items":[{"id":281990,"name":"Stellaris"}]}""")
                        url.path.contains("appdetails") -> JSONObject("""{"281990":{"success":true,"data":{"type":"game","name":"Stellaris","dlc":[]}}}""")
                        url.path.endsWith("search2") -> JSONObject("""{"items":{"item":[]}}""")
                        url.path.contains("price/calc") -> JSONObject("""{"retval":0,"data":{"amount":450,"commission":0,"count":1,"currency":"RUB"}}""")
                        else -> JSONObject("""{"retval":0,"product":{"id":10000,"name":"Stellaris Steam Gift","is_available":1,"prices":{"default":{"RUB":1}},"options":[{"id":11,"label":"Регион","type":"radio","variants":[{"value":22,"text":"Россия","visible":1}]}]}}""")
                    }
                    return ByteArrayInputStream(json.toString().toByteArray())
                }
            }
        }}
        val context=ApplicationProvider.getApplicationContext<Context>()
        val now=System.currentTimeMillis()
        val history=JSONArray().put(JSONObject().put("at",now-10800000).put("price",1000))
            .put(JSONObject().put("at",now-7200000)).put(JSONObject().put("at",now-3600000).put("price",800))
        val ggselOffer=JSONObject().put("id","ggsel:90001").put("name","Stellaris Steam Key РФ")
            .put("price",450).put("type","Key").put("marketplace","GGSEL")
            .put("url","https://ggsel.net/en/catalog/product/stellaris-key")
        WatchStore.write(context,JSONArray().put(JSONObject().put("name","Stellaris").put("kind","Gift").put("steamAppId",281990)
            .put("limit",1000).put("rf",true).put("last",800).put("checked",now-3600000).put("history",history)
            .put("sourceCounts",JSONObject().put("Plati",0).put("GGSEL",1)).put("topOffers",JSONArray().put(ggselOffer))))
        context.getSharedPreferences("tracked_products",0).edit().putString("ids","10000").remove("product_cache_10000").commit()
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("Игролов 1.2.7")),15000))
        device.findObject(By.text("Мониторинг")).click()
        val scroll=UiScrollable(UiSelector().className("android.widget.ScrollView").scrollable(true)).setMaxSearchSwipes(30)
        assertTrue(scroll.scrollIntoView(UiSelector().text("Источники · Plati: 0 · GGSEL: 1")))
        assertTrue(scroll.scrollIntoView(UiSelector().text("Три минимальные цены (1)")))
        assertTrue(scroll.scrollIntoView(UiSelector().textStartsWith("Следующая проверка")))
        device.findObject(By.text("Три минимальные цены (1)")).click()
        assertTrue(scroll.scrollIntoView(UiSelector().text("Открыть на GGSEL")))
        assertTrue(device.hasObject(By.text("Отслеживать товар")))
        assertTrue(scroll.scrollIntoView(UiSelector().text("Скрыть предложения")))
        device.findObject(By.text("Скрыть предложения")).click()
        assertTrue(scroll.scrollIntoView(UiSelector().textStartsWith("История цен (")))
        if(!WatchStore.read(context).getJSONObject(0).has("eligibleCount")) {
            assertFalse("Old records without counters must not be labelled as an empty marketplace",device.hasObject(By.text("Подходящих предложений нет — это не ошибка сети.")))
        }
        device.findObject(By.textStartsWith("История цен (")).click()
        assertTrue(scroll.scrollIntoView(UiSelector().textStartsWith("Диапазон:")))
        assertTrue(device.findObject(By.textStartsWith("Диапазон:")).text.contains("800"))
        // Missing-price history gaps are covered by WatchHistory unit tests; UI focuses on the visible chart range.
        device.takeScreenshot(File(context.getExternalFilesDir(null),"monitor-history.png"))
        device.findObject(By.text("Товары")).click()
        assertTrue(device.wait(Until.hasObject(By.text("450,00 ₽")),10000))
        assertFalse(device.hasObject(By.text("1,00 ₽")))
        device.findObject(By.text("Мониторинг")).click()
        scroll.scrollToBeginning(30)
        device.findObject(By.clazz("android.widget.EditText")).text="Стелларис"
        assertTrue(device.wait(Until.hasObject(By.text("Stellaris · Steam #281990")),15000))
        device.findObject(By.text("Stellaris · Steam #281990")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Выбрано: Stellaris · Steam #281990")),5000))
        device.takeScreenshot(File(context.getExternalFilesDir(null),"monitor-selection.png"))
        WatchStore.write(context,JSONArray())
        context.getSharedPreferences("tracked_products",0).edit().putString("ids","").remove("product_cache_10000").commit()
    }
}
