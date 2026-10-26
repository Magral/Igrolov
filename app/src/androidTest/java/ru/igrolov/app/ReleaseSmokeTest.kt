package ru.igrolov.app

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.semantics.SemanticsActions
import org.junit.Rule
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
    @get:Rule val compose = createEmptyComposeRule()
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
        assertEquals(157,PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName,0)))
    }
    @Test fun launchAndNavigateFiltersSettingsAndMonitoring() {
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(UiTestScroll.await(compose, 15000) { device.hasObject(By.text("Игролов")) })
        compose.onNodeWithText("Лидеры").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        assertTrue(UiTestScroll.await(compose, 5000) { device.hasObject(By.text("Бестселлеры")) })
        assertTrue(device.hasObject(By.text("Бестселлеры")))
        assertTrue(device.hasObject(By.text("Новинки")))
        assertTrue(device.hasObject(By.text("Скидки")))
        assertTrue(device.hasObject(By.text("Steam RU · Все")))
        compose.onNodeWithText("Steam RU · Все").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        assertTrue(device.hasObject(By.text("Все игры")))
        assertTrue(device.hasObject(By.text("Доступны в Steam RU")))
        assertTrue(device.hasObject(By.text("Недоступны в Steam RU")))
        compose.onNodeWithText("Недоступны в Steam RU").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        assertTrue(device.hasObject(By.text("Steam RU · Нет")))
        compose.onNodeWithText("Ещё").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        compose.onNodeWithText("Сбросить").performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertTrue(UiTestScroll.await(compose, 5000) { device.hasObject(By.text("Steam RU · Все")) })
        compose.onNodeWithText("Поиск").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        compose.onNodeWithText("Gift").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        compose.onNodeWithText("Key").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        compose.onNodeWithText("Товары").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        assertTrue(UiTestScroll.await(compose, 5000) { device.hasObject(By.text("Ссылка Plati или ID товара")) })
        compose.onNodeWithText("Мониторинг").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        assertTrue(UiTestScroll.await(compose, 5000) { device.hasObject(By.text("Мониторинг")) })
        compose.onNodeWithContentDescription("Настройки").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        assertTrue(UiTestScroll.await(compose, 5000) { device.hasObject(By.text("Настройки")) })
        compose.onNodeWithContentDescription("Закрыть настройки").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        compose.onNodeWithText("Поиск").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        UiTestScroll.reveal(compose,"Поиск игры")
        assertTrue(UiTestScroll.await(compose, 5000) { device.hasObject(By.text("Поиск игры")) })
        assertTrue(device.hasObject(By.text("GGSEL")))
        assertTrue(device.hasObject(By.text("Plati")))
        UiTestScroll.reveal(compose,"Искать")
        assertTrue(device.hasObject(By.text("Искать")))
        ManualSearchSettings.save(context,ManualSearchFilters())
    }

    @Test fun wishlistImportIsSeparateFromOfferSearch() {
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        context.getSharedPreferences("tracked_products",0).edit()
            .remove("wishlist_profiles").remove("wishlist_selected_profile").remove("wishlist_profile")
            .remove("wishlist_games").remove("wishlist_imported_at").commit()
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(UiTestScroll.await(compose, 15000) { device.hasObject(By.text("Игролов")) })
        compose.onNodeWithText("Желания").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        assertTrue(UiTestScroll.await(compose, 5000) { device.hasObject(By.text("Загрузить и закрепить профиль")) })
        device.takeScreenshot(java.io.File(context.getExternalFilesDir(null),"wishlist.png"))
        assertFalse(device.hasObject(By.text("Искать")))
        compose.onNodeWithText("Загрузить и закрепить профиль").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        assertTrue(UiTestScroll.await(compose, 3000) { device.hasObject(By.textContains("Вставьте SteamID64")) })
        assertFalse(device.hasObject(By.text("Искать")))
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
        assertTrue(UiTestScroll.await(compose, 15000) { device.hasObject(By.text("Игролов")) })
        compose.onNodeWithText("Желания").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        assertTrue(UiTestScroll.await(compose, 5000) { device.hasObject(By.textContains("Namtar")) })
        assertTrue(device.hasObject(By.textContains("Friend")))
        UiTestScroll.reveal(compose,"Stellaris")
        assertTrue(device.hasObject(By.text("Stellaris")))
        assertFalse(device.hasObject(By.text("AppID 281990")))
        UiTestScroll.reveal(compose,"Подробнее")
        compose.onNodeWithText("Подробнее").assertIsDisplayed()
        if (!device.hasObject(By.text("750 ₽"))) {
            UiTestScroll.reveal(compose,"750 ₽")
        }
        UiTestScroll.reveal(compose, "900 ₽")
        compose.onNodeWithText("900 ₽").assertIsDisplayed()
        // Compose's text matcher confirms the discount note is rendered; UIAutomator
        // does not expose this nested Text as an exact standalone accessibility node.
        UiTestScroll.reveal(compose,"−50%")
        compose.onNodeWithText("750 ₽").assertIsDisplayed()
        UiTestScroll.reveal(compose, "Цены")
        compose.onNodeWithText("Цены").assertIsDisplayed()
        assertFalse(device.hasObject(By.text("Искать Plati")))
        device.takeScreenshot(java.io.File(context.getExternalFilesDir(null),"wishlist.png"))
        compose.onNodeWithText("Цены").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("Ищем минимальную цену", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        UiTestScroll.reveal(compose, "900 ₽")
        compose.onNodeWithText("900 ₽").assertIsDisplayed()
        compose.onNodeWithText("Stellaris").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        assertTrue(UiTestScroll.await(compose, 5000) { device.hasObject(By.text("Поиск игры")) })
        assertTrue(UiTestScroll.await(compose, 5000) { device.hasObject(By.textContains("Автопоиск цен")) })
        assertFalse(device.hasObject(By.text("Искать Plati")))
        prefs.edit().remove("wishlist_profiles").remove("wishlist_selected_profile").remove("wishlist_profile")
            .remove("wishlist_games").remove("wishlist_imported_at").commit()
    }
}
