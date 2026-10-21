package ru.igrolov.app

import android.os.Bundle
import android.text.Html
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextDecoration
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
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.delay

data class Offer(val id: String, val name: String, val price: Double, val type: String, val rfStatus: String, val edition: String, val categoryLabel: String = "", val detailsChecked: Boolean = false, val variablePrice: Boolean = false, val available: Boolean? = null, val priceVerified: Boolean = false, val purchaseNote: String = "", val canonical:String="", val steamAppId:Int=0)

data class Tracked(val id: String, val name: String, val price: Double?, val status: String, val url: String, val canonical:String="", val steamAppId:Int=0)

class MainActivity : ComponentActivity() {
    // Bound concurrent product detail requests so large watch lists do not spawn unlimited threads.
    private val detailExecutor = Executors.newFixedThreadPool(4)
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val wishlistExecutor = Executors.newSingleThreadExecutor()
    private val catalogExecutor = Executors.newSingleThreadExecutor()
    private val suggestionExecutor = Executors.newSingleThreadExecutor()
    private val watchSuggestionGeneration=java.util.concurrent.atomic.AtomicInteger(0)
    private val suggestionGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    private val activeCatalogGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    override fun onDestroy() {
        activeCatalogGeneration.incrementAndGet()
        catalogExecutor.shutdownNow()
        suggestionGeneration.incrementAndGet()
        watchSuggestionGeneration.incrementAndGet()
        suggestionExecutor.shutdownNow()
        detailExecutor.shutdownNow()
        ioExecutor.shutdownNow()
        wishlistExecutor.shutdownNow()
        super.onDestroy()
    }
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val prefs by lazy { getSharedPreferences("tracked_products", MODE_PRIVATE) }
    private fun loadIds(): List<String> = prefs.getString("ids", "")!!.split(",").filter { it.isNotBlank() }
    private fun saveIds(ids: List<String>) = prefs.edit().putString("ids", ids.distinct().joinToString(",")).apply()
    private fun cachedTracked(id:String)=TrackedPriceCache.decode(id,MonitorRoomStore.readTrackedProduct(this,id))
    private fun cachedTrackedFromPreferences(id:String)=TrackedPriceCache.decode(id,prefs.getString("product_cache_$id",null))
    private fun cacheTracked(item:Tracked) {
        val cached=TrackedPriceCache.encode(item) ?: return
        prefs.edit().putString("product_cache_${item.id}",cached).apply()
        ioExecutor.execute { MonitorRoomStore.writeTrackedProduct(this,item.id,cached) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WatchScheduler.start(this)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFF315DA8),
                secondary = Color(0xFF087E72),
                tertiary = Color(0xFFB84F3A),
                background = Color(0xFFF6F7FB),
                surface = Color(0xFFFFFFFF)
            )) { Screen() }
        }
    }
    private fun fetch(id: String, callback: (Tracked) -> Unit) {
        detailExecutor.execute {
            val previous=cachedTracked(id)
            val result = try {
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
                    Tracked(id,product.optString("name","Товар $id"),proof.price.takeIf {proof.available},state,"https://plati.market/itm/$id",identity.canonical.takeIf {identity.confirmed}.orEmpty(),identity.appId)
                } finally { conn.disconnect() }
            } catch (e: Exception) {previous.copy(price=null,status="Ошибка загрузки: ${e.message ?: "нет соединения"}")}
            runOnUiThread { callback(result) }
        }
    }
    // Network requests are performed only on background threads.
    // Retry transient DNS/timeout/server failures without duplicating search results.
    private fun getCatalogJson(url: String): JSONObject {
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                conn.setRequestProperty("Accept", "application/json")
                try {
                    val status = conn.responseCode
                    if (status !in 200..299) {
                        if (status == 429 || status in 500..599) throw java.io.IOException("HTTP $status")
                        error("HTTP $status: поиск каталога недоступен")
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
                Thread.sleep(400L * (attempt + 1))
            }
        }
        throw last ?: java.io.IOException("Нет соединения")
    }

    private fun searchCatalog(query: String, pageCount: Int, generation: Int, selectedAppId: Int?, additionalAliases: List<String>, progress: (Int, Int, Int) -> Unit, callback: (List<Offer>, String, List<String>) -> Unit) {
        catalogExecutor.execute {
            val found = mutableListOf<Offer>()
            val seenIds = mutableSetOf<String>()
            val sellerIds = mutableMapOf<String, String>()
            var failure = ""
            val reference = ManualGameReference.lookup(query,selectedAppId,additionalAliases)
            if (generation != activeCatalogGeneration.get()) return@execute
            if(selectedAppId != null && reference.appId != selectedAppId) {
                runOnUiThread { if(generation == activeCatalogGeneration.get()) callback(emptyList(),"Steam не подтвердил выбранную основную игру. Повторите запрос или выберите другую игру.",emptyList()) }
                return@execute
            }
            val marketQuery=ManualGameReference.catalogQuery(query,reference.aliases)
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
                    if (generation == activeCatalogGeneration.get()) progress(page, pageCount, found.size)
                }
                val totalPages = root.optJSONObject("result")?.optInt("total_pages", pageCount) ?: pageCount
                if (page >= totalPages) break
                }
            } catch (e: Exception) { failure = e.message ?: "Не удалось подключиться к каталогу" }
            val sorted = found.distinctBy { it.id }.sortedBy { it.price }
            val enriched = ManualCatalogEnricher().enrich(sorted, sellerIds, reference = reference,
                active = { generation == activeCatalogGeneration.get() && !Thread.currentThread().isInterrupted },
                progress = { checked, total ->
                    if (generation == activeCatalogGeneration.get()) runOnUiThread {
                        if (generation == activeCatalogGeneration.get()) progress(pageCount, pageCount, checked)
                    }
                })
            var aliasSuggestions = emptyList<String>()
            if (enriched.isEmpty() && failure.isBlank() && generation == activeCatalogGeneration.get()) {
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
                if (generation == activeCatalogGeneration.get()) callback(enriched, failure, aliasSuggestions)
            }
        }
    }

    // Steam store search, ordered by top sellers; free-to-play is excluded by Steam's filter.
    // This is a store ranking, not the separate Steam Charts revenue chart.
    private fun loadPaidTopSellers(callback: (List<String>, String) -> Unit) {
        Thread {
            val names = linkedSetOf<String>()
            var error = ""
            try {
                for (start in listOf(0, 50, 100, 150)) {
                    if (names.size >= 100) break
                    val url = "https://store.steampowered.com/search/results/?" +
                        "query&start=$start&count=50&dynamic_data=&sort_by=TopSellers" +
                        "&category1=998&hidef2p=1&infinite=1&json=1&cc=us&l=english"
                    val connection = URL(url).openConnection() as HttpURLConnection
                    connection.connectTimeout = 12000
                    connection.readTimeout = 20000
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Igrolov/1.0.8)")
                    try {
                        if (connection.responseCode !in 200..299) error("Steam HTTP ${connection.responseCode}")
                        val root = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                        val html = root.optString("results_html", "")
                        if (html.isBlank()) break
                        val anchors = Regex("""(?s)<a\b[^>]*class=\"[^\"]*search_result_row[^\"]*\"[^>]*>.*?</a>""")
                        val title = Regex("""(?s)<span\b[^>]*class=\"title\"[^>]*>(.*?)</span>""")
                        for (match in anchors.findAll(html)) {
                            val nameHtml = title.find(match.value)?.groupValues?.get(1) ?: continue
                            val plain = Html.fromHtml(nameHtml, Html.FROM_HTML_MODE_LEGACY).toString().trim()
                            if (plain.isNotBlank()) names.add(plain)
                            if (names.size >= 100) break
                        }
                    } finally { connection.disconnect() }
                }
                if (names.isEmpty()) error = "Steam не вернул список. Попробуйте обновить позже."
            } catch (e: Exception) { error = e.message ?: "Ошибка загрузки Steam" }
            runOnUiThread { callback(names.take(100), error) }
        }.start()
    }

    @Composable
    private fun Screen() {
        val savedFilters = remember {ManualSearchSettings.read(this@MainActivity)}
        var gameSuggestions by remember {mutableStateOf<List<GameSearchResolver.Candidate>>(emptyList())}
        var selectedGame by remember {mutableStateOf<GameSearchResolver.Candidate?>(null)}
        var suggestionBusy by remember {mutableStateOf(false)}
        var maxPrice by remember {mutableStateOf(savedFilters.maxPrice)}
        var confirmedRf by remember {mutableStateOf(savedFilters.confirmedRf)}
        var showRejections by remember {mutableStateOf(false)}
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
        var searchMessage by remember { mutableStateOf("Нажмите «Искать на Plati» для запроса к каталогу") }
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
        val rfOnly = true
        var editionFilter by remember { mutableStateOf(savedFilters.edition) }
        val manualFilters=ManualSearchFilters(selectedType,editionFilter,maxPrice,confirmedRf)
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
                suggestionExecutor.execute {
                    val suggestions=GameSearchResolver.suggest(requested,local,{suggestionGeneration.get()==generation}) { term ->
                        SteamGameReference.searchGames(term).map {GameSearchResolver.Candidate(it.id,it.name)}
                    }
                    runOnUiThread {if(suggestionGeneration.get()==generation) {gameSuggestions=suggestions;suggestionBusy=false}}
                }
            }
        }
        var items by remember { mutableStateOf(loadIds().map { cachedTrackedFromPreferences(it) }) }
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
        var wishlistProfile by remember {
            mutableStateOf(if (prefs.getString("wishlist_profiles", null).isNullOrBlank())
                prefs.getString("wishlist_profile", "") ?: "" else "")
        }
        var wishlistProfiles by remember {
            mutableStateOf(SteamWishlist.decodeProfiles(prefs.getString("wishlist_profiles", null)).ifEmpty {
                val legacyId = SteamWishlist.profileId(prefs.getString("wishlist_profile", "") ?: "")
                if (legacyId == null) emptyList() else listOf(WishlistProfile(
                    legacyId, "Steam " + legacyId, prefs.getString("wishlist_profile", legacyId) ?: legacyId,
                    SteamWishlist.decode(prefs.getString("wishlist_games", null)), prefs.getLong("wishlist_imported_at", 0L)
                ))
            })
        }
        var selectedWishlistId by remember {
            mutableStateOf(prefs.getString("wishlist_selected_profile", null) ?: wishlistProfiles.firstOrNull()?.steamId64)
        }
        var wishlistGames by remember {
            mutableStateOf(wishlistProfiles.firstOrNull { it.steamId64 == selectedWishlistId }?.games
                ?: SteamWishlist.decode(prefs.getString("wishlist_games", null)))
        }
        var wishlistBusy by remember { mutableStateOf(false) }
        var wishlistMessage by remember { mutableStateOf("") }
        var wishlistImportedAt by remember { mutableLongStateOf(prefs.getLong("wishlist_imported_at", 0L)) }
        var wishlistVisibleLimit by remember { mutableIntStateOf(50) }
        var wishlistSearchingKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
        var wishlistSearchStatuses by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
        var showAlertLog by remember { mutableStateOf(true) }
        var watchMessage by remember { mutableStateOf("Укажите название игры и цену в рублях") }
        fun persistWishlistProfiles(profiles: List<WishlistProfile>) {
            wishlistProfiles = profiles.distinctBy { it.steamId64 }.take(20)
            prefs.edit().putString("wishlist_profiles", SteamWishlist.encodeProfiles(wishlistProfiles)).apply()
        }
        fun reloadWatches() {
            ioExecutor.execute {
                val latestWatches=WatchStore.read(this@MainActivity).toString()
                val latestAlerts=AlertJournal.read(this@MainActivity).toString()
                runOnUiThread { watches=latestWatches; alertLog=latestAlerts }
            }
        }
        LaunchedEffect(Unit) {
            reloadWatches()
            val ids=loadIds()
            ioExecutor.execute {
                val restored=ids.map { id -> TrackedPriceCache.decode(id,MonitorRoomStore.readTrackedProduct(this@MainActivity,id)) }
                runOnUiThread {
                    items=items.map { current ->
                        if (current.price == null) restored.firstOrNull { it.id==current.id } ?: current else current
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
            val ids = items.map { it.id }
            if (ids.isEmpty()) { busy = false; return }
            var remaining = ids.size
            ids.forEach { id -> fetch(id) { found ->
                cacheTracked(found)
                items = items.map { current ->
                    if (current.id != id) current
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
        var topGames by remember { mutableStateOf<List<String>>(emptyList()) }
        var topBusy by remember { mutableStateOf(false) }
        var topError by remember { mutableStateOf("") }
        fun refreshTop() {
            if (topBusy) return
            topBusy = true
            loadPaidTopSellers { games, error ->
                topGames = games
                topError = error
                topBusy = false
            }
        }
        LaunchedEffect(activeTab, settingsOpen) {
            if (activeTab == 4 && !settingsOpen && topGames.isEmpty() && !topBusy) refreshTop()
        }
        fun importWishlist() {
            if (wishlistBusy) return
            val profile = wishlistProfile.trim().ifBlank {
                wishlistProfiles.firstOrNull { it.steamId64 == selectedWishlistId }?.input.orEmpty()
            }
            if (profile.isBlank()) {
                wishlistMessage = "Вставьте SteamID64 или ссылку на открытый профиль Steam."
                return
            }
            wishlistBusy = true
            wishlistMessage = "Загружаем профиль, список и цены Steam RU…"
            wishlistExecutor.execute {
                try {
                    val snapshot = SteamWishlist.fetchSnapshot(profile)
                    val timestamp = System.currentTimeMillis()
                    runOnUiThread {
                        val previous = wishlistProfiles.firstOrNull { it.steamId64 == snapshot.steamId64 }
                        val games = snapshot.games.map { game ->
                            game.copy(lowestPlatiPrice = previous?.games?.firstOrNull { it.appId == game.appId }?.lowestPlatiPrice)
                        }
                        val savedProfile = WishlistProfile(snapshot.steamId64, snapshot.displayName, profile, games, timestamp)
                        persistWishlistProfiles(listOf(savedProfile) + wishlistProfiles.filterNot { it.steamId64 == snapshot.steamId64 })
                        selectedWishlistId = snapshot.steamId64
                        wishlistProfile = ""
                        prefs.edit().putString("wishlist_profile", profile)
                            .putString("wishlist_games", SteamWishlist.encode(games))
                            .putString("wishlist_selected_profile", snapshot.steamId64)
                            .putLong("wishlist_imported_at", timestamp).apply()
                        wishlistGames = games
                        wishlistVisibleLimit = 50
                        wishlistImportedAt = timestamp
                        wishlistBusy = false
                        wishlistMessage = if (games.isEmpty()) "В открытом списке пока нет игр."
                            else "Сохранён профиль «${snapshot.displayName}». Игр: ${games.size}. Цены Steam RU обновлены. Нажмите на название игры для общего поиска или «Искать» для проверки цены в этом списке."
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        wishlistBusy = false
                        wishlistMessage = "Не удалось обновить список: ${e.message ?: "ошибка Steam"}. Сохранённая копия оставлена."
                    }
                }
            }
        }
        fun startCatalogSearch(
            candidateOverride: GameSearchResolver.Candidate? = null,
            wishlistProfileId: String? = null,
            hiddenWishlistSearch: Boolean = false
        ) {
            if (searching && candidateOverride == null) return
            if (!manualFilters.valid) {
                val key = if (wishlistProfileId != null && candidateOverride != null)
                    "$wishlistProfileId:${candidateOverride.appId}" else null
                if (key != null) wishlistSearchStatuses = wishlistSearchStatuses + (key to "Проверьте ценовой фильтр")
                else searchMessage = "Проверьте ценовой фильтр"
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
            if (requestedGame.isBlank()) return

            wishlistSearchingKeys.forEach { key -> wishlistSearchStatuses = wishlistSearchStatuses + (key to "Поиск остановлен") }
            wishlistSearchingKeys = emptySet()
            val wishlistKey = wishlistProfileId?.let { "$it:${identity?.appId ?: candidateOverride?.appId}" }
            if (hiddenWishlistSearch && wishlistKey != null) {
                wishlistSearchingKeys = setOf(wishlistKey)
                wishlistSearchStatuses = wishlistSearchStatuses + (wishlistKey to "Ищем минимальную цену…")
            }

            searchGeneration++
            activeCatalogGeneration.set(searchGeneration)
            val generation = searchGeneration
            visibleOfferLimit = 30
            searching = true
            searchPage = 0
            searchFound = 0
            if (!hiddenWishlistSearch) {
                offers = emptyList()
                searchMessage = if (candidateOverride != null)
                    "Автопоиск Plati · выполняется для «$requestedGame»…"
                else "Поиск предложений…"
                query = requestedGame
                if (identity != null) selectedGame = identity
                queryHistory = (listOf(requestedGame) + queryHistory.filterNot { it.equals(requestedGame, ignoreCase = true) }).take(20)
                searchPrefs.edit().putString("queries", JSONArray(queryHistory).toString()).apply()
                activeTab = 0
                settingsOpen = false
            }
            if (identity != null) ManualSearchSettings.rememberGame(this@MainActivity, identity)
            steamGame = null

            searchCatalog(requestedGame, searchTotalPages, generation, identity?.appId, identity?.aliases.orEmpty(), { page, _, count ->
                if (generation == searchGeneration) {
                    searchPage = page
                    searchFound = count
                }
            }) { found, failure, aliasSuggestions ->
                if (generation != searchGeneration) return@searchCatalog
                if (aliasSuggestions.isNotEmpty() && identity != null) {
                    searching = false
                    pendingAliasGame = identity
                    pendingAliasSuggestions = aliasSuggestions
                    pendingAliasProfileId = wishlistProfileId
                    pendingAliasHiddenWishlist = hiddenWishlistSearch
                    if (wishlistKey != null) {
                        wishlistSearchingKeys = wishlistSearchingKeys - wishlistKey
                        wishlistSearchStatuses = wishlistSearchStatuses + (wishlistKey to "Найдено другое название · требуется подтверждение")
                    }
                    if (!hiddenWishlistSearch) {
                        offers = emptyList()
                        searchMessage = "По точному названию Steam подтверждённая цена не найдена. Найдены возможные названия товара в каталоге."
                    }
                    return@searchCatalog
                }
                if (!hiddenWishlistSearch) offers = found
                searching = false
                val ready = found.count { ManualContentRules.visible(it, "Игра", "Все") }
                val unavailable = found.count { it.available != true }
                val uncertainPrice = found.count { it.available == true && !it.priceVerified }
                val wishlistSearch = wishlistProfileId != null && identity?.appId != null
                val wishlistRules = if (wishlistSearch) GameRuleStore.read(this@MainActivity, requestedGame) else null
                val minimum = if (wishlistSearch) found.asSequence()
                    .filter {
                        ManualSearchFilter.rejection(it, manualFilters, wishlistRules!!, ignoredIds) == null &&
                            it.available == true && it.priceVerified && it.price > 0.0
                    }
                    .minOfOrNull { it.price } else null
                if (wishlistSearch && wishlistProfileId != null && identity != null) {
                    val updatedProfiles = wishlistProfiles.map { profile ->
                        if (profile.steamId64 != wishlistProfileId || minimum == null) profile else profile.copy(games = profile.games.map { game ->
                            if (game.appId == identity.appId) game.copy(lowestPlatiPrice = minimum) else game
                        })
                    }
                    if (minimum != null) {
                        persistWishlistProfiles(updatedProfiles)
                        if (selectedWishlistId == wishlistProfileId) {
                            wishlistGames = updatedProfiles.firstOrNull { it.steamId64 == wishlistProfileId }?.games.orEmpty()
                            prefs.edit().putString("wishlist_games", SteamWishlist.encode(wishlistGames)).apply()
                        }
                    }
                    val key = "$wishlistProfileId:${identity.appId}"
                    wishlistSearchingKeys = wishlistSearchingKeys - key
                    wishlistSearchStatuses = wishlistSearchStatuses + (key to when {
                        failure.isNotBlank() -> "Ошибка поиска цен"
                        minimum != null -> "Минимум найден в Plati"
                        else -> "Подтверждённая цена не найдена"
                    })
                }
                if (!hiddenWishlistSearch) {
                    val summary = if (failure.isNotBlank()) "Ошибка поиска: $failure" else
                        "Проверено: ${found.size} · основная игра с подтверждёнными наличием и ценой: $ready. " +
                            "Скрыто без подтверждённого наличия: $unavailable; без точной цены варианта: $uncertainPrice." +
                            if (wishlistSearch) {
                                if (minimum != null) " Минимальная подтверждённая Plati цена сохранена в списке желаемого."
                                else " Подтверждённая Plati цена не найдена; сохранённое значение не изменено."
                            } else ""
                    searchMessage = if (candidateOverride != null) "Автопоиск Plati · $summary" else summary
                }
            }
        }
        fun dismissAliasPrompt() {
            val appId = pendingAliasGame?.appId
            val profileId = pendingAliasProfileId
            if (pendingAliasHiddenWishlist && appId != null && profileId != null) {
                val key = "$profileId:$appId"
                wishlistSearchStatuses = wishlistSearchStatuses + (key to "Подтверждённая цена не найдена")
            }
            pendingAliasGame = null
            pendingAliasSuggestions = emptyList()
            pendingAliasProfileId = null
        }
        val searchScroll = rememberScrollState()
        val productsScroll = rememberScrollState()
        val watchScroll = rememberScrollState()
        val directoryScroll = rememberScrollState()
        Column(Modifier.fillMaxSize()) {
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
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Игролов 1.1.3", style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = { settingsOpen = !settingsOpen; showDirectory = false }) {
                    Text(if (settingsOpen) "×" else "⚙", fontSize = 26.sp,
                        color = MaterialTheme.colorScheme.onSurface)
                }
            }
            TabRow(selectedTabIndex = activeTab) {
                listOf("Поиск", "Товары", "Мониторинг", "Желания", "Лидеры").forEachIndexed { index, title ->
                    Tab(selected = activeTab == index, onClick = { activeTab = index; settingsOpen = false; showDirectory = false }, text = { Text(title, maxLines = 1, fontSize = 10.sp) })
                }
            }
            Column(Modifier.fillMaxSize().verticalScroll(if (settingsOpen) directoryScroll else when(activeTab) { 0 -> searchScroll; 1 -> productsScroll; 2 -> watchScroll; else -> directoryScroll }).padding(16.dp)) {
            if (activeTab == 0 && !settingsOpen) {
            Text("Поиск предложений Steam · RUB", style = MaterialTheme.typography.bodySmall)
            if (searchMessage.startsWith("Автопоиск Plati")) {
                Text(searchMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(12.dp))
            Text("Поиск игры по названию", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(query, {
                query = it
                selectedGame = null
                gameSuggestions = emptyList()
                searchGeneration++
                activeCatalogGeneration.set(searchGeneration)
                searching = false
                offers = emptyList()
                steamGame = null
            }, label = { Text("Например, Cyberpunk 2077") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            if(selectedGame!=null) Text("Выбрано: ${selectedGame!!.name} · Steam #${selectedGame!!.appId}",style=MaterialTheme.typography.bodySmall)
            if(suggestionBusy) Text("Подбираем названия Steam…",style=MaterialTheme.typography.bodySmall)
            if(gameSuggestions.isNotEmpty() && selectedGame==null) {
                Text("Уточните игру · название и Steam ID",style=MaterialTheme.typography.titleSmall)
                gameSuggestions.forEach { candidate ->
                    TextButton(onClick={
                        selectedGame=candidate
                        suggestionGeneration.incrementAndGet()
                        suggestionBusy=false
                        gameSuggestions=emptyList()
                        query=candidate.name.replace(Regex("[™®©]"),"")
                        offers=emptyList()
                        searchGeneration++
                        activeCatalogGeneration.set(searchGeneration)
                        searching=false
                        ManualSearchSettings.rememberGame(this@MainActivity,candidate)
                    },modifier=Modifier.fillMaxWidth()) {Text("${candidate.name} · Steam #${candidate.appId}")}
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
                    FilterChip(selected = selectedType == type, onClick = { selectedType = type },
                        label = { Text(type) })
                }
            }
            FilterChip(selected=confirmedRf,onClick={confirmedRf=!confirmedRf},label={Text("Только подтверждённая РФ")})
            OutlinedTextField(maxPrice,{maxPrice=it},label={Text("Цена до, ₽")},singleLine=true,
                isError=!manualFilters.valid,supportingText={Text(if(manualFilters.valid) "Пустое поле — без ограничения" else "Укажите положительную цену")},modifier=Modifier.fillMaxWidth())
            Text("Состав товара", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Игра", "Все", "DLC").forEach { edition ->
                    FilterChip(selected = editionFilter == edition,
                        onClick = { editionFilter = edition }, label = { Text(edition) })
                }
            }
            Text("Основная игра должна быть явно распознана до показа. Недоступные товары и неподтверждённые цены вариантов скрываются.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { startCatalogSearch() },
                enabled = query.isNotBlank() && !searching && !suggestionBusy && manualFilters.valid) {
                Text(if (searching) "Ищем..." else "Искать на Plati")
            }
            if (searching) {
                LinearProgressIndicator(progress = { searchPage.toFloat() / searchTotalPages }, modifier = Modifier.fillMaxWidth())
                Text("Страниц обработано: $searchPage / $searchTotalPages · найдено кандидатов: $searchFound", style = MaterialTheme.typography.bodySmall)
            }
            Text(searchMessage, style = MaterialTheme.typography.bodySmall)
            Text("Поиск по Plati (до $catalogLimit товаров). Цена берётся из свежей карточки или расчёта выбранного варианта РФ.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))
            Spacer(Modifier.height(12.dp))
            } // конец настроек поиска
            if (activeTab == 1 && !settingsOpen) {
            OutlinedTextField(entry, { entry = it }, label = { Text("Ссылка Plati или ID товара") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
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
                    else if (items.any { it.id == id }) message = "Товар уже добавлен"
                    else { items = items + Tracked(id, "Товар $id", null, "Загрузка...", "https://plati.market/itm/$id"); saveIds(items.map { it.id }); entry = ""; message = "Товар добавлен"; fetch(id) { found -> cacheTracked(found); items = items.map { if (it.id == id) TrackedPriceCache.merge(it,found) else it } } }
                }) { Text("Добавить") }
                OutlinedButton(onClick = { refresh() }, enabled = !busy && items.isNotEmpty()) { Text(if (busy) "Обновление..." else "Обновить цены") }
            }
            Text(message, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))
            Text("Отслеживаемые товары: ${items.size}", style = MaterialTheme.typography.titleMedium)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items.sortedBy { it.price ?: Double.MAX_VALUE }.forEach { item ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(item.name, style = MaterialTheme.typography.titleMedium)
                            Text("ID ${item.id} · ${item.status}", style = MaterialTheme.typography.bodySmall)
                            Text(item.price?.let { String.format(Locale("ru", "RU"), "%.2f ₽", it) } ?: "Цена недоступна", style = MaterialTheme.typography.titleLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url))) }) { Text("Открыть Plati") }
                                TextButton(onClick = { items = items.filterNot { it.id == item.id }; saveIds(items.map { it.id }); prefs.edit().remove("product_cache_${item.id}").apply(); ioExecutor.execute { MonitorRoomStore.writeTrackedProduct(this@MainActivity,item.id,null) } }) { Text("Удалить") }
                            }
                        }
                    }
                }
            }
            } // конец вкладки товаров
            if (activeTab == 2 && !settingsOpen) {
            Text("Автоматическое отслеживание игр", style = MaterialTheme.typography.titleLarge)
            Text("Каждый кандидат проверяется: основная игра, Gift/Key, РФ, наличие и цена выбранного варианта. Затем выбираются три минимальные цены.", style = MaterialTheme.typography.bodySmall)
            Text("Проверяет примерно раз в час при наличии сети. Сообщает о новых предложениях до вашей цены и дальнейшем снижении уже найденной цены. Android может задерживать проверки.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(watchGame, { watchGame = it;watchSelection=null }, label = { Text("Название игры") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            if(watchSuggesting) Text("Подбираем игру Steam…",style=MaterialTheme.typography.bodySmall)
            if(watchSelection!=null) Text("Выбрано: ${watchSelection!!.name} · Steam #${watchSelection!!.appId}",style=MaterialTheme.typography.bodySmall)
            if(watchSelection==null) watchSuggestions.forEach {candidate ->
                TextButton(onClick={
                    watchSelection=candidate;watchGame=candidate.name.replace(Regex("[™®©]"),"")
                    watchSuggestionGeneration.incrementAndGet();watchSuggestions=emptyList();watchSuggesting=false
                    ManualSearchSettings.rememberGame(this@MainActivity,candidate)
                }) {Text("${candidate.name} · Steam #${candidate.appId}")}
            }
            OutlinedTextField(targetPrice, { targetPrice = it }, label = { Text("Желаемая цена, ₽") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Название игры и желаемая цена вводятся отдельно.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Все", "Gift", "Key").forEach { kind ->
                    FilterChip(selected = watchType == kind, onClick = { watchType = kind }, label = { Text(kind) })
                }
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = watchRf, onCheckedChange = { }, enabled = false)
                Text("Только с указанием РФ")
            }
            Button(onClick = {
                val amount = targetPrice.replace(',', '.').toDoubleOrNull()
                if (watchGame.isBlank() || amount == null || !amount.isFinite() || amount <= 0) {
                    watchMessage = "Введите название игры и положительную желаемую цену"
                } else {
                    val identity=watchSelection ?: GameSearchResolver.exact(watchGame,watchSuggestions+GameSearchResolver.seeds)
                    if(identity==null && watchSuggestions.isNotEmpty()) {
                        watchMessage="Выберите точную игру Steam"
                        return@Button
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
            },enabled=!watchSuggesting) { Text("Отслеживать игру") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    WatchScheduler.checkNow(this@MainActivity)
                    watchMessage = "Проверка запущена..."
                }, enabled = !checkingNow && JSONArray(watches).length() > 0) {
                    Text(if (checkingNow) "Проверяем..." else "Проверить сейчас")
                }

            }
            if (checkingNow) {
                LinearProgressIndicator(progress = { if (monitorTotal > 0) monitorDone.toFloat() / monitorTotal else 0f }, modifier = Modifier.fillMaxWidth())
                Text("Проверено игр: $monitorDone / $monitorTotal${if (monitorCurrent.isNotBlank()) " · Сейчас: $monitorCurrent" else ""}", style = MaterialTheme.typography.bodySmall)
            }
            Text(watchMessage, style = MaterialTheme.typography.bodySmall)
            val watchArray = remember(watches) { JSONArray(watches) }
            for (wi in 0 until watchArray.length()) {
                val w = watchArray.optJSONObject(wi) ?: continue
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text(w.optString("name"), style = MaterialTheme.typography.titleMedium)
                        Text("${w.optString("kind")} · цель: ${w.optDouble("limit").toInt()} ₽ · РФ: ${if (w.optBoolean("rf",true)) "да" else "не обязательно"}")
                        Text(if (w.optDouble("last", -1.0) > 0) "Последний найденный минимум: ${w.optDouble("last").toInt()} ₽" else "Проверка ещё не завершена или подходящих товаров нет")
                        if (w.optLong("checked", 0L) > 0L) {
                            val stamp = java.text.SimpleDateFormat("dd.MM HH:mm", Locale("ru", "RU"))
                                .format(java.util.Date(w.optLong("checked")))
                            Text("Последняя проверка: $stamp", style = MaterialTheme.typography.bodySmall)
                        }
                        if(w.optInt("steamAppId")>0) Text("Steam #${w.optInt("steamAppId")}",style=MaterialTheme.typography.bodySmall)
                        if (w.has("error")) Text("Ошибка проверки: ${w.optString("error")}. Показаны предыдущие данные.", style = MaterialTheme.typography.bodySmall)
                        if(w.has("notificationError"))Text(w.optString("notificationError"),style=MaterialTheme.typography.bodySmall)
                        if (w.optLong("checked", 0L) > 0L) {
                                        if (w.has("eligibleCount") && w.optInt("eligibleCount") == 0) Text("Подходящих предложений нет — это не ошибка сети.", style = MaterialTheme.typography.bodySmall)
                                        else if (w.has("verifiedCount") && w.optInt("verifiedCount", 0) == 0) Text("Нет предложений с подтверждёнными наличием, ценой, РФ и способом передачи; уведомления не отправляются.", style = MaterialTheme.typography.bodySmall)
                        }
                        var resultsExpanded by remember(w.optString("name")) { mutableStateOf(false) }
                        val bestOffers = w.optJSONArray("topOffers") ?: JSONArray()
                        TextButton(onClick = { resultsExpanded = !resultsExpanded }) {
                            Text(if (resultsExpanded) "Скрыть предложения" else "Три минимальные цены (${bestOffers.length()})")
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
                                ElevatedCard(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(10.dp)) {
                                        Text(title, style = MaterialTheme.typography.bodyMedium)
                                        Text("$type · " + String.format(Locale("ru", "RU"), "%.2f ₽", price))
                                        if(offerJson.optString("note").isNotBlank())Text(offerJson.optString("note"),style=MaterialTheme.typography.bodySmall)
                                        Row {
                                            TextButton(onClick = {
                                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://plati.market/itm/$id")))
                                            }) { Text("Открыть") }
                                            TextButton(onClick = {
                                                if (items.none { it.id == id }) {
                                                    val tracked=Tracked(id,title,price,"Ожидает обновления","https://plati.market/itm/$id",offerJson.optString("canonical",w.optString("name")),offerJson.optInt("steamAppId",w.optInt("steamAppId")))
                                                    items = items + tracked
                                                    cacheTracked(tracked)
                                                    saveIds(items.map { it.id })
                                                    fetch(id) { found -> cacheTracked(found); items = items.map { if (it.id == id) TrackedPriceCache.merge(it,found) else it } }
                                                }
                                            }, enabled = items.none { it.id == id }) { Text("Отслеживать") }
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
                        TextButton(onClick={historyExpanded=!historyExpanded}) {Text(if(historyExpanded) "Скрыть историю цен" else "История цен (${history.size})")}
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
                        TextButton(onClick = { ioExecutor.execute { WatchStore.remove(this@MainActivity, wi); runOnUiThread { reloadWatches() } } }) { Text("Удалить отслеживание") }
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
                    val stamp = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru", "RU"))
                        .format(java.util.Date(alert.optLong("at")))
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(alert.optString("game"), style = MaterialTheme.typography.titleMedium)
                            Text(alert.optString("name"), style = MaterialTheme.typography.bodySmall)
                            Text("${alert.optString("type")} · ${String.format(Locale("ru", "RU"), "%.2f ₽", alert.optDouble("price"))} · цель до ${alert.optDouble("limit").toInt()} ₽")
                            Text("Найдено: $stamp · ID $id", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://plati.market/itm/$id")))
                            }) { Text("Открыть на Plati") }
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
            Spacer(Modifier.height(12.dp))
            Text("Результаты поиска Steam Gift / Key", style = MaterialTheme.typography.titleMedium)
            Text("Показаны только карточки с подтверждёнными наличием и ценой. Нерассчитанные варианты скрыты.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            if (offers.isNotEmpty()) {
                val currentRules = remember(query, rulesRevision) { GameRuleStore.read(this@MainActivity, query) }
                val filteredOffers = remember(offers, manualFilters, currentRules, ignoredIds) {
                    offers.filter { ManualSearchFilter.rejection(it,manualFilters,currentRules,ignoredIds)==null }
                }
                val visibleCount = filteredOffers.size
                val rejectionCounts=remember(offers,manualFilters,currentRules,ignoredIds) {
                    offers.mapNotNull {ManualSearchFilter.rejection(it,manualFilters,currentRules,ignoredIds)}.groupingBy {it}.eachCount()
                }
                if(rejectionCounts.isNotEmpty()) {
                    TextButton(onClick={showRejections=!showRejections}) {Text(if(showRejections) "Скрыть причины" else "Почему скрыты предложения (${rejectionCounts.values.sum()})")}
                    if(showRejections) rejectionCounts.forEach {(reason,count)->Text("$reason: $count",style=MaterialTheme.typography.bodySmall)}
                }
                Text("Предложения по цене ↑ · $visibleCount", style = MaterialTheme.typography.titleMedium)
                if (visibleCount == 0) Text("По выбранным фильтрам предложений нет. Уточните название или проверьте регион в заголовках.", style = MaterialTheme.typography.bodySmall)
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    filteredOffers.take(visibleOfferLimit).forEach { offer ->
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp)) {
                                Text(offer.name, style = MaterialTheme.typography.bodyMedium)
                                if (offer.categoryLabel.isNotBlank()) Text(offer.categoryLabel, style = MaterialTheme.typography.bodySmall)
                                if (offer.purchaseNote.isNotBlank()) Text(offer.purchaseNote, style = MaterialTheme.typography.bodySmall)
                                Text("${offer.type} · ${offer.rfStatus} · " + String.format(Locale("ru", "RU"), "%.2f ₽", offer.price))
                                Text(if (offer.variablePrice) "Цена варианта не подтверждена" else offer.edition, style = MaterialTheme.typography.bodySmall)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://plati.market/itm/${offer.id}"))) },
                                        modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)) { Text("Открыть", maxLines = 1) }
                                    TextButton(onClick = {
                                        if (items.none { it.id == offer.id }) {
                                            val tracked=TrackedPriceCache.fromOffer(offer)
                                            items = items + tracked
                                            cacheTracked(tracked)
                                            saveIds(items.map { it.id })
                                            fetch(offer.id) { found -> cacheTracked(found); items = items.map { if (it.id == offer.id) TrackedPriceCache.merge(it,found) else it } }
                                        }
                                    }, enabled = items.none { it.id == offer.id }, modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)) { Text("Отслеживать", maxLines = 1, style = MaterialTheme.typography.labelSmall) }
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = {
                                        IgnoredStore.add(this@MainActivity, offer.id, offer.name)
                                        ignoredIds = IgnoredStore.ids(this@MainActivity)
                                    }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)) {
                                        Text("Игнорировать", maxLines = 1, style = MaterialTheme.typography.labelSmall)
                                    }
                                    TextButton(onClick = {
                                        GameRuleStore.add(this@MainActivity, query, "blocked", offer.name)
                                        rulesRevision++
                                    }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)) {
                                        Text("Исключить как DLC", maxLines = 1, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                    if (visibleCount > visibleOfferLimit) {
                        OutlinedButton(onClick = { visibleOfferLimit += 30 }, modifier = Modifier.fillMaxWidth()) {
                            Text("Показать ещё 30 · отображено ${minOf(visibleOfferLimit, visibleCount)} из $visibleCount")
                        }
                    }
                }
            }
            } // конец вкладки поиска
            if (activeTab == 3 && !settingsOpen) {
                WishlistPanel(
                    profiles = wishlistProfiles, selectedId = selectedWishlistId, games = wishlistGames,
                    input = wishlistProfile, busy = wishlistBusy, message = wishlistMessage,
                    importedAt = wishlistImportedAt, visibleLimit = wishlistVisibleLimit,
                    searchingKeys = wishlistSearchingKeys, searchStatuses = wishlistSearchStatuses,
                    searchEnabled = manualFilters.valid,
                    onInput = { wishlistProfile = it }, onImport = { importWishlist() },
                    onSelect = { profile ->
                        selectedWishlistId = profile.steamId64
                        wishlistProfile = ""
                        wishlistGames = profile.games
                        wishlistImportedAt = profile.importedAt
                        wishlistVisibleLimit = 50
                        wishlistMessage = "Выбран профиль «${profile.displayName}»."
                        prefs.edit().putString("wishlist_selected_profile", profile.steamId64)
                            .putString("wishlist_profile", profile.input)
                            .putString("wishlist_games", SteamWishlist.encode(profile.games))
                            .putLong("wishlist_imported_at", profile.importedAt).apply()
                    },
                    onDelete = {
                        val active = wishlistProfiles.firstOrNull { it.steamId64 == selectedWishlistId }
                        if (active != null) {
                            val remaining = wishlistProfiles.filterNot { it.steamId64 == active.steamId64 }
                            persistWishlistProfiles(remaining)
                            val next = remaining.firstOrNull()
                            selectedWishlistId = next?.steamId64
                            wishlistProfile = ""
                            wishlistGames = next?.games.orEmpty()
                            wishlistImportedAt = next?.importedAt ?: 0L
                            prefs.edit().putString("wishlist_selected_profile", next?.steamId64)
                                .putString("wishlist_profile", next?.input.orEmpty())
                                .putString("wishlist_games", SteamWishlist.encode(next?.games.orEmpty()))
                                .putLong("wishlist_imported_at", next?.importedAt ?: 0L).apply()
                        }
                    },
                    onOpenSearch = { game ->
                        startCatalogSearch(
                            candidateOverride = GameSearchResolver.Candidate(game.appId, game.title),
                            wishlistProfileId = selectedWishlistId
                        )
                    },
                    onSearchPrice = { game ->
                        val profileId = selectedWishlistId
                        if (profileId != null) startCatalogSearch(
                            candidateOverride = GameSearchResolver.Candidate(game.appId, game.title),
                            wishlistProfileId = profileId,
                            hiddenWishlistSearch = true
                        )
                    },
                    onShowMore = { wishlistVisibleLimit += 50 }
                )
            }
            if (activeTab == 4 && !settingsOpen) {
                Text("Лидеры продаж Steam", style = MaterialTheme.typography.titleLarge)
                Text("Популярные платные игры по сортировке магазина Steam. Бесплатные игры скрыты. Это не отдельный рейтинг выручки Steam Charts.",
                    style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Загружено: ${topGames.size} из 100", modifier = Modifier.weight(1f))
                    TextButton(onClick = { refreshTop() }, enabled = !topBusy) { Text("Обновить") }
                }
                if (topBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (topError.isNotBlank()) Text(topError, color = MaterialTheme.colorScheme.error)
                topGames.forEachIndexed { index, game ->
                    TextButton(onClick = {
                        searchGeneration++
                        activeCatalogGeneration.set(searchGeneration)
                        searching = false
                        steamGame = null
                        selectedGame=null
                        query = game
                        offers = emptyList()
                        searchMessage = "Игра выбрана из лидеров продаж. Нажмите «Искать на Plati»."
                        activeTab = 0
                    }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 6.dp)) {
                        Text("${index + 1}. $game", modifier = Modifier.fillMaxWidth())
                    }
                    HorizontalDivider()
                }
            }
            if (settingsOpen && !showDirectory) {
                Text("Настройки", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                Text("Количество обрабатываемых товаров", style = MaterialTheme.typography.titleMedium)
                Text("Для поиска и мониторинга. Чем больше товаров, тем полнее выборка, но дольше проверка.", style = MaterialTheme.typography.bodySmall)
                listOf(100, 200, 300, 500).forEach { count ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(selected = catalogLimit == count, onClick = {
                            catalogLimit = count
                            CatalogPageSettings.set(this@MainActivity, count)
                        })
                        Text("$count товаров", modifier = Modifier.padding(start = 8.dp))
                    }
                }
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { showDirectory = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Справочник игр и правила DLC")
                }
                Text("Сохранённые правила справочника продолжают работать в поиске и мониторинге.", style = MaterialTheme.typography.bodySmall)
            }
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
                        TextButton(onClick = {
                            directoryGame = candidate
                            directoryCanonical = GameRuleStore.read(this@MainActivity, candidate).optString("canonical")
                            directoryGameInfo = null
                            directorySuggestions = emptyList()
                            directorySelectedAppId = null
                        }) { Text(candidate) }
                    }
                }
                Button(onClick = {
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
                    TextButton(onClick = {
                        directoryGame = candidate.name
                        directorySelectedAppId = candidate.id
                        directoryCanonical = GameRuleStore.read(this@MainActivity, candidate.name).optString("canonical")
                        directoryGameInfo = null
                        directorySuggestions = emptyList()
                    }) { Text("${candidate.name} (Steam #${candidate.id})") }
                }
                if (savedGames.isNotEmpty()) {
                    Text("Ранее изменённые игры", style = MaterialTheme.typography.titleSmall)
                    savedGames.forEach { saved ->
                        TextButton(onClick = {
                            directoryGame = saved
                            directorySelectedAppId = null
                            directoryCanonical = GameRuleStore.read(this@MainActivity, saved).optString("canonical")
                            directoryGameInfo = null
                        }) { Text(saved) }
                    }
                }
                Button(onClick = {
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
                                directoryMessage = "Источник: ${game.source} · DLC: ${game.dlcNames.size}"
                            }
                            directoryBusy = false
                        }
                    }.start()
                }, enabled = directoryGame.isNotBlank() && !directoryBusy) { Text("Открыть / обновить из Steam") }
                if (directoryBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(directoryMessage, style = MaterialTheme.typography.bodySmall)
                if (directoryGame.isNotBlank()) {
                    val directoryRules = remember(directoryGame, rulesRevision) {
                        GameRuleStore.read(this@MainActivity, directoryGame)
                    }
                    Text("Официальное название: ${directoryGameInfo?.canonical ?: "ещё не загружено"}")
                    OutlinedTextField(directoryCanonical, { directoryCanonical = it },
                        label = { Text("Название основной игры (ручная правка)") }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = {
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
                            FilterChip(selected = directoryField == field, onClick = { directoryField = field }, label = { Text(label) })
                        }
                    }
                    OutlinedTextField(directoryInput, { directoryInput = it }, label = { Text("Название или отличительная часть") },
                        modifier = Modifier.fillMaxWidth())
                    Button(onClick = {
                        GameRuleStore.add(this@MainActivity, directoryGame, directoryField, directoryInput)
                        directoryInput = ""
                        rulesRevision++
                    }, enabled = directoryInput.isNotBlank()) { Text("Добавить правило") }
                }
            }
            } // прокручиваемое содержимое
        } // экран с вкладками
    }
    @Composable
    private fun WishlistPanel(
        profiles: List<WishlistProfile>,
        selectedId: String?,
        games: List<WishlistGame>,
        input: String,
        busy: Boolean,
        message: String,
        importedAt: Long,
        visibleLimit: Int,
        searchingKeys: Set<String>,
        searchStatuses: Map<String, String>,
        searchEnabled: Boolean,
        onInput: (String) -> Unit,
        onImport: () -> Unit,
        onSelect: (WishlistProfile) -> Unit,
        onDelete: () -> Unit,
        onOpenSearch: (WishlistGame) -> Unit,
        onSearchPrice: (WishlistGame) -> Unit,
        onShowMore: () -> Unit
    ) {
        ElevatedCard(Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Список желаемого Steam", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("Закрепляйте открытые профили друзей. Нажмите название игры для поиска Plati; «Искать» проверяет цену прямо в списке.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Spacer(Modifier.height(12.dp))
        if (profiles.isNotEmpty()) {
            Text("Профили", style = MaterialTheme.typography.titleMedium)
            profiles.forEach { profile ->
                TextButton(onClick = { onSelect(profile) }, modifier = Modifier.fillMaxWidth()) {
                    Text((if (profile.steamId64 == selectedId) "●  " else "○  ") + profile.displayName + " · " + profile.steamId64.takeLast(4),
                        modifier = Modifier.fillMaxWidth(), maxLines = 1)
                }
            }
        }
        OutlinedTextField(
            input, onInput,
            label = { Text("Новый профиль Steam") },
            placeholder = { Text("SteamID64, /profiles/… или /id/…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        val selected = profiles.firstOrNull { it.steamId64 == selectedId }
        Button(onClick = onImport, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
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
        Spacer(Modifier.height(8.dp))
        if (games.isEmpty() && importedAt == 0L) {
            Text("Вставьте открытый профиль Steam и загрузите его, чтобы закрепить в списке профилей.", style = MaterialTheme.typography.bodyMedium)
        }
        games.take(visibleLimit).forEach { game ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            TextButton(onClick = { onOpenSearch(game) }, enabled = game.title.isNotBlank(),
                                contentPadding = PaddingValues(0.dp)) {
                                Text(game.title.ifBlank { "Название игры не получено" }, style = MaterialTheme.typography.titleSmall,
                                    maxLines = 2)
                            }
                            Text("Steam AppID ${game.appId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(game.steamPrice.ifBlank { "Нет цены RU" }, style = MaterialTheme.typography.titleSmall)
                            if (game.discountPercent > 0) {
                                if (game.steamInitialPrice.isNotBlank()) Text(game.steamInitialPrice,
                                    style = MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.LineThrough),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("−${game.discountPercent}% · Steam RU", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary)
                            } else Text("Steam RU", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            game.lowestPlatiPrice?.let { price ->
                                Text("Plati от ${String.format(Locale("ru", "RU"), "%.0f", price)} ₽",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                            }
                            val searchKey = selectedId?.let { "$it:${game.appId}" }
                            searchKey?.let { searchStatuses[it] }?.let { status ->
                                Text(status, style = MaterialTheme.typography.labelSmall,
                                    color = if (status.startsWith("Ошибка")) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { onSearchPrice(game) },
                                enabled = searchEnabled && searchKey != null && searchKey !in searchingKeys,
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                                Text(if (searchKey != null && searchKey in searchingKeys) "Ищем…" else "Искать")
                            }
                        }
                    }
                }
            }
        }
        if (games.size > visibleLimit) {
            OutlinedButton(onClick = onShowMore, modifier = Modifier.fillMaxWidth()) {
                Text("Показать ещё · ${minOf(visibleLimit, games.size)} из ${games.size}")
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

