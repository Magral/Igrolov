package ru.igrolov.app

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UnifiedListsUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun wishlistHasOneListAndRestoresPositionAfterDetailsAndTabSwitch() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("tracked_products", 0)
        val saved = prefs.all.filterKeys { it.startsWith("wishlist_") }
        val profile = WishlistProfile("76561198000000000", "Scroll test", "76561198000000000",
            (0 until 80).map { WishlistGame(9000000 + it, "Тестовая игра $it", "900 руб.", genres = listOf("Стратегия")) }, System.currentTimeMillis())
        try {
            prefs.edit().putString("wishlist_profiles", SteamWishlist.encodeProfiles(listOf(profile)))
                .putString("wishlist_selected_profile", profile.steamId64).commit()
            context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            compose.waitUntil(15000) { compose.onAllNodesWithText("Игролов").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Желания").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("wishlist-list").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodes(hasScrollToIndexAction()).assertCountEquals(1)
            compose.onNodeWithTag("wishlist-list").performScrollToIndex(25)
            compose.waitForIdle()
            fun position() = compose.onNodeWithTag("wishlist-list").fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange].value()
            val before = position()
            assertTrue(before >= 24f)
            compose.onAllNodesWithText("Подробнее").onFirst().performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onNodeWithText("Закрыть").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitForIdle()
            assertEquals(before, position(), 0.01f)
            compose.onNodeWithText("Товары").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onNodeWithText("Желания").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitForIdle()
            assertEquals(before, position(), 0.01f)
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                .takeScreenshot(java.io.File(context.getExternalFilesDir(null), "unified-lists.png"))
        } finally {
            val edit = prefs.edit()
            prefs.all.keys.filter { it.startsWith("wishlist_") }.forEach { edit.remove(it) }
            saved.forEach { (key, value) -> when (value) {
                is String -> edit.putString(key, value)
                is Long -> edit.putLong(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
            } }
            edit.commit()
        }
    }
}
