package ru.igrolov.app

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.json.JSONObject
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FullRedesignScreenshotsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun captureAllMainScreens() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        GameRuleStore.add(context, "Stellaris", "blocked", "DLC")
        val product = JSONObject().put("proofSchema", 106).put("name", "Stellaris Steam Key РФ")
            .put("canonical", "Stellaris").put("steamAppId", 281990).put("marketplace", "Plati")
            .put("url", "https://plati.market/itm/123456").put("price", 4914)
        context.getSharedPreferences("tracked_products", 0).edit()
            .putString("ids", "Plati:123456")
            .putString("product_cache_Plati:123456", product.toString()).commit()
        context.startActivity(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("Игролов").fetchSemanticsNodes().isNotEmpty()
        }
        val out = context.getExternalFilesDir(null)!!
        fun capture(name: String) {
            compose.waitForIdle()
            Thread.sleep(350)
            device.takeScreenshot(File(out, "redesign-$name.png"))
        }
        capture("search")
        val tabs = listOf("Товары", "Мониторинг", "Желания", "Лидеры", "Справочник")
        val files = listOf("products", "monitoring", "wishlist", "leaders", "directory")
        tabs.zip(files).forEach { (tab, file) ->
            compose.onAllNodesWithText(tab).onFirst()
                .performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(10000) {
                compose.onAllNodesWithText(tab).fetchSemanticsNodes().isNotEmpty()
            }
            capture(file)
        }
        context.getSharedPreferences("tracked_products", 0).edit().putString("ids", "")
            .remove("product_cache_Plati:123456").commit()
        GameRuleStore.remove(context, "Stellaris", "blocked", "DLC")
    }
}