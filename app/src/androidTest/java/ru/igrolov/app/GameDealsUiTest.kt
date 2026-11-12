package ru.igrolov.app

import android.content.Context
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
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
class GameDealsUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun wishlistSortsByPriceAndVerifiedSavingAndRetainsDeliveryLabels() {
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
        val prefs=context.getSharedPreferences("tracked_products",0)
        val filterPrefs=context.getSharedPreferences("catalogue_filters",0)
        val quotes=context.getSharedPreferences("steam_ru_quotes",0)
        filterPrefs.edit().clear().commit()
        ManualSearchSettings.save(context,ManualSearchFilters())
        context.getSharedPreferences("manual_search_105",0).edit().putString("games","[]").commit()
        val games=listOf(WishlistGame(281990,"Stellaris","1000 руб."),WishlistGame(1091500,"Cyberpunk 2077","2000 руб."))
        val now=System.currentTimeMillis()
        games.forEachIndexed {i,game->
            GameRuleStore.save(context,game.title,JSONObject())
            val key=OfferPriceCache.key(game.appId,ManualSearchFilters(),JSONObject(),IgnoredStore.ids(context),CatalogPageSettings.pages(context),emptyList())
            OfferPriceCacheStore.update(context,key,mapOf("Plati" to if(i==0)100.0 else 500.0),emptySet(),now,now,"",
                proofs=mapOf("Plati" to OfferComparisonProof("base:${game.appId}",if(i==0)"Key" else "Gift")))
            quotes.edit().putString(SteamRuQuotes.key(game.appId),SteamRuQuotes.encode(SteamRuQuote(if(i==0)1000.0 else 2000.0,true,"base:${game.appId}",game.title,now))).commit()
        }
        val profile=WishlistProfile("76561198000000000","Deals test","76561198000000000",games,now)
        prefs.edit().putString("wishlist_profiles",SteamWishlist.encodeProfiles(listOf(profile))).putString("wishlist_selected_profile",profile.steamId64).commit()
        try {
            context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            compose.waitUntil(15000) {compose.onAllNodesWithText("Желания").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Желания").performSemanticsAction(SemanticsActions.OnClick) {it()}
            UiTestScroll.reveal(compose,"Сортировка: По умолчанию")
            compose.onNodeWithTag("wishlist-deal-sort").performClick()
            compose.onNodeWithText("По найденной цене").performClick()
            compose.onNodeWithTag("wishlist-list").performScrollToIndex(1)
            compose.onNodeWithText("Stellaris").assertIsDisplayed()
            compose.onAllNodesWithTag("delivery:Plati",useUnmergedTree=true).onFirst().assertTextEquals("Key")
            UiTestScroll.reveal(compose,"Сортировка: По найденной цене")
            compose.onNodeWithTag("wishlist-deal-sort").performClick()
            compose.onNodeWithText("По выгоде").performClick()
            compose.onNodeWithTag("wishlist-list").performScrollToIndex(1)
            compose.onNodeWithText("Cyberpunk 2077").assertIsDisplayed()
            compose.onAllNodesWithTag("deal-saving").onFirst().assertTextContains("1500,00 ₽",substring=true)
            compose.onAllNodesWithTag("delivery:Plati",useUnmergedTree=true).onFirst().assertTextEquals("Gift")
            compose.waitUntil(5000) {CatalogFilterSettings.decode(filterPrefs.getString("wishlist",null)).sort==DealSort.SAVING}
            assertEquals(DealSort.DEFAULT,CatalogFilterSettings.decode(filterPrefs.getString("leaders",null)).sort)
        } finally {
            prefs.edit().remove("wishlist_profiles").remove("wishlist_selected_profile").commit()
            filterPrefs.edit().clear().commit()
            games.forEach {quotes.edit().remove(SteamRuQuotes.key(it.appId)).commit()}
        }
    }
}
