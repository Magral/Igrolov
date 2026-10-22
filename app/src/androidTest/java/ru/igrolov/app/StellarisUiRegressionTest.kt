package ru.igrolov.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import org.junit.Rule
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
class StellarisUiRegressionTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun searchScreenShowsOnlyAvailableBaseGameAtFreshPrice() {
        URL.setURLStreamHandlerFactory { protocol -> if(protocol!="https") null else object:URLStreamHandler() {
            override fun openConnection(url:URL):URLConnection=object:HttpURLConnection(url) {
                override fun connect(){}
                override fun disconnect(){}
                override fun usingProxy()=false
                override fun getResponseCode()=200
                override fun getInputStream():ByteArrayInputStream {
                    val json=when {
                        url.path.contains("storesearch") -> JSONObject("""{"items":[{"id":281990,"name":"Stellaris"}]}""")
                        url.path.contains("appdetails") -> JSONObject("""{"281990":{"success":true,"data":{"name":"Stellaris","type":"game","dlc":[518910]}}}""")
                        url.path.endsWith("search2") -> {
                            val items=JSONArray()
                            for(id in 10000..10003)items.put(JSONObject().put("id",id).put("name",if(id==10001)"Stellaris Utopia Steam Key РФ" else "Stellaris Steam Key РФ").put("price_rur",id-9999).put("seller_id",101))
                            JSONObject().put("result",JSONObject().put("total_pages",1)).put("items",JSONObject().put("item",items))
                        }
                        else -> {
                            val id=url.path.split('/')[3].toInt()
                            val p=JSONObject().put("id",id).put("is_available",if(id==10002)0 else 1)
                                .put("name",if(id==10001)"Stellaris Utopia Steam Key РФ" else "Stellaris Steam Key РФ")
                                .put("prices",JSONObject().put("default",JSONObject().put("RUB",1000)))
                            if(id==10003)p.put("options",JSONArray("""[{"id":11,"label":"Страна","type":"radio","variants":[{"value":21,"text":"Украина","visible":1}]}]"""))
                            JSONObject().put("retval",0).put("product",p)
                        }
                    }
                    return ByteArrayInputStream(json.toString().toByteArray())
                }
            }
        }}
        val context=ApplicationProvider.getApplicationContext<Context>()
        ManualSearchSettings.save(context,ManualSearchFilters())
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(UiTestScroll.await(compose, 15000) { device.hasObject(By.text("Игролов 1.2.7")) })
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("Stelaris")
        compose.waitForIdle()
        assertTrue(UiTestScroll.await(compose, 15000) { device.hasObject(By.text("Stellaris · Steam #281990")) })
        compose.onNodeWithText("Stellaris · Steam #281990").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        UiTestScroll.reveal(compose,"Искать")
        compose.onNodeWithText("Искать").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        assertTrue(UiTestScroll.await(compose, 15000) { device.hasObject(By.textContains("Проверено:")) })
        UiTestScroll.reveal(compose,"Предложения по цене ↑ · 1")
        assertTrue(UiTestScroll.await(compose, 5000) { device.hasObject(By.text("Stellaris Steam Key РФ")) })
        assertEquals(1,device.findObjects(By.text("Stellaris Steam Key РФ")).size)
        assertFalse(device.hasObject(By.text("Stellaris Utopia Steam Key РФ")))
        val priceVisible = { device.hasObject(By.textContains("1 000,00 ₽")) || device.hasObject(By.textContains("1000,00 ₽")) || device.hasObject(By.textContains("1 000,00 ₽")) }
        compose.onAllNodes(hasText("1 000,00 ₽", substring=true) or
            hasText("1000,00 ₽", substring=true) or hasText("1 000,00 ₽", substring=true))
            .onFirst().performScrollTo()
        compose.waitForIdle()
        assertTrue(priceVisible())
        device.takeScreenshot(File(context.getExternalFilesDir(null),"stellaris-result.png"))
    }
}
