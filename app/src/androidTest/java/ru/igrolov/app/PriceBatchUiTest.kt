package ru.igrolov.app

import android.content.Context
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLDecoder
import java.net.URLStreamHandler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap

@RunWith(AndroidJUnit4::class)
class PriceBatchUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun batchKeepsTwoSlotsMakesProgressAndStopsAllRequests() {
        val slow=CountDownLatch(1)
        val releaseSlow=CountDownLatch(1)
        val third=CountDownLatch(1)
        val blockAll=AtomicBoolean(false)
        val releaseAll=CountDownLatch(1)
        val blocked=ConcurrentHashMap.newKeySet<String>()
        val titles=mapOf(281990 to "Stellaris",1091500 to "Cyberpunk 2077",1203620 to "Enshrouded")
        URL.setURLStreamHandlerFactory {protocol->if(protocol!="https")null else object:URLStreamHandler() {
            override fun openConnection(url:URL):URLConnection=object:HttpURLConnection(url) {
                override fun connect(){}
                override fun disconnect(){}
                override fun usingProxy()=false
                override fun getResponseCode()=200
                override fun getInputStream():ByteArrayInputStream {
                    val params=url.query.orEmpty().split('&').mapNotNull {
                        val pair=it.split('=',limit=2)
                        if(pair.size==2)pair[0] to URLDecoder.decode(pair[1],"UTF-8") else null
                    }.toMap()
                    val json=when {
                        url.path.contains("appdetails") -> {
                            val id=params["appids"]?.toIntOrNull() ?: 281990
                            """{"$id":{"success":true,"data":{"name":"${titles[id]}","type":"game","dlc":[]}}}"""
                        }
                        url.path.endsWith("search2") -> {
                            val query=params["query"].orEmpty()
                            if(blockAll.get()) {
                                blocked.add(query)
                                check(releaseAll.await(30,TimeUnit.SECONDS))
                            } else if(query.contains("Stellaris",true) || query.contains("Стелларис",true)) {
                                slow.countDown()
                                check(releaseSlow.await(30,TimeUnit.SECONDS))
                            } else if(query.contains("Enshrouded",true) || query.contains("Эншраудед",true))third.countDown()
                            """{"items":{"item":[]}}"""
                        }
                        else -> """{"data":{"categories":[]},"items":[]}"""
                    }
                    return ByteArrayInputStream(json.toByteArray())
                }
            }
        }}
        val context=ApplicationProvider.getApplicationContext<Context>()
        val prefs=context.getSharedPreferences("tracked_products",0)
        val profile=WishlistProfile("76561198000000000","Batch test","76561198000000000",
            titles.map {(id,title)->WishlistGame(id,title,"900 руб.",genres=listOf("Приключение"))},System.currentTimeMillis())
        ManualSearchSettings.save(context,ManualSearchFilters())
        context.getSharedPreferences("manual_search_105",0).edit().putString("games","[]").commit()
        context.getSharedPreferences("catalogue_filters",0).edit().clear().commit()
        prefs.edit().putString("wishlist_profiles",SteamWishlist.encodeProfiles(listOf(profile)))
            .putString("wishlist_selected_profile",profile.steamId64).commit()
        try {
            context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            compose.waitUntil(15000) {compose.onAllNodesWithText("Желания").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Желания").performSemanticsAction(SemanticsActions.OnClick) {it()}
            UiTestScroll.reveal(compose,"Проверить цены · 3")
            compose.onNodeWithTag("wishlist-check-prices").performClick()
            compose.waitUntil(10000) {slow.count==0L && third.count==0L}
            assertEquals("Third game must start before the slow first one finishes",1L,releaseSlow.count)
            compose.waitUntil(10000) {compose.onAllNodesWithText("Проверено 2 / 3",substring=true).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("price-batch-status").assertTextContains("Проверено 2 / 3",substring=true)
            releaseSlow.countDown()
            compose.waitUntil(10000) {compose.onAllNodesWithText("Готово 3 / 3").fetchSemanticsNodes().isNotEmpty()}
            blockAll.set(true)
            compose.onNodeWithTag("wishlist-check-prices").performClick()
            compose.waitUntil(10000) {blocked.size==2}
            compose.onNodeWithTag("price-batch-stop").performClick()
            compose.waitUntil(5000) {compose.onAllNodesWithText("Остановлено: 0 / 3").fetchSemanticsNodes().isNotEmpty()}
            releaseAll.countDown()
            compose.waitForIdle()
            assertEquals("Stopping the batch must not launch the queued third game",2,blocked.size)
            compose.onNodeWithTag("wishlist-check-prices").assertIsEnabled()
        } finally {releaseSlow.countDown();releaseAll.countDown();prefs.edit().remove("wishlist_profiles").remove("wishlist_selected_profile").commit()}
    }
}
