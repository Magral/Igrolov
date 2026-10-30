package ru.igrolov.app

import android.content.Context
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.util.Collections

@RunWith(AndroidJUnit4::class)
class EditionComparisonUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun comparesContentsAndSearchesOnlySelectedEditionWithoutExtraPages() {
        val title="Kingdom Come: Deliverance II"
        val royal="$title Royal Edition"
        val queries=Collections.synchronizedList(mutableListOf<String>())
        URL.setURLStreamHandlerFactory {protocol->if(protocol!="https")null else object:URLStreamHandler() {
            override fun openConnection(url:URL):URLConnection=object:HttpURLConnection(url) {
                override fun connect(){}
                override fun disconnect(){}
                override fun usingProxy()=false
                override fun getResponseCode()=200
                override fun getOutputStream()=ByteArrayOutputStream()
                override fun getInputStream():ByteArrayInputStream {
                    val json=when {
                        url.path.contains("storesearch") -> """{"items":[{"id":1771300,"name":"$title"}]}"""
                        url.path.contains("appdetails") -> if(url.query.contains("cc=ru"))
                            """{"1771300":{"success":true,"data":{"type":"game","name":"$title","package_groups":[{"subs":[{"packageid":990001,"price_in_cents_with_discount":150000}]}]}}}"""
                            else """{"1771300":{"success":true,"data":{"type":"game","name":"$title","dlc":[],"package_groups":[{"subs":[{"packageid":990001},{"packageid":990002}]}]}}}"""
                        url.path.contains("packagedetails") -> if(url.query.contains("990002"))
                            """{"990002":{"success":true,"data":{"name":"$royal","apps":[{"id":1771300,"name":"$title"},{"id":3119920,"name":"Royal Expansion"}]}}}"""
                            else """{"990001":{"success":true,"data":{"name":"$title","apps":[{"id":1771300,"name":"$title"}]}}}"""
                        url.path.endsWith("search2") -> {
                            queries.add(url.query)
                            """{"items":{"item":[{"id":10000,"name":"$royal Steam Key РФ","price_rur":500},{"id":10001,"name":"$title Steam Key РФ","price_rur":100}]}}"""
                        }
                        url.path.contains("/api/products/") -> {
                            val id=url.path.split('/')[3].toInt()
                            val name=if(id==10000)royal else title
                            val price=if(id==10000)500 else 100
                            """{"retval":0,"product":{"id":$id,"name":"$name Steam Key РФ","is_available":1,"prices":{"default":{"RUB":$price}}}}"""
                        }
                        else -> """{"data":{"categories":[]},"items":[]}"""
                    }
                    return ByteArrayInputStream(json.toByteArray())
                }
            }
        }}
        val context=ApplicationProvider.getApplicationContext<Context>()
        ManualSearchSettings.save(context,ManualSearchFilters())
        CatalogPageSettings.set(context,300)
        WatchStore.write(context,JSONArray())
        context.getSharedPreferences("confirmed_offer_price_cache_v1",0).edit().clear().commit()
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        compose.waitUntil(15000) {compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()}
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextReplacement(title)
        compose.waitUntil(15000) {compose.onAllNodes(hasText(title) and hasClickAction() and hasSetTextAction().not()).fetchSemanticsNodes().isNotEmpty()}
        compose.onAllNodes(hasText(title) and hasClickAction() and hasSetTextAction().not()).onLast()
            .performSemanticsAction(SemanticsActions.OnClick) {it()}
        compose.waitUntil(15000) {compose.onAllNodesWithTag("compare-editions").fetchSemanticsNodes().isNotEmpty()}
        UiTestScroll.reveal(compose,"Сравнить издания (2)")
        compose.onNodeWithTag("compare-editions").performClick()
        compose.onNodeWithTag("edition-compare:990001").assertExists()
        compose.onNodeWithTag("edition-search:990002").performScrollTo()
        compose.onNodeWithText("В составе: $title, Royal Expansion").assertExists()
        compose.onNodeWithText("Нет предложения в Steam RU").assertExists()
        compose.onNodeWithTag("edition-search:990002").performClick()
        compose.waitUntil(20000) {OfferPriceCacheStore.readAll(context).values.any {it.plati.price==500.0}}
        assertTrue(queries.isNotEmpty())
        assertTrue("Every edition query must stop after the short first page",queries.all {"pagenum=1" in it})
        UiTestScroll.reveal(compose,"Сравнить издания (2)")
        compose.onNodeWithTag("compare-editions").performClick()
        compose.onNodeWithTag("edition-compare:990001").assert(hasAnyDescendant(hasText("—")))
        compose.onNodeWithTag("edition-search:990002").performScrollTo()
        compose.onNodeWithTag("edition-compare:990002").assert(hasAnyDescendant(hasText("500,00 ₽")))
        compose.onNodeWithText("100,00 ₽").assertDoesNotExist()
        compose.onNodeWithText("Закрыть").performClick()
        CatalogPageSettings.set(context,100)
    }
}
