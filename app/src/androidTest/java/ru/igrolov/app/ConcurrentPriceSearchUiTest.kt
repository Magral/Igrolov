package ru.igrolov.app

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLDecoder
import java.net.URLStreamHandler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ConcurrentPriceSearchUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun anotherGameStartsWhileFirstIsSlowAndFirstCanBeCancelledAndRetried() {
        val firstStarted = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstRequests = AtomicInteger()
        URL.setURLStreamHandlerFactory { protocol -> if (protocol != "https") null else object : URLStreamHandler() {
            override fun openConnection(url: URL): URLConnection = object : HttpURLConnection(url) {
                override fun connect() {}
                override fun disconnect() {}
                override fun usingProxy() = false
                override fun getResponseCode() = if (url.host.contains("ggsel")) 404 else 200
                override fun getInputStream(): ByteArrayInputStream {
                    val query = url.query.orEmpty().split('&').mapNotNull {
                        val pair = it.split('=', limit = 2)
                        if (pair.size == 2) pair[0] to URLDecoder.decode(pair[1], "UTF-8") else null
                    }.toMap()
                    val body = when {
                        url.path.contains("appdetails") -> {
                            val id = query["appids"] ?: "281990"
                            val name = if (id == "1091500") "Cyberpunk 2077" else "Stellaris"
                            """{"$id":{"success":true,"data":{"type":"game","name":"$name","dlc":[]}}}"""
                        }
                        url.path.endsWith("search2") -> {
                            if (query["query"].orEmpty().contains("Stellaris", true)) {
                                firstRequests.incrementAndGet()
                                firstStarted.countDown()
                                check(release.await(20, TimeUnit.SECONDS))
                            } else secondStarted.countDown()
                            """{"retval":0,"result":{"total_pages":1},"items":{"item":[]}}"""
                        }
                        else -> "{}"
                    }
                    return ByteArrayInputStream(body.toByteArray())
                }
            }
        } }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("tracked_products", 0)
        val saved = prefs.all.filterKeys { it.startsWith("wishlist_") }
        val profile = WishlistProfile("76561198000000000", "Concurrent price test", "76561198000000000", listOf(
            WishlistGame(281990, "Stellaris", "900 руб.", genres = listOf("Стратегия")),
            WishlistGame(1091500, "Cyberpunk 2077", "900 руб.", genres = listOf("Ролевая игра"))
        ), System.currentTimeMillis())
        try {
            ManualSearchSettings.save(context, ManualSearchFilters())
            prefs.edit().putString("wishlist_profiles", SteamWishlist.encodeProfiles(listOf(profile)))
                .putString("wishlist_selected_profile", profile.steamId64).commit()
            context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            compose.waitUntil(15000) { compose.onAllNodesWithText("Игролов").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Желания").performSemanticsAction(SemanticsActions.OnClick) { it() }
            fun click(appId: Int, label: String) {
                compose.onNodeWithTag("wishlist-list").performScrollToNode(hasTestTag("wishlist-game:$appId"))
                compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("wishlist-game:$appId")))
                    .performSemanticsAction(SemanticsActions.OnClick) { it() }
            }
            click(281990, "Цены")
            compose.waitUntil(5000) { firstStarted.count == 0L }
            click(1091500, "Цены")
            compose.waitUntil(5000) { secondStarted.count == 0L }
            assertEquals("First source must still be blocked when the second starts", 1L, release.count)
            click(281990, "Стоп")
            compose.waitUntil(5000) {
                compose.onAllNodesWithText("Проверка остановлена").fetchSemanticsNodes().isNotEmpty()
            }
            release.countDown()
            click(281990, "Цены")
            compose.waitUntil(5000) { firstRequests.get() >= 2 }
        } finally {
            release.countDown()
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
