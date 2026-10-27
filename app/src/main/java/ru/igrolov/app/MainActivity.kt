package ru.igrolov.app

import android.os.Bundle
import android.text.Html
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import android.Manifest
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.lifecycle.Observer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade

data class Offer(val id: String, val name: String, val price: Double, val type: String, val rfStatus: String, val edition: String, val categoryLabel: String = "", val detailsChecked: Boolean = false, val variablePrice: Boolean = false, val available: Boolean? = null, val priceVerified: Boolean = false, val purchaseNote: String = "", val canonical:String="", val steamAppId:Int=0, val marketplace:String="Plati", val productUrl:String="")

data class Tracked(val id: String, val name: String, val price: Double?, val status: String, val url: String, val canonical:String="", val steamAppId:Int=0, val marketplace:String="Plati") { val storageKey:String get() = "$marketplace:$id" }

class MainActivity : ComponentActivity() {
    // Bound concurrent product detail requests so large watch lists do not spawn unlimited threads.
    private val detailExecutor = Executors.newFixedThreadPool(4)
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val wishlistExecutor = Executors.newSingleThreadExecutor()
    private val catalogExecutor = Executors.newSingleThreadExecutor()
    private val suggestionExecutor = Executors.newSingleThreadExecutor()
    private val editionExecutor = Executors.newSingleThreadExecutor()
    private val watchSuggestionGeneration=java.util.concurrent.atomic.AtomicInteger(0)
    private val suggestionGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    private val activeCatalogGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    override fun onDestroy() {
        activeCatalogGeneration.incrementAndGet()
        catalogExecutor.shutdownNow()
        suggestionGeneration.incrementAndGet()
        watchSuggestionGeneration.incrementAndGet()
        suggestionExecutor.shutdownNow()
        editionExecutor.shutdownNow()
        detailExecutor.shutdownNow()
        ioExecutor.shutdownNow()
        wishlistExecutor.shutdownNow()
        super.onDestroy()
    }
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val prefs by lazy { getSharedPreferences("tracked_products", MODE_PRIVATE) }
    private val steamLeaderCachePrefs by lazy { getSharedPreferences("steam_leader_cache", MODE_PRIVATE) }
    private fun loadIds(): List<String> = prefs.getString("ids", "")!!.split(",").filter { it.isNotBlank() }
    private fun saveIds(ids: List<String>) = prefs.edit().putString("ids", ids.distinct().joinToString(",")).apply()
    private fun trackedLegacyId(key:String)=key.substringAfter(':',key)
    private fun cachedTracked(key:String):Tracked {
        val legacyId=trackedLegacyId(key)
        val cached=MonitorRoomStore.readTrackedProduct(this,key)
            ?: if(legacyId!=key) MonitorRoomStore.readTrackedProduct(this,legacyId) else null
        return TrackedPriceCache.decode(key,cached)
    }
    private fun cachedTrackedFromPreferences(key:String):Tracked {
        val legacyId=trackedLegacyId(key)
        val cached=prefs.getString("product_cache_$key",null)
            ?: if(legacyId!=key) prefs.getString("product_cache_$legacyId",null) else null
        return TrackedPriceCache.decode(key,cached)
    }
    private fun cacheTracked(item:Tracked) {
        val cached=TrackedPriceCache.encode(item) ?: return
        val key=item.storageKey
        prefs.edit().putString("product_cache_$key",cached).apply()
        ioExecutor.execute { MonitorRoomStore.writeTrackedProduct(this,key,cached) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WatchScheduler.start(this)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFF17212D),
                onPrimary = Color.White,
                secondary = Color(0xFF168F57),
                onSecondary = Color.White,
                tertiary = Color(0xFF4C5969),
                background = Color(0xFFF4F6F8),
                surface = Color(0xFFFFFFFF),
                surfaceVariant = Color(0xFFEEF1F4),
                primaryContainer = Color(0xFFFFE2A6),
                onPrimaryContainer = Color(0xFF17212D),
                secondaryContainer = Color(0xFFE0F4E9),
                onSecondaryContainer = Color(0xFF155B35),
                tertiaryContainer = Color(0xFFE9EDF1),
                onTertiaryContainer = Color(0xFF344253),
                outline = Color(0xFFD8DEE5),
                outlineVariant = Color(0xFFE2E6EB),
                onSurface = Color(0xFF17212D),
                onSurfaceVariant = Color(0xFF566273)
            ), typography = Typography().run {
                copy(
                    bodySmall = bodySmall.copy(fontSize = 11.sp),
                    bodyMedium = bodyMedium.copy(fontSize = 12.sp),
                    bodyLarge = bodyLarge.copy(fontSize = 13.sp),
                    titleSmall = titleSmall.copy(fontSize = 12.sp),
                    titleMedium = titleMedium.copy(fontSize = 14.sp),
                    titleLarge = titleLarge.copy(fontSize = 16.sp),
                    labelSmall = labelSmall.copy(fontSize = 12.sp),
                    labelMedium = labelMedium.copy(fontSize = 13.sp),
                    labelLarge = labelLarge.copy(fontSize = 12.sp),
                    headlineSmall = headlineSmall.copy(fontSize = 24.sp)
                )
            }, shapes = Shapes(
                extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                small = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                medium = androidx.compose.foundation.shape.RoundedCornerShape(9.dp),
                large = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
            )) { Screen() }
        }
    }
    private fun fetch(id:String,callback:(Tracked)->Unit) =
        fetch(Tracked(id,"Товар $id",null,"Обновляем цену…","https://plati.market/itm/$id"),callback)

    private fun fetch(item:Tracked, callback:(Tracked)->Unit) {
        detailExecutor.execute {
            val previous=cachedTracked(item.storageKey).let { cached ->
                if(cached.price==null && item.price!=null) item else cached
            }
            val result = try {
                if(item.marketplace=="GGSEL") {
                    if(item.steamAppId<=0 || item.canonical.isBlank())
                        error("Для отслеживания GGSEL не хватает подтверждённой игры Steam")
                    val reference=ManualGameReference.lookup(item.canonical,item.steamAppId,emptyList())
                    if(reference.appId!=item.steamAppId)
                        error("Steam не подтвердил игру для обновления GGSEL")
                    val offer=GgselBuyerApi.find(reference).firstOrNull { it.id==item.id }
                    if(offer==null) item.copy(price=null,status="Нет активного предложения GGSEL")
                    else Tracked(item.id,offer.name,offer.price.takeIf {offer.priceVerified && offer.available==true},
                        if(offer.priceVerified && offer.available==true) "В продаже · GGSEL" else "Цена или наличие не подтверждены",
                        offer.productUrl.ifBlank { item.url },offer.canonical,item.steamAppId,"GGSEL")
                } else {
                    val id=item.id
                    val conn = (URL("https://api.digiseller.com/api/products/$id/data?currency=RUB&lang=ru-RU").openConnection() as HttpURLConnection)
                    conn.connectTimeout = 12000
                    conn.readTimeout = 12000
                    conn.setRequestProperty("Accept", "application/json")
                    try {
                        if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
                        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                        if (json.optInt("retval", -1) != 0) error(json.optString("retdesc", "Ошибка API"))
                        val product = json.optJSONObject("product") ?: error("Нет данных товара")
                        if(product.optString("id")!=id) error("ID карточки не совпадает")
                        val identity=TrackedGameIdentity.resolve(previous,product.optString("name"),ManualSearchSettings.games(this)+GameSearchResolver.seeds)
                        var calculationFailure:Exception?=null
                        val proof=ManualPurchaseProof.inspect(product,identity.canonical) {path->
                            try {CatalogApi.get(path,true)}catch(e:Exception) {calculationFailure=e;null}
                        }
                        calculationFailure?.let {throw it}
                        val state=when {
                            product.optInt("is_available",-1)==0 -> "Нет в наличии"
                            !proof.available -> "Наличие не подтверждено"
                            proof.price==null -> proof.label
                            else -> "В продаже · ${proof.label}"
                        }
                        Tracked(id,product.optString("name","Товар $id"),proof.price.takeIf {proof.available},state,item.url,
                            identity.canonical.takeIf {identity.confirmed}.orEmpty(),identity.appId,"Plati")
                    } finally { conn.disconnect() }
                }
            } catch (e: Exception) {previous.copy(price=null,status="Не удалось обновить цену")}
            runOnUiThread { callback(TrackedPriceCache.merge(previous,result)) }
        }
    }
    // Network requests are performed only on background threads.
    // Retry transient DNS/timeout/server failures without duplicating search results.
    private fun getCatalogJson(url: String): JSONObject {
        var last: Exception? = null
        repeat(3) { attempt ->
            var statusCode = 0
            var retryAfter: String? = null
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                conn.setRequestProperty("Accept", "application/json")
                try {
                    statusCode = conn.responseCode
                    if (statusCode !in 200..299) {
                        retryAfter = conn.getHeaderField("Retry-After")
                        if (statusCode == 429 || statusCode in 500..599) throw java.io.IOException("HTTP $statusCode")
                        error("HTTP $statusCode: поиск каталога недоступен")
                    }
                    return JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                } finally { conn.disconnect() }
            } catch (e: Exception) {
                val retryable = e is java.net.UnknownHostException ||
                    e is java.net.SocketTimeoutException ||
                    e is java.net.ConnectException ||
                    (e is java.io.IOException && (e.message?.startsWith("HTTP 5") == true || e.message == "HTTP 429"))
                if (!retryable || attempt == 2) throw e
                last = e
                Thread.sleep(CatalogRetryPolicy.delayMillis(statusCode, retryAfter, attempt))
            }
        }
        throw last ?: java.io.IOException("Нет соединения")
    }

    private fun searchCatalog(query: String, pageCount: Int, generation: Int, selectedAppId: Int?, additionalAliases: List<String>, selectedEdition: SteamEditionReference.Edition? = null, availableEditions: List<SteamEditionReference.Edition> = emptyList(), progress: (Int, Int, Int) -> Unit, callback: (List<Offer>, String, List<String>, Set<String>) -> Unit) {
        catalogExecutor.execute {
            val found = mutableListOf<Offer>()
            val seenIds = mutableSetOf<String>()
            val sellerIds = mutableMapOf<String, String>()
            var failure = ""
            val failedSources = mutableSetOf<String>()
            val editionAliases = selectedEdition?.let { listOf(it.searchTitle) + additionalAliases } ?: additionalAliases
            val reference = ManualGameReference.lookup(query,selectedAppId,editionAliases.distinct())
            if (generation != activeCatalogGeneration.get()) return@execute
            if(selectedAppId != null && reference.appId != selectedAppId) {
                runOnUiThread { if(generation == activeCatalogGeneration.get()) callback(emptyList(),"Steam не подтвердил выбранную основную игру. Повторите запрос или выберите другую игру.",emptyList(),setOf("Plati","GGSEL")) }
                return@execute
            }
            val marketQuery=selectedEdition?.searchTitle ?: ManualGameReference.catalogQuery(query,reference.aliases)
            val marketQueries = selectedEdition?.let {
                ManualContentRules.marketplaceEditionSearchQueries(reference.canonical, it.searchTitle)
            } ?: listOf(marketQuery)
            val progressPages = pageCount * marketQueries.size
            for ((queryIndex, marketQuery) in marketQueries.withIndex()) {
            try {
                // Marketplace-wide search endpoint used by the Plati site ecosystem.
                // It is not the seller-specific shop_search.asp interface.
                for (page in 1..pageCount) {
                if (generation != activeCatalogGeneration.get() || Thread.currentThread().isInterrupted) return@execute
                val params = linkedMapOf(
                    "query" to marketQuery,
                    "searchmode" to "10",
                    "sortmode" to "2",
                    "pagesize" to "100",
                    "pagenum" to page.toString(),
                    "owner" to "1",
                    "details" to "1",
                    "checkhidesales" to "1",
                    "host" to "plati.market"
                ).entries.joinToString("&") { (k, v) ->
                    "${URLEncoder.encode(k, "UTF-8") }=${URLEncoder.encode(v, "UTF-8") }"
                }
                val root = getCatalogJson("https://api.digiseller.com/api/products/search2?$params")
                run {
                    val result = root.optJSONObject("result")
                    val retval = root.optInt("retval", 0)
                    if (retval != 0) error(root.optString("retdesc", "Ошибка API: $retval"))
                    val rawItems = root.optJSONObject("items")?.optJSONArray("item")
                        ?: root.optJSONArray("items")
                        ?: result?.optJSONArray("items")
                    if (rawItems == null) {
                        val total = result?.optInt("total", 0) ?: 0
                        if (total > 0) error("Каталог нашёл $total товаров, но изменил формат ответа")
                    } else {
                        for (i in 0 until rawItems.length()) {
                            val item = rawItems.optJSONObject(i) ?: continue
                            val id = item.optString("id", item.optString("id_goods", ""))
                            val name = item.optString("name", "").trim()
                            val priceText = item.optString("price_rur", "")
                                .ifBlank { item.optString("price_rub", "") }
                                .ifBlank { item.optString("price", "") }
                            val price = priceText.replace(',', '.').replace(" ", "").toDoubleOrNull()
                            if (!id.matches(Regex("[0-9]{4,12}")) || name.isBlank() || price == null || !price.isFinite() || price <= 0) continue
                            val type = (listOf(query)+reference.aliases).firstNotNullOfOrNull {ManualOfferClassifier.titleType(name,it)} ?: continue
                            if (seenIds.add(id)) {
                                found.add(Offer(id, name, price, type, OfferRules.rfStatus(name), OfferRules.editionType(name, query)))
                                sellerIds[id] = item.optString("seller_id")
                            }
                        }

                    }
                }
                if (generation == activeCatalogGeneration.get()) runOnUiThread {
                    if (generation == activeCatalogGeneration.get()) progress(queryIndex * pageCount + page, progressPages, found.size)
                }
                val totalPages = root.optJSONObject("result")?.optInt("total_pages", pageCount) ?: pageCount
                if (page >= totalPages) break
                }
            } catch (e: Exception) {
                failedSources.add("Plati")
                failure = listOf(failure, "Plati: ${e.message ?: "не удалось подключиться к каталогу"}")
                    .filter { it.isNotBlank() }.joinToString(" · ")
            }
            }
            val sorted = found.distinctBy { it.id }.sortedBy { it.price }
            val detailFailures = java.util.concurrent.atomic.AtomicInteger(0)
            val enriched = runCatching { ManualCatalogEnricher { path ->
                try {
                    CatalogApi.get(path, true)
                } catch (_: Exception) {
                    if (CatalogRetryPolicy.isSourceTransportFailure(path, requestFailed = true)) detailFailures.incrementAndGet()
                    null
                }
            }.enrich(sorted, sellerIds, reference = reference,
                active = { generation == activeCatalogGeneration.get() && !Thread.currentThread().isInterrupted },
                progress = { checked, total ->
                    if (generation == activeCatalogGeneration.get()) runOnUiThread {
                        if (generation == activeCatalogGeneration.get()) progress(progressPages, progressPages, checked)
                    }
                }) }.getOrElse {
                    detailFailures.incrementAndGet()
                    emptyList()
                }
            if (detailFailures.get() > 0) {
                failedSources.add("Plati")
                failure = listOf(failure, "Не все карточки Plati удалось проверить").filter { it.isNotBlank() }.joinToString(" · ")
            }
            var ggselFailure = ""
            if (generation == activeCatalogGeneration.get() && !Thread.currentThread().isInterrupted) {
                try {
                    found.addAll(GgselBuyerApi.find(reference,
                        active = { generation == activeCatalogGeneration.get() && !Thread.currentThread().isInterrupted },
                        editionSearchTerms = selectedEdition?.let { marketQueries }.orEmpty()))
                    if (generation == activeCatalogGeneration.get()) runOnUiThread {
                        if (generation == activeCatalogGeneration.get()) progress(progressPages, progressPages, found.size)
                    }
                } catch (e: Exception) {
                    failedSources.add("GGSEL")
                    ggselFailure = "GGSEL: ${e.message ?: "каталог временно недоступен"}"
                }
            }
            val matchedEditionOffers = (enriched + found.filter { it.marketplace == "GGSEL" })
                .filter { selectedEdition == null || ManualContentRules.matchesSelectedSteamEdition(it.name, reference.canonical, selectedEdition.name, availableEditions.map { option -> option.name }) }
            val finalOffers = matchedEditionOffers
                .distinctBy { "${it.marketplace}:${it.id}" }.sortedBy { it.price }
            if (ggselFailure.isNotBlank()) failure = listOf(failure, ggselFailure).filter { it.isNotBlank() }.joinToString(" · ")
            var aliasSuggestions = emptyList<String>()
            if (finalOffers.isEmpty() && selectedEdition == null && failure.isBlank() && generation == activeCatalogGeneration.get()) {
                val core = CatalogAliasResolver.fallbackQuery(reference.canonical)
                if (core != null && !reference.aliases.any { GameSearchResolver.normalized(it) == GameSearchResolver.normalized(core) }) {
                    runCatching {
                        val params = linkedMapOf("query" to core, "searchmode" to "10", "sortmode" to "2", "pagesize" to "100",
                            "pagenum" to "1", "owner" to "1", "details" to "1", "checkhidesales" to "1", "host" to "plati.market")
                            .entries.joinToString("&") { (k, v) -> "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}" }
                        val root = getCatalogJson("https://api.digiseller.com/api/products/search2?$params")
                        if (root.optInt("retval", 0) == 0) {
                            val result = root.optJSONObject("result")
                            val raw = root.optJSONObject("items")?.optJSONArray("item") ?: root.optJSONArray("items") ?: result?.optJSONArray("items")
                            val titles = if (raw == null) emptyList() else (0 until raw.length()).mapNotNull { raw.optJSONObject(it)?.optString("name")?.takeIf { it.isNotBlank() } }
                            aliasSuggestions = CatalogAliasResolver.suggestions(core, titles)
                        }
                    }
                }
            }
            if (generation == activeCatalogGeneration.get()) runOnUiThread {
                if (generation == activeCatalogGeneration.get()) callback(finalOffers, failure, aliasSuggestions, failedSources.toSet())
            }
        }
    }

    private fun steamGet(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 12000
        connection.readTimeout = 20000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "Igrolov Android")
        try {
            if (connection.responseCode !in 200..299) throw java.io.IOException("Steam HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { connection.disconnect() }
    }

    private fun checkTopSellerRu(game: SteamTopSeller): SteamTopSeller {
        val regional = try {
            val details = steamGet("https://store.steampowered.com/api/appdetails?appids=${game.appId}&cc=ru&l=russian")
            val entry = JSONObject(details).optJSONObject(game.appId.toString())
            if (entry?.optBoolean("success") == true) {
                SteamTopSellerParser.applyRuDetails(game, details, null)
            } else {
                // Sale-page event data contains app IDs but may not include names. Resolve one globally
                // before using the RU store search as regional evidence.
                val searchable = if (game.title.isBlank()) runCatching {
                    val global = steamGet("https://store.steampowered.com/api/appdetails?appids=${game.appId}&cc=us&l=english")
                    SteamTopSellerParser.applyGlobalMetadata(game, global)
                }.getOrDefault(game) else game
                val term = searchable.title.takeIf { it.isNotBlank() }?.let { URLEncoder.encode(it, "UTF-8") }
                val searchContainsApp = term?.let { encodedTitle -> runCatching {
                    val search = JSONObject(steamGet("https://store.steampowered.com/api/storesearch/?term=$encodedTitle&l=russian&cc=ru"))
                    val items = search.optJSONArray("items") ?: return@runCatching null
                    (0 until items.length()).any { index ->
                        val item = items.optJSONObject(index) ?: return@any false
                        item.optInt("id", item.optInt("appid", 0)) == game.appId
                    }
                }.getOrNull() }
                SteamTopSellerParser.applyRuDetails(searchable, details, searchContainsApp)
            }
        } catch (_: Exception) { game }

        // The global store supplies artwork and description for games without a Russian store page.
        // Its price and availability are deliberately ignored: only cc=ru can establish RU status.
        return if (regional.headerImageUrl.isBlank() || regional.description.isBlank() ||
            regional.features.needsRussianLocalizationFallback || regional.genres.isEmpty()) {
            runCatching {
                val global = steamGet("https://store.steampowered.com/api/appdetails?appids=${game.appId}&cc=us&l=english")
                SteamTopSellerParser.applyGlobalMetadata(regional, global)
            }.getOrDefault(regional)
        } else regional
    }

    private fun fetchSteamRankingPage(
        start: Int,
        count: Int,
        sortBy: String,
        filter: String,
        country: String,
        language: String
    ): SteamRankingPage {
        val filterPart = if (filter.isBlank()) "" else "&filter=$filter"
        val url = "https://store.steampowered.com/search/results/?" +
            "query&start=$start&count=$count&dynamic_data=&sort_by=$sortBy" +
            "$filterPart&category1=998&hidef2p=1&infinite=1&json=1&cc=$country&l=$language"
        return SteamTopSellerParser.parseSearchPage(steamGet(url), start)
    }

    private fun loadSteamReviewSummary(appId: Int): SteamReviewSummary? {
        val cache = getSharedPreferences("steam_review_cache", MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val age = now - cache.getLong("$appId:at", 0L)
        if (age in 0 until 6L * 60 * 60 * 1000) {
            cache.getString("$appId:json", null)?.let { SteamReviews.parse(it) }?.let { return it }
        }
        for (url in listOf(SteamReviews.requestUrl(appId), SteamReviews.legacyRequestUrl(appId))) {
            val raw = runCatching { steamGet(url) }.getOrNull() ?: continue
            val result = SteamReviews.parse(raw) ?: continue
            cache.edit().putString("$appId:json", raw).putLong("$appId:at", now).apply()
            return result
        }
        return null
    }

    @Composable
    private fun SteamReviewBadge(summary: SteamReviewSummary?) {
        val percent = summary?.percent ?: return
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("$percent%", style = MaterialTheme.typography.labelMedium, color = when (summary.tone) {
                SteamReviewTone.POSITIVE -> Color(0xFF168F57)
                SteamReviewTone.NEGATIVE -> Color(0xFFC13F42)
                SteamReviewTone.NEUTRAL -> MaterialTheme.colorScheme.onSurface
            })
            if (summary.tone != SteamReviewTone.NEUTRAL) {
                Icon(painterResource(R.drawable.ic_thumb_up),
                    contentDescription = if (summary.tone == SteamReviewTone.POSITIVE) "Положительные отзывы" else "Низкий рейтинг",
                    tint = if (summary.tone == SteamReviewTone.POSITIVE) Color(0xFF218739) else Color(0xFFC62828),
                    modifier = Modifier.size(14.dp).rotate(if (summary.tone == SteamReviewTone.NEGATIVE) 180f else 0f))
            }
            Text("· ${summary.compactTotal} отзывов", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }

    private fun steamRuEditionLabel(edition: SteamEditionReference.Edition): String = when (edition.steamRuAvailable) {
        true -> edition.steamRuPrice?.let { "Steam RU · $it" } ?: "Steam RU · цена не указана"
        false -> "Нет предложения в Steam RU"
        null -> "Статус Steam RU не проверен"
    }

    private fun enrichSteamGames(games: List<SteamTopSeller>): List<SteamTopSeller> {
        val now = System.currentTimeMillis()
        val cached = games.associate { game ->
            game.appId to steamLeaderCachePrefs.getString("game:${game.appId}", null)
                ?.let { SteamLeaderCache.decode(it, now) }
        }
        val fresh = games.filter { cached[it.appId] == null }
        val refreshed = if (fresh.isEmpty()) emptyMap() else {
            detailExecutor.invokeAll(fresh.map { game -> java.util.concurrent.Callable {
                checkTopSellerRu(game)
            } }).mapNotNull { future ->
                runCatching { future.get() }.getOrNull()
            }.associateBy { it.appId }
        }
        if (refreshed.isNotEmpty()) {
            val editor = steamLeaderCachePrefs.edit()
            refreshed.values
                .filter { it.isGame && it.availability != SteamRuAvailability.UNKNOWN }
                .forEach { game ->
                    editor.putString("game:${game.appId}", SteamLeaderCache.encode(game, now))
                }
            editor.apply()
        }
        return games.mapNotNull { game ->
            (cached[game.appId] ?: refreshed[game.appId])?.copy(saleEventName = game.saleEventName)
        }.filter { it.isGame && !it.isFree }
    }

    /** Reviews are fetched only for visible cards, except when review filters need complete data. */
    private fun loadReviewSummaries(games: List<SteamTopSeller>): Map<Int, SteamReviewSummary> {
        val missing = games.filter { it.reviewSummary == null && it.isGame && !it.isFree }.distinctBy { it.appId }
        if (missing.isEmpty()) return emptyMap()
        return detailExecutor.invokeAll(missing.map { game -> java.util.concurrent.Callable {
            game.appId to loadSteamReviewSummary(game.appId)
        } }).mapNotNull { runCatching { it.get() }.getOrNull() }
            .mapNotNull { (appId, summary) -> summary?.let { appId to it } }.toMap()
    }

    // Publish verified games in small batches so the first results appear before the full ranking is checked.
    private fun loadPaidTopSellers(callback: (List<SteamTopSeller>, String, Int, Boolean, Boolean) -> Unit) {
        Thread {
            val games = linkedMapOf<Int, SteamTopSeller>()
            var nextStart = 0
            var hasMore = false
            var error = ""
            try {
                for (pageIndex in 0 until 2) {
                    val page = fetchSteamRankingPage(nextStart, 50, "TopSellers", "", "us", "english")
                    val freshGames = page.games.filter { games[it.appId] == null }.take(100 - games.size)
                    freshGames.chunked(10).forEach { batch ->
                        enrichSteamGames(batch).forEach { games.putIfAbsent(it.appId, it) }
                        val snapshot = games.values.toList()
                        runOnUiThread { callback(snapshot, "", page.nextStart, page.hasMore, false) }
                    }
                    nextStart = page.nextStart
                    hasMore = page.hasMore
                    if (page.games.isEmpty() || !hasMore || games.size >= 100) break
                }
                if (games.isEmpty()) error = "Steam не вернул список. Попробуйте обновить позже."
            } catch (e: Exception) { error = e.message ?: "Ошибка загрузки Steam" }
            val snapshot = games.values.toList()
            runOnUiThread { callback(snapshot, error, nextStart, hasMore, true) }
        }.start()
    }

    // Steam Search exposes the same Popular New Releases ranking in pageable result batches.
    private fun loadPopularNewReleasePage(
        start: Int,
        count: Int,
        callback: (SteamRankingPage, String, Boolean) -> Unit
    ) {
        loadSteamRankingPage(start, count, "Released_DESC", "popularnew", "ru", "russian", callback)
    }

    private fun loadDiscountedTopSellerPage(
        start: Int,
        callback: (SteamRankingPage, String, Boolean) -> Unit
    ) {
        loadSteamRankingPage(start, 50, "TopSellers", "", "us", "english", callback)
    }

    private fun steamSalePageGet(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 12000
        connection.readTimeout = 20000
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
        connection.setRequestProperty("User-Agent", "Igrolov Android")
        connection.setRequestProperty("Referer", "https://store.steampowered.com/")
        try {
            if (connection.responseCode !in 200..299) throw java.io.IOException("Steam sale HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { connection.disconnect() }
    }

    /** Steam's current spotlight feed supplies active themed sale pages; game prices are rechecked for RU. */
    private fun loadThemedSaleGames(callback: (List<SteamTopSeller>, String, Boolean) -> Unit) {
        Thread {
            val loaded = linkedMapOf<Int, SteamTopSeller>()
            var error = ""
            try {
                val featured = steamGet("https://store.steampowered.com/api/featuredcategories/?cc=ru&l=russian")
                var events = SteamThemedSales.parseFeaturedCategories(featured)
                if (events.isEmpty()) {
                    val fallback = steamGet("https://store.steampowered.com/api/discounts_and_events_data/?cc=ru&l=russian")
                    events = SteamThemedSales.parseDiscountsAndEvents(fallback)
                }
                if (events.isEmpty()) {
                    error = "Steam сейчас не показывает активные тематические распродажи."
                } else {
                    val eventGames = events.take(5).flatMap { event ->
                        runCatching {
                            val separator = if (event.url.contains("?")) "&" else "?"
                            val localizedUrl = "${event.url}${separator}cc=ru&l=russian"
                            SteamThemedSales.parseSalePageGames(steamSalePageGet(localizedUrl), event.name)
                        }.getOrDefault(emptyList())
                    }.distinctBy { it.appId }.take(100)
                    eventGames.chunked(10).forEach { batch ->
                        val enriched = enrichSteamGames(batch)
                        enriched.filter { !it.isFree && it.discountPercent > 0 && it.saleEventName.isNotBlank() }
                            .forEach { loaded.putIfAbsent(it.appId, it) }
                        val snapshot = loaded.values.toList()
                        runOnUiThread { callback(snapshot, "", false) }
                    }
                    if (eventGames.isEmpty()) {
                        error = "Steam нашёл тематическую распродажу, но не вернул список игр."
                    } else if (loaded.isEmpty()) {
                        error = "В найденных распродажах нет игр со скидкой Steam RU."
                    }
                }
            } catch (e: Exception) {
                error = e.message ?: "Не удалось загрузить тематические распродажи Steam."
            }
            runOnUiThread { callback(loaded.values.toList(), error, true) }
        }.start()
    }

    private fun loadSteamRankingPage(
        start: Int,
        count: Int,
        sortBy: String,
        filter: String,
        country: String,
        language: String,
        callback: (SteamRankingPage, String, Boolean) -> Unit
    ) {
        Thread {
            var error = ""
            var page = SteamRankingPage(emptyList(), start, start)
            try {
                page = fetchSteamRankingPage(start, count, sortBy, filter, country, language)
                if (page.games.isEmpty() && start == 0) error = "Steam не вернул список. Попробуйте обновить позже."
            } catch (e: Exception) { error = e.message ?: "Ошибка загрузки Steam" }

            val checkedGames = mutableListOf<SteamTopSeller>()
            page.games.chunked(10).forEach { batch ->
                enrichSteamGames(batch).forEach { checkedGames.add(it) }
                val partialPage = page.copy(games = checkedGames.toList())
                runOnUiThread { callback(partialPage, error, false) }
            }
            val result = page.copy(games = checkedGames.toList())
            runOnUiThread { callback(result, error, true) }
        }.start()
    }

    private fun steamArtworkJson(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 7000
        connection.readTimeout = 9000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "Igrolov Android")
        try {
            if (connection.responseCode !in 200..299) throw java.io.IOException("Steam HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (error: Exception) {
            SteamArtworkDiagnostics.record(null, url, error)
            throw error
        } finally { connection.disconnect() }
    }

    private fun steamArtworkFallbackUrls(appId: Int, portrait: Boolean): List<String> {
        val urls = mutableListOf<String>()
        if (portrait) {
            // Resolve Steam's current hashed 600x900 assets before trying guessed legacy paths.
            runCatching {
                val raw = steamArtworkJson(SteamArtworkPresentation.storeBrowseAssetsUrl(appId))
                urls += SteamArtworkPresentation.libraryCapsuleUrls(raw, appId)
            }
            // Community posters are read from the public game page, without a key or SGDB API.
            urls += SteamArtworkPresentation.directFallbackUrls(appId, portrait = true)
        } else {
            urls += SteamArtworkPresentation.directFallbackUrls(appId, portrait = false)
            runCatching {
                val raw = steamArtworkJson("https://store.steampowered.com/api/appdetails?appids=$appId&cc=us&l=english")
                SteamTopSellerParser.applyGlobalMetadata(SteamTopSeller(appId, ""), raw).headerImageUrl
                    .takeIf { it.isNotBlank() }?.let(urls::add)
            }
        }
        return SteamArtworkPresentation.filterForAspect(urls, portrait)
    }

    @Composable
    private fun SteamArtwork(url: String, modifier: Modifier = Modifier, appId: Int? = null) {
        val context = LocalContext.current
        val view = LocalView.current
        val scope = rememberCoroutineScope()
        val artworkPrefs = remember(context) { context.getSharedPreferences("steam_artwork_urls", MODE_PRIVATE) }
        val requestedPortrait = remember(url) { SteamArtworkPresentation.isPortrait(url) }
        val artworkCacheKey = remember(appId, requestedPortrait) {
            appId?.let { SteamArtworkPresentation.cacheKey(it, requestedPortrait) }
        }
        LaunchedEffect(artworkCacheKey, requestedPortrait) {
            artworkCacheKey?.let { key ->
                val cached = artworkPrefs.getString(key, null)
                if (!cached.isNullOrBlank() && !SteamArtworkPresentation.matchesAspect(cached, requestedPortrait)) {
                    artworkPrefs.edit().remove(key).apply()
                }
            }
        }
        val initialCandidates = remember(url, appId, artworkCacheKey) {
            val cachedRequested = artworkCacheKey?.let { artworkPrefs.getString(it, null) }
                ?.takeIf { it.isNotBlank() && SteamArtworkPresentation.matchesAspect(it, requestedPortrait) }
            listOfNotNull(cachedRequested, url.takeIf { SteamArtworkPresentation.matchesAspect(it, requestedPortrait) }).distinct()
        }
        var candidates by remember(url, appId) { mutableStateOf(initialCandidates) }
        var candidateIndex by remember(url, appId) { mutableIntStateOf(0) }
        var shouldLoad by remember(url, appId) { mutableStateOf(true) }
        var imageLoaded by remember(url, appId) { mutableStateOf(false) }
        var imageFailed by remember(url, appId) { mutableStateOf(false) }
        var communityLoaded by remember(url, appId) { mutableStateOf(false) }
        var retryCount by remember(url, appId) { mutableIntStateOf(0) }
        var imageUrl by remember(url, appId) { mutableStateOf(initialCandidates.firstOrNull().orEmpty()) }
        var fallbackCandidatesLoaded by remember(url, appId) { mutableStateOf(false) }
        val displayedPortrait = remember(imageUrl) { SteamArtworkPresentation.isPortrait(imageUrl) }
        val displayedCacheKey = remember(appId, displayedPortrait) {
            appId?.let { SteamArtworkPresentation.cacheKey(it, displayedPortrait) }
        }

        Box(
            modifier
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text("🎮", style = MaterialTheme.typography.bodySmall)
            if (shouldLoad && imageUrl.isNotBlank()) {
                val imageReferer = if (imageUrl.contains("steamgriddb.com", ignoreCase = true)) {
                    "https://www.steamgriddb.com/"
                } else {
                    "https://store.steampowered.com/"
                }
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(imageUrl)
                        .httpHeaders(NetworkHeaders.Builder()
                            .set("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36 Igrolov/1.5.7")
                            .set("Referer", imageReferer)
                            .set("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
                            .build())
                        .crossfade(true)
                        .build(),
                    contentDescription = if (imageLoaded) "Обложка Steam $appId загружена" else "Обложка Steam $appId загружается",
                    contentScale = if (displayedPortrait) androidx.compose.ui.layout.ContentScale.Crop
                        else androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = modifier,
                    onSuccess = {
                        android.util.Log.i("SteamArtwork", "decoded app=$appId url=$imageUrl")
                        SteamArtworkDiagnostics.record(appId, imageUrl)
                        imageFailed = false
                        imageLoaded = true
                        displayedCacheKey?.let { artworkPrefs.edit().putString(it, imageUrl).apply() }
                    },
                    onError = {
                        android.util.Log.w("SteamArtwork", "failed app=$appId url=$imageUrl", it.result.throwable)
                        SteamArtworkDiagnostics.record(appId, imageUrl, it.result.throwable)
                        imageFailed = true
                        imageLoaded = false
                        displayedCacheKey?.let { artworkPrefs.edit().remove(it).apply() }
                        val nextIndex = candidateIndex + 1
                        if (nextIndex < candidates.size) {
                            candidateIndex = nextIndex
                            imageUrl = candidates[nextIndex]
                        } else if (!fallbackCandidatesLoaded && appId != null && appId > 0) {
                            fallbackCandidatesLoaded = true
                            scope.launch {
                                val additional = withContext(Dispatchers.IO) {
                                    steamArtworkFallbackUrls(appId, requestedPortrait)
                                }
                                val merged = (candidates + additional).distinct()
                                val nextUrl = merged.drop(candidateIndex + 1).firstOrNull()
                                candidates = merged
                                if (nextUrl != null) {
                                    candidateIndex = merged.indexOf(nextUrl)
                                    imageUrl = nextUrl
                                } else if (!communityLoaded && requestedPortrait) {
                                    // Steam may return only missing/obsolete URLs (or none).
                                    // Continue to the community portrait source instead of
                                    // leaving the card stuck after its first 404.
                                    communityLoaded = true
                                    val community = withContext(Dispatchers.IO) {
                                        SteamGridDbArtwork.fetchPortraitUrls(this@MainActivity, appId)
                                    }
                                    val withCommunity = (merged + community).distinct()
                                    val communityUrl = withCommunity.drop(candidateIndex + 1).firstOrNull()
                                    candidates = withCommunity
                                    if (communityUrl != null) {
                                        candidateIndex = withCommunity.indexOf(communityUrl)
                                        imageUrl = communityUrl
                                    } else if (retryCount < 2 && withCommunity.isNotEmpty()) {
                                        retryCount++
                                        kotlinx.coroutines.delay(2000L * retryCount)
                                        fallbackCandidatesLoaded = false
                                        communityLoaded = false
                                        candidateIndex = 0
                                        imageUrl = withCommunity.first()
                                    }
                                }
                            }
                        } else if (!communityLoaded && requestedPortrait && appId != null && appId > 0) {
                            communityLoaded = true
                            scope.launch {
                                val additional = withContext(Dispatchers.IO) {
                                    SteamGridDbArtwork.fetchPortraitUrls(this@MainActivity, appId)
                                }
                                val merged = (candidates + additional).distinct()
                                val nextUrl = merged.drop(candidateIndex + 1).firstOrNull()
                                candidates = merged
                                if (nextUrl != null) {
                                    candidateIndex = merged.indexOf(nextUrl)
                                    imageUrl = nextUrl
                                } else if (retryCount < 2) {
                                    retryCount++
                                    kotlinx.coroutines.delay(2000L * retryCount)
                                    fallbackCandidatesLoaded = false
                                    communityLoaded = false
                                    candidateIndex = 0
                                    imageUrl = candidates.first()
                                }
                            }
                        } else if (retryCount < 2) {
                            retryCount++
                            scope.launch {
                                kotlinx.coroutines.delay(2000L * retryCount)
                                fallbackCandidatesLoaded = false
                                communityLoaded = false
                                candidateIndex = 0
                                imageUrl = candidates.first()
                            }
                        }
                    }
                )
            }
        }
    }

    private fun comparisonAliases(appId: Int, supplied: List<String> = emptyList()): List<String> =
        (supplied + ManualSearchSettings.games(this).firstOrNull { it.appId == appId }?.aliases.orEmpty()).distinct()

    @Composable
    private fun CachedPriceLines(cached: CachedGamePrices?, legacyPlati: Double? = null, legacyGgsel: Double? = null) {
        val date = remember { java.text.SimpleDateFormat("dd.MM HH:mm", Locale("ru", "RU")) }
        listOf(Triple("Plati", cached?.plati, legacyPlati), Triple("GGSEL", cached?.ggsel, legacyGgsel)).forEach { (name, check, legacy) ->
            val source = check ?: SourcePriceCheck()
            val price = source.price ?: legacy.takeIf { source.checkedAt == 0L }
            if (source.hasData || price != null) {
                val value = price?.let { "$name от " + String.format(Locale("ru", "RU"), "%.0f ₽", it) }
                    ?: if (source.checkedAt > 0) "$name: предложений нет" else "$name: нет подтверждённой цены"
                val time = if (source.checkedAt > 0) "проверено " + date.format(java.util.Date(source.checkedAt))
                    else if (price != null) "сохранено ранее · время неизвестно" else "проверка не завершена"
                Text("$value · $time" + if (source.failedAt > 0) " · цена не обновлена" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (source.failedAt > 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary)
            }
        }
    }

    @Composable
    private fun DealPriceStrip(
        steamPrice: String,
        discountPercent: Int,
        platiPrice: Double?,
        ggselPrice: Double?,
        steamAvailable: Boolean? = null
    ) {
        val cheapest = listOfNotNull(platiPrice?.let { "Plati" to it }, ggselPrice?.let { "GGSEL" to it })
            .filter { it.second.isFinite() && it.second > 0.0 }.minByOrNull { it.second }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Top) {
            DealPriceCell("Steam RU", if (steamAvailable == false) "Нет в РФ" else steamPrice.replace(" руб.", " ₽").replace(" руб", " ₽").ifBlank { "—" },
                Modifier.weight(1f), false, if (discountPercent > 0) "−$discountPercent%" else null)
            DealPriceCell("Plati", platiPrice?.let { String.format(Locale("ru", "RU"), "%.0f ₽", it) } ?: "—",
                Modifier.weight(1f), cheapest?.first == "Plati", if (cheapest?.first == "Plati") "Лучшая цена" else null)
            DealPriceCell("GGSEL", ggselPrice?.let { String.format(Locale("ru", "RU"), "%.0f ₽", it) } ?: "—",
                Modifier.weight(1f), cheapest?.first == "GGSEL", if (cheapest?.first == "GGSEL") "Лучшая цена" else null)
        }
    }

    @Composable
    private fun PriceChecksSummary(cached: CachedGamePrices?) {
        if (cached == null) return
        val rows = listOf("Plati" to cached.plati, "GGSEL" to cached.ggsel)
            .filter { (_, source) -> source.hasData }
            .map { (name, source) ->
                when {
                    source.failedAt > source.checkedAt -> "$name: ошибка обновления"
                    source.checkedAt > 0L -> "$name: проверено " +
                        java.text.SimpleDateFormat("dd.MM HH:mm", Locale("ru", "RU")).format(java.util.Date(source.checkedAt))
                    else -> "$name: сохранено ранее"
                }
            }
        if (rows.isNotEmpty()) Text(rows.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }

    @Composable
    private fun DealPriceCell(label: String, price: String, modifier: Modifier, highlighted: Boolean, note: String? = null) {
        Surface(modifier = modifier.heightIn(min = 65.dp),
            color = if (highlighted) Color(0xFFE0F4E9) else Color.Transparent,
            shape = MaterialTheme.shapes.medium,
            border = if (highlighted) BorderStroke(1.dp, Color(0xFFB8E1C9)) else null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (note == "Лучшая цена") Surface(color = Color(0xFF168F57), shape = MaterialTheme.shapes.small) {
                    Text("ЛУЧШАЯ", Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = Color.White, maxLines = 1)
                }
                Text(label, style = MaterialTheme.typography.labelSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), maxLines = 1, color = Color(0xFF17212D))
                Text(price, style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (note != null && note != "Лучшая цена") Surface(color = Color(0xFFFFE4AA), shape = MaterialTheme.shapes.small) {
                    Text(note, Modifier.padding(horizontal = 4.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                        color = Color(0xFF17212D), maxLines = 1)
                }
            }
        }
    }

    @Composable
    private fun PriceBatchStatus(batch: PriceCheckBatch, onStop: () -> Unit) {
        if (batch.total == 0) return
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(when {
                batch.running -> "Проверено ${batch.completed} / ${batch.total} · ${batch.current?.title}"
                batch.cancelled -> "Остановлено: ${batch.completed} / ${batch.total}"
                else -> "Готово ${batch.completed} / ${batch.total}"
            }, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            if (batch.running) TextButton(onClick = onStop) { Text("Остановить") }
        }
        if (batch.running) LinearProgressIndicator(progress = { batch.completed.toFloat() / batch.total },
            modifier = Modifier.fillMaxWidth())
    }

    @Composable
    private fun SteamFeatureBadges(features: SteamGameFeatures) {
        val localization = when (features.russianLocalization) {
            SteamRussianLocalization.SUPPORTED -> "RU"
            SteamRussianLocalization.NOT_SUPPORTED -> "Без RU"
            SteamRussianLocalization.UNKNOWN -> "Язык ?"
        }
        val localizationColor = when (features.russianLocalization) {
            SteamRussianLocalization.SUPPORTED -> MaterialTheme.colorScheme.primaryContainer
            SteamRussianLocalization.NOT_SUPPORTED -> MaterialTheme.colorScheme.surfaceVariant
            SteamRussianLocalization.UNKNOWN -> MaterialTheme.colorScheme.tertiaryContainer
        }
        val controller = when (features.controllerSupport) {
            SteamControllerSupport.FULL -> "Геймпад ✓"
            SteamControllerSupport.PARTIAL -> "Частично"
            SteamControllerSupport.NONE -> "Без геймпада"
            SteamControllerSupport.UNKNOWN -> "Геймпад ?"
        }
        val controllerColor = when (features.controllerSupport) {
            SteamControllerSupport.FULL -> MaterialTheme.colorScheme.primaryContainer
            SteamControllerSupport.PARTIAL -> MaterialTheme.colorScheme.tertiaryContainer
            SteamControllerSupport.NONE -> MaterialTheme.colorScheme.surfaceVariant
            SteamControllerSupport.UNKNOWN -> MaterialTheme.colorScheme.tertiaryContainer
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = localizationColor, shape = MaterialTheme.shapes.small) {
                Text(localization, Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
            Surface(color = controllerColor, shape = MaterialTheme.shapes.small) {
                Text(controller, Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }

    @Composable
    private fun SteamGenreLine(genres: List<String>) {
        val label = SteamTopSellerParser.formatGenreLine(genres)
        if (label.isNotBlank()) {
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    @Composable
    private fun SteamLeaderCard(
        index: Int, game: SteamTopSeller, marketplacePrices: LeaderOfferPrices?,
        searchEnabled: Boolean,
        onOpenSearch: () -> Unit, onDetails: () -> Unit,
        onSearchPrice: () -> Unit, onMonitor: () -> Unit,
        cachedPrices: CachedGamePrices?
    ) {
        var reviewSummary by remember(game.appId) { mutableStateOf(game.reviewSummary) }
        LaunchedEffect(game.appId, game.reviewSummary) {
            if (game.reviewSummary != null) reviewSummary = game.reviewSummary
            else if (reviewSummary == null && game.isGame && !game.isFree) {
                reviewSummary = withContext(Dispatchers.IO) { loadSteamReviewSummary(game.appId) }
            }
        }
        val platiPrice = marketplacePrices?.platiMinimum ?: cachedPrices?.plati?.price
        val ggselPrice = marketplacePrices?.ggselMinimum ?: cachedPrices?.ggsel?.price
        OutlinedCard(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, Color(0xFFDCE2E8)),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            colors = CardDefaults.outlinedCardColors(containerColor = Color.White)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onOpenSearch, modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF111820))) {
                    Text(game.title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (game.saleEventName.isNotBlank()) {
                    Text("Фест Steam · ${game.saleEventName}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SteamArtwork("https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/${game.appId}/library_600x900.jpg",
                        Modifier.size(76.dp, 114.dp), appId = game.appId)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        SteamReviewBadge(reviewSummary)
                        SteamGenreLine(game.genres)
                        SteamFeatureBadges(game.features)
                    }
                }
                DealPriceStrip(
                    steamPrice = game.steamPrice.ifBlank { "—" }, discountPercent = game.discountPercent,
                    platiPrice = platiPrice, ggselPrice = ggselPrice,
                    steamAvailable = when (game.availability) {
                        SteamRuAvailability.AVAILABLE -> true
                        SteamRuAvailability.UNAVAILABLE -> false
                        SteamRuAvailability.UNKNOWN -> null
                    }
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = onSearchPrice, enabled = searchEnabled && marketplacePrices?.checking != true,
                        modifier = Modifier.weight(1f).height(36.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), border = BorderStroke(1.dp, Color(0xFFD2D9E1))) {
                        Text(if (marketplacePrices?.checking == true) "Ищем цены…" else "Цены",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), maxLines = 1, color = Color(0xFF17212D))
                    }
                    OutlinedButton(onClick = onMonitor, modifier = Modifier.weight(1f).height(36.dp),
                        contentPadding = PaddingValues(horizontal = 3.dp, vertical = 0.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), border = BorderStroke(1.dp, Color(0xFFD2D9E1))) {
                        Text("Следить",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                            color = Color(0xFF17212D), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(onClick = onDetails, modifier = Modifier.weight(1f).height(36.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        border = BorderStroke(1.dp, Color(0xFFD2D9E1)), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF17212D))) {
                        Text("Подробнее", style = MaterialTheme.typography.labelMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                            color = Color(0xFF17212D), maxLines = 1)
                    }
                }
                if (marketplacePrices?.checking == true || (cachedPrices == null && !marketplacePrices?.message.isNullOrBlank())) {
                    Text(if (marketplacePrices?.checking == true) "Ищем цены…" else "Не удалось обновить цены.", style = MaterialTheme.typography.labelSmall,
                        color = if (marketplacePrices?.checking == true) Color(0xFF416C9A) else MaterialTheme.colorScheme.error,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    @Composable
    private fun GameMonitoringDialog(
        game: GameSearchResolver.Candidate,
        onDismiss: () -> Unit,
        onSaved: (String) -> Unit
    ) {
        var pendingWatchPrice by remember(game.appId) { mutableStateOf("") }
        var pendingWatchKind by remember(game.appId) { mutableStateOf("Все") }
        var pendingEditions by remember(game.appId) { mutableStateOf<List<SteamEditionReference.Edition>>(emptyList()) }
        var pendingEdition by remember(game.appId) { mutableStateOf<SteamEditionReference.Edition?>(null) }
        var editionMenu by remember(game.appId) { mutableStateOf(false) }
        var editionsLoading by remember(game.appId) { mutableStateOf(true) }
        var pendingWatchSaving by remember(game.appId) { mutableStateOf(false) }
        var pendingWatchError by remember(game.appId) { mutableStateOf("") }
        LaunchedEffect(game.appId) {
            editionExecutor.execute {
                val found = runCatching { SteamEditionReference.discover(game.appId) }.getOrDefault(emptyList())
                runOnUiThread {
                    pendingEditions = found
                    pendingEdition = found.firstOrNull { it.isBase }
                    editionsLoading = false
                }
            }
        }
        AlertDialog(
            onDismissRequest = { if (!pendingWatchSaving) onDismiss() },
            title = { Text("В мониторинг") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(game.name, style = MaterialTheme.typography.titleSmall)
                    Box {
                        OutlinedButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = { editionMenu = true }, enabled = !pendingWatchSaving) {
                            Text(pendingEdition?.name ?: "Основная игра", maxLines = 1)
                        }
                        DropdownMenu(expanded = editionMenu, onDismissRequest = { editionMenu = false }) {
                            val baseEdition = pendingEditions.firstOrNull { it.isBase }
                            if (baseEdition != null) {
                                DropdownMenuItem(text = { Column {
                                    Text("Основная игра")
                                    Text(steamRuEditionLabel(baseEdition), style = MaterialTheme.typography.labelSmall)
                                } }, onClick = {
                                    pendingEdition = baseEdition; editionMenu = false
                                })
                            } else {
                                DropdownMenuItem(text = { Text("Основная игра") }, onClick = {
                                    pendingEdition = null; editionMenu = false
                                })
                            }
                            pendingEditions.filterNot { it.isBase }.forEach { edition ->
                                DropdownMenuItem(text = { Column {
                                    Text(edition.name)
                                    Text(steamRuEditionLabel(edition), style = MaterialTheme.typography.labelSmall)
                                } }, onClick = {
                                    pendingEdition = edition; editionMenu = false
                                })
                            }
                        }
                    }
                    pendingEdition?.let { Text(steamRuEditionLabel(it), style = MaterialTheme.typography.bodySmall) }
                    if (editionsLoading) Text("Загружаем издания Steam…", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(pendingWatchPrice, { pendingWatchPrice = it; pendingWatchError = "" },
                        label = { Text("Желаемая цена, ₽") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), enabled = !pendingWatchSaving)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Все", "Gift", "Key").forEach { kind ->
                            FilterChip(colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFFFFE2A6), selectedLabelColor = Color(0xFF17212D)), selected = pendingWatchKind == kind, enabled = !pendingWatchSaving,
                                onClick = { pendingWatchKind = kind }, label = { Text(kind) })
                        }
                    }
                    Text("Plati и GGSEL · только предложения для РФ", style = MaterialTheme.typography.bodySmall)
                    if (pendingWatchError.isNotBlank()) Text(pendingWatchError,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                OutlinedButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), enabled = !pendingWatchSaving, onClick = {
                    val amount = pendingWatchPrice.trim().replace(',', '.').toDoubleOrNull()
                    if (amount == null || !amount.isFinite() || amount <= 0) {
                        pendingWatchError = "Введите положительную цену"
                    } else {
                        pendingWatchSaving = true
                        val kind = pendingWatchKind
                        val selectedEdition = pendingEdition
                        ioExecutor.execute {
                            val result = runCatching {
                                WatchStore.add(this@MainActivity, game.name, kind, amount, true, game.appId,
                                    selectedEdition?.name, selectedEdition?.packageId)
                                WatchStore.read(this@MainActivity).toString()
                            }
                            runOnUiThread {
                                pendingWatchSaving = false
                                result.onSuccess {
                                    onSaved(it)
                                    WatchScheduler.start(this@MainActivity)
                                    WatchScheduler.checkNow(this@MainActivity)
                                    if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }.onFailure {
                                    pendingWatchError = "Не удалось добавить игру. Повторите попытку."
                                }
                            }
                        }
                    }
                }) { Text(if (pendingWatchSaving) "Добавляем…" else "Добавить") }
            },
            dismissButton = {
                TextButton(enabled = !pendingWatchSaving, onClick = onDismiss) { Text("Отмена") }
            }
        )
    }

    @Composable
    private fun ScreenSection(content: @Composable () -> Unit) {
        content()
    }

    private class CatalogueUiState(prefs: android.content.SharedPreferences, context: android.content.Context) {
        var wishlistProfile by mutableStateOf(if (prefs.getString("wishlist_profiles", null).isNullOrBlank())
            prefs.getString("wishlist_profile", "") ?: "" else "")
        var wishlistProfiles by mutableStateOf(SteamWishlist.decodeProfiles(prefs.getString("wishlist_profiles", null)).ifEmpty {
            val legacyId = SteamWishlist.profileId(prefs.getString("wishlist_profile", "") ?: "")
            if (legacyId == null) emptyList() else listOf(WishlistProfile(
                legacyId, "Steam " + legacyId, prefs.getString("wishlist_profile", legacyId) ?: legacyId,
                SteamWishlist.decode(prefs.getString("wishlist_games", null)), prefs.getLong("wishlist_imported_at", 0L)))
        })
        var selectedWishlistId by mutableStateOf(prefs.getString("wishlist_selected_profile", null)
            ?: wishlistProfiles.firstOrNull()?.steamId64)
        var wishlistGames by mutableStateOf(wishlistProfiles.firstOrNull { it.steamId64 == selectedWishlistId }?.games
            ?: SteamWishlist.decode(prefs.getString("wishlist_games", null)))
        var wishlistBusy by mutableStateOf(false)
        var wishlistGenresLoading by mutableStateOf(false)
        var wishlistMessage by mutableStateOf("")
        var wishlistImportedAt by mutableLongStateOf(prefs.getLong("wishlist_imported_at", 0L))
        var wishlistVisibleLimit by mutableIntStateOf(30)
        var wishlistSearchingKeys by mutableStateOf<Set<String>>(emptySet())
        var wishlistSearchStatuses by mutableStateOf<Map<String, String>>(emptyMap())
        var priceCache by mutableStateOf(OfferPriceCacheStore.readAll(context))
        var priceBatch by mutableStateOf(PriceCheckBatch())
    }

    @Composable
    private fun NavigationHeader(activeTab: Int, settingsOpen: Boolean,
                                 onSettings: () -> Unit, onTab: (Int) -> Unit) {
        Row(Modifier.fillMaxWidth().background(Color.White).padding(start = 12.dp, end = 6.dp, top = 3.dp, bottom = 0.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(painterResource(R.drawable.ic_gamepad), contentDescription = null,
                    tint = Color(0xFF17212D), modifier = Modifier.size(32.dp))
                Column(verticalArrangement = Arrangement.spacedBy((-3).dp)) {
                    Text("Игролов", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold),
                        color = Color(0xFF17212D), maxLines = 1)
                    Text("Сравнивай. Выбирай. Играй выгодно.", style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF566273), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = { onTab(0) }, modifier = Modifier.size(38.dp)) {
                Icon(painterResource(R.drawable.ic_search), contentDescription = "Поиск", tint = Color(0xFF17212D), modifier = Modifier.size(22.dp))
            }
            IconButton(onClick = onSettings, modifier = Modifier.size(38.dp)) {
                Icon(painterResource(R.drawable.ic_settings), contentDescription = if (settingsOpen) "Закрыть настройки" else "Настройки",
                    tint = Color(0xFF17212D), modifier = Modifier.size(22.dp))
            }
        }
        val tabs = listOf("Поиск", "Товары", "Мониторинг", "Желания", "Лидеры")
        val icons = listOf(R.drawable.ic_search, R.drawable.ic_grid, R.drawable.ic_pulse, R.drawable.ic_heart, R.drawable.ic_trophy)
        Row(Modifier.fillMaxWidth().height(54.dp).background(Color.White), verticalAlignment = Alignment.CenterVertically) {
            tabs.forEachIndexed { index, title ->
                val selected = activeTab == index
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight()
                        .selectable(selected = selected, onClick = { onTab(index) }, role = Role.Tab),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top
                ) {
                    Spacer(Modifier.height(4.dp))
                    Icon(painterResource(icons[index]), contentDescription = null,
                        tint = if (selected) Color(0xFF17212D) else Color(0xFF596575), modifier = Modifier.size(22.dp))
                    Spacer(Modifier.height(1.dp))
                    Text(title, maxLines = 1, softWrap = false, fontSize = 11.sp,
                        fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                        color = if (selected) Color(0xFF17212D) else Color(0xFF596575))
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.fillMaxWidth(0.78f).height(3.dp)
                        .background(if (selected) Color(0xFFF2B94B) else Color.Transparent,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)))
                }
            }
        }
    }

    @Composable
    private fun Screen() {
        val keyboardController = LocalSoftwareKeyboardController.current
        val focusManager = LocalFocusManager.current
        val savedFilters = remember {ManualSearchSettings.read(this@MainActivity)}
        var gameSuggestions by remember {mutableStateOf<List<GameSearchResolver.Candidate>>(emptyList())}
        var selectedGame by remember {mutableStateOf<GameSearchResolver.Candidate?>(null)}
        var steamEditions by remember { mutableStateOf<List<SteamEditionReference.Edition>>(emptyList()) }
        var selectedSteamEdition by remember { mutableStateOf<SteamEditionReference.Edition?>(null) }
        var editionLoading by remember { mutableStateOf(false) }
        var editionMenuExpanded by remember { mutableStateOf(false) }
        var suggestionBusy by remember {mutableStateOf(false)}
        var maxPrice by remember {mutableStateOf(savedFilters.maxPrice)}
        var confirmedRf by remember {mutableStateOf(savedFilters.confirmedRf)}
        
        var entry by remember { mutableStateOf("") }
        var query by remember { mutableStateOf("") }
        var offers by remember { mutableStateOf<List<Offer>>(emptyList()) }
        var steamGame by remember { mutableStateOf<SteamGameReference.Game?>(null) }
        var rulesRevision by remember { mutableIntStateOf(0) }
        var directoryGame by remember { mutableStateOf("") }
        var directoryInput by remember { mutableStateOf("") }
        var directoryCanonical by remember { mutableStateOf("") }
        var directoryField by remember { mutableStateOf("blocked") }
        var directoryGameInfo by remember { mutableStateOf<SteamGameReference.Game?>(null) }
        var directoryBusy by remember { mutableStateOf(false) }
        var directoryMessage by remember { mutableStateOf("Выберите игру для редактирования") }
        var directorySuggestions by remember { mutableStateOf<List<SteamGameReference.Suggestion>>(emptyList()) }
        var directorySelectedAppId by remember { mutableStateOf<Int?>(null) }
        var searchMessage by remember { mutableStateOf("Введите игру и нажмите «Искать», чтобы увидеть цены.") }
        var pendingAliasGame by remember { mutableStateOf<GameSearchResolver.Candidate?>(null) }
        var pendingAliasSuggestions by remember { mutableStateOf<List<String>>(emptyList()) }
        var pendingAliasProfileId by remember { mutableStateOf<String?>(null) }
        var pendingAliasHiddenWishlist by remember { mutableStateOf(false) }
        var searching by remember { mutableStateOf(false) }
        var searchGeneration by remember { mutableIntStateOf(0) }
        var visibleOfferLimit by remember { mutableIntStateOf(30) }
        var searchPage by remember { mutableIntStateOf(0) }
        var catalogLimit by remember { mutableIntStateOf(CatalogPageSettings.get(this@MainActivity)) }
        val searchTotalPages = catalogLimit / 100
        var showDirectory by remember { mutableStateOf(false) }
        var searchFound by remember { mutableIntStateOf(0) }
        var monitorDone by remember { mutableIntStateOf(0) }
        var monitorTotal by remember { mutableIntStateOf(0) }
        var monitorCurrent by remember { mutableStateOf("") }
        var selectedType by remember { mutableStateOf(savedFilters.type) }
        var selectedMarketplace by remember { mutableStateOf("Все") }
        val rfOnly = true
        var editionFilter by remember { mutableStateOf(savedFilters.edition) }
        val manualFilters=ManualSearchFilters(selectedType,editionFilter,maxPrice,confirmedRf)
        val comparisonSavedAliases=ManualSearchSettings.games(this@MainActivity).associate { it.appId to it.aliases }
        LaunchedEffect(selectedType,editionFilter,maxPrice,confirmedRf) {ManualSearchSettings.save(this@MainActivity,manualFilters)}
        LaunchedEffect(query) {
            val requested=query.trim()
            val generation=suggestionGeneration.incrementAndGet()
            gameSuggestions=emptyList()
            suggestionBusy=false
            if(requested.length>=3 && selectedGame==null) {
                suggestionBusy=true
                delay(450)
                val local=ManualSearchSettings.games(this@MainActivity)
                val exact=GameSearchResolver.exact(requested,local+GameSearchResolver.seeds)
                if(exact!=null) {
                    gameSuggestions=listOf(exact)
                    suggestionBusy=false
                } else {
                    suggestionExecutor.execute {
                        val suggestions=GameSearchResolver.suggest(requested,local,{suggestionGeneration.get()==generation}) { term ->
                            SteamGameReference.searchGames(term).map {GameSearchResolver.Candidate(it.id,it.name)}
                        }
                        runOnUiThread {if(suggestionGeneration.get()==generation) {gameSuggestions=suggestions;suggestionBusy=false}}
                    }
                }
            }
        }
        var items by remember { mutableStateOf(loadIds().map { cachedTrackedFromPreferences(it) }) }
        var trackedVisibleLimit by remember { mutableIntStateOf(30) }
        var message by remember { mutableStateOf("Вставьте ссылку на товар Plati или его ID") }
        var busy by remember { mutableStateOf(false) }
        var watchGame by remember { mutableStateOf("") }
        var watchSelection by remember {mutableStateOf<GameSearchResolver.Candidate?>(null)}
        var watchSuggestions by remember {mutableStateOf<List<GameSearchResolver.Candidate>>(emptyList())}
        var watchSuggesting by remember {mutableStateOf(false)}
        LaunchedEffect(watchGame) {
            val generation=watchSuggestionGeneration.incrementAndGet()
            watchSuggestions=emptyList();watchSuggesting=false
            if(watchGame.trim().length>=3 && watchSelection==null) {
                watchSuggesting=true
                delay(450)
                val requested=watchGame.trim()
                val local=ManualSearchSettings.games(this@MainActivity)
                suggestionExecutor.execute {
                    val result=GameSearchResolver.suggest(requested,local,{watchSuggestionGeneration.get()==generation}) {term->
                        SteamGameReference.searchGames(term).map {GameSearchResolver.Candidate(it.id,it.name)}
                    }
                    runOnUiThread {if(watchSuggestionGeneration.get()==generation) {watchSuggestions=result;watchSuggesting=false}}
                }
            }
        }
        var targetPrice by remember { mutableStateOf("") }
        var ignoredIds by remember { mutableStateOf(IgnoredStore.ids(this@MainActivity)) }
        var showIgnored by remember { mutableStateOf(false) }
        var checkingNow by remember { mutableStateOf(false) }
        var watchType by remember { mutableStateOf("Все") }
        val watchRf = true
        val searchPrefs = remember { getSharedPreferences("search_history", MODE_PRIVATE) }
        var queryHistory by remember {
            mutableStateOf(try {
                val saved = JSONArray(searchPrefs.getString("queries", "[]"))
                (0 until saved.length()).map { saved.optString(it) }.filter { it.isNotBlank() }
            } catch (_: Exception) { emptyList<String>() })
        }
        var watches by remember { mutableStateOf("[]") }
        var alertLog by remember { mutableStateOf("[]") }
        var watchListFilter by remember { mutableStateOf(WatchListPresentation.Filter.ALL) }
        var watchListSort by remember { mutableStateOf(WatchListPresentation.Sort.ATTENTION) }
        val catalogueUi = remember { CatalogueUiState(prefs, this@MainActivity) }
        var showAlertLog by remember { mutableStateOf(true) }
        var watchMessage by remember { mutableStateOf("Укажите название игры и цену в рублях") }
        fun persistWishlistProfiles(profiles: List<WishlistProfile>) {
            catalogueUi.wishlistProfiles = profiles.distinctBy { it.steamId64 }.take(20)
            prefs.edit().putString("wishlist_profiles", SteamWishlist.encodeProfiles(catalogueUi.wishlistProfiles)).apply()
        }
        fun reloadWatches() {
            ioExecutor.execute {
                val latestWatches=WatchStore.read(this@MainActivity).toString()
                val latestAlerts=AlertJournal.read(this@MainActivity).toString()
                runOnUiThread { watches=latestWatches; alertLog=latestAlerts; catalogueUi.priceCache=OfferPriceCacheStore.readAll(this@MainActivity) }
            }
        }
        LaunchedEffect(Unit) {
            reloadWatches()
            val ids=loadIds()
            ioExecutor.execute {
                val restored=ids.map { id ->
                    val legacyId=id.substringAfter(':',id)
                    val cached=MonitorRoomStore.readTrackedProduct(this@MainActivity,id)
                        ?: if(legacyId!=id) MonitorRoomStore.readTrackedProduct(this@MainActivity,legacyId) else null
                    TrackedPriceCache.decode(id,cached)
                }
                runOnUiThread {
                    items=items.map { current ->
                        if (current.price == null) restored.firstOrNull { it.storageKey==current.storageKey } ?: current else current
                    }
                }
            }
        }
        DisposableEffect(Unit) {
            val live = WorkManager.getInstance(this@MainActivity)
                .getWorkInfosForUniqueWorkLiveData("plati_manual_price_check")
            var manualRunning=false
            var periodicRunning=false
            val observer = Observer<List<WorkInfo>> { infos ->
                val running = infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
                manualRunning=running
                checkingNow = manualRunning || periodicRunning
                val info = infos.firstOrNull { it.state == WorkInfo.State.RUNNING } ?: infos.firstOrNull()
                if(manualRunning || !periodicRunning) {
                    monitorDone = info?.progress?.getInt("done", 0) ?: 0
                    monitorTotal = info?.progress?.getInt("total", 0) ?: 0
                    monitorCurrent = info?.progress?.getString("game") ?: ""
                }
                if (infos.isNotEmpty() && !running && !periodicRunning) {
                    reloadWatches()
                    watchMessage = if (infos.any { it.state == WorkInfo.State.SUCCEEDED })
                        "Проверка завершена. Минимальные цены обновлены." else "Проверка не завершилась. Посмотрите ошибку в карточке."
                }
            }
            val periodic=WorkManager.getInstance(this@MainActivity).getWorkInfosForUniqueWorkLiveData("plati_game_price_watch")
            val periodicObserver=Observer<List<WorkInfo>> {infos->
                val running=infos.firstOrNull {it.state==WorkInfo.State.RUNNING}
                periodicRunning=running!=null
                checkingNow=manualRunning || periodicRunning
                if(running!=null) {
                    monitorDone=running.progress.getInt("done",0)
                    monitorTotal=running.progress.getInt("total",0)
                    monitorCurrent=running.progress.getString("game") ?: ""
                    watchMessage="Автоматическая проверка цен…"
                } else {
                    reloadWatches()
                    if(!manualRunning && infos.isNotEmpty())watchMessage="Результаты и ошибки проверки — в карточках отслеживания."
                }
            }
            live.observe(this@MainActivity, observer)
            periodic.observe(this@MainActivity,periodicObserver)
            onDispose {live.removeObserver(observer);periodic.removeObserver(periodicObserver)}
        }
        fun refresh() {
            busy = true
            val targets = items
            if (targets.isEmpty()) { busy = false; return }
            var remaining = targets.size
            targets.forEach { target -> fetch(target) { found ->
                cacheTracked(found)
                items = items.map { current ->
                    if (current.storageKey != target.storageKey) current
                    else TrackedPriceCache.merge(current,found)
                }
                remaining--
                if (remaining == 0) busy = false
            } }
        }
        // Automatically refresh when the screen opens and when returning after a break.
        val lifecycleOwner = LocalLifecycleOwner.current
        var lastAutoRefreshAt by remember { mutableLongStateOf(0L) }
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    val now = System.currentTimeMillis()
                    if (!busy && now - lastAutoRefreshAt >= 60_000L) {
                        lastAutoRefreshAt = now
                        refresh()
                    }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        LaunchedEffect(Unit) {
            if (lastAutoRefreshAt == 0L) {
                lastAutoRefreshAt = System.currentTimeMillis()
                refresh()
            }
        }
        var activeTab by remember { mutableStateOf(0) }
        var settingsOpen by remember { mutableStateOf(false) }
        var topGames by remember { mutableStateOf<List<SteamTopSeller>>(emptyList()) }
        var newReleaseGames by remember { mutableStateOf<List<SteamTopSeller>>(emptyList()) }
        var discountedExtraGames by remember { mutableStateOf<List<SteamTopSeller>>(emptyList()) }
        var themedSaleGames by remember { mutableStateOf<List<SteamTopSeller>>(emptyList()) }
        var themedSalesInitialLoaded by remember { mutableStateOf(false) }
        var newReleaseNextStart by remember { mutableStateOf(0) }
        var newReleaseHasMore by remember { mutableStateOf(true) }
        var newReleaseInitialLoaded by remember { mutableStateOf(false) }
        var discountedNextStart by remember { mutableStateOf(100) }
        var discountedHasMore by remember { mutableStateOf(true) }
        var discountedInitialLoaded by remember { mutableStateOf(false) }
        var loadMoreBusy by remember { mutableStateOf(false) }
        var leaderDetailsGame by remember { mutableStateOf<SteamTopSeller?>(null) }
        var leaderDetailsLoading by remember { mutableStateOf(false) }
        var pendingWatchGame by remember { mutableStateOf<GameSearchResolver.Candidate?>(null) }
        val watchEntries = remember(watches) {
            val array = runCatching { JSONArray(watches) }.getOrDefault(JSONArray())
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        }
        val visibleWatchEntries = remember(watchEntries, watchListFilter, watchListSort) {
            WatchListPresentation.arrange(
                watchEntries.mapIndexed { index, game -> WatchListPresentation.Entry(index, game) },
                watchListFilter,
                watchListSort
            )
        }
        fun isGameWatched(appId: Int, name: String) = watchEntries.any { WatchGameIdentity.matches(it, appId, name) }
        fun openGameMonitoring(appId: Int, name: String) {
            if (appId <= 0 || name.isBlank()) return
            pendingWatchGame = GameSearchResolver.Candidate(appId, name)
        }
        var leaderSection by remember { mutableStateOf(SteamLeaderSection.TOP_SELLERS) }
        var topBusy by remember { mutableStateOf(false) }
        var topError by remember { mutableStateOf("") }
        var topAvailabilityFilter by remember { mutableStateOf("Все игры") }
        var leaderVisibleLimit by remember { mutableIntStateOf(20) }
        var leaderFilterInput by remember { mutableStateOf(SteamLeaderFilterInput()) }
        var wishlistFilterInput by remember { mutableStateOf(SteamLeaderFilterInput()) }
        var wishlistGenreBackfillAttempt by remember { mutableStateOf("") }
        var leaderOfferPrices by remember { mutableStateOf<Map<Int, LeaderOfferPrices>>(emptyMap()) }
        fun refreshTop() {
            if (topBusy || loadMoreBusy) return
            topBusy = true
            topError = ""
            when (leaderSection) {
                SteamLeaderSection.POPULAR_NEW_RELEASES -> {
                    newReleaseGames = emptyList()
                    newReleaseNextStart = 0
                    newReleaseHasMore = true
                    newReleaseInitialLoaded = false
                    loadPopularNewReleasePage(0, 30) { page, error, isFinal ->
                        newReleaseGames = page.games
                        newReleaseNextStart = page.nextStart
                        newReleaseHasMore = page.hasMore && page.nextStart > 0
                        topError = error
                        topBusy = !isFinal
                        if (isFinal) newReleaseInitialLoaded = true
                    }
                }
                SteamLeaderSection.THEMED_SALES -> {
                    themedSaleGames = emptyList()
                    themedSalesInitialLoaded = false
                    loadThemedSaleGames { games, error, isFinal ->
                        themedSaleGames = games
                        topError = error
                        topBusy = !isFinal
                        if (isFinal) themedSalesInitialLoaded = true
                    }
                }
                SteamLeaderSection.TOP_SELLERS, SteamLeaderSection.DISCOUNTED_TOP_SELLERS -> {
                    discountedExtraGames = emptyList()
                    discountedNextStart = 100
                    discountedHasMore = true
                    discountedInitialLoaded = false
                    loadPaidTopSellers { games, error, nextStart, hasMore, isFinal ->
                        topGames = games
                        discountedNextStart = nextStart
                        discountedHasMore = hasMore
                        topError = error
                        topBusy = !isFinal
                        if (isFinal) discountedInitialLoaded = true
                    }
                }
            }
        }
        fun loadMoreLeaderGames() {
            if (loadMoreBusy || topBusy) return
            loadMoreBusy = true
            topError = ""
            when (leaderSection) {
                SteamLeaderSection.POPULAR_NEW_RELEASES -> {
                    val requestedStart = newReleaseNextStart
                    loadPopularNewReleasePage(requestedStart, 30) { page, error, isFinal ->
                        newReleaseGames = (newReleaseGames + page.games).distinctBy { it.appId }
                        newReleaseNextStart = page.nextStart
                        newReleaseHasMore = page.hasMore && page.nextStart > requestedStart
                        topError = error
                        loadMoreBusy = !isFinal
                    }
                }
                SteamLeaderSection.DISCOUNTED_TOP_SELLERS -> {
                    val requestedStart = discountedNextStart
                    loadDiscountedTopSellerPage(requestedStart) { page, error, isFinal ->
                        discountedExtraGames = (discountedExtraGames + page.games.filter { it.discountPercent > 0 })
                            .distinctBy { it.appId }
                        discountedNextStart = page.nextStart
                        discountedHasMore = page.hasMore && page.nextStart > requestedStart
                        topError = error
                        loadMoreBusy = !isFinal
                    }
                }
                SteamLeaderSection.TOP_SELLERS, SteamLeaderSection.THEMED_SALES -> loadMoreBusy = false
            }
        }
        LaunchedEffect(activeTab, settingsOpen, leaderSection, topBusy) {
            if (activeTab == 4 && !settingsOpen && !topBusy) {
                val needsLoad = when (leaderSection) {
                    SteamLeaderSection.POPULAR_NEW_RELEASES -> newReleaseGames.isEmpty()
                    SteamLeaderSection.THEMED_SALES -> themedSaleGames.isEmpty()
                    SteamLeaderSection.TOP_SELLERS, SteamLeaderSection.DISCOUNTED_TOP_SELLERS -> topGames.isEmpty()
                }
                if (needsLoad) refreshTop()
            }
        }
        fun backfillWishlistGenres() {
            val profileId = catalogueUi.selectedWishlistId ?: return
            val games = catalogueUi.wishlistGames
            val missing = games.filter { it.genres.isEmpty() }
            if (missing.isEmpty()) return
            val attemptKey = "$profileId:${missing.joinToString(",") { it.appId.toString() }}"
            if (wishlistGenreBackfillAttempt == attemptKey || catalogueUi.wishlistGenresLoading) return
            wishlistGenreBackfillAttempt = attemptKey
            catalogueUi.wishlistGenresLoading = true
            ioExecutor.execute {
                val enriched = SteamWishlist.enrichMissingGenres(games)
                runOnUiThread {
                    val genresById = enriched.associate { it.appId to it.genres }
                    val profiles = catalogueUi.wishlistProfiles.map { profile ->
                        if (profile.steamId64 == profileId) profile.copy(games = profile.games.map { game ->
                            if (game.genres.isEmpty()) game.copy(genres = genresById[game.appId].orEmpty()) else game
                        }) else profile
                    }
                    if (profiles.any { it.steamId64 == profileId }) persistWishlistProfiles(profiles)
                    if (catalogueUi.selectedWishlistId == profileId) {
                        val latestGames = profiles.firstOrNull { it.steamId64 == profileId }?.games.orEmpty()
                        catalogueUi.wishlistGames = latestGames
                        prefs.edit().putString("wishlist_games", SteamWishlist.encode(latestGames)).apply()
                    }
                    catalogueUi.wishlistGenresLoading = false
                }
            }
        }
        LaunchedEffect(activeTab, settingsOpen, catalogueUi.selectedWishlistId, catalogueUi.wishlistGenresLoading) {
            if (activeTab == 3 && !settingsOpen) backfillWishlistGenres()
        }
        fun importWishlist() {
            if (catalogueUi.wishlistBusy) return
            val profile = catalogueUi.wishlistProfile.trim().ifBlank {
                catalogueUi.wishlistProfiles.firstOrNull { it.steamId64 == catalogueUi.selectedWishlistId }?.input.orEmpty()
            }
            if (profile.isBlank()) {
                catalogueUi.wishlistMessage = "Укажите ссылку на открытый профиль Steam."
                return
            }
            catalogueUi.wishlistBusy = true
            catalogueUi.wishlistMessage = "Загружаем профиль, список и цены Steam RU…"
            wishlistExecutor.execute {
                try {
                    val snapshot = SteamWishlist.fetchSnapshot(profile)
                    val timestamp = System.currentTimeMillis()
                    runOnUiThread {
                        val previous = catalogueUi.wishlistProfiles.firstOrNull { it.steamId64 == snapshot.steamId64 }
                        val games = snapshot.games.map { game ->
                            val previousGame = previous?.games?.firstOrNull { it.appId == game.appId }
                        game.copy(
                            lowestPlatiPrice = previousGame?.lowestPlatiPrice,
                            lowestGgselPrice = previousGame?.lowestGgselPrice
                        )
                        }
                        val savedProfile = WishlistProfile(snapshot.steamId64, snapshot.displayName, profile, games, timestamp)
                        persistWishlistProfiles(listOf(savedProfile) + catalogueUi.wishlistProfiles.filterNot { it.steamId64 == snapshot.steamId64 })
                        catalogueUi.selectedWishlistId = snapshot.steamId64
                        catalogueUi.wishlistProfile = ""
                        prefs.edit().putString("wishlist_profile", profile)
                            .putString("wishlist_games", SteamWishlist.encode(games))
                            .putString("wishlist_selected_profile", snapshot.steamId64)
                            .putLong("wishlist_imported_at", timestamp).apply()
                        catalogueUi.wishlistGames = games
                        catalogueUi.wishlistVisibleLimit = 30
                        catalogueUi.wishlistImportedAt = timestamp
                        catalogueUi.wishlistBusy = false
                        catalogueUi.wishlistMessage = if (games.isEmpty()) "В открытом списке пока нет игр."
                            else "Сохранён профиль «${snapshot.displayName}». Игр: ${games.size}. Цены Steam RU обновлены. Нажмите на название игры для общего поиска или «Искать» для проверки цены в этом списке."
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        catalogueUi.wishlistBusy = false
                        catalogueUi.wishlistMessage = "Не удалось обновить список. Сохранённая копия оставлена."
                    }
                }
            }
        }
        fun comparisonKey(appId: Int, title: String, leader: Boolean): String = OfferPriceCache.key(appId,
            if (leader) OfferPriceCache.leaderFilters(manualFilters) else manualFilters,
            GameRuleStore.read(this@MainActivity, title), ignoredIds, searchTotalPages, comparisonSavedAliases[appId].orEmpty())
        fun recordComparison(key: String, minima: Map<String, Double>, failed: Set<String>,
                             started: Long, finished: Long, reason: String): CachedGamePrices {
            val saved = runCatching { OfferPriceCacheStore.update(this@MainActivity, key, minima, failed, started, finished, reason) }
                .getOrElse { OfferPriceCache.merge(catalogueUi.priceCache[key], minima, failed, started, finished, reason) }
            catalogueUi.priceCache = catalogueUi.priceCache + (key to saved)
            return saved
        }
        fun stopPriceBatch() {
            if (!catalogueUi.priceBatch.running) return
            val current = catalogueUi.priceBatch.current
            catalogueUi.priceBatch = catalogueUi.priceBatch.cancel()
            searchGeneration++
            activeCatalogGeneration.set(searchGeneration)
            searching = false
            catalogueUi.wishlistSearchingKeys.forEach { catalogueUi.wishlistSearchStatuses = catalogueUi.wishlistSearchStatuses + (it to "Проверка остановлена") }
            catalogueUi.wishlistSearchingKeys = emptySet()
            current?.let { target ->
                leaderOfferPrices[target.appId]?.let {
                    leaderOfferPrices = leaderOfferPrices + (target.appId to it.copy(checking = false, message = "Проверка остановлена"))
                }
            }
        }
        fun startCatalogSearch(
            candidateOverride: GameSearchResolver.Candidate? = null,
            wishlistProfileId: String? = null,
            hiddenWishlistSearch: Boolean = false,
            leaderboardAppId: Int? = null,
            onFinished: (() -> Unit)? = null,
            filtersOverride: ManualSearchFilters? = null,
            pagesOverride: Int? = null
        ) {
            if (catalogueUi.priceBatch.running && onFinished == null) return
            if (searching && candidateOverride == null) return
            val requestedFilters = filtersOverride ?: manualFilters
            val requestedPages = pagesOverride ?: searchTotalPages
            if (!requestedFilters.valid) {
                val key = if (wishlistProfileId != null && candidateOverride != null)
                    "$wishlistProfileId:${candidateOverride.appId}" else null
                if (key != null) catalogueUi.wishlistSearchStatuses = catalogueUi.wishlistSearchStatuses + (key to "Проверьте ценовой фильтр")
                else if (leaderboardAppId != null) leaderOfferPrices = leaderOfferPrices +
                    (leaderboardAppId to (leaderOfferPrices[leaderboardAppId] ?: LeaderOfferPrices()).copy(message = "Проверьте ценовой фильтр"))
                else searchMessage = "Проверьте ценовой фильтр"
                onFinished?.invoke()
                return
            }
            val selectedIdentity = candidateOverride ?: selectedGame ?: GameSearchResolver.exact(query, gameSuggestions)
            val savedAliases = selectedIdentity?.let { candidate -> ManualSearchSettings.games(this@MainActivity).firstOrNull { it.appId == candidate.appId }?.aliases.orEmpty() }.orEmpty()
            val identity = selectedIdentity?.copy(aliases = (selectedIdentity.aliases + savedAliases).distinct())
            if (candidateOverride == null && selectedGame == null && gameSuggestions.isNotEmpty() && identity == null) {
                searching = false
                searchMessage = "Выберите точную игру из предложений Steam"
                return
            }
            val requestedGame = identity?.name?.replace(Regex("[™®©]"), "") ?: query.trim()
            if (requestedGame.isBlank()) { onFinished?.invoke(); return }
            val requestedEdition = selectedSteamEdition?.takeIf { candidateOverride == null && leaderboardAppId == null && it.appId == identity?.appId }
            val requestedAt = System.currentTimeMillis()
            val requestRules = JSONObject(GameRuleStore.read(this@MainActivity, requestedGame).toString())
            val requestIgnored = ignoredIds.toSet()
            val requestFilters = if (leaderboardAppId != null) OfferPriceCache.leaderFilters(requestedFilters) else requestedFilters
            val cacheAliases = identity?.let { comparisonAliases(it.appId, it.aliases) + listOfNotNull(requestedEdition?.let { edition -> "steam-edition:${edition.name}" }) }.orEmpty()
            val primaryCacheKey = identity?.let { OfferPriceCache.key(it.appId, requestFilters, requestRules, requestIgnored, requestedPages, cacheAliases) }
            val leaderCacheKey = identity?.takeIf { requestedEdition == null }?.let { OfferPriceCache.key(it.appId, OfferPriceCache.leaderFilters(requestedFilters), requestRules, requestIgnored, requestedPages, comparisonAliases(it.appId, it.aliases)) }

            val backgroundLeaderboardSearch = leaderboardAppId != null
            val backgroundSearch = hiddenWishlistSearch || backgroundLeaderboardSearch
            if (leaderboardAppId != null) {
                val previous = leaderOfferPrices[leaderboardAppId] ?: LeaderOfferPrices()
                leaderOfferPrices = leaderOfferPrices + (leaderboardAppId to previous.copy(checking = true, message = "Проверяем Plati и GGSEL…", scopeKey = primaryCacheKey.orEmpty()))
            }

            catalogueUi.wishlistSearchingKeys.forEach { key -> catalogueUi.wishlistSearchStatuses = catalogueUi.wishlistSearchStatuses + (key to "Поиск остановлен") }
            catalogueUi.wishlistSearchingKeys = emptySet()
            val wishlistKey = wishlistProfileId?.let { "$it:${identity?.appId ?: candidateOverride?.appId}" }
            if (hiddenWishlistSearch && wishlistKey != null) {
                catalogueUi.wishlistSearchingKeys = setOf(wishlistKey)
                catalogueUi.wishlistSearchStatuses = catalogueUi.wishlistSearchStatuses + (wishlistKey to "Ищем минимальную цену…")
            }

            if (!backgroundSearch) {
                searchGeneration++
                activeCatalogGeneration.set(searchGeneration)
            }
            val generation = searchGeneration
            visibleOfferLimit = 30
            if (!backgroundSearch) searching = true
            searchPage = 0
            searchFound = 0
            if (!backgroundSearch) {
                offers = emptyList()
                searchMessage = if (candidateOverride != null)
                    "Автопоиск цен · выполняется для «$requestedGame»…"
                else "Поиск предложений…"
                query = requestedGame
                if (identity != null) selectedGame = identity
                queryHistory = (listOf(requestedGame) + queryHistory.filterNot { it.equals(requestedGame, ignoreCase = true) }).take(20)
                searchPrefs.edit().putString("queries", JSONArray(queryHistory).toString()).apply()
                activeTab = 0
                settingsOpen = false
            }
            if (identity != null) ManualSearchSettings.rememberGame(this@MainActivity, identity)
            if (!backgroundSearch) steamGame = null

            searchCatalog(requestedGame, requestedPages, generation, identity?.appId, identity?.aliases.orEmpty(), requestedEdition, steamEditions, { page, _, count ->
                if (generation == searchGeneration) {
                    searchPage = page
                    searchFound = count
                }
            }) { found, failure, aliasSuggestions, failedSources ->
                if (generation != searchGeneration && !backgroundSearch) return@searchCatalog
                if (aliasSuggestions.isNotEmpty() && identity != null) {
                    if (leaderboardAppId != null || onFinished != null) {
                        if (primaryCacheKey != null) recordComparison(primaryCacheKey, emptyMap(), setOf("Plati", "GGSEL"),
                            requestedAt, System.currentTimeMillis(), "Требуется подтверждение названия")
                        if (leaderboardAppId != null) {
                        val previous = leaderOfferPrices[leaderboardAppId] ?: LeaderOfferPrices()
                        leaderOfferPrices = leaderOfferPrices + (leaderboardAppId to previous.copy(
                            checking = false,
                            message = if (failure.isNotBlank()) "Не удалось подтвердить предложения: $failure" else "Нужен поиск по названию площадки"
                        ))
                        }
                        if (wishlistKey != null) {
                            catalogueUi.wishlistSearchingKeys = catalogueUi.wishlistSearchingKeys - wishlistKey
                            catalogueUi.wishlistSearchStatuses = catalogueUi.wishlistSearchStatuses + (wishlistKey to "Нужно уточнить название")
                        }
                        if (!backgroundSearch) searching = false
                        onFinished?.invoke()
                        return@searchCatalog
                    }
                    if (!backgroundSearch) searching = false
                    pendingAliasGame = identity
                    pendingAliasSuggestions = aliasSuggestions
                    pendingAliasProfileId = wishlistProfileId
                    pendingAliasHiddenWishlist = hiddenWishlistSearch
                    if (wishlistKey != null) {
                        catalogueUi.wishlistSearchingKeys = catalogueUi.wishlistSearchingKeys - wishlistKey
                        catalogueUi.wishlistSearchStatuses = catalogueUi.wishlistSearchStatuses + (wishlistKey to "Найдено другое название · требуется подтверждение")
                    }
                    if (!hiddenWishlistSearch) {
                        offers = emptyList()
                        searchMessage = "По точному названию Steam подтверждённая цена не найдена. Найдены возможные названия товара в каталоге."
                    }
                    return@searchCatalog
                }
                val results = found.map { offer ->
                    offer.copy(
                        canonical = offer.canonical.ifBlank { identity?.name ?: requestedGame },
                        steamAppId = offer.steamAppId.takeIf { it > 0 } ?: identity?.appId ?: 0
                    )
                }
                if (!backgroundSearch) offers = results
                if (!backgroundSearch) searching = false
                val ready = results.count { ManualContentRules.visible(it, "Игра", "Все") }
                val wishlistSearch = wishlistProfileId != null && identity?.appId != null
                val minimaByMarketplace = OfferPriceCache.minima(results, requestFilters, requestRules, requestIgnored)
                val platiMinimum = minimaByMarketplace["Plati"]
                val ggselMinimum = minimaByMarketplace["GGSEL"]
                val hasWishlistMinimum = platiMinimum != null || ggselMinimum != null
                val finishedAt = System.currentTimeMillis()
                val cached = primaryCacheKey?.let {
                    recordComparison(it, minimaByMarketplace, failedSources, requestedAt, finishedAt, failure)
                }
                if (leaderCacheKey != null && leaderCacheKey != primaryCacheKey) {
                    val leaderMinima = LeaderOfferSelector.minimumConfirmedByMarketplace(results, requestedFilters, requestRules, requestIgnored)
                    recordComparison(leaderCacheKey, leaderMinima, failedSources, requestedAt, finishedAt, failure)
                }
                if (leaderboardAppId != null) {
                    leaderOfferPrices = leaderOfferPrices + (leaderboardAppId to LeaderOfferPrices(
                        checking = false, platiMinimum = cached?.plati?.price, ggselMinimum = cached?.ggsel?.price,
                        checkedAt = cached?.let { maxOf(it.plati.checkedAt, it.ggsel.checkedAt).takeIf { time -> time > 0 } },
                        message = if (failedSources.isNotEmpty()) "Часть источников не обновилась" else "",
                        scopeKey = primaryCacheKey.orEmpty()
                    ))
                }
                if (wishlistSearch && wishlistProfileId != null && identity != null && cached != null) {
                    val updatedProfiles = catalogueUi.wishlistProfiles.map { profile ->
                        if (profile.steamId64 != wishlistProfileId) profile else profile.copy(games = profile.games.map { game ->
                            if (game.appId == identity.appId) game.copy(
                                lowestPlatiPrice = cached.plati.price ?: game.lowestPlatiPrice.takeIf { "Plati" in failedSources },
                                lowestGgselPrice = cached.ggsel.price ?: game.lowestGgselPrice.takeIf { "GGSEL" in failedSources }
                            ) else game
                        })
                    }
                    persistWishlistProfiles(updatedProfiles)
                    if (catalogueUi.selectedWishlistId == wishlistProfileId) {
                        catalogueUi.wishlistGames = updatedProfiles.firstOrNull { it.steamId64 == wishlistProfileId }?.games.orEmpty()
                        prefs.edit().putString("wishlist_games", SteamWishlist.encode(catalogueUi.wishlistGames)).apply()
                    }
                    val key = "$wishlistProfileId:${identity.appId}"
                    catalogueUi.wishlistSearchingKeys = catalogueUi.wishlistSearchingKeys - key
                    catalogueUi.wishlistSearchStatuses = catalogueUi.wishlistSearchStatuses + (key to when {
                        failedSources.isNotEmpty() -> "Есть ошибки обновления"
                        hasWishlistMinimum -> "Минимум обновлён"
                        else -> "Подтверждённая цена не найдена"
                    })
                }
                if (!backgroundSearch) {
                    val summary = when {
                        ready > 0 -> "Найдено предложений: $ready."
                        else -> "Подходящих предложений не найдено. Попробуйте изменить название или фильтры."
                    } + if (wishlistSearch) {
                        if (hasWishlistMinimum) " Минимальные цены: " +
                            listOfNotNull(
                                platiMinimum?.let { "Plati ${String.format(Locale("ru", "RU"), "%.0f ₽", it)}" },
                                ggselMinimum?.let { "GGSEL ${String.format(Locale("ru", "RU"), "%.0f ₽", it)}" }
                            ).joinToString(" · ") + "."
                        else " Подтверждённые цены не найдены."
                    } else ""
                    searchMessage = summary
                    searchMessage = summary
                }
                onFinished?.invoke()
            }
        }
        LaunchedEffect(catalogueUi.priceBatch.current, catalogueUi.priceBatch.completed, searching) {
            val target = catalogueUi.priceBatch.current
            if (catalogueUi.priceBatch.running && !searching && target != null) {
                startCatalogSearch(GameSearchResolver.Candidate(target.appId, target.title),
                    wishlistProfileId = target.profileId, hiddenWishlistSearch = target.profileId != null,
                    leaderboardAppId = target.appId.takeIf { target.profileId == null },
                    onFinished = { catalogueUi.priceBatch = catalogueUi.priceBatch.advance(target) },
                    filtersOverride = catalogueUi.priceBatch.filters, pagesOverride = catalogueUi.priceBatch.pages)
            }
        }
        fun dismissAliasPrompt() {
            val appId = pendingAliasGame?.appId
            val profileId = pendingAliasProfileId
            if (pendingAliasHiddenWishlist && appId != null && profileId != null) {
                val key = "$profileId:$appId"
                catalogueUi.wishlistSearchStatuses = catalogueUi.wishlistSearchStatuses + (key to "Подтверждённая цена не найдена")
            }
            pendingAliasGame = null
            pendingAliasSuggestions = emptyList()
            pendingAliasProfileId = null
        }
        val searchScroll = rememberScrollState()
        val productsScroll = rememberScrollState()
        val watchScroll = rememberScrollState()
        val directoryScroll = rememberScrollState()
        Column(Modifier.fillMaxSize().background(Color(0xFFF4F6F8))) {
            val aliasGame = pendingAliasGame
            if (aliasGame != null && pendingAliasSuggestions.isNotEmpty()) {
                AlertDialog(
                    onDismissRequest = { dismissAliasPrompt() },
                    title = { Text("Подтвердить название в каталоге") },
                    text = {
                        Column {
                            Text("Для «${aliasGame.name}» не нашлось подтверждённых предложений по названию Steam. Выберите возможное название продавца. После сохранения поиск повторится с теми же фильтрами.")
                            Spacer(Modifier.height(8.dp))
                            pendingAliasSuggestions.forEach { alias ->
                                TextButton(onClick = {
                                    val profileId = pendingAliasProfileId
                                    val hidden = pendingAliasHiddenWishlist
                                    val updated = aliasGame.copy(aliases = (aliasGame.aliases + alias).distinct())
                                    ManualSearchSettings.rememberAlias(this@MainActivity, aliasGame, alias)
                                    pendingAliasGame = null
                                    pendingAliasSuggestions = emptyList()
                                    pendingAliasProfileId = null
                                    startCatalogSearch(updated, profileId, hidden)
                                }, modifier = Modifier.fillMaxWidth()) { Text(alias) }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { dismissAliasPrompt() }) { Text("Пропустить") } }
                )
            }
            pendingWatchGame?.let { game ->
                GameMonitoringDialog(game, onDismiss = { pendingWatchGame = null },
                    onSaved = { saved -> watches = saved; pendingWatchGame = null })
            }
            NavigationHeader(if (settingsOpen) -1 else activeTab, settingsOpen,
                onSettings = {
                    keyboardController?.hide()
                    focusManager.clearFocus()
                    settingsOpen = !settingsOpen
                    showDirectory = false
                },
                onTab = {
                    keyboardController?.hide()
                    focusManager.clearFocus()
                    activeTab = it
                    settingsOpen = false
                    showDirectory = false
                })
            PriceBatchStatus(catalogueUi.priceBatch, onStop = { stopPriceBatch() })
            Column(Modifier.fillMaxSize().background(Color(0xFFF4F6F8)).verticalScroll(if (settingsOpen) directoryScroll else when(activeTab) { 0 -> searchScroll; 1 -> productsScroll; 2 -> watchScroll; else -> directoryScroll }).padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ScreenSection {
            if (activeTab == 0 && !settingsOpen) {
            Text("Цены на игры Steam · ₽", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (searchMessage.startsWith("Автопоиск цен")) {
                Text(searchMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(4.dp))
            Text("Поиск игры", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(query, {
                query = it
                selectedGame = null
                                steamEditions = emptyList()
                                selectedSteamEdition = null
                                editionLoading = false
                gameSuggestions = emptyList()
                searchGeneration++
                activeCatalogGeneration.set(searchGeneration)
                searching = false
                offers = emptyList()
                steamGame = null
            }, label = { Text("Например, Cyberpunk 2077") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            if(selectedGame!=null) {
                Text("Выбрано: ${selectedGame!!.name}", style=MaterialTheme.typography.bodySmall)
                Text("Издания из вариантов покупки Steam. Доступность активации в РФ проверяется отдельно.", style=MaterialTheme.typography.bodySmall)
                if (editionLoading) Text("Загружаем издания Steam…", style = MaterialTheme.typography.bodySmall)
                else if (steamEditions.isEmpty()) Text("Для игры нет списка изданий. Можно искать по названию.", style = MaterialTheme.typography.bodySmall)
                Box {
                    OutlinedButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = { editionMenuExpanded = true }, enabled = !editionLoading) {
                        Text("Издание: ${selectedSteamEdition?.name ?: "Все издания"}")
                    }
                    DropdownMenu(expanded = editionMenuExpanded, onDismissRequest = { editionMenuExpanded = false }) {
                        DropdownMenuItem(text = { Text("Все издания") }, onClick = {
                            selectedSteamEdition = null
                            editionMenuExpanded = false
                        })
                        steamEditions.forEach { edition ->
                            DropdownMenuItem(text = { Column {
                                Text(edition.name)
                                Text(steamRuEditionLabel(edition), style = MaterialTheme.typography.labelSmall)
                            } }, onClick = {
                                selectedSteamEdition = edition
                                editionMenuExpanded = false
                            })
                        }
                    }
                }
                selectedSteamEdition?.let { Text(steamRuEditionLabel(it), style = MaterialTheme.typography.bodySmall) }
            }
            if(suggestionBusy) Text("Подбираем названия Steam…",style=MaterialTheme.typography.bodySmall)
            if(gameSuggestions.isNotEmpty() && selectedGame==null) {
                Text("Уточните название игры", style=MaterialTheme.typography.titleSmall)
                gameSuggestions.forEach { candidate ->
                    TextButton(onClick={
                        selectedGame=candidate
                        selectedSteamEdition=null
                        steamEditions=emptyList()
                        editionLoading=true
                        editionExecutor.execute {
                            val editions=runCatching { SteamEditionReference.discover(candidate.appId) }.getOrDefault(emptyList())
                            runOnUiThread {
                                if (selectedGame?.appId == candidate.appId) {
                                    steamEditions=editions
                                    editionLoading=false
                                }
                            }
                        }
                        suggestionGeneration.incrementAndGet()
                        suggestionBusy=false
                        gameSuggestions=emptyList()
                        query=candidate.name.replace(Regex("[™®©]"),"")
                        offers=emptyList()
                        searchGeneration++
                        activeCatalogGeneration.set(searchGeneration)
                        searching=false
                        ManualSearchSettings.rememberGame(this@MainActivity,candidate)
                    },modifier=Modifier.fillMaxWidth()) {Text(candidate.name)}
                }
            }
            if (queryHistory.isNotEmpty()) {
                var historyExpanded by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("История поиска", style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { historyExpanded = !historyExpanded }) {
                        Text(if (historyExpanded) "Скрыть" else "Показать (${queryHistory.size})")
                    }
                }
                if (historyExpanded) {
                    queryHistory.forEach { savedQuery ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = {
                                searchGeneration++
                                activeCatalogGeneration.set(searchGeneration)
                                searching = false
                                offers = emptyList()
                                steamGame = null
                                steamEditions = emptyList()
                                selectedSteamEdition = null
                                editionLoading = false
                                selectedGame=null
                                query = savedQuery
                            }, modifier = Modifier.weight(1f)) {
                                Text(savedQuery, maxLines = 1)
                            }
                            TextButton(onClick = {
                                queryHistory = queryHistory.filterNot { it == savedQuery }
                                searchPrefs.edit().putString("queries", JSONArray(queryHistory).toString()).apply()
                            }) { Text("Удалить") }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Все", "Gift", "Key").forEach { type ->
                    FilterChip(colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFFFFE2A6), selectedLabelColor = Color(0xFF17212D)), selected = selectedType == type, onClick = { selectedType = type },
                        label = { Text(type) })
                }
            }
            Text("Маркетплейс", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Все", "Plati", "GGSEL").forEach { marketplace ->
                    FilterChip(colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFFFFE2A6), selectedLabelColor = Color(0xFF17212D)), selected = selectedMarketplace == marketplace,
                        onClick = { selectedMarketplace = marketplace }, label = { Text(marketplace) })
                }
            }
            FilterChip(colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFFFFE2A6), selectedLabelColor = Color(0xFF17212D)), selected=confirmedRf,onClick={confirmedRf=!confirmedRf},label={Text("Только подтверждённая РФ")})
            OutlinedTextField(maxPrice,{maxPrice=it},label={Text("Цена до, ₽")},singleLine=true,
                isError=!manualFilters.valid,supportingText={Text(if(manualFilters.valid) "Пустое поле — без ограничения" else "Укажите положительную цену")},modifier=Modifier.fillMaxWidth())
            Text("Состав товара", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Игра", "Все", "DLC").forEach { edition ->
                    FilterChip(colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFFFFE2A6), selectedLabelColor = Color(0xFF17212D)), selected = editionFilter == edition,
                        onClick = { editionFilter = edition }, label = { Text(edition) })
                }
            }
            OutlinedButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = { startCatalogSearch() },
                enabled = query.isNotBlank() && !searching && !suggestionBusy && manualFilters.valid) {
                Text(if (searching) "Ищем..." else selectedSteamEdition?.let { "Искать ${it.name}" } ?: "Искать")
            }
            if (searching) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Ищем предложения…", style = MaterialTheme.typography.bodySmall)
            }
            Text(searchMessage, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            } // конец настроек поиска
            }
            ScreenSection {
            if (activeTab == 1 && !settingsOpen) {
            OutlinedTextField(entry, { entry = it }, label = { Text("Ссылка на товар Plati") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = {
                    val raw = entry.trim()
                    val id = if (raw.all { it.isDigit() }) raw else {
                        val uri = runCatching { Uri.parse(raw) }.getOrNull()
                        val host = uri?.host?.lowercase(Locale.ROOT) ?: ""
                        if (uri?.scheme == "https" && (host == "plati.ru" || host == "plati.market" || host == "www.plati.ru" || host == "www.plati.market")) {
                            Regex("""(?:/itm/[^/]+/|/itm/|/asp/pay[.]asp[?]id=)([0-9]{4,})""", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)
                                ?: Regex("([0-9]{4,})(?:/?(?:\\?.*)?)?$").find(raw)?.groupValues?.get(1) ?: ""
                        } else ""
                    }
                    if (id.length !in 4..12) message = "Не удалось определить ID. Введите числовой ID товара."
                    else if (items.any { it.storageKey == TrackedPriceCache.storageKey("Plati",id) }) message = "Товар уже добавлен"
                    else {
                        val tracked=Tracked(id,"Товар $id",null,"Загрузка...","https://plati.market/itm/$id")
                        items = items + tracked
                        saveIds(items.map { it.storageKey })
                        entry = ""
                        message = "Товар добавлен"
                        fetch(tracked) { found ->
                            cacheTracked(found)
                            items = items.map { if (it.storageKey == tracked.storageKey) TrackedPriceCache.merge(it,found) else it }
                        }
                    }
                }) { Text("Добавить") }
                OutlinedButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = { refresh() }, enabled = !busy && items.isNotEmpty()) { Text(if (busy) "Обновление..." else "Обновить цены") }
            }
            if (message.isNotBlank()) OutlinedCard(Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, Color(0xFFDCE2E8)),
                colors = CardDefaults.outlinedCardColors(containerColor = Color.White)) {
                Text(message, Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Text("Отслеживаемые товары: ${items.size}", style = MaterialTheme.typography.titleMedium)
            val sortedTrackedItems = remember(items) { items.sortedBy { it.price ?: Double.MAX_VALUE } }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                sortedTrackedItems.take(trackedVisibleLimit).forEach { item ->
                    OutlinedCard(Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFFDCE2E8)),
                        colors = CardDefaults.outlinedCardColors(containerColor = Color.White)) {
                        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (item.steamAppId > 0) SteamArtwork(
                                "https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/${item.steamAppId}/library_600x900.jpg",
                                Modifier.size(48.dp, 72.dp), appId = item.steamAppId
                            )
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(item.name, style = MaterialTheme.typography.titleSmall,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${item.marketplace} · ${item.status}", style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(item.price?.let { String.format(Locale("ru", "RU"), "%.2f ₽", it) } ?: "Цена недоступна",
                                        style = MaterialTheme.typography.titleMedium, maxLines = 1)
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        OutlinedButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url))) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("Открыть") }
                                        OutlinedButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = {
                                            items = items.filterNot { it.storageKey == item.storageKey }
                                            saveIds(items.map { it.storageKey })
                                            prefs.edit().remove("product_cache_${item.storageKey}").remove("product_cache_${item.id}").apply()
                                            ioExecutor.execute { MonitorRoomStore.writeTrackedProduct(this@MainActivity,item.storageKey,null) }
                                        }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("Удалить") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (sortedTrackedItems.size > trackedVisibleLimit) {
                OutlinedButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = { trackedVisibleLimit += 30 }, modifier = Modifier.fillMaxWidth()) {
                    Text("Показать ещё · ${minOf(trackedVisibleLimit, sortedTrackedItems.size)} из ${sortedTrackedItems.size}")
                }
            }
            } // конец вкладки товаров
            }
            ScreenSection {
            if (activeTab == 2 && !settingsOpen) {
            Text("Мониторинг цен", style = MaterialTheme.typography.titleLarge)
            Text("Plati + GGSEL · только подтверждённые предложения РФ.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(watchGame, { watchGame = it;watchSelection=null }, label = { Text("Название игры") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            if(watchSuggesting) Text("Подбираем игру Steam…",style=MaterialTheme.typography.bodySmall)
            if(watchSelection!=null) Text("Выбрано: ${watchSelection!!.name}", style=MaterialTheme.typography.bodySmall)
            if(watchSelection==null) watchSuggestions.forEach {candidate ->
                TextButton(onClick={
                    watchSelection=candidate;watchGame=candidate.name.replace(Regex("[™®©]"),"")
                    watchSuggestionGeneration.incrementAndGet();watchSuggestions=emptyList();watchSuggesting=false
                    ManualSearchSettings.rememberGame(this@MainActivity,candidate)
                }) {Text(candidate.name)}
            }
            OutlinedTextField(targetPrice, { targetPrice = it }, label = { Text("Желаемая цена, ₽") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Все", "Gift", "Key").forEach { kind ->
                    FilterChip(colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFFFFE2A6), selectedLabelColor = Color(0xFF17212D)), selected = watchType == kind, onClick = { watchType = kind }, label = { Text(kind) })
                }
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = watchRf, onCheckedChange = { }, enabled = false)
                Text("Только с указанием РФ")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val amount = targetPrice.replace(',', '.').toDoubleOrNull()
                    if (watchGame.isBlank() || amount == null || !amount.isFinite() || amount <= 0) {
                        watchMessage = "Введите название игры и положительную желаемую цену"
                    } else {
                        val identity=watchSelection ?: GameSearchResolver.exact(watchGame,watchSuggestions+GameSearchResolver.seeds)
                        if(identity==null && watchSuggestions.isNotEmpty()) {
                            watchMessage="Выберите точную игру Steam"
                            return@OutlinedButton
                        }
                        watchMessage="Добавляем игру…"
                        ioExecutor.execute {
                            WatchStore.add(this@MainActivity,identity?.name ?: watchGame.trim(),watchType,amount,watchRf,identity?.appId)
                            runOnUiThread {
                                WatchScheduler.start(this@MainActivity)
                                WatchScheduler.checkNow(this@MainActivity)
                                if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                                reloadWatches()
                                watchMessage = "Игра добавлена. Проверка запрошена; фоновые проверки — примерно раз в час."
                            }
                        }
                    }
                }, enabled = !watchSuggesting, modifier = Modifier.weight(1f),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)) {
                    Text("Отслеживать игру", maxLines = 1)
                }
                OutlinedButton(onClick = {
                    WatchScheduler.checkNow(this@MainActivity)
                    watchMessage = "Проверка запущена..."
                }, enabled = !checkingNow && JSONArray(watches).length() > 0,
                    modifier = Modifier.weight(1f),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)) {
                    Text(if (checkingNow) "Проверяем..." else "Проверить сейчас", maxLines = 1)
                }
            }
            if (checkingNow) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Обновляем цены…", style = MaterialTheme.typography.bodySmall)
            }
            if (watchMessage != "Укажите название игры и цену в рублях") {
                Text(watchMessage, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (watchEntries.isNotEmpty()) {
                WatchListControls(
                    filter = watchListFilter,
                    sort = watchListSort,
                    visibleCount = visibleWatchEntries.size,
                    totalCount = watchEntries.size,
                    onFilter = { watchListFilter = it },
                    onSort = { watchListSort = it }
                )
                if (visibleWatchEntries.isEmpty()) {
                    Text("По этому фильтру игр нет.", style = MaterialTheme.typography.bodySmall)
                }
            }
            for (watchEntry in visibleWatchEntries) {
                val wi = watchEntry.originalIndex
                val w = watchEntry.game
                OutlinedCard(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                    Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val watchAppId = w.optInt("steamAppId", w.optInt("appId", 0))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (watchAppId > 0) SteamArtwork(
                                "https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/${watchAppId}/library_600x900.jpg",
                                Modifier.size(48.dp, 72.dp), appId = watchAppId
                            )
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(w.optString("name"), style = MaterialTheme.typography.titleSmall, maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Text(listOfNotNull(w.optString("kind"), w.optString("steamEditionName").takeIf { it.isNotBlank() },
                                    "цель ${w.optDouble("limit").toInt()} ₽", "РФ ${if (w.optBoolean("rf",true)) "да" else "не обязательно"}").joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Text(if (w.optDouble("last", -1.0) > 0) "Минимум: ${w.optDouble("last").toInt()} ₽" else "Подходящих цен пока нет",
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        WatchHistory.priceChange(w.optJSONArray("history") ?: JSONArray())?.let { change ->
                            val difference = kotlin.math.abs(change.difference)
                            val changeText = when {
                                difference < 0.005 -> "Динамика: минимум не изменился к прошлой подтверждённой цене"
                                change.difference < 0 -> "Динамика: минимум снизился на ${String.format(Locale("ru", "RU"), "%.2f ₽ (%.1f%%)", difference, kotlin.math.abs(change.percent))}"
                                else -> "Динамика: минимум вырос на ${String.format(Locale("ru", "RU"), "%.2f ₽ (%.1f%%)", difference, change.percent)}"
                            }
                            Text(changeText, style = MaterialTheme.typography.bodySmall,
                                color = when {
                                    difference < 0.005 -> MaterialTheme.colorScheme.onSurfaceVariant
                                    change.difference < 0 -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.error
                                })
                        }
                        val checkedAt = w.optLong("checked", 0L)
                                                val monitorDate = java.text.SimpleDateFormat("dd.MM HH:mm", Locale("ru", "RU"))
                                                var resultsExpanded by remember(w.optString("name")) { mutableStateOf(false) }
                        val bestOffers = w.optJSONArray("topOffers") ?: JSONArray()
                        if (checkedAt > 0L) Text("Проверено ${monitorDate.format(java.util.Date(checkedAt))}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = { resultsExpanded = !resultsExpanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                            Text(if (resultsExpanded) "Скрыть цены" else "Цены (${bestOffers.length()})", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        }
                        if (w.has("error")) {
                                                    Text(
                                                        MonitorErrorPresentation.summary(w.optString("error")),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                if (resultsExpanded) {
                            if (bestOffers.length() == 0) {
                                Text("Пока нет результатов. Нажмите «Проверить сейчас».", style = MaterialTheme.typography.bodySmall)
                            }
                            for (oi in 0 until bestOffers.length()) {
                                val offerJson = bestOffers.optJSONObject(oi) ?: continue
                                val id = offerJson.optString("id")
                                val title = offerJson.optString("name")
                                val price = offerJson.optDouble("price")
                                val type = offerJson.optString("type")
                                val marketplace=offerJson.optString("marketplace","Plati")
                                val productUrl=offerJson.optString("url").ifBlank {
                                    if(marketplace=="GGSEL") "https://ggsel.net/" else "https://plati.market/itm/$id"
                                }
                                OutlinedCard(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                                    Column(Modifier.padding(10.dp)) {
                                        Text(title, style = MaterialTheme.typography.bodyMedium)
                                        Text("$marketplace · $type · " + String.format(Locale("ru", "RU"), "%.2f ₽", price))
                                        if(offerJson.optString("note").isNotBlank())Text(offerJson.optString("note"),style=MaterialTheme.typography.bodySmall)
                                        Row {
                                            TextButton(onClick = {
                                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(productUrl)))
                                            }) { Text("Открыть на $marketplace") }
                                            TextButton(onClick = {
                                                val tracked=TrackedPriceCache.fromOffer(Offer(
                                                    id,title,price,type,"РФ","Игра",available=true,priceVerified=true,
                                                    canonical=offerJson.optString("canonical",w.optString("name")),
                                                    steamAppId=offerJson.optInt("steamAppId",w.optInt("steamAppId")),
                                                    marketplace=marketplace,productUrl=productUrl
                                                ))
                                                if(items.none { it.storageKey==tracked.storageKey }) {
                                                    items = items + tracked
                                                    cacheTracked(tracked)
                                                    saveIds(items.map { it.storageKey })
                                                    fetch(tracked) { found ->
                                                        cacheTracked(found)
                                                        items = items.map { if (it.storageKey == tracked.storageKey) TrackedPriceCache.merge(it,found) else it }
                                                    }
                                                }
                                            }, enabled = marketplace in setOf("Plati","GGSEL") &&
                                                items.none { it.storageKey==TrackedPriceCache.storageKey(marketplace,id) }) {
                                                Text(if(items.any { it.storageKey==TrackedPriceCache.storageKey(marketplace,id) }) "Уже отслеживается" else "Отслеживать товар")
                                            }
                                            TextButton(onClick = {
                                                IgnoredStore.add(this@MainActivity, id, title)
                                                ignoredIds = IgnoredStore.ids(this@MainActivity)
                                            }, enabled = id !in ignoredIds) { Text("Игнорировать") }
                                        }
                                    }
                                }
                            }
                        }
                        val history=WatchHistory.points(w.optJSONArray("history") ?: JSONArray())
                        var historyExpanded by remember(w.optString("name")) {mutableStateOf(false)}
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick={historyExpanded=!historyExpanded},
                                modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                                Text(if(historyExpanded) "Скрыть историю" else "История цен (${history.size})",
                                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, fontSize = 11.sp)
                            }
                            OutlinedButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = { ioExecutor.execute { WatchStore.remove(this@MainActivity, wi); runOnUiThread { reloadWatches() } } },
                                modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                                Text("Убрать игру", maxLines = 1, softWrap = false, fontSize = 11.sp)
                            }
                        }
                        if(historyExpanded) {
                            val prices=history.mapNotNull {it.price}
                            if(history.isEmpty())Text("История появится после первой успешной проверки.",style=MaterialTheme.typography.bodySmall)
                            else {
                                Text("Минимумы за 90 дней · до 400 проверок. Пропуски означают отсутствие подтверждённого предложения, а не нулевую цену.",style=MaterialTheme.typography.bodySmall)
                                if(prices.isNotEmpty()) {
                                    Text("Диапазон: ${String.format(Locale("ru","RU"),"%.2f–%.2f ₽",prices.minOrNull(),prices.maxOrNull())}",style=MaterialTheme.typography.bodySmall)
                                    PriceHistoryChart(history)
                                }
                                history.takeLast(10).reversed().forEach {point ->
                                    val stamp=java.text.SimpleDateFormat("dd.MM HH:mm",Locale("ru","RU")).format(java.util.Date(point.at))
                                    Text("$stamp · ${point.price?.let {String.format(Locale("ru","RU"),"%.2f ₽",it)} ?: "Нет подтверждённого предложения"}",style=MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            HorizontalDivider()
            val alerts = remember(alertLog) { JSONArray(alertLog) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Журнал находок (${alerts.length()})", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { showAlertLog = !showAlertLog }) {
                    Text(if (showAlertLog) "Скрыть" else "Показать")
                }
            }
            Text("Новые предложения и дальнейшее снижение цены до вашей цели. Повторы с той же ценой не добавляются. Журнал хранится на телефоне.",
                style = MaterialTheme.typography.bodySmall)
            if (showAlertLog) {
                if (alerts.length() == 0) Text("Находок пока нет. Они появятся после срабатывания отслеживания.")
                for (ai in 0 until alerts.length()) {
                    val alert = alerts.optJSONObject(ai) ?: continue
                    val id = alert.optString("id")
                    val marketplace=alert.optString("marketplace","Plati")
                    val alertUrl=alert.optString("url").ifBlank { "https://plati.market/itm/$id" }
                    val stamp = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru", "RU"))
                        .format(java.util.Date(alert.optLong("at")))
                    OutlinedCard(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(alert.optString("game"), style = MaterialTheme.typography.titleMedium)
                            Text(alert.optString("name"), style = MaterialTheme.typography.bodySmall)
                            Text("$marketplace · ${alert.optString("type")} · ${String.format(Locale("ru", "RU"), "%.2f ₽", alert.optDouble("price"))} · цель до ${alert.optDouble("limit").toInt()} ₽")
                            Text("Найдено: $stamp · ID $id", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(alertUrl)))
                            }) { Text("Открыть на $marketplace") }
                        }
                    }
                }
                if (alerts.length() > 0) {
                    TextButton(onClick = { ioExecutor.execute { AlertJournal.clear(this@MainActivity); runOnUiThread { reloadWatches() } } }) {
                        Text("Очистить журнал")
                    }
                }
            }
            HorizontalDivider()
            } // конец вкладки автоотслеживания
            }
            ScreenSection {
            if (activeTab == 0 && !settingsOpen) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Игнорируемые товары: ${ignoredIds.size}", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { showIgnored = !showIgnored }) { Text(if (showIgnored) "Скрыть" else "Показать") }
            }
            if (showIgnored) {
                ignoredIds.sorted().forEach { ignoredId ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(IgnoredStore.title(this@MainActivity, ignoredId), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            IgnoredStore.remove(this@MainActivity, ignoredId)
                            ignoredIds = IgnoredStore.ids(this@MainActivity)
                        }) { Text("Вернуть") }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("Результаты поиска", style = MaterialTheme.typography.titleMedium)
            Text("В результатах — активные товары с подтверждённой ценой и регионом.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            if (offers.isNotEmpty()) {
                val currentRules = remember(query, rulesRevision) { GameRuleStore.read(this@MainActivity, query) }
                val filteredOffers = remember(offers, manualFilters, currentRules, ignoredIds, selectedMarketplace) {
                    offers.filter { (selectedMarketplace == "Все" || it.marketplace == selectedMarketplace) &&
                        ManualSearchFilter.rejection(it,manualFilters,currentRules,ignoredIds)==null }
                }
                val visibleCount = filteredOffers.size
                Text("Предложения по цене ↑ · $visibleCount", style = MaterialTheme.typography.titleMedium)
                if (visibleCount == 0) Text("По выбранным фильтрам предложений нет. Уточните название или проверьте регион в заголовках.", style = MaterialTheme.typography.bodySmall)
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    filteredOffers.take(visibleOfferLimit).forEach { offer ->
                        val offerAppId = offer.steamAppId.takeIf { it > 0 } ?: selectedGame?.appId ?: 0
                        OutlinedCard(Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color(0xFFDCE2E8)),
                            colors = CardDefaults.outlinedCardColors(containerColor = Color.White)) {
                            Row(Modifier.fillMaxWidth().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                if (offerAppId > 0) SteamArtwork(
                                    "https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/${offerAppId}/library_600x900.jpg",
                                    Modifier.size(48.dp, 72.dp), appId = offerAppId
                                )
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Text(offer.marketplace, style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF17212D), maxLines = 1)
                                        Text(String.format(Locale("ru", "RU"), "%.2f ₽", offer.price),
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                                            color = Color(0xFF17212D), maxLines = 1)
                                    }
                                    Text(offer.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                                        overflow = TextOverflow.Ellipsis)
                                    val compactDetails = listOf(offer.categoryLabel, offer.purchaseNote)
                                        .filter { it.isNotBlank() }.joinToString(" · ")
                                    if (compactDetails.isNotBlank()) Text(compactDetails,
                                        style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${offer.type} · ${offer.rfStatus} · " +
                                        (if (offer.variablePrice) "Цена варианта не подтверждена" else offer.edition),
                                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        OutlinedButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                                            offer.productUrl.ifBlank { "https://plati.market/itm/${offer.id}" }
                                        ))) }, modifier = Modifier.weight(1f).height(30.dp),
                                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                                            Text("Открыть", maxLines = 1, style = MaterialTheme.typography.labelSmall)
                                        }
                                        OutlinedButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = {
                                            val tracked=TrackedPriceCache.fromOffer(offer.copy(
                                                canonical=offer.canonical.ifBlank { selectedGame?.name ?: query },
                                                steamAppId=offer.steamAppId.takeIf { it>0 } ?: selectedGame?.appId ?: 0
                                            ))
                                            if(items.none { it.storageKey==tracked.storageKey }) {
                                                items = items + tracked
                                                cacheTracked(tracked)
                                                saveIds(items.map { it.storageKey })
                                                fetch(tracked) { found ->
                                                    cacheTracked(found)
                                                    items = items.map { if (it.storageKey == tracked.storageKey) TrackedPriceCache.merge(it,found) else it }
                                                }
                                            }
                                        }, enabled = offer.marketplace in setOf("Plati","GGSEL") &&
                                            items.none { it.storageKey==TrackedPriceCache.storageKey(offer.marketplace,offer.id) },
                                            modifier = Modifier.weight(1f).height(30.dp),
                                            contentPadding = PaddingValues(horizontal = 3.dp, vertical = 0.dp)) {
                                            Text(if (items.any { it.storageKey==TrackedPriceCache.storageKey(offer.marketplace,offer.id) })
                                                "Уже отслеживается" else "Отслеживать", maxLines = 1, style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        OutlinedButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = {
                                            IgnoredStore.add(this@MainActivity, offer.id, offer.name)
                                            ignoredIds = IgnoredStore.ids(this@MainActivity)
                                        }, modifier = Modifier.weight(1f).height(30.dp),
                                            contentPadding = PaddingValues(horizontal = 3.dp, vertical = 0.dp)) {
                                            Text("Игнорировать", maxLines = 1, style = MaterialTheme.typography.labelSmall)
                                        }
                                        OutlinedButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = {
                                            GameRuleStore.add(this@MainActivity, query, "blocked", offer.name)
                                            rulesRevision++
                                        }, modifier = Modifier.weight(1f).height(30.dp),
                                            contentPadding = PaddingValues(horizontal = 3.dp, vertical = 0.dp)) {
                                            Text("Исключить как DLC", maxLines = 1, style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (visibleCount > visibleOfferLimit) {
                        OutlinedButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = { visibleOfferLimit += 30 }, modifier = Modifier.fillMaxWidth()) {
                            Text("Показать ещё 30 · отображено ${minOf(visibleOfferLimit, visibleCount)} из $visibleCount")
                        }
                    }
                }
            }
            } // конец вкладки поиска
            }
            ScreenSection {
            if (activeTab == 3 && !settingsOpen) {
                val wishlistCacheKeys = remember(catalogueUi.wishlistGames, manualFilters, rulesRevision,
                    ignoredIds, searchTotalPages, comparisonSavedAliases) {
                    catalogueUi.wishlistGames.associate { it.appId to comparisonKey(it.appId, it.title, false) }
                }
                WishlistPanel(
                    profiles = catalogueUi.wishlistProfiles, selectedId = catalogueUi.selectedWishlistId, games = catalogueUi.wishlistGames,
                    filterInput = wishlistFilterInput, onFilterInput = { wishlistFilterInput = it },
                    input = catalogueUi.wishlistProfile, busy = catalogueUi.wishlistBusy, message = catalogueUi.wishlistMessage,
                    importedAt = catalogueUi.wishlistImportedAt, visibleLimit = catalogueUi.wishlistVisibleLimit,
                    genresLoading = catalogueUi.wishlistGenresLoading,
                    searchingKeys = catalogueUi.wishlistSearchingKeys, searchStatuses = catalogueUi.wishlistSearchStatuses,
                    searchEnabled = manualFilters.valid && !catalogueUi.priceBatch.running,
                    onInput = { catalogueUi.wishlistProfile = it }, onImport = { importWishlist() },
                    onSelect = { profile ->
                        catalogueUi.selectedWishlistId = profile.steamId64
                        catalogueUi.wishlistProfile = ""
                        catalogueUi.wishlistGames = profile.games
                        catalogueUi.wishlistImportedAt = profile.importedAt
                        catalogueUi.wishlistVisibleLimit = 30
                        catalogueUi.wishlistMessage = "Выбран профиль «${profile.displayName}»."
                        prefs.edit().putString("wishlist_selected_profile", profile.steamId64)
                            .putString("wishlist_profile", profile.input)
                            .putString("wishlist_games", SteamWishlist.encode(profile.games))
                            .putLong("wishlist_imported_at", profile.importedAt).apply()
                    },
                    onDelete = {
                        val active = catalogueUi.wishlistProfiles.firstOrNull { it.steamId64 == catalogueUi.selectedWishlistId }
                        if (active != null) {
                            val remaining = catalogueUi.wishlistProfiles.filterNot { it.steamId64 == active.steamId64 }
                            persistWishlistProfiles(remaining)
                            val next = remaining.firstOrNull()
                            catalogueUi.selectedWishlistId = next?.steamId64
                            catalogueUi.wishlistProfile = ""
                            catalogueUi.wishlistGames = next?.games.orEmpty()
                            catalogueUi.wishlistImportedAt = next?.importedAt ?: 0L
                            prefs.edit().putString("wishlist_selected_profile", next?.steamId64)
                                .putString("wishlist_profile", next?.input.orEmpty())
                                .putString("wishlist_games", SteamWishlist.encode(next?.games.orEmpty()))
                                .putLong("wishlist_imported_at", next?.importedAt ?: 0L).apply()
                        }
                    },
                    onOpenSearch = { game ->
                        startCatalogSearch(
                            candidateOverride = GameSearchResolver.Candidate(game.appId, game.title),
                            wishlistProfileId = catalogueUi.selectedWishlistId
                        )
                    },
                    onSearchPrice = { game ->
                        val profileId = catalogueUi.selectedWishlistId
                        if (profileId != null) startCatalogSearch(
                            candidateOverride = GameSearchResolver.Candidate(game.appId, game.title),
                            wishlistProfileId = profileId,
                            hiddenWishlistSearch = true
                        )
                    },
                    onMonitor = { game -> openGameMonitoring(game.appId, game.title) },
                    cachedPrices = { game -> wishlistCacheKeys[game.appId]?.let { catalogueUi.priceCache[it] } },
                    onDetails = { game ->
                        val initial = SteamTopSeller(
                            appId = game.appId,
                            title = game.title,
                            headerImageUrl = "https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/${game.appId}/header.jpg"
                        )
                        leaderDetailsGame = initial
                        leaderDetailsLoading = true
                        detailExecutor.execute {
                            val detailed = checkTopSellerRu(initial)
                            runOnUiThread {
                                if (leaderDetailsGame?.appId == game.appId) {
                                    leaderDetailsGame = detailed
                                    leaderDetailsLoading = false
                                }
                            }
                        }
                    },
                    onShowMore = { catalogueUi.wishlistVisibleLimit += 30 }
                )
            }
            }
            ScreenSection {
            if (activeTab == 4 && !settingsOpen) {
                if (topBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (topError.isNotBlank()) Text("Не удалось загрузить игры. Попробуйте ещё раз.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SteamLeaderSection.values().forEach { section ->
                        FilterChip(colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFFFFE2A6), selectedLabelColor = Color(0xFF17212D)), selected = leaderSection == section,
                            onClick = { leaderSection = section; topError = "" },
                            label = { Text(section.label, maxLines = 1, style = MaterialTheme.typography.labelSmall) })
                    }
                }
                val sectionGames = remember(leaderSection, newReleaseGames, topGames, discountedExtraGames, themedSaleGames) {
                    when (leaderSection) {
                        SteamLeaderSection.POPULAR_NEW_RELEASES -> newReleaseGames
                        SteamLeaderSection.TOP_SELLERS -> topGames
                        SteamLeaderSection.DISCOUNTED_TOP_SELLERS -> topGames + discountedExtraGames
                        SteamLeaderSection.THEMED_SALES -> themedSaleGames
                    }
                }
                val reviewFiltersActive = (leaderFilterInput.minimumReviewText.toIntOrNull() ?: 0) > 0 ||
                    (leaderFilterInput.minimumReviewCountText.toLongOrNull() ?: 0L) > 0L
                LaunchedEffect(sectionGames, reviewFiltersActive) {
                    if (reviewFiltersActive) {
                        val loaded = withContext(Dispatchers.IO) { loadReviewSummaries(sectionGames) }
                        if (loaded.isNotEmpty()) {
                            fun attachReviews(games: List<SteamTopSeller>) = games.map { game ->
                                loaded[game.appId]?.let { game.copy(reviewSummary = it) } ?: game
                            }
                            topGames = attachReviews(topGames)
                            newReleaseGames = attachReviews(newReleaseGames)
                            discountedExtraGames = attachReviews(discountedExtraGames)
                        }
                    }
                }
                val countLabel = when (leaderSection) {
                    SteamLeaderSection.TOP_SELLERS -> "Загружено: ${sectionGames.size} из 100"
                    SteamLeaderSection.POPULAR_NEW_RELEASES -> "Новинок: ${sectionGames.size}"
                    SteamLeaderSection.DISCOUNTED_TOP_SELLERS -> "Со скидкой: ${SteamLeaderSection.DISCOUNTED_TOP_SELLERS.filter(sectionGames).size}"
                    SteamLeaderSection.THEMED_SALES -> "Игры в текущих фестах: ${sectionGames.size}"
                }
                val availableLeaderGenres = remember(sectionGames) {
                    sectionGames.asSequence().flatMap { it.genres.asSequence() }.distinct().sorted().toList()
                }
                Text(countLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SteamLeaderFiltersPanel(
                    leaderFilterInput,
                    { leaderFilterInput = it },
                    availableGenres = availableLeaderGenres,
                    availabilityFilter = topAvailabilityFilter,
                    onAvailabilitySelected = { filter ->
                        topAvailabilityFilter = filter
                    },
                    onReset = { topAvailabilityFilter = "Все игры" }
                )
                val availabilityFilteredGames = remember(sectionGames, leaderSection, topAvailabilityFilter) {
                    leaderSection.filter(sectionGames).filter { game ->
                        game.isGame && when (topAvailabilityFilter) {
                            "Доступны в Steam RU" -> game.availability == SteamRuAvailability.AVAILABLE
                            "Недоступны в Steam RU" -> game.availability == SteamRuAvailability.UNAVAILABLE
                            else -> true
                        }
                    }
                }
                val visibleTopGames = remember(availabilityFilteredGames, leaderFilterInput) {
                    SteamLeaderFilter.apply(availabilityFilteredGames, leaderFilterInput.toFilters())
                }
                LaunchedEffect(leaderSection, leaderFilterInput, topAvailabilityFilter) {
                    leaderVisibleLimit = 20
                }
                val displayedTopGames = remember(visibleTopGames, leaderVisibleLimit) {
                    visibleTopGames.take(leaderVisibleLimit)
                }
                val leaderHasMore = when (leaderSection) {
                    SteamLeaderSection.POPULAR_NEW_RELEASES -> newReleaseHasMore
                    SteamLeaderSection.DISCOUNTED_TOP_SELLERS -> discountedHasMore
                    SteamLeaderSection.THEMED_SALES, SteamLeaderSection.TOP_SELLERS -> false
                }
                val leaderInitialLoaded = when (leaderSection) {
                    SteamLeaderSection.POPULAR_NEW_RELEASES -> newReleaseInitialLoaded
                    SteamLeaderSection.DISCOUNTED_TOP_SELLERS -> discountedInitialLoaded
                    SteamLeaderSection.THEMED_SALES -> themedSalesInitialLoaded
                    SteamLeaderSection.TOP_SELLERS -> false
                }
                LaunchedEffect(activeTab, settingsOpen, leaderSection, leaderFilterInput, topAvailabilityFilter,
                    visibleTopGames.size, newReleaseNextStart, discountedNextStart, leaderHasMore,
                    leaderInitialLoaded, topBusy, loadMoreBusy) {
                    if (activeTab == 4 && !settingsOpen && leaderInitialLoaded && !topBusy && !loadMoreBusy &&
                        SteamLeaderFilter.shouldLoadMoreMatching(leaderSection, visibleTopGames.size, leaderHasMore)) {
                        loadMoreLeaderGames()
                    }
                }
                Text("Показано: ${visibleTopGames.size}", style = MaterialTheme.typography.labelMedium)
                if (leaderInitialLoaded) {
                    SteamLeaderFilter.paginationStatus(
                        leaderSection,
                        visibleTopGames.size,
                        leaderHasMore,
                        topBusy || loadMoreBusy
                    )?.let { status ->
                        Text(status, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!topBusy && topError.isBlank() && visibleTopGames.isEmpty()) {
                    Text(
                        when (leaderSection) {
                            SteamLeaderSection.DISCOUNTED_TOP_SELLERS ->
                                "В загруженном топе нет игр с подтверждённой скидкой Steam RU."
                            SteamLeaderSection.THEMED_SALES ->
                                "В текущих тематических распродажах нет подходящих игр со скидкой в Steam RU."
                            else -> "Подходящих игр пока нет."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val sectionHasMore = when (leaderSection) {
                    SteamLeaderSection.POPULAR_NEW_RELEASES -> newReleaseHasMore
                    SteamLeaderSection.DISCOUNTED_TOP_SELLERS -> discountedHasMore
                    SteamLeaderSection.THEMED_SALES, SteamLeaderSection.TOP_SELLERS -> false
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                    contentPadding = PaddingValues(vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(displayedTopGames, key = { _, game -> game.appId }) { index, game ->
                    val cacheKey = remember(game.appId, game.title, manualFilters, rulesRevision, ignoredIds,
                        searchTotalPages, comparisonSavedAliases[game.appId]) { comparisonKey(game.appId, game.title, true) }
                    SteamLeaderCard(index, game, leaderOfferPrices[game.appId]?.takeIf { it.scopeKey == cacheKey },
                        searchEnabled = !catalogueUi.priceBatch.running && manualFilters.valid,
                        cachedPrices = catalogueUi.priceCache[cacheKey],
                        onOpenSearch = {
                                    if (!catalogueUi.priceBatch.running) {
                                        searchGeneration++
                                        activeCatalogGeneration.set(searchGeneration)
                                        searching = false
                                    }
                                    steamGame = null
                                    selectedGame = GameSearchResolver.Candidate(game.appId, game.title)
                                    query = game.title
                                    offers = emptyList()
                                    searchMessage = "Игра выбрана. Нажмите «Искать», чтобы проверить предложения с активацией в РФ."
                                    activeTab = 0

                        },
                        onDetails = { leaderDetailsGame = game; leaderDetailsLoading = false },
                        onSearchPrice = {
                            startCatalogSearch(
                                candidateOverride = GameSearchResolver.Candidate(game.appId, game.title),
                                leaderboardAppId = game.appId
                            )
                        },
                        onMonitor = { openGameMonitoring(game.appId, game.title) })
                    }
                }
                if (visibleTopGames.size > leaderVisibleLimit) {
                    OutlinedButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = { leaderVisibleLimit += 20 }, modifier = Modifier.fillMaxWidth()) {
                        Text("Показать ещё · ${minOf(leaderVisibleLimit, visibleTopGames.size)} из ${visibleTopGames.size}")
                    }
                }
                if (sectionHasMore) {
                    OutlinedButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), 
                        onClick = { loadMoreLeaderGames() },
                        enabled = !loadMoreBusy && !topBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (loadMoreBusy) "Загружаем…" else "Загрузить ещё")
                    }
                }

            }
                leaderDetailsGame?.let { game ->
                    AlertDialog(
                        onDismissRequest = { leaderDetailsGame = null },
                        title = { Text(game.title) },
                        text = {
                            Column(
                                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                SteamArtwork(game.headerImageUrl, Modifier.fillMaxWidth().height(176.dp), appId = game.appId)
                                if (game.description.isNotBlank()) {
                                    Text(Html.fromHtml(game.description, Html.FROM_HTML_MODE_COMPACT).toString().trim())
                                } else if (leaderDetailsLoading) {
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                    Text("Загружаем описание Steam…")
                                } else {
                                    Text("Описание Steam для этой игры не получено.")
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { leaderDetailsGame = null }) { Text("Закрыть") }
                        }
                    )
                }
            }
            ScreenSection {
            if (settingsOpen && !showDirectory) {
                Text("Настройки", style = MaterialTheme.typography.titleLarge)
                val versionName = remember { packageManager.getPackageInfo(packageName, 0).versionName ?: "—" }
                Text("Версия $versionName", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                OutlinedButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = { showDirectory = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Справочник игр и правила DLC")
                }
                Text("Сохранённые правила справочника продолжают работать в поиске и мониторинге.", style = MaterialTheme.typography.bodySmall)
            }
            }
            ScreenSection {
            if (settingsOpen && showDirectory) {
                TextButton(onClick = { showDirectory = false }) { Text("← Назад к настройкам") }
                Text("Справочник игр", style = MaterialTheme.typography.titleLarge)
                Text("Ручные правила применяются к поиску и мониторингу. Обновление Steam их не удаляет.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(directoryGame, {
                    directoryGame = it
                    directorySuggestions = emptyList()
                    directorySelectedAppId = null
                    directoryGameInfo = null
                }, label = { Text("Название игры или часть названия") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                val savedGames = remember(rulesRevision) { GameRuleStore.names(this@MainActivity) }
                val localSuggestions = (savedGames + queryHistory).distinct()
                    .filter { directoryGame.isNotBlank() && it.contains(directoryGame.trim(), ignoreCase = true) }
                    .take(8)
                if (localSuggestions.isNotEmpty()) {
                    Text("Совпадения в истории и справочнике", style = MaterialTheme.typography.labelMedium)
                    localSuggestions.forEach { candidate ->
                        OutlinedButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = {
                            directoryGame = candidate
                            directoryCanonical = GameRuleStore.read(this@MainActivity, candidate).optString("canonical")
                            directoryGameInfo = null
                            directorySuggestions = emptyList()
                            directorySelectedAppId = null
                        }, modifier = Modifier.fillMaxWidth().height(34.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                            Text(candidate, modifier = Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                OutlinedButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = {
                    val term = directoryGame.trim()
                    directoryBusy = true
                    directoryMessage = "Ищем игры в Steam…"
                    Thread {
                        val suggestions = SteamGameReference.searchGames(term)
                        runOnUiThread {
                            if (directoryGame.trim() == term) {
                                directorySuggestions = suggestions
                                directoryMessage = if (suggestions.isEmpty()) "Совпадений не найдено" else "Выберите полное название игры"
                            }
                            directoryBusy = false
                        }
                    }.start()
                }, enabled = directoryGame.isNotBlank() && !directoryBusy) { Text("Найти название в Steam") }
                directorySuggestions.forEach { candidate ->
                    OutlinedButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = {
                        directoryGame = candidate.name
                        directorySelectedAppId = candidate.id
                        directoryCanonical = GameRuleStore.read(this@MainActivity, candidate.name).optString("canonical")
                        directoryGameInfo = null
                        directorySuggestions = emptyList()
                    }, modifier = Modifier.fillMaxWidth().height(34.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                        Text(candidate.name, modifier = Modifier.fillMaxWidth(),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (savedGames.isNotEmpty()) {
                    Text("Ранее изменённые игры", style = MaterialTheme.typography.titleSmall)
                    savedGames.forEach { saved ->
                        OutlinedButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = {
                            directoryGame = saved
                            directorySelectedAppId = null
                            directoryCanonical = GameRuleStore.read(this@MainActivity, saved).optString("canonical")
                            directoryGameInfo = null
                        }, modifier = Modifier.fillMaxWidth().height(34.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                            Text(saved, modifier = Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                OutlinedButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = {
                    val name = directoryGame.trim()
                    val appId = directorySelectedAppId
                    directoryCanonical = GameRuleStore.read(this@MainActivity, name).optString("canonical")
                    directoryBusy = true
                    directoryMessage = "Получаем данные Steam..."
                    Thread {
                        val game = SteamGameReference.lookup(this@MainActivity, name, appId)
                        runOnUiThread {
                            if (directoryGame.trim() == name && directorySelectedAppId == appId) {
                                directoryGameInfo = game
                                directoryMessage = "${game.canonical} · дополнений: ${game.dlcNames.size}"
                            }
                            directoryBusy = false
                        }
                    }.start()
                }, enabled = directoryGame.isNotBlank() && !directoryBusy) { Text("Открыть / обновить из Steam") }
                if (directoryBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                OutlinedCard(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, Color(0xFFDCE2E8)),
                    colors = CardDefaults.outlinedCardColors(containerColor = Color.White)) {
                    Text(directoryMessage, Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (directoryGame.isNotBlank()) {
                    val directoryRules = remember(directoryGame, rulesRevision) {
                        GameRuleStore.read(this@MainActivity, directoryGame)
                    }
                    Text("Официальное название: ${directoryGameInfo?.canonical ?: "ещё не загружено"}")
                    OutlinedTextField(directoryCanonical, { directoryCanonical = it },
                        label = { Text("Название основной игры (ручная правка)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = {
                        val data = GameRuleStore.read(this@MainActivity, directoryGame)
                        data.put("canonical", directoryCanonical.trim())
                        GameRuleStore.save(this@MainActivity, directoryGame, data)
                        rulesRevision++
                        directoryMessage = "Название сохранено"
                    }) { Text("Сохранить название") }
                    Text("Известные DLC из Steam", style = MaterialTheme.typography.titleSmall)
                    if (directoryGameInfo?.dlcNames.isNullOrEmpty()) Text("Нет данных Steam или DLC не найдены", style = MaterialTheme.typography.bodySmall)
                    directoryGameInfo?.dlcNames?.forEach { dlc ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(dlc, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = {
                                GameRuleStore.add(this@MainActivity, directoryGame, "blocked", dlc)
                                rulesRevision++
                            }) { Text("Исключить") }
                        }
                    }
                    listOf("blocked" to "Исключаемые названия / DLC", "allowed" to "Разрешённые издания").forEach { (field, title) ->
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        GameRuleStore.words(directoryRules, field).forEach { value ->
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = {
                                    GameRuleStore.remove(this@MainActivity, directoryGame, field, value)
                                    rulesRevision++
                                }) { Text("Удалить") }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("blocked" to "DLC", "allowed" to "Издание").forEach { (field, label) ->
                            FilterChip(colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFFFFE2A6), selectedLabelColor = Color(0xFF17212D)), selected = directoryField == field, onClick = { directoryField = field }, label = { Text(label) })
                        }
                    }
                    OutlinedTextField(directoryInput, { directoryInput = it }, label = { Text("Название или отличительная часть") },
                        modifier = Modifier.fillMaxWidth())
                    OutlinedButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = {
                        GameRuleStore.add(this@MainActivity, directoryGame, directoryField, directoryInput)
                        directoryInput = ""
                        rulesRevision++
                    }, enabled = directoryInput.isNotBlank()) { Text("Добавить правило") }
                }
            }
            }
            } // прокручиваемое содержимое
        } // экран с вкладками
    }
    @Composable
    private fun SteamRuAvailabilityFilter(selected: String, onSelected: (String) -> Unit) {
        var expanded by remember { mutableStateOf(false) }
        val choices = listOf("Все игры", "Доступны в Steam RU", "Недоступны в Steam RU")
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.height(32.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                border = BorderStroke(1.dp, Color(0xFFD2D9E1))
            ) {
                Text(
                    when (selected) {
                        "Доступны в Steam RU" -> "Steam RU · Да"
                        "Недоступны в Steam RU" -> "Steam RU · Нет"
                        else -> "Steam RU · Все"
                    },
                    maxLines = 1,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                choices.forEach { choice ->
                    DropdownMenuItem(text = { Text(choice) }, onClick = { onSelected(choice); expanded = false })
                }
            }
        }
    }

    @Composable
    private fun SteamLeaderFiltersPanel(
        input: SteamLeaderFilterInput,
        onInput: (SteamLeaderFilterInput) -> Unit,
        includeReviewFilters: Boolean = true,
        availableGenres: List<String> = emptyList(),
        availabilityFilter: String? = null,
        onAvailabilitySelected: ((String) -> Unit)? = null,
        onReset: (() -> Unit)? = null
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (availabilityFilter != null && onAvailabilitySelected != null) {
                    SteamRuAvailabilityFilter(availabilityFilter, onAvailabilitySelected)
                }
                SteamFeatureFilterChoices(input, onInput)
                SteamGenreFilterChoices(input, onInput, availableGenres)
                OutlinedButton(
                    onClick = { onInput(input.copy(expanded = !input.expanded)) },
                    modifier = Modifier.height(32.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    border = BorderStroke(1.dp, Color(0xFFD2D9E1))
                ) {
                    val activeCount = input.activeFilterCount
                    Text(if (input.expanded) "Скрыть" else if (activeCount > 0) "Ещё · $activeCount" else "Ещё",
                        maxLines = 1, style = MaterialTheme.typography.labelSmall)
                }
            }
            if (input.expanded) {
                OutlinedCard(
                    Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color(0xFFDCE2E8)),
                    colors = CardDefaults.outlinedCardColors(containerColor = Color.White)
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(value = input.titleQuery,
                            onValueChange = { onInput(input.copy(titleQuery = it)) },
                            label = { Text("Название содержит") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = input.maxPriceText,
                                onValueChange = { onInput(input.copy(maxPriceText = it)) },
                                label = { Text("Цена до, ₽", maxLines = 1) }, singleLine = true,
                                isError = input.maxPriceText.isNotBlank() &&
                                    input.maxPriceText.trim().replace(',', '.').toDoubleOrNull()?.let { it > 0.0 } != true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f))
                            OutlinedTextField(value = input.minimumDiscountText,
                                onValueChange = { onInput(input.copy(minimumDiscountText = it)) },
                                label = { Text("Скидка от, %", maxLines = 1) }, singleLine = true,
                                isError = input.minimumDiscountText.isNotBlank() &&
                                    input.minimumDiscountText.toIntOrNull()?.let { it in 0..100 } != true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f))
                        }
                        if (includeReviewFilters) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value = input.minimumReviewText,
                                    onValueChange = { onInput(input.copy(minimumReviewText = it)) },
                                    label = { Text("Рейтинг от, %", maxLines = 1) }, singleLine = true,
                                    isError = input.minimumReviewText.isNotBlank() &&
                                        input.minimumReviewText.toIntOrNull()?.let { it in 0..100 } != true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f))
                                OutlinedTextField(value = input.minimumReviewCountText,
                                    onValueChange = { onInput(input.copy(minimumReviewCountText = it)) },
                                    label = { Text("Отзывов от, шт.", maxLines = 1) }, singleLine = true,
                                    isError = input.minimumReviewCountText.isNotBlank() &&
                                        input.minimumReviewCountText.trim().toLongOrNull()?.let { it > 0 } != true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f))
                            }
                        }
                        if (input.hasInvalidInput) Text(
                            "Цена и число отзывов должны быть больше 0, проценты — от 0 до 100.",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = {
                                onInput(SteamLeaderFilterInput(expanded = true))
                                onReset?.invoke()
                            },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) { Text("Сбросить") }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SteamGenreFilterChoices(
        input: SteamLeaderFilterInput,
        onInput: (SteamLeaderFilterInput) -> Unit,
        availableGenres: List<String>
    ) {
        var menuExpanded by remember { mutableStateOf(false) }
        val selectedLabel = if (input.selectedGenres.isEmpty()) "Жанры" else "Жанры · ${input.selectedGenres.size}"
        Box {
            OutlinedButton(onClick = { menuExpanded = true }, enabled = availableGenres.isNotEmpty(),
                modifier = Modifier.height(32.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                border = BorderStroke(1.dp, Color(0xFFD2D9E1))) {
                Text(if (availableGenres.isEmpty()) "Жанры · …" else selectedLabel,
                    maxLines = 1, style = MaterialTheme.typography.labelSmall)
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false },
                modifier = Modifier.heightIn(max = 360.dp)) {
                availableGenres.forEach { genre ->
                    DropdownMenuItem(text = { Text(genre) }, onClick = {
                        val next = input.selectedGenres.toMutableSet().apply { if (!add(genre)) remove(genre) }
                        onInput(input.copy(selectedGenres = next))
                    }, leadingIcon = { Checkbox(checked = genre in input.selectedGenres, onCheckedChange = null) })
                }
                if (input.selectedGenres.isNotEmpty()) {
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Очистить выбор") }, onClick = {
                        onInput(input.copy(selectedGenres = emptySet())); menuExpanded = false
                    })
                }
            }
        }
    }

    @Composable
    private fun SteamFeatureFilterChoices(input: SteamLeaderFilterInput, onInput: (SteamLeaderFilterInput) -> Unit) {
        var languageExpanded by remember { mutableStateOf(false) }
        var controllerExpanded by remember { mutableStateOf(false) }
        val languageLabel = when (input.russianLocalization) {
            SteamRussianLocalization.SUPPORTED -> "Русский · Да"
            SteamRussianLocalization.NOT_SUPPORTED -> "Русский · Нет"
            SteamRussianLocalization.UNKNOWN -> "Русский · ?"
            null -> "Русский"
        }
        val controllerLabel = when (input.controllerSupport) {
            SteamControllerSupport.FULL -> "Геймпад · Полная"
            SteamControllerSupport.PARTIAL -> "Геймпад · Частичная"
            SteamControllerSupport.NONE -> "Геймпад · Нет"
            SteamControllerSupport.UNKNOWN -> "Геймпад · ?"
            null -> "Геймпад"
        }
        Box {
            OutlinedButton(onClick = { languageExpanded = true }, modifier = Modifier.height(32.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                border = BorderStroke(1.dp, Color(0xFFD2D9E1))) {
                Text(languageLabel, maxLines = 1, style = MaterialTheme.typography.labelSmall)
            }
            DropdownMenu(expanded = languageExpanded, onDismissRequest = { languageExpanded = false }) {
                listOf(null to "Любой", SteamRussianLocalization.SUPPORTED to "Русский есть",
                    SteamRussianLocalization.NOT_SUPPORTED to "Русского нет",
                    SteamRussianLocalization.UNKNOWN to "Не указано").forEach { (value, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = {
                        onInput(input.copy(russianLocalization = value)); languageExpanded = false
                    })
                }
            }
        }
        Box {
            OutlinedButton(onClick = { controllerExpanded = true }, modifier = Modifier.height(32.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                border = BorderStroke(1.dp, Color(0xFFD2D9E1))) {
                Text(controllerLabel, maxLines = 1, style = MaterialTheme.typography.labelSmall)
            }
            DropdownMenu(expanded = controllerExpanded, onDismissRequest = { controllerExpanded = false }) {
                listOf(null to "Любой", SteamControllerSupport.FULL to "Полная поддержка",
                    SteamControllerSupport.PARTIAL to "Частичная поддержка",
                    SteamControllerSupport.NONE to "Нет поддержки",
                    SteamControllerSupport.UNKNOWN to "Не указано").forEach { (value, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = {
                        onInput(input.copy(controllerSupport = value)); controllerExpanded = false
                    })
                }
            }
        }
    }

    @Composable
    private fun WishlistPanel(
        profiles: List<WishlistProfile>,
        selectedId: String?,
        games: List<WishlistGame>,
        filterInput: SteamLeaderFilterInput,
        onFilterInput: (SteamLeaderFilterInput) -> Unit,
        input: String,
        busy: Boolean,
        message: String,
        importedAt: Long,
        visibleLimit: Int,
        genresLoading: Boolean = false,
        searchingKeys: Set<String>,
        searchStatuses: Map<String, String>,
        searchEnabled: Boolean,
        onInput: (String) -> Unit,
        onImport: () -> Unit,
        onSelect: (WishlistProfile) -> Unit,
        onDelete: () -> Unit,
        onOpenSearch: (WishlistGame) -> Unit,
        onSearchPrice: (WishlistGame) -> Unit,
        onDetails: (WishlistGame) -> Unit,
        onShowMore: () -> Unit,
        onMonitor: (WishlistGame) -> Unit,
        cachedPrices: (WishlistGame) -> CachedGamePrices?
    ) {
        Text("ЖЕЛАЕМОЕ STEAM", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (profiles.isNotEmpty()) {
            Text("Профили", style = MaterialTheme.typography.titleMedium)
            profiles.forEach { profile ->
                TextButton(onClick = { onSelect(profile) }, modifier = Modifier.fillMaxWidth()) {
                    Text((if (profile.steamId64 == selectedId) "●  " else "○  ") + profile.displayName,
                        modifier = Modifier.fillMaxWidth(), maxLines = 1)
                }
            }
        }
        OutlinedTextField(
            input, onInput,
            label = { Text("Новый профиль Steam") },
            placeholder = { Text("Вставьте ссылку на открытый профиль") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        val selected = profiles.firstOrNull { it.steamId64 == selectedId }
        OutlinedButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = onImport, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "Загружаем из Steam…" else if (input.isBlank() && selected != null)
                "Обновить «${selected.displayName}» · Steam RU" else "Загрузить и закрепить профиль")
        }
        if (selected != null) TextButton(onClick = onDelete) { Text("Удалить профиль «${selected.displayName}»") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (importedAt > 0L) {
            val imported = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru", "RU"))
                .format(java.util.Date(importedAt))
            Text("${selected?.displayName ?: "Последний импорт"} · обновлено $imported · игр: ${games.size}", style = MaterialTheme.typography.bodySmall)
        }
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall,
            color = if (message.startsWith("Не удалось")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        if (genresLoading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Загружаем жанры для сохранённых игр…", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        val availableGenres = remember(games) {
            games.asSequence().flatMap { it.genres.asSequence() }.distinct().sorted().toList()
        }
        SteamLeaderFiltersPanel(filterInput, onFilterInput, includeReviewFilters = false,
            availableGenres = availableGenres)
        val visibleGames = remember(games, filterInput) {
            SteamLeaderFilter.applyWishlist(games, filterInput.toFilters())
        }
        Text("Показано: ${visibleGames.size} из ${games.size}", style = MaterialTheme.typography.labelMedium)
        if (games.isEmpty() && importedAt == 0L) {
            Text("Вставьте открытый профиль Steam и загрузите его, чтобы закрепить в списке профилей.", style = MaterialTheme.typography.bodyMedium)
        } else if (games.isNotEmpty() && visibleGames.isEmpty()) {
            Text("По выбранным фильтрам игр нет.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
            contentPadding = PaddingValues(vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
        items(visibleGames.take(visibleLimit), key = { it.appId }) { game ->
            OutlinedCard(Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFFDCE2E8)), colors = CardDefaults.outlinedCardColors(containerColor = Color.White)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { onOpenSearch(game) }, enabled = game.title.isNotBlank() && searchEnabled,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp), contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF111820))) {
                            Text(game.title.ifBlank { "Название игры не получено" }, modifier = Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                                color = Color(0xFF111820), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        val prices = cachedPrices(game)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SteamArtwork("https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/${game.appId}/library_600x900.jpg",
                                Modifier.size(76.dp, 114.dp), appId = game.appId)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                SteamGenreLine(game.genres)
                                SteamFeatureBadges(game.features)
                            }
                        }
                        DealPriceStrip(game.steamPrice, game.discountPercent,
                            prices?.plati?.price ?: game.lowestPlatiPrice,
                            prices?.ggsel?.price ?: game.lowestGgselPrice)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { onSearchPrice(game) },
                                enabled = searchEnabled && selectedId != null && "$selectedId:${game.appId}" !in searchingKeys,
                                modifier = Modifier.weight(1f).height(36.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), border = BorderStroke(1.dp, Color(0xFFD2D9E1))) {
                                Text(if (selectedId != null && "$selectedId:${game.appId}" in searchingKeys) "Ищем цены…" else "Цены",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = Color(0xFF17212D), maxLines = 1)
                            }
                            OutlinedButton(onClick = { onMonitor(game) }, enabled = game.appId > 0,
                                modifier = Modifier.weight(1f).height(36.dp), contentPadding = PaddingValues(horizontal = 3.dp, vertical = 0.dp),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), border = BorderStroke(1.dp, Color(0xFFD2D9E1))) {
                                Text("Следить",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                                    color = Color(0xFF17212D), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            OutlinedButton(onClick = { onDetails(game) }, modifier = Modifier.weight(1f).height(36.dp),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp), border = BorderStroke(1.dp, Color(0xFFD2D9E1))) {
                                Text("Подробнее", style = MaterialTheme.typography.labelMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = Color(0xFF17212D), maxLines = 1)
                            }
                        }
                        val searchKey = selectedId?.let { "$it:${game.appId}" }
                        PriceChecksSummary(prices)
                        searchKey?.let { key -> searchStatuses[key]?.let { status ->
                            Text(status, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (status.startsWith("Ошибка")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        } }
                    }
            }
        }
        }
        if (visibleGames.size > visibleLimit) {
            OutlinedButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = onShowMore, modifier = Modifier.fillMaxWidth()) {
                Text("Показать ещё · ${minOf(visibleLimit, visibleGames.size)} из ${visibleGames.size}")
            }
        }
    }

    @Composable
    private fun PriceHistoryChart(history:List<WatchHistory.Point>) {
        val prices=history.mapNotNull {it.price}
        val low=prices.minOrNull() ?: return
        val high=prices.maxOrNull() ?: return
        Canvas(Modifier.fillMaxWidth().height(100.dp).padding(vertical=8.dp)) {
            val range=(high-low).takeIf {it>0} ?: 1.0
            val first=history.first().at
            val duration=(history.last().at-first).coerceAtLeast(1L).toDouble()
            var previous:Offset?=null
            for(point in history) {
                val price=point.price
                if(price==null) {previous=null;continue}
                val position=Offset(if(history.size==1) size.width/2 else ((point.at-first)/duration*size.width).toFloat(),
                    (size.height-((price-low)/range).toFloat()*size.height).coerceIn(0f,size.height))
                previous?.let {drawLine(Color(0xFF1976D2),it,position,strokeWidth=2.dp.toPx())}
                drawCircle(Color(0xFF1976D2),radius=3.dp.toPx(),center=position)
                previous=position
            }
        }
    }
}

@Composable
private fun WatchListControls(
    filter: WatchListPresentation.Filter,
    sort: WatchListPresentation.Sort,
    visibleCount: Int,
    totalCount: Int,
    onFilter: (WatchListPresentation.Filter) -> Unit,
    onSort: (WatchListPresentation.Sort) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf(
            WatchListPresentation.Filter.ALL to "Все",
            WatchListPresentation.Filter.BELOW_TARGET to "Ниже цели",
            WatchListPresentation.Filter.ERRORS to "С ошибкой"
        ).forEach { (value, label) ->
            FilterChip(colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFFFFE2A6), selectedLabelColor = Color(0xFF17212D)), selected = filter == value, onClick = { onFilter(value) }, label = { Text(label) })
        }
    }
    var sortMenuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Показано $visibleCount из $totalCount", style = MaterialTheme.typography.bodySmall)
        Box {
            OutlinedButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = { sortMenuOpen = true }) {
                Text("Сортировка: ${when (sort) {
                    WatchListPresentation.Sort.ATTENTION -> "внимание"
                    WatchListPresentation.Sort.LOWEST_PRICE -> "цена"
                    WatchListPresentation.Sort.NAME -> "название"
                }}")
            }
            DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                listOf(
                    WatchListPresentation.Sort.ATTENTION to "Сначала проблемы",
                    WatchListPresentation.Sort.LOWEST_PRICE to "Сначала низкая цена",
                    WatchListPresentation.Sort.NAME to "По названию"
                ).forEach { (value, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = { onSort(value); sortMenuOpen = false }
                    )
                }
            }
        }
    }
}

