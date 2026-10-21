package ru.igrolov.app

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReleaseSmokeTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Test fun seedUpgradeData() {
        assertTrue(context.getSharedPreferences("game_watches",0).edit().putString("watches","""[{"name":"Cyberpunk 2077","kind":"Key","limit":1000,"rf":true,"notified":["10005"],"history":[{"at":1792468800000,"price":500,"count":1}]}]""").commit())
        assertTrue(context.getSharedPreferences("manual_search_105",0).edit().putString("filters","""{"type":"Gift","edition":"Игра","maxPrice":"2500","confirmedRf":false}""").commit())
        assertTrue(context.getSharedPreferences("release_upgrade_probe",0).edit().putString("sentinel","keep-104").commit())
        assertTrue(context.getSharedPreferences("search_history",0).edit().putString("queries","[\"Cyberpunk 2077\"]").commit())
        assertTrue(context.getSharedPreferences("game_rule_overrides",0).edit().putString("cyberpunk 2077","{\"canonical\":\"Cyberpunk 2077\"}").commit())
    }
    @Test fun verifyUpgradeData() {
        assertEquals("keep-104",context.getSharedPreferences("release_upgrade_probe",0).getString("sentinel",null))
        assertEquals("[\"Cyberpunk 2077\"]",context.getSharedPreferences("search_history",0).getString("queries",null))
        assertTrue(context.getSharedPreferences("game_rule_overrides",0).contains("cyberpunk 2077"))
        val watch=WatchStore.read(context).getJSONObject(0)
        assertEquals("10005",watch.getJSONArray("notified").getString(0))
        assertEquals(500.0,watch.getJSONArray("history").getJSONObject(0).getDouble("price"),0.001)
        assertEquals("2500",ManualSearchSettings.read(context).maxPrice)
        WatchStore.write(context,org.json.JSONArray())
        ManualSearchSettings.save(context,ManualSearchFilters())
        assertEquals(118,PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName,0)))
    }
    @Test fun launchAndNavigateFiltersSettingsAndMonitoring() {
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("Игролов 1.1.1")),15000))
        device.findObject(By.text("Gift")).click()
        device.findObject(By.text("Key")).click()
        device.findObject(By.text("Товары")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Ссылка Plati или ID товара")),5000))
        device.findObject(By.text("Мониторинг")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Мониторинг")),5000))
        device.findObject(By.text("⚙")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Настройки")),5000))
        device.findObject(By.text("×")).click()
        device.findObject(By.text("Поиск")).click()
        UiScrollable(UiSelector().scrollable(true)).scrollToBeginning(15)
        assertTrue(device.wait(Until.hasObject(By.text("Поиск игры по названию")),5000))
        ManualSearchSettings.save(context,ManualSearchFilters())
    }

    @Test fun wishlistImportIsSeparateFromOfferSearch() {
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("Игролов 1.1.1")),15000))
        device.findObject(By.text("Желания")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Загрузить и закрепить профиль")),5000))
        device.takeScreenshot(java.io.File(context.getExternalFilesDir(null),"wishlist.png"))
        assertFalse(device.hasObject(By.text("Искать на Plati")))
        device.findObject(By.text("Загрузить и закрепить профиль")).click()
        assertTrue(device.wait(Until.hasObject(By.textContains("Вставьте SteamID64")),3000))
        assertFalse(device.hasObject(By.text("Искать на Plati")))
    }

    @Test fun savedWishlistProfilesShowSteamPricesAndKeepSearchSeparate() {
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val prefs=context.getSharedPreferences("tracked_products",0)
        val steamId="76561198000000000"
        val profile=WishlistProfile(steamId,"Namtar","https://steamcommunity.com/profiles/$steamId/",listOf(
            WishlistGame(281990,"Stellaris","900 руб.","1 800 руб.",50,750.0)
        ),System.currentTimeMillis())
        val friendId="76561198000000001"
        val friend=WishlistProfile(friendId,"Friend",friendId,listOf(WishlistGame(730,"Counter-Strike 2","Бесплатно")),System.currentTimeMillis())
        prefs.edit().putString("wishlist_profiles",SteamWishlist.encodeProfiles(listOf(profile,friend)))
            .putString("wishlist_selected_profile",steamId).putString("wishlist_profile",profile.input)
            .putString("wishlist_games",SteamWishlist.encode(profile.games)).putLong("wishlist_imported_at",profile.importedAt).commit()
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("Игролов 1.1.1")),15000))
        device.findObject(By.text("Желания")).click()
        assertTrue(device.wait(Until.hasObject(By.textContains("Namtar")),5000))
        assertTrue(device.hasObject(By.textContains("Friend")))
        device.pressBack()
        if (!device.wait(Until.hasObject(By.textContains("Namtar")),1500)) {
            context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            assertTrue(device.wait(Until.hasObject(By.text("Игролов 1.1.1")),15000))
            device.findObject(By.text("Желания")).click()
            assertTrue(device.wait(Until.hasObject(By.textContains("Namtar")),5000))
        }
        val scroll=UiScrollable(UiSelector().scrollable(true)).setMaxSearchSwipes(20)
        assertTrue(scroll.scrollTextIntoView("Stellaris"))
        assertTrue(device.hasObject(By.text("Stellaris")))
        assertTrue(device.hasObject(By.text("900 руб.")))
        assertTrue(device.hasObject(By.text("−50% · Steam RU")))
        assertTrue(device.hasObject(By.text("Plati от 750 ₽")))
        assertTrue(device.hasObject(By.text("Искать")))
        assertFalse(device.hasObject(By.text("Искать Plati")))
        device.takeScreenshot(java.io.File(context.getExternalFilesDir(null),"wishlist.png"))
        device.findObject(By.text("Искать")).click()
        assertTrue(device.wait(Until.hasObject(By.textContains("Ищем минимальную цену")),5000))
        assertTrue(device.hasObject(By.text("900 руб.")))
        device.findObject(By.text("Stellaris")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Поиск игры по названию")),5000))
        val searchScroll=UiScrollable(UiSelector().scrollable(true)).setMaxSearchSwipes(20)
        assertTrue(searchScroll.scrollTextIntoView("Автопоиск Plati"))
        assertTrue(device.wait(Until.hasObject(By.textContains("Автопоиск Plati")),5000))
        assertTrue(searchScroll.scrollTextIntoView("Искать на Plati"))
        assertTrue(device.hasObject(By.text("Искать на Plati")))
        assertFalse(device.hasObject(By.text("Искать Plati")))
        prefs.edit().remove("wishlist_profiles").remove("wishlist_selected_profile").remove("wishlist_profile")
            .remove("wishlist_games").remove("wishlist_imported_at").commit()
    }
}
