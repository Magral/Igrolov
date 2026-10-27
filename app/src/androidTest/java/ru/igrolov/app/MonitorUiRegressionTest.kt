package ru.igrolov.app

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler

@RunWith(AndroidJUnit4::class)
class MonitorUiRegressionTest {
    @get:Rule val compose = createEmptyComposeRule()
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
        assertTrue(device.wait(Until.hasObject(By.text("Игролов")),15000))
        compose.onNodeWithText("Мониторинг").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        val scroll=UiScrollable(UiSelector().className("android.widget.ScrollView").scrollable(true)).setMaxSearchSwipes(30)
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("Показано 1 из 1").fetchSemanticsNodes().isNotEmpty()
        }
        UiTestScroll.reveal(compose, "Показано 1 из 1")
        assertTrue(device.hasObject(By.text("Показано 1 из 1")))
        assertTrue(device.hasObject(By.text("Ниже цели")))
        assertTrue(device.hasObject(By.text("С ошибкой")))
        assertTrue(device.hasObject(By.text("Сортировка: внимание")))
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("Динамика: минимум снизился", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        UiTestScroll.reveal(compose, "Динамика: минимум снизился", contains = true)
        assertFalse("Internal check details must not be shown to shoppers",
            compose.onAllNodesWithText("Детали проверки").fetchSemanticsNodes().isNotEmpty())
        assertFalse(device.hasObject(By.text("Источники · Plati: 0 · GGSEL: 1")))
        assertFalse(device.hasObject(By.textStartsWith("Следующая проверка")))
        device.takeScreenshot(File(context.getExternalFilesDir(null),"monitor-compact.png"))
        UiTestScroll.reveal(compose, "Цены (1)")
        assertTrue(device.hasObject(By.text("Цены (1)")))
        compose.onNodeWithText("Цены (1)").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("Открыть на GGSEL").fetchSemanticsNodes().isNotEmpty()
        }
        UiTestScroll.reveal(compose, "Открыть на GGSEL")
        assertTrue(device.hasObject(By.text("Открыть на GGSEL")))
        assertTrue(device.hasObject(By.text("Отслеживать товар")))
        compose.onNodeWithText("Скрыть цены").performSemanticsAction(SemanticsActions.OnClick) { it() }
        UiTestScroll.reveal(compose, "История цен (", contains = true)
        assertTrue(device.hasObject(By.textStartsWith("История цен (")))
        if(!WatchStore.read(context).getJSONObject(0).has("eligibleCount")) {
            assertFalse("Old records without counters must not be labelled as an empty marketplace",device.hasObject(By.text("Подходящих предложений нет — это не ошибка сети.")))
        }
        compose.onAllNodesWithText("История цен (", substring = true).onFirst()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Скрыть историю").fetchSemanticsNodes().isNotEmpty() }
        var historyAttempts=0
        while(!device.hasObject(By.textStartsWith("Диапазон:")) && historyAttempts++<12) {
            device.swipe(device.displayWidth/2,device.displayHeight*3/4,device.displayWidth/2,device.displayHeight/3,12)
            Thread.sleep(250)
        }
        assertTrue(device.hasObject(By.textStartsWith("Диапазон:")))
        assertTrue(device.findObject(By.textStartsWith("Диапазон:")).text.contains("800"))
        // Missing-price history gaps are covered by WatchHistory unit tests; UI focuses on the visible chart range.
        device.takeScreenshot(File(context.getExternalFilesDir(null),"monitor-history.png"))
        compose.onNodeWithText("Товары").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(10000) {
            compose.onAllNodesWithText("450,00 ₽").fetchSemanticsNodes().isNotEmpty()
        }
        assertFalse(device.hasObject(By.text("1,00 ₽")))
        compose.onNodeWithText("Мониторинг").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onAllNodes(hasSetTextAction()).onFirst().performScrollTo()
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Стелларис")
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("Stellaris").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText("Stellaris").filter(hasClickAction()).onLast().performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("Выбрано: Stellaris").fetchSemanticsNodes().isNotEmpty()
        }
        device.takeScreenshot(File(context.getExternalFilesDir(null),"monitor-selection.png"))
        WatchStore.write(context,JSONArray())
        context.getSharedPreferences("tracked_products",0).edit().putString("ids","").remove("product_cache_10000").commit()
    }
}
