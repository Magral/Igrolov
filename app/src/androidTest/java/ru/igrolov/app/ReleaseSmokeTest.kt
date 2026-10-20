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
        assertEquals(114,PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName,0)))
    }
    @Test fun launchAndNavigateFiltersSettingsAndMonitoring() {
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.text("Игролов 1.0.7")),15000))
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
}
