package ru.igrolov.app

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import org.junit.Rule
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
import org.junit.After
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler

@RunWith(AndroidJUnit4::class)
class OfferPriceCacheUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private val device get()=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val games=listOf(WishlistGame(281990,"Stellaris","900 руб."),WishlistGame(381210,"Dead by Daylight",""))
    @After fun capture() {
        device.takeScreenshot(java.io.File(context.getExternalFilesDir(null),"offer-cache-last.png"))
        device.dumpWindowHierarchy(java.io.File(context.getExternalFilesDir(null),"offer-cache-last.xml"))
    }
    private fun reveal(text:String) {
        UiTestScroll.reveal(compose,text)
    }
    private fun launchWishlist() {
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(UiTestScroll.await(compose, 15000) { device.hasObject(By.text("Игролов 1.4.0")) })
        compose.onNodeWithText("Желания").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        assertTrue(UiTestScroll.await(compose, 5000) { device.hasObject(By.textContains("Price cache test")) })
    }
    @Test fun batchRetainsSuccessfulTimesOnSourceFailures() {
        URL.setURLStreamHandlerFactory {protocol->if(protocol!="https")null else object:URLStreamHandler() {
            override fun openConnection(url:URL):URLConnection=object:HttpURLConnection(url) {
                override fun connect(){}
                override fun disconnect(){}
                override fun usingProxy()=false
                override fun getResponseCode()=if(url.host.endsWith("steampowered.com"))200 else 503
                override fun getInputStream():ByteArrayInputStream {
                    val body=when {
                        url.path.contains("appdetails") -> """{"281990":{"success":true,"data":{"type":"game","name":"Stellaris","dlc":[]}},"381210":{"success":true,"data":{"type":"game","name":"Dead by Daylight","dlc":[]}}}"""
                        url.path.contains("storesearch") -> """{"items":[{"id":281990,"name":"Stellaris"},{"id":381210,"name":"Dead by Daylight"}]}"""
                        else -> "{}"
                    }
                    return ByteArrayInputStream(body.toByteArray())
                }
                override fun getErrorStream()=ByteArrayInputStream("{}".toByteArray())
            }
        }}
        WorkManager.getInstance(context).cancelAllWork().result.get()
        WatchStore.write(context,JSONArray())
        ManualSearchSettings.save(context,ManualSearchFilters())
        context.getSharedPreferences("manual_search_105",0).edit().putString("games","[]").commit()
        context.getSharedPreferences("confirmed_offer_price_cache_v1",0).edit().clear().commit()
        val checked=System.currentTimeMillis()-7200000L
        val keys=JSONArray()
        games.forEachIndexed {index,game->
            GameRuleStore.save(context,game.title,JSONObject())
            for(filters in listOf(ManualSearchFilters(),OfferPriceCache.leaderFilters(ManualSearchFilters()))) {
                val key=OfferPriceCache.key(game.appId,filters,JSONObject(),IgnoredStore.ids(context),CatalogPageSettings.pages(context),emptyList())
                OfferPriceCacheStore.update(context,key,mapOf("Plati" to 500.0+index*100,"GGSEL" to 800.0+index*100),
                    emptySet(),checked-1000,checked,"")
                keys.put(key)
            }
        }
        context.getSharedPreferences("offer_cache_ui_proof",0).edit().putString("keys",keys.toString()).putLong("checked",checked).commit()
        val profile=WishlistProfile("76561198000000000","Price cache test","76561198000000000",games,System.currentTimeMillis())
        context.getSharedPreferences("tracked_products",0).edit().putString("wishlist_profiles",SteamWishlist.encodeProfiles(listOf(profile)))
            .putString("wishlist_selected_profile",profile.steamId64).commit()
        launchWishlist()
        reveal("Выбрать первые 5")
        compose.onNodeWithText("Выбрать первые 5").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        assertTrue(UiTestScroll.await(compose, 5000) { device.hasObject(By.text("Проверить выбранные (2)")) })
        compose.onNodeWithText("Проверить выбранные (2)").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        assertTrue(UiTestScroll.await(compose, 90000) { device.hasObject(By.text("Готово 2 / 2")) })
        val cached=OfferPriceCacheStore.readAll(context)
        for(i in 0 until keys.length()) {
            val entry=cached[keys.getString(i)]!!
            assertEquals(checked,entry.plati.checkedAt)
            assertEquals(checked,entry.ggsel.checkedAt)
            assertTrue(entry.plati.failedAt>checked)
            assertTrue(entry.ggsel.failedAt>checked)
            assertNotNull(entry.plati.price)
            assertNotNull(entry.ggsel.price)
        }
        assertTrue(context.getSharedPreferences("confirmed_offer_price_cache_v1",0).edit().putBoolean("ui_test_flush",true).commit())
        assertFalse(device.hasObject(By.text("Поиск игры по названию")))
        device.takeScreenshot(java.io.File(context.getExternalFilesDir(null),"offer-cache-batch.png"))
    }
    @Test fun restartRestoresCachedPricesAndOriginalSuccessfulTime() {
        val proof=context.getSharedPreferences("offer_cache_ui_proof",0)
        val keys=JSONArray(proof.getString("keys","[]"))
        assertEquals(4,keys.length())
        val checked=proof.getLong("checked",0)
        val cached=OfferPriceCacheStore.readAll(context)
        for(i in 0 until keys.length()) {
            val entry=cached[keys.getString(i)]!!
            assertEquals(checked,entry.plati.checkedAt)
            assertEquals(checked,entry.ggsel.checkedAt)
            assertTrue(entry.ggsel.failedAt>checked)
        }
        launchWishlist()
        reveal("Stellaris")
        if(!device.hasObject(By.textContains("Plati от 500 ₽"))) {
            UiTestScroll.reveal(compose,"Plati от 500 ₽",contains=true)
        }
        assertTrue(UiTestScroll.await(compose, 5000) { device.hasObject(By.textContains("Plati от 500 ₽")) })
        assertTrue(device.hasObject(By.textContains("ошибка обновления")))
        device.takeScreenshot(java.io.File(context.getExternalFilesDir(null),"offer-cache-restored.png"))
        context.getSharedPreferences("tracked_products",0).edit().remove("wishlist_profiles").remove("wishlist_selected_profile")
            .remove("wishlist_profile").remove("wishlist_games").remove("wishlist_imported_at").commit()
        context.getSharedPreferences("confirmed_offer_price_cache_v1",0).edit().clear().commit()
        WorkManager.getInstance(context).cancelAllWork().result.get()
    }
}
