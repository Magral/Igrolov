package ru.igrolov.app

import android.content.Context
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler

@RunWith(AndroidJUnit4::class)
class CatalogFilterPreferencesTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun filtersRestoreSeparatelyAndCanBeClearedWithoutOpeningMore() {
        URL.setURLStreamHandlerFactory {protocol->if(protocol!="https")null else object:URLStreamHandler() {
            override fun openConnection(url:URL):URLConnection=object:HttpURLConnection(url) {
                override fun connect(){}
                override fun disconnect(){}
                override fun usingProxy()=false
                override fun getResponseCode()=200
                override fun getInputStream()=ByteArrayInputStream("{}".toByteArray())
            }
        }}
        val context=ApplicationProvider.getApplicationContext<Context>()
        androidx.work.WorkManager.getInstance(context).cancelAllWork().result.get()
        WatchStore.write(context,JSONArray())
        val prefs=context.getSharedPreferences("catalogue_filters",0)
        val wishlist=SteamLeaderFilterInput(maxPriceText="500",selectedGenres=setOf("Стратегии"))
        prefs.edit().clear().putString("leaders",CatalogFilterSettings.encode(
            SteamLeaderFilterInput(maxPriceText="1500",selectedGenres=setOf("RPG")),"Недоступны в Steam RU"))
            .putString("wishlist",CatalogFilterSettings.encode(wishlist)).commit()
        fun launch()=context.startActivity(Intent(context,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        try {
            launch()
            compose.waitUntil(15000) {compose.onAllNodesWithText("Лидеры").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Лидеры").performSemanticsAction(SemanticsActions.OnClick) {it()}
            compose.waitUntil(10000) {compose.onAllNodesWithText("До 1500 ₽").fetchSemanticsNodes().isNotEmpty()}
            // A selected genre remains removable even when network metadata is missing.
            compose.onNodeWithText("Жанры · 1").performSemanticsAction(SemanticsActions.OnClick) {it()}
            compose.onNodeWithText("RPG").performClick()
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
            compose.waitUntil(5000) {CatalogFilterSettings.decode(prefs.getString("leaders",null)).input.selectedGenres.isEmpty()}
            compose.onNodeWithTag("catalog-filter-reset").performSemanticsAction(SemanticsActions.OnClick) {it()}
            compose.waitUntil(5000) {CatalogFilterSettings.decode(prefs.getString("leaders",null))==CatalogFilterSettings.Snapshot()}
            assertEquals(wishlist,CatalogFilterSettings.decode(prefs.getString("wishlist",null)).input)
            compose.onNodeWithText("Желания").performSemanticsAction(SemanticsActions.OnClick) {it()}
            compose.waitUntil(5000) {compose.onAllNodesWithText("До 500 ₽").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Русский").performSemanticsAction(SemanticsActions.OnClick) {it()}
            compose.onNodeWithText("Русского нет").performClick()
            compose.waitUntil(5000) {CatalogFilterSettings.decode(prefs.getString("wishlist",null)).input.russianLocalization==SteamRussianLocalization.NOT_SUPPORTED}
            launch()
            compose.waitUntil(10000) {compose.onAllNodesWithText("Желания").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Желания").performSemanticsAction(SemanticsActions.OnClick) {it()}
            compose.waitUntil(5000) {compose.onAllNodesWithText("Русский · Нет").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("До 500 ₽").assertExists()
            compose.onNodeWithTag("catalog-filter-reset").performSemanticsAction(SemanticsActions.OnClick) {it()}
            compose.waitUntil(5000) {CatalogFilterSettings.decode(prefs.getString("wishlist",null))==CatalogFilterSettings.Snapshot()}
        } finally {prefs.edit().clear().commit()}
    }
}
