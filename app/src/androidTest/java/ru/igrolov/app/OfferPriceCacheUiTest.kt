package ru.igrolov.app

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasRole
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.Role
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
        assertTrue(UiTestScroll.await(compose, 15000) { device.hasObject(By.text("Игролов")) })
        compose.onNodeWithText("Желания").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        assertTrue(UiTestScroll.await(compose, 5000) { device.hasObject(By.textContains("Price cache test")) })
    }
    @Test fun multiSelectionIsRemovedAndCachedPricesRemainVisible() {
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
                val minima = mapOf("Plati" to 500.0+index*100,"GGSEL" to 800.0+index*100)
                OfferPriceCacheStore.update(context,key,minima,emptySet(),checked-2000,checked,"")
                OfferPriceCacheStore.update(context,key,minima,setOf("Plati", "GGSEL"),checked+500,checked+1000,"HTTP 503")
                keys.put(key)
            }
        }
        context.getSharedPreferences("offer_cache_ui_proof",0).edit().putString("keys",keys.toString()).putLong("checked",checked).commit()
        val profile=WishlistProfile("76561198000000000","Price cache test","76561198000000000",games,System.currentTimeMillis())
        context.getSharedPreferences("tracked_products",0).edit().putString("wishlist_profiles",SteamWishlist.encodeProfiles(listOf(profile)))
            .putString("wishlist_selected_profile",profile.steamId64).commit()
        launchWishlist()
        reveal("Stellaris")
        compose.onNodeWithText("Stellaris").assertIsDisplayed()
        compose.onAllNodes(hasRole(Role.Checkbox)).assertCountEquals(0)
        assertFalse(device.hasObject(By.text("Выбрать первые 5")))
        assertFalse(device.hasObject(By.textContains("Проверить выбранные")))
        assertTrue(UiTestScroll.await(compose, 5000) { device.hasObject(By.text("500 ₽")) })
        assertFalse(device.hasObject(By.text("Поиск игры по названию")))
        device.takeScreenshot(java.io.File(context.getExternalFilesDir(null),"offer-cache-no-multiselect.png"))
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
        if(!device.hasObject(By.text("500 ₽"))) {
            UiTestScroll.reveal(compose,"500 ₽")
        }
        assertTrue(UiTestScroll.await(compose, 5000) { device.hasObject(By.text("500 ₽")) })
        compose.onAllNodesWithText("ошибка обновления", substring = true).onFirst().performScrollTo()
        compose.waitForIdle()
        assertTrue(device.hasObject(By.textContains("ошибка обновления")))
        device.takeScreenshot(java.io.File(context.getExternalFilesDir(null),"offer-cache-restored.png"))
        context.getSharedPreferences("tracked_products",0).edit().remove("wishlist_profiles").remove("wishlist_selected_profile")
            .remove("wishlist_profile").remove("wishlist_games").remove("wishlist_imported_at").commit()
        context.getSharedPreferences("confirmed_offer_price_cache_v1",0).edit().clear().commit()
        WorkManager.getInstance(context).cancelAllWork().result.get()
    }
}
