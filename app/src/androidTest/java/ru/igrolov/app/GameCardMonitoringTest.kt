package ru.igrolov.app

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import androidx.work.WorkManager
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

@RunWith(AndroidJUnit4::class)
class GameCardMonitoringTest {
    @Test fun leaderRatingAndWishlistMonitoringKeepExactAppIdAndPreventDuplicates() {
        URL.setURLStreamHandlerFactory { protocol -> if (protocol != "https") null else object : URLStreamHandler() {
            override fun openConnection(url: URL): URLConnection = object : HttpURLConnection(url) {
                override fun connect() {}
                override fun disconnect() {}
                override fun usingProxy() = false
                override fun getResponseCode() = 200
                override fun getInputStream(): ByteArrayInputStream {
                    val raw = when {
                        url.path.contains("GetAppReviews") ->
                            """{"response":{"query_summary":{"total_positive":92,"total_reviews":100}}}"""
                        url.path.contains("/search/results") -> JSONObject().put("total_count", 1).put("results_html",
                            """<a class="search_result_row" data-ds-appid="281990"><span class="title">Stellaris</span></a>""").toString()
                        url.path.contains("appdetails") ->
                            """{"281990":{"success":true,"data":{"type":"game","name":"Stellaris","short_description":"A strategy game.","price_overview":{"final_formatted":"900 руб.","discount_percent":50}}}}"""
                        url.path.endsWith("search2") -> """{"items":{"item":[]}}"""
                        else -> "{}"
                    }
                    return ByteArrayInputStream(raw.toByteArray())
                }
            }
        }}
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManager.getInstance(context).cancelAllWork().result.get()
        WatchStore.write(context, JSONArray())
        WatchStore.add(context, "Old title", "Gift", 300.0, true, 281990)
        WatchStore.add(context, "Stellaris", "Gift", 500.0, true, 281990)
        assertEquals(1, WatchStore.read(context).length())
        assertEquals(500.0, WatchStore.read(context).getJSONObject(0).getDouble("limit"), 0.0)
        WatchStore.write(context, JSONArray())
        context.getSharedPreferences("steam_review_cache", 0).edit().clear().commit()
        val id = "76561198000000000"
        val profile = WishlistProfile(id, "Card test", id, listOf(
            WishlistGame(281990, "Stellaris", "900 руб."),
            WishlistGame(381210, "Dead by Daylight", "")
        ), System.currentTimeMillis())
        val prefs = context.getSharedPreferences("tracked_products", 0)
        prefs.edit().putString("wishlist_profiles", SteamWishlist.encodeProfiles(listOf(profile)))
            .putString("wishlist_selected_profile", id).commit()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("Игролов 1.2.6")), 15000))
        device.findObject(By.text("Лидеры")).click()
        assertTrue(device.wait(Until.hasObject(By.text("92%")), 15000))
        assertTrue(device.hasObject(By.desc("Положительные отзывы")))
        val scroll = UiScrollable(UiSelector().scrollable(true)).setMaxSearchSwipes(20)
        assertTrue(scroll.scrollTextIntoView("В мониторинг"))
        device.findObject(By.text("В мониторинг")).click()
        device.findObject(By.text("Добавить")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Введите положительную цену")), 3000))
        device.findObjects(By.clazz("android.widget.EditText")).last().text = "500"
        device.findObject(By.text("Gift")).click()
        device.findObject(By.text("Добавить")).click()
        assertTrue(device.wait(Until.hasObject(By.text("В мониторинге")), 5000))
        assertFalse(device.findObject(By.text("В мониторинге")).isEnabled)
        WorkManager.getInstance(context).cancelAllWork().result.get()
        var saved = WatchStore.read(context)
        assertEquals(1, saved.length())
        assertEquals(281990, saved.getJSONObject(0).getInt("steamAppId"))
        assertEquals("Gift", saved.getJSONObject(0).getString("kind"))
        device.takeScreenshot(java.io.File(context.getExternalFilesDir(null), "leader-review-monitor.png"))

        device.findObject(By.text("Желания")).click()
        assertTrue(scroll.scrollTextIntoView("Dead by Daylight"))
        assertTrue(scroll.scrollTextIntoView("В мониторинг"))
        device.findObject(By.text("В мониторинг")).click()
        device.findObjects(By.clazz("android.widget.EditText")).last().text = "750,50"
        device.findObject(By.text("Key")).click()
        device.findObject(By.text("Добавить")).click()
        assertTrue(device.wait(Until.hasObject(By.text("В мониторинге")), 5000))
        WorkManager.getInstance(context).cancelAllWork().result.get()
        saved = WatchStore.read(context)
        assertEquals(2, saved.length())
        val second = (0 until saved.length()).map { saved.getJSONObject(it) }.first { it.getInt("steamAppId") == 381210 }
        assertEquals("Key", second.getString("kind"))
        assertEquals(750.5, second.getDouble("limit"), 0.0)
        assertTrue(second.getBoolean("rf"))
        device.takeScreenshot(java.io.File(context.getExternalFilesDir(null), "wishlist-monitor.png"))
        WatchStore.write(context, JSONArray())
        prefs.edit().remove("wishlist_profiles").remove("wishlist_selected_profile").commit()
    }
}
