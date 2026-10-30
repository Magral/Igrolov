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
import java.net.URLStreamHandler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PartialPriceSearchUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun checkedOfferAppearsWhileAnotherCardIsStillLoading() {
        val slowStarted=CountDownLatch(1)
        val release=CountDownLatch(1)
        URL.setURLStreamHandlerFactory {protocol->if(protocol!="https")null else object:URLStreamHandler() {
            override fun openConnection(url:URL):URLConnection=object:HttpURLConnection(url) {
                override fun connect(){}
                override fun disconnect(){}
                override fun usingProxy()=false
                override fun getResponseCode()=200
                override fun getInputStream():ByteArrayInputStream {
                    val json=when {
                        url.path.contains("storesearch") -> """{"items":[{"id":281990,"name":"Stellaris"}]}"""
                        url.path.contains("appdetails") -> """{"281990":{"success":true,"data":{"name":"Stellaris","type":"game","dlc":[]}}}"""
                        url.path.endsWith("search2") -> """{"items":{"item":[{"id":10000,"name":"Stellaris Steam Key РФ","price_rur":1},{"id":10001,"name":"Stellaris Steam Key РФ","price_rur":2}]}}"""
                        url.path.contains("/api/products/") -> {
                            val id=url.path.split('/')[3].toInt()
                            if(id==10000) {slowStarted.countDown();check(release.await(30,TimeUnit.SECONDS))}
                            """{"retval":0,"product":{"id":$id,"name":"Stellaris Steam Key РФ","is_available":1,"prices":{"default":{"RUB":${if(id==10000)100 else 500}}}}}"""
                        }
                        else -> """{"data":{"categories":[]},"items":[]}"""
                    }
                    return ByteArrayInputStream(json.toByteArray())
                }
            }
        }}
        val context=ApplicationProvider.getApplicationContext<Context>()
        ManualSearchSettings.save(context,ManualSearchFilters())
        context.getSharedPreferences("manual_search_105",0).edit().putString("games","[]").commit()
        context.getSharedPreferences("confirmed_offer_price_cache_v1",0).edit().clear().commit()
        try {
            context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            compose.waitUntil(15000) {compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()}
            compose.onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("Stellaris")
            compose.waitUntil(15000) {compose.onAllNodes(hasText("Stellaris") and hasClickAction() and hasSetTextAction().not()).fetchSemanticsNodes().isNotEmpty()}
            compose.onAllNodes(hasText("Stellaris") and hasClickAction() and hasSetTextAction().not()).onLast()
                .performSemanticsAction(SemanticsActions.OnClick) {it()}
            compose.onNodeWithText("Поиск").performSemanticsAction(SemanticsActions.OnClick) {it()}
            UiTestScroll.reveal(compose,"Искать")
            compose.onNodeWithText("Искать").performSemanticsAction(SemanticsActions.OnClick) {it()}
            assertTrue(slowStarted.await(10,TimeUnit.SECONDS))
            UiTestScroll.reveal(compose,"Предложения по цене ↑ · 1")
            compose.onNodeWithTag("offer-row-price:Plati:10001").assertExists()
            assertEquals("Slow card must still be blocked",1L,release.count)
            assertFalse("Partial offers must not mark Plati fully checked",OfferPriceCacheStore.readAll(context).values.any {it.plati.checkedAt>0})
            release.countDown()
            compose.waitUntil(15000) {OfferPriceCacheStore.readAll(context).values.any {it.plati.price==100.0 && it.plati.checkedAt>0}}
            UiTestScroll.reveal(compose,"Предложения по цене ↑ · 2")
            compose.onNodeWithTag("offer-row-price:Plati:10000").assertExists()
        } finally {release.countDown()}
    }
}
