package ru.igrolov.app

import android.os.Bundle
import android.text.Html
import androidx.compose.ui.Alignment
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

data class Offer(val id: String, val name: String, val price: Double, val type: String, val rfStatus: String, val edition: String)

data class Tracked(val id: String, val name: String, val price: Double?, val status: String, val url: String)

class MainActivity : ComponentActivity() {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val prefs by lazy { getSharedPreferences("tracked_products", MODE_PRIVATE) }
    private fun loadIds(): List<String> = prefs.getString("ids", "")!!.split(",").filter { it.isNotBlank() }
    private fun saveIds(ids: List<String>) = prefs.edit().putString("ids", ids.distinct().joinToString(",")).apply()
    private fun cachedTracked(id: String): Tracked {
        val url = "https://plati.market/itm/$id"
        val saved = prefs.getString("product_cache_$id", null) ?: return Tracked(id, "Товар $id", null, "Обновляем цену…", url)
        return try {
            val obj = JSONObject(saved)
            Tracked(id, obj.optString("name", "Товар $id"),
                if (obj.has("price") && !obj.isNull("price")) obj.optDouble("price").takeIf { it.isFinite() && it > 0 } else null,
                "Обновляем цену…", url)
        } catch (_: Exception) { Tracked(id, "Товар $id", null, "Обновляем цену…", url) }
    }
    private fun cacheTracked(item: Tracked) {
        if (item.status.startsWith("Ошибка загрузки")) return
        val json = JSONObject().put("name", item.name).put("price", item.price ?: JSONObject.NULL)
        prefs.edit().putString("product_cache_${item.id}", json.toString()).apply()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WatchScheduler.start(this)
        setContent { MaterialTheme { Screen() } }
    }
    private fun fetch(id: String, callback: (Tracked) -> Unit) {
        Thread {
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
                    val rub = product.optJSONObject("prices")?.optJSONObject("default")?.optDouble("RUB", Double.NaN) ?: Double.NaN
                    val available = product.optInt("is_available", 0) == 1
                    Tracked(id, product.optString("name", "Товар $id"), rub.takeIf { it.isFinite() && it > 0 }, if (available) "В продаже" else "Нет в наличии", "https://plati.market/itm/$id")
                } finally { conn.disconnect() }
            } catch (e: Exception) { Tracked(id, "Товар $id", null, "Ошибка загрузки: ${e.message ?: "нет соединения"}", "https://plati.market/itm/$id") }
            runOnUiThread { callback(result) }
        }.start()
    }
    private fun searchCatalog(query: String, pageCount: Int, progress: (Int, Int, Int) -> Unit, callback: (List<Offer>, String) -> Unit) {
        Thread {
            val found = mutableListOf<Offer>()
            val seenIds = mutableSetOf<String>()
            var failure = ""
            try {
                // Marketplace-wide search endpoint used by the Plati site ecosystem.
                // It is not the seller-specific shop_search.asp interface.
                for (page in 1..pageCount) {
                val params = linkedMapOf(
                    "query" to query,
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
                val conn = (URL("https://api.digiseller.com/api/products/search2?$params")
                    .openConnection() as HttpURLConnection)
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                conn.setRequestProperty("Accept", "application/json")
                try {
                    val status = conn.responseCode
                    if (status !in 200..299) error("HTTP $status: поиск каталога недоступен")
                    val root = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
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
                            val type = OfferRules.classifySteamGame(name, query) ?: continue
                            if (seenIds.add(id)) found.add(Offer(id, name, price, type, OfferRules.rfStatus(name), OfferRules.editionType(name, query)))
                        }

                    }
                } finally { conn.disconnect() }
                runOnUiThread { progress(page, pageCount, found.size) }
                }
            } catch (e: Exception) { failure = e.message ?: "Не удалось подключиться к каталогу" }
            runOnUiThread { callback(found.distinctBy { it.id }.sortedBy { it.price }, failure) }
        }.start()
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
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Igrolov/1.0.3)")
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
        var searching by remember { mutableStateOf(false) }
        var searchPage by remember { mutableIntStateOf(0) }
        var catalogLimit by remember { mutableIntStateOf(CatalogPageSettings.get(this@MainActivity)) }
        val searchTotalPages = catalogLimit / 100
        var showDirectory by remember { mutableStateOf(false) }
        var searchFound by remember { mutableIntStateOf(0) }
        var monitorDone by remember { mutableIntStateOf(0) }
        var monitorTotal by remember { mutableIntStateOf(0) }
        var monitorCurrent by remember { mutableStateOf("") }
        var selectedType by remember { mutableStateOf("Все") }
        val rfOnly = true
        var editionFilter by remember { mutableStateOf("Игра") }
        var items by remember { mutableStateOf(loadIds().map { cachedTracked(it) }) }
        var message by remember { mutableStateOf("Вставьте ссылку на товар Plati или его ID") }
        var busy by remember { mutableStateOf(false) }
        var watchGame by remember { mutableStateOf("") }
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
        var watches by remember { mutableStateOf(WatchStore.read(this@MainActivity).toString()) }
        var alertLog by remember { mutableStateOf(AlertJournal.read(this@MainActivity).toString()) }
        var showAlertLog by remember { mutableStateOf(true) }
        var watchMessage by remember { mutableStateOf("Укажите название игры и цену в рублях") }
        fun reloadWatches() {
            watches = WatchStore.read(this@MainActivity).toString()
            alertLog = AlertJournal.read(this@MainActivity).toString()
        }
        DisposableEffect(Unit) {
            val live = WorkManager.getInstance(this@MainActivity)
                .getWorkInfosForUniqueWorkLiveData("plati_manual_price_check")
            val observer = Observer<List<WorkInfo>> { infos ->
                val running = infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
                checkingNow = running
                val info = infos.firstOrNull { it.state == WorkInfo.State.RUNNING } ?: infos.firstOrNull()
                monitorDone = info?.progress?.getInt("done", 0) ?: 0
                monitorTotal = info?.progress?.getInt("total", 0) ?: 0
                monitorCurrent = info?.progress?.getString("game") ?: ""
                if (infos.isNotEmpty() && !running) {
                    reloadWatches()
                    watchMessage = if (infos.any { it.state == WorkInfo.State.SUCCEEDED })
                        "Проверка завершена. Минимальные цены обновлены." else "Проверка не завершилась. Посмотрите ошибку в карточке."
                }
            }
            live.observe(this@MainActivity, observer)
            onDispose { live.removeObserver(observer) }
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
                    else if (found.status.startsWith("Ошибка загрузки") && current.price != null)
                        current.copy(status = "Нет связи · показана последняя цена")
                    else found
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
            if (activeTab == 3 && !settingsOpen && topGames.isEmpty() && !topBusy) refreshTop()
        }
        val searchScroll = rememberScrollState()
        val productsScroll = rememberScrollState()
        val watchScroll = rememberScrollState()
        val directoryScroll = rememberScrollState()
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Игролов 1.0.3", style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = { settingsOpen = !settingsOpen; showDirectory = false }) {
                    Text(if (settingsOpen) "×" else "⚙", fontSize = 26.sp,
                        color = MaterialTheme.colorScheme.onSurface)
                }
            }
            TabRow(selectedTabIndex = activeTab) {
                listOf("Поиск", "Товары", "Мониторинг", "Лидеры продаж").forEachIndexed { index, title ->
                    Tab(selected = activeTab == index, onClick = { activeTab = index; settingsOpen = false; showDirectory = false }, text = { Text(title, maxLines = 2, fontSize = 10.sp) })
                }
            }
            Column(Modifier.fillMaxSize().verticalScroll(if (settingsOpen) directoryScroll else when(activeTab) { 0 -> searchScroll; 1 -> productsScroll; 2 -> watchScroll; else -> directoryScroll }).padding(16.dp)) {
            if (activeTab == 0 && !settingsOpen) {
            Text("Поиск предложений Steam · RUB", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            Text("Поиск игры по названию", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(query, { query = it }, label = { Text("Например, Cyberpunk 2077") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
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
                            TextButton(onClick = { query = savedQuery }, modifier = Modifier.weight(1f)) {
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
            Text("Регион: только подтверждённая РФ (по названию)", style = MaterialTheme.typography.bodySmall)
            Text("Состав товара", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Игра", "Все", "DLC").forEach { edition ->
                    FilterChip(selected = editionFilter == edition,
                        onClick = { editionFilter = edition }, label = { Text(edition) })
                }
            }
            Text("Проверка по заголовкам скрывает известные DLC и услуги. Состав издания и возможность активации в РФ обязательно уточняйте на странице товара.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = {
                searching = true
                searchPage = 0
                searchFound = 0
                searchMessage = "Поиск предложений..."
                offers = emptyList()
                val requestedGame = query.trim()
                queryHistory = (listOf(requestedGame) + queryHistory.filterNot { it.equals(requestedGame, ignoreCase = true) }).take(20)
                searchPrefs.edit().putString("queries", JSONArray(queryHistory).toString()).apply()
                steamGame = null
                searchCatalog(requestedGame, searchTotalPages, { page, _, count ->
                    searchPage = page
                    searchFound = count
                }) { found, failure ->
                    offers = found
                    searching = false
                    searchMessage = if (failure.isNotBlank()) "Ошибка поиска: $failure" else
                        "Поиск завершён. Справочник Steam загружается…"
                    if (found.isNotEmpty()) Thread {
                        val game = SteamGameReference.lookup(this@MainActivity, requestedGame)
                        runOnUiThread {
                            if (query.trim() == requestedGame) {
                                steamGame = game
                                searchMessage = "Каталог: ${found.size} · справочник: ${game.source}. Проверка только по названиям."
                            }
                        }
                    }.start()
                }
            }, enabled = query.isNotBlank() && !searching) { Text(if (searching) "Ищем..." else "Искать на Plati") }
            if (searching) {
                LinearProgressIndicator(progress = { searchPage.toFloat() / searchTotalPages }, modifier = Modifier.fillMaxWidth())
                Text("Страниц обработано: $searchPage / $searchTotalPages · найдено кандидатов: $searchFound", style = MaterialTheme.typography.bodySmall)
            }
            Text(searchMessage, style = MaterialTheme.typography.bodySmall)
            Text("Поиск по Plati (до $catalogLimit товаров). Предложения без подтверждения РФ скрыты.", style = MaterialTheme.typography.bodySmall)
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
                    else { items = items + Tracked(id, "Товар $id", null, "Загрузка...", "https://plati.market/itm/$id"); saveIds(items.map { it.id }); entry = ""; message = "Товар добавлен"; fetch(id) { found -> cacheTracked(found); items = items.map { if (it.id == id) found else it } } }
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
                                TextButton(onClick = { items = items.filterNot { it.id == item.id }; saveIds(items.map { it.id }); prefs.edit().remove("product_cache_${item.id}").apply() }) { Text("Удалить") }
                            }
                        }
                    }
                }
            }
            } // конец вкладки товаров
            if (activeTab == 2 && !settingsOpen) {
            Text("Автоматическое отслеживание игр", style = MaterialTheme.typography.titleLarge)
            Text("Ищет новые предложения продавцов примерно раз в час. Android может задерживать проверки.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(watchGame, { watchGame = it }, label = { Text("Название игры") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
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
                    WatchStore.add(this@MainActivity, watchGame.trim(), watchType, amount, watchRf)
                    WatchScheduler.start(this@MainActivity)
                    WatchScheduler.checkNow(this@MainActivity)
                    if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    reloadWatches()
                    watchMessage = "Игра добавлена. Первая проверка запущена; следующие — примерно раз в час."
                }
            }) { Text("Отслеживать игру") }
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
                        if (w.has("error")) Text("Ошибка проверки: ${w.optString("error")}", style = MaterialTheme.typography.bodySmall)
                        if (w.optLong("checked", 0L) > 0L) {
                                        if (w.optInt("eligibleCount") == 0) Text("Подходящих предложений нет — это не ошибка сети.", style = MaterialTheme.typography.bodySmall)
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
                                        Row {
                                            TextButton(onClick = {
                                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://plati.market/itm/$id")))
                                            }) { Text("Открыть") }
                                            TextButton(onClick = {
                                                if (items.none { it.id == id }) {
                                                    items = items + Tracked(id, title, price, "Ожидает обновления", "https://plati.market/itm/$id")
                                                    saveIds(items.map { it.id })
                                                    fetch(id) { found -> cacheTracked(found); items = items.map { if (it.id == id) found else it } }
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
                        TextButton(onClick = { WatchStore.remove(this@MainActivity, wi); reloadWatches() }) { Text("Удалить отслеживание") }
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
            Text("Предложения, впервые найденные по заданной цене. Журнал хранится на телефоне.",
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
                    TextButton(onClick = { AlertJournal.clear(this@MainActivity); reloadWatches() }) {
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
            Text("Показываются только предложения с явным указанием РФ в названии.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            if (offers.isNotEmpty()) {
                val currentRules = remember(query, rulesRevision) { GameRuleStore.read(this@MainActivity, query) }
                val visibleCount = offers.count { (selectedType == "Все" || it.type == selectedType) &&
                    OfferRules.eligible(it, editionFilter, selectedType, steamGame, currentRules) && it.id !in ignoredIds }
                Text("Предложения по цене ↑ · $visibleCount", style = MaterialTheme.typography.titleMedium)
                if (visibleCount == 0) Text("По выбранным фильтрам предложений нет. Уточните название или проверьте регион в заголовках.", style = MaterialTheme.typography.bodySmall)
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    offers.filter { (selectedType == "Все" || it.type == selectedType) &&
                        OfferRules.eligible(it, editionFilter, selectedType, steamGame, currentRules) && it.id !in ignoredIds }.forEach { offer ->
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp)) {
                                Text(offer.name, style = MaterialTheme.typography.bodyMedium)
                                Text("${offer.type} · ${offer.rfStatus} · " + String.format(Locale("ru", "RU"), "%.2f ₽", offer.price))
                                Text(if (OfferRules.variablePrice(offer.name)) "Цена может зависеть от выбранного варианта" else offer.edition, style = MaterialTheme.typography.bodySmall)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://plati.market/itm/${offer.id}"))) },
                                        modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)) { Text("Открыть", maxLines = 1) }
                                    TextButton(onClick = {
                                        if (items.none { it.id == offer.id }) {
                                            items = items + Tracked(offer.id, offer.name, offer.price, "Ожидает обновления", "https://plati.market/itm/${offer.id}")
                                            saveIds(items.map { it.id })
                                            fetch(offer.id) { found -> cacheTracked(found); items = items.map { if (it.id == offer.id) found else it } }
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
                }
            }
            } // конец вкладки поиска
            if (activeTab == 3 && !settingsOpen) {
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
    private fun PriceHistoryChart(prices: List<Double>) {
        val low = prices.minOrNull() ?: return
        val high = prices.maxOrNull() ?: return
        Canvas(Modifier.fillMaxWidth().height(100.dp).padding(vertical = 8.dp)) {
            val range = (high - low).takeIf { it > 0.0 } ?: 1.0
            val width = size.width
            val height = size.height
            val points = prices.mapIndexed { index, price ->
                Offset(
                    x = if (prices.size == 1) width / 2f else width * index / (prices.size - 1),
                    y = (height - ((price - low) / range).toFloat() * height).coerceIn(0f, height)
                )
            }
            if (points.size == 1) drawCircle(Color(0xFF1976D2), radius = 5.dp.toPx(), center = points.first())
            else for (index in 1 until points.size) {
                drawLine(Color(0xFF1976D2), points[index - 1], points[index], strokeWidth = 2.dp.toPx())
            }
        }
    }

}
