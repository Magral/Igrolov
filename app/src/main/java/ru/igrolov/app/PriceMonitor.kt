package ru.igrolov.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.workDataOf
import androidx.work.WorkerParameters
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Pure title classifiers shared by manual search and scheduled checks. */
object OfferRules {
    // Normalize seller punctuation and typographic apostrophes before title matching.
    fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace('ё', 'е').replace('´', '\'').replace('’', '\'').replace('‘', '\'')
        .replace('`', '\'').replace('ʼ', '\'').replace('′', '\'')
        .replace(Regex("[\\u00A0\\s]+"), " ").trim()

    private fun tokens(value: String): List<String> = normalize(value)
        .replace(Regex("(?<=\\p{L})'(?=\\p{L})"), "")
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.isNotEmpty() }

    private fun hasPhrase(value: String, phrase: String): Boolean {
        val a = tokens(value); val b = tokens(phrase)
        return b.isNotEmpty() && a.windowed(b.size).any { it == b }
    }
    private fun anyPhrase(value: String, phrases: List<String>) = phrases.any { hasPhrase(value, it) }

    private fun matchesGame(title: String, query: String): Boolean {
        val words = tokens(query)
        return words.isNotEmpty() && tokens(title).windowed(words.size).any { it == words }
    }

    fun classifySteamGame(name: String, query: String): String? {
        val t = normalize(name)
        if (!matchesGame(t, query) || !hasPhrase(t, "steam")) return null
        val excluded = listOf(
            "аккаунт", "аккаунты", "акк", "account", "accounts", "аренда", "rent", "rental",
            "offline", "оффлайн", "офлайн", "общий аккаунт", "shared account", "family sharing",
            "семейный доступ", "общий доступ", "доступ к", "вход в аккаунт", "пополнение",
            "баланс", "steam wallet", "wallet", "steam guard", "подписка", "subscription",
            "xbox", "game pass", "gog", "epic games", "ubisoft", "uplay", "origin",
            "ea app", "ps4", "ps5", "playstation", "battle.net", "rockstar launcher",
            "услуга", "service", "boost", "прокачка", "скин", "skin", "coins", "валюта",
            "рандом", "random", "лотерея", "прокат", "смена региона", "смена страны",
            "посредник", "посредничество", "только оффлайн"
        )
        if (anyPhrase(t, excluded)) return null
        val gift = anyPhrase(t, listOf("gift", "гифт", "подарок", "подарочная копия"))
        val key = anyPhrase(t, listOf("key", "keys", "ключ", "ключи", "ключик", "cd key"))
        return when {
            gift && !key -> "Gift"
            key && !gift -> "Key"
            else -> "Не указан" // Never infer Gift solely from AUTO/AUTODELIVERY.
        }
    }

    fun editionType(name: String, query: String): String {
        val t = normalize(name)
        val strongDlc = listOf(
            "vip membership", "vip pass", "car pass", "cars pass", "car pack", "cars pack", "vehicle pack", "vehicles pack", "expansion pass",
            "season pass", "battle pass", "premium add-ons", "premium addons", "deluxe upgrade", "premium upgrade",
            "add-ons bundle", "addons bundle", "upgrade", "upgrade pack", "upgrade only",
            "dlc only", "expansion only", "expansion pack", "season ticket", "soundtrack",
            "саундтрек", "artbook", "артбук", "cosmetic pack", "content pack",
            "currency pack", "starter pack", "skin pack", "car bundle", "expansions bundle", "time attack car pack", "italian passion car pack", "набор машин", "пак автомобилей", "набор дополнений", "пак дополнений",
            "отдельное дополнение", "сезонный пропуск", "дополнение отдельно", "пропуск", "доп контент"
        )
        if (anyPhrase(t, strongDlc)) return "DLC"
        val fullEdition = listOf("deluxe edition", "premium edition", "ultimate edition",
            "complete edition", "gold edition", "definitive edition", "game of the year",
            "goty edition", "digital deluxe", "делюкс издание", "премиум издание",
            "полное издание", "ультимативное издание")
        if (anyPhrase(t, fullEdition)) return "Игра"
        if (anyPhrase(t, listOf("dlc", "дополнение", "дополнения", "доп", "add-on", "addon", "add on", "seasonal content", "expansion"))) return "DLC"
        return "Игра"
    }

    fun rfStatus(name: String): String {
        val t = normalize(name)
        val excluded = listOf(
            "без рф", "без россии", "без ru", "кроме ру", "кроме ru", "не для ru", "кроме рф", "кроме россии", "исключая рф", "исключая россию",
            "не для рф", "не для россии", "не работает в рф", "не работает в россии",
            "не активируется в рф", "не активируется в россии", "not ru", "no ru",
            "except ru", "except russia", "excluding russia", "кроме ru", "кроме rus",
            "without ru", "excluding ru", "not available ru", "not for russia",
            "not available in russia", "russia excluded", "ru excluded", "ru unavailable", "ru not supported", "region locked ru",
            "ru restricted", "кз only", "kazakhstan only", "турция only", "turkey only",
            "не для снг", "кроме снг", "ru not available", "russia not available",
            "not for ru", "ru excluded", "no russia", "except rf", "except rus"
        )
        if (anyPhrase(t, excluded)) return "РФ исключена"
        val positive = listOf("рф", "ру", "россия", "россии", "россию", "russia",
            "russian federation", "ru", "rus", "ru+мир", "ru+world", "ru cis", "ru cнг")
        // Region labels are not equivalent to Russian-language labels.
        val languageOnly = listOf("ru language", "language ru", "русский язык", "русская озвучка",
            "русские субтитры", "russian language", "russian subtitles", "ru subtitles", "ru voice", "ru text", "ru localization", "русский перевод", "русский интерфейс")
        val withoutLanguage = languageOnly.fold(t) { text, phrase ->
            val parts = tokens(phrase)
            val pattern = parts.joinToString("[^\\p{L}\\p{N}]+") { Regex.escape(it) }
            text.replace(Regex("(?<![\\p{L}\\p{N}])$pattern(?![\\p{L}\\p{N}])"), " ")
        }
        return if (anyPhrase(withoutLanguage, positive)) "РФ указана в названии"
            else "Регион РФ не подтвержден"
    }

    fun variablePrice(name: String): Boolean = anyPhrase(name, listOf(
        "choose edition", "choose the edition", "select edition", "выбор издания",
        "выберите издание", "выбери издание", "select region", "choose region",
        "выбор региона", "выберите регион", "выбери регион", "выбор страны"
    ))

    fun eligible(offer: Offer, edition: String, kind: String, game: SteamGameReference.Game?, rules: JSONObject = JSONObject(), allowUnconfirmed: Boolean = false): Boolean {
        if (offer.rfStatus != "РФ указана в названии") return false
        // Unknown region is visible for manual verification, but never described as confirmed.
        if (kind != "Все" && offer.type != kind) return false
        if (edition == "Игра") {
            if (game != null && !SteamGameReference.isMainGame(offer, game, rules)) return false
            if (game == null && offer.edition != "Игра") return false
        } else if (edition == "DLC") {
            if (offer.edition != "DLC" && (game == null || !SteamGameReference.isKnownDlc(offer, game, rules))) return false
        }
        return true
    }
}

/** Reads publicly exposed purchase options. Never assumes that a catalog price is the Russian price. */
object RegionOptions {
    data class Info(val status: String, val rubPrice: Double?, val note: String)
    private fun objects(value: Any?): List<JSONObject> = when (value) {
        is JSONArray -> (0 until value.length()).mapNotNull { value.optJSONObject(it) }
        is JSONObject -> listOf(value)
        else -> emptyList()
    }
    private fun label(value: Any?): String = when (value) {
        is String -> value
        is JSONObject -> value.optString("text").ifBlank { value.optString("value") }
        is JSONArray -> objects(value).firstOrNull { it.optString("locale") == "ru-RU" }?.optString("value")
            ?: objects(value).firstOrNull()?.optString("value") ?: ""
        else -> ""
    }
    private fun isRussia(value: String) = Regex("(?iu)(?<![\\p{L}])(?:россия|россии|россию|рф|russia|russian federation)(?![\\p{L}])").containsMatchIn(value)
    private fun isRegionQuestion(value: String): Boolean {
        val t = value.lowercase(Locale.ROOT)
        return listOf("стран", "регион", "country", "region", "аккаунт steam").any { it in t }
    }
    fun inspect(id: String, base: Double): Info {
        val conn = URL("https://api.digiseller.com/api/products/$id/data?currency=RUB&lang=ru-RU").openConnection() as HttpURLConnection
        conn.connectTimeout = 6500; conn.readTimeout = 6500
        conn.setRequestProperty("Accept", "application/json")
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            val root = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            if (root.optInt("retval", -1) != 0) error("Ошибка карточки")
            val product = root.optJSONObject("product") ?: error("Нет карточки")
            val price = product.optJSONObject("prices")?.optJSONObject("default")?.optDouble("RUB", Double.NaN)
                ?.takeIf { it.isFinite() && it > 0 } ?: base
            val options = product.optJSONObject("options")?.let { objects(it.opt("option")) }
                ?: objects(product.opt("options"))
            for (option in options) {
                val question = label(option.opt("name")).ifBlank { label(option.opt("text")) }
                if (!isRegionQuestion(question)) continue
                val variants = option.optJSONObject("variants")?.let { objects(it.opt("variant")) }
                    ?: objects(option.opt("variants"))
                if (variants.isEmpty()) continue
                val russian = variants.firstOrNull { isRussia(label(it.opt("text")).ifBlank { label(it.opt("name")) }) }
                    ?: return Info("РФ исключена", null, "В выборе региона Россия отсутствует")
                val modifierType = russian.optString("modify_type").uppercase(Locale.ROOT)
                val raw = russian.optDouble("modify_value", Double.NaN)
                val delta = if (raw.isFinite()) raw else russian.optDouble("modify", Double.NaN)
                val computed = when {
                    !delta.isFinite() -> null
                    modifierType == "RUB" -> price + delta
                    modifierType == "%" -> price * (1.0 + delta / 100.0)
                    else -> null
                }?.takeIf { it.isFinite() && it > 0 }
                // No price adjustment: the base price may be for another selected country.
                return Info("РФ доступна в параметрах", computed, if (computed == null) "Цена РФ требует проверки на Plati" else "Цена рассчитана по параметру РФ")
            }
            return Info("Регион РФ не подтвержден", null, "В API нет явного выбора региона")
        } finally { conn.disconnect() }
    }
}

object IgnoredStore {
    private const val PREFS = "ignored_products"
    fun ids(context: Context): Set<String> = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getStringSet("ids", emptySet())?.toSet() ?: emptySet()
    fun add(context: Context, id: String, name: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putStringSet("ids", ids(context) + id)
            .putString("title_$id", name).apply()
    }
    fun remove(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet("ids", ids(context) - id).remove("title_$id").apply()
    }
    fun title(context: Context, id: String): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString("title_$id", "Товар $id") ?: "Товар $id"
}


/** Persistent local log of offers that met a configured threshold. */
object AlertJournal {
    private const val PREFS = "price_alert_journal"
    private const val KEY = "entries"
    private val lock = Any()
    fun read(context: Context): JSONArray = synchronized(lock) {
        try { JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]")) }
        catch (_: Exception) { JSONArray() }
    }
    fun record(context: Context, game: String, offer: Offer, limit: Double) = synchronized(lock) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = try { JSONArray(prefs.getString(KEY, "[]")) } catch (_: Exception) { JSONArray() }
        val updated = JSONArray()
        updated.put(JSONObject().put("id", offer.id).put("game", game)
            .put("name", offer.name).put("price", offer.price).put("type", offer.type)
            .put("limit", limit).put("at", System.currentTimeMillis()))
        for (i in 0 until minOf(existing.length(), 199)) updated.put(existing.get(i))
        prefs.edit().putString(KEY, updated.toString()).commit()
    }
    fun clear(context: Context) = synchronized(lock) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).commit()
    }
}

object WatchStore {
    val lock = Any()
    private const val KEY = "game_watches"
    fun read(context: Context): JSONArray = try {
        JSONArray(context.getSharedPreferences(KEY, Context.MODE_PRIVATE).getString("watches", "[]"))
    } catch (_: Exception) { JSONArray() }
    fun write(context: Context, array: JSONArray) {
        context.getSharedPreferences(KEY, Context.MODE_PRIVATE).edit().putString("watches", array.toString()).apply()
    }
    fun add(context: Context, name: String, kind: String, limit: Double, rf: Boolean = true) = synchronized(lock) {
        val arr = read(context)
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("name").equals(name, true) && obj.optString("kind") == kind) {
                obj.put("limit", limit).put("rf", rf)
                write(context, arr); return
            }
        }
        arr.put(JSONObject().put("name", name).put("kind", kind).put("limit", limit).put("rf", rf).put("last", -1.0).put("notified", JSONArray()))
        write(context, arr)
    }
    fun remove(context: Context, index: Int) = synchronized(lock) {
        val old = read(context); val result = JSONArray()
        for (i in 0 until old.length()) if (i != index) result.put(old.get(i))
        write(context, result)
    }
}

/** User-maintained rules survive Steam cache refreshes. */
object GameRuleStore {
    private fun key(name: String) = name.trim().lowercase(Locale.ROOT)
    fun read(context: Context, name: String): JSONObject = try {
        JSONObject(context.getSharedPreferences("game_rule_overrides", Context.MODE_PRIVATE)
            .getString(key(name), "{}") ?: "{}")
    } catch (_: Exception) { JSONObject() }
    fun save(context: Context, name: String, data: JSONObject) {
        context.getSharedPreferences("game_rule_overrides", Context.MODE_PRIVATE).edit()
            .putString(key(name), data.toString()).apply()
    }
    fun names(context: Context): List<String> = context.getSharedPreferences("game_rule_overrides", Context.MODE_PRIVATE)
        .all.keys.sorted()
    fun words(data: JSONObject, field: String): List<String> {
        val a = data.optJSONArray(field) ?: JSONArray()
        return (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotBlank() }
    }
    fun add(context: Context, name: String, field: String, value: String) {
        val clean = value.trim()
        if (clean.isEmpty()) return
        val data = read(context, name)
        val values = (words(data, field) + clean).distinctBy { it.lowercase(Locale.ROOT) }
        data.put(field, JSONArray(values))
        save(context, name, data)
    }
    fun remove(context: Context, name: String, field: String, value: String) {
        val data = read(context, name)
        data.put(field, JSONArray(words(data, field).filterNot { it.equals(value, ignoreCase = true) }))
        save(context, name, data)
    }
}

/** Cached Steam metadata; never query Plati product detail pages. */
object SteamGameReference {
    data class Game(val canonical: String, val dlcNames: List<String>, val source: String)
    private const val TTL = 7L * 24 * 60 * 60 * 1000

    private fun getJson(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 6000
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "Igrolov/1.0.2")
        return try {
            if (conn.responseCode !in 200..299) error("Steam HTTP ${conn.responseCode}")
            JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally { conn.disconnect() }
    }

    data class Suggestion(val id: Int, val name: String)

    /** Search results retain Steam AppID, avoiding ambiguous second title searches. */
    fun searchGames(term: String): List<Suggestion> {
        if (term.isBlank()) return emptyList()
        return try {
            val encoded = URLEncoder.encode(term.trim(), "UTF-8")
            val items = getJson("https://store.steampowered.com/api/storesearch/?term=$encoded&l=english&cc=us")
                .optJSONArray("items") ?: JSONArray()
            (0 until minOf(items.length(), 40)).mapNotNull { i ->
                val item = items.optJSONObject(i) ?: return@mapNotNull null
                val id = item.optInt("id", 0)
                val name = item.optString("name").trim()
                if (id > 0 && name.isNotBlank()) Suggestion(id, name) else null
            }.distinctBy { it.id }.take(20)
        } catch (_: Exception) { emptyList() }
    }

    fun searchTitles(term: String): List<String> = searchGames(term).map { it.name }

    fun lookup(context: Context, query: String, selectedAppId: Int? = null): Game {
        val prefs = context.getSharedPreferences("steam_game_reference", Context.MODE_PRIVATE)
        val key = query.lowercase(Locale.ROOT).trim()
        val saved = prefs.getString(key, null)
        if (saved != null) {
            runCatching {
                val data = JSONObject(saved)
                if (data.optInt("schema", 0) >= 3 &&
                    (selectedAppId == null || selectedAppId == data.optInt("appId")) &&
                    System.currentTimeMillis() - data.optLong("at") < TTL) {
                    val arr = data.optJSONArray("dlc") ?: JSONArray()
                    return Game(data.optString("name", query),
                        (0 until arr.length()).map { arr.optString(it) }, "Steam (кэш)")
                }
            }
        }
        return try {
            val match = if (selectedAppId != null && selectedAppId > 0) {
                Suggestion(selectedAppId, query)
            } else {
                searchGames(query).firstOrNull { it.name.equals(query, ignoreCase = true) }
                    ?: return Game(query, emptyList(), "игра не найдена в Steam — выберите из поиска")
            }
            val appId = match.id
            // Request the full details object. Steam's filtered response may omit the DLC array.
            val entry = getJson("https://store.steampowered.com/api/appdetails?appids=$appId&l=english&cc=us")
                .optJSONObject(appId.toString())
            if (entry?.optBoolean("success") != true) return Game(query, emptyList(), "Steam не вернул данные игры")
            val details = entry.optJSONObject("data") ?: return Game(query, emptyList(), "Steam не вернул описание игры")
            if (details.optString("type") != "game") return Game(query, emptyList(), "выбранный объект не является основной игрой")
            val canonical = details.optString("name", query)
            val ids = details.optJSONArray("dlc") ?: JSONArray()
            val dlcIds = (0 until minOf(ids.length(), 300)).map { ids.optInt(it, 0) }.filter { it > 0 }.distinct()
            val names = linkedSetOf<String>()
            var failures = 0
            // Steam can reject multi-app requests. Fetch each DLC individually.
            for (dlcId in dlcIds) {
                val name = runCatching {
                    val response = getJson("https://store.steampowered.com/api/appdetails?appids=$dlcId&filters=basic&l=english&cc=us")
                        .optJSONObject(dlcId.toString())
                    if (response?.optBoolean("success") == true)
                        response.optJSONObject("data")?.optString("name")?.trim()?.takeIf { it.isNotBlank() }
                    else null
                }.getOrNull()
                if (name != null) names.add(name) else failures++
            }
            // Don't cache incomplete results, so a retry can recover after rate limiting.
            if (failures == 0) {
                val obj = JSONObject().put("at", System.currentTimeMillis()).put("name", canonical)
                    .put("appId", appId).put("schema", 3).put("dlc", JSONArray(names.toList()))
                prefs.edit().putString(key, obj.toString()).apply()
            }
            val status = when {
                failures > 0 -> "Steam (получено ${names.size} из ${dlcIds.size}; часть DLC недоступна, повторите позже)"
                ids.length() > dlcIds.size -> "Steam (показаны первые ${dlcIds.size} из ${ids.length()} DLC)"
                else -> "Steam"
            }
            Game(canonical, names.toList(), status)
        } catch (e: Exception) {
            Game(query, emptyList(), "ошибка Steam: ${e.message ?: "нет соединения"}")
        }
    }

    fun isKnownDlc(offer: Offer, game: Game, rules: JSONObject = JSONObject()): Boolean {
        val title = OfferRules.normalize(offer.name)
        val canonical = rules.optString("canonical").ifBlank { game.canonical }
        return game.dlcNames.any { dlc ->
            val suffix = OfferRules.normalize(dlc).removePrefix(OfferRules.normalize(canonical))
                .trim(' ', ':', '-', '–', '—')
            suffix.length >= 5 && title.contains(suffix)
        }
    }

    fun isMainGame(offer: Offer, game: Game, rules: JSONObject = JSONObject()): Boolean {
        val title = offer.name.lowercase(Locale.ROOT).replace('ё', 'е')
        val allowed = GameRuleStore.words(rules, "allowed")
        val blocked = GameRuleStore.words(rules, "blocked")
        fun containsPhrase(phrase: String): Boolean {
            val words = phrase.lowercase(Locale.ROOT).replace('ё', 'е').trim()
            if (words.isEmpty()) return false
            return title.contains(words)
        }
        if (blocked.any(::containsPhrase)) return false
        // Explicit Steam DLC matches and strong add-on markers override manually allowed editions.
        if (isKnownDlc(offer, game, rules)) return false
        if (OfferRules.editionType(offer.name, game.canonical) == "DLC") return false
        if (allowed.any(::containsPhrase)) return offer.edition == "Игра"
        if (offer.edition != "Игра") return false
        return true
    }
}

object CatalogPageSettings {
    private const val PREFS = "catalog_page_settings"
    private val allowed = setOf(100, 200, 300, 500)
    fun get(context: Context): Int = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getInt("max_items", 200).takeIf { it in allowed } ?: 200
    fun set(context: Context, count: Int) {
        if (count in allowed) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt("max_items", count).apply()
    }
    fun pages(context: Context): Int = get(context) / 100
}

object OfferAvailability {
    /** Conservative confirmation: an absent field or failed request is not proof of stock. */
    fun confirmedInStock(id: String): Boolean {
        if (!id.matches(Regex("[0-9]{4,12}"))) return false
        return try {
            val conn = URL("https://api.digiseller.com/api/products/$id/data?currency=RUB&lang=ru-RU")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/json")
            try {
                if (conn.responseCode !in 200..299) return false
                val root = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                if (root.optInt("retval", -1) != 0) return false
                val product = root.optJSONObject("product") ?: return false
                product.has("is_available") && product.optInt("is_available", 0) == 1
            } finally { conn.disconnect() }
        } catch (_: Exception) { false }
    }
}

object CatalogCheck {
    /** The same first-page search as v0.5.3; it is not a guarantee of exhaustive marketplace coverage. */
    fun find(name: String, pageCount: Int): List<Offer> {
        val offers = mutableListOf<Offer>()
        val seen = mutableSetOf<String>()
        for (page in 1..pageCount) {
        val params = linkedMapOf(
            "query" to name, "searchmode" to "10", "sortmode" to "2", "pagesize" to "100",
            "pagenum" to page.toString(), "owner" to "1", "details" to "1", "checkhidesales" to "1", "host" to "plati.market"
        ).entries.joinToString("&") { (k,v) -> "${URLEncoder.encode(k,"UTF-8") }=${URLEncoder.encode(v,"UTF-8")}" }
        var root: JSONObject? = null
        for (attempt in 0..2) {
            try {
                val conn = URL("https://api.digiseller.com/api/products/search2?$params").openConnection() as HttpURLConnection
                conn.connectTimeout = 15000; conn.readTimeout = 20000
                conn.setRequestProperty("Accept", "application/json")
                try {
                    val status = conn.responseCode
                    if (status !in 200..299) {
                        if (status == 429 || status in 500..599) throw java.io.IOException("HTTP $status")
                        error("HTTP $status")
                    }
                    root = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                    break
                } finally { conn.disconnect() }
            } catch (e: Exception) {
                val retryable = e is java.net.UnknownHostException ||
                    e is java.net.SocketTimeoutException ||
                    e is java.net.ConnectException ||
                    (e is java.io.IOException && (e.message?.startsWith("HTTP 5") == true || e.message == "HTTP 429"))
                if (!retryable || attempt == 2) throw e
                Thread.sleep(400L * (attempt + 1))
            }
        }
        val response = root ?: error("Нет ответа каталога")
        if (true) {
            if (response.optInt("retval",0) != 0) error(response.optString("retdesc", "Ошибка API"))
            val result = response.optJSONObject("result")
            val raw = response.optJSONObject("items")?.optJSONArray("item")
                ?: response.optJSONArray("items") ?: result?.optJSONArray("items")
                ?: continue
            for (i in 0 until raw.length()) {
                val item = raw.optJSONObject(i) ?: continue
                val id = item.optString("id",item.optString("id_goods",""))
                val title = item.optString("name","").trim()
                val p = item.optString("price_rur", "").ifBlank { item.optString("price_rub", "") }
                    .ifBlank { item.optString("price", "") }.replace(',', '.').replace(" ", "").toDoubleOrNull()
                if (!id.matches(Regex("[0-9]{4,12}")) || title.isEmpty() || p == null || !p.isFinite() || p <= 0) continue
                val type = OfferRules.classifySteamGame(title,name) ?: continue
                if (seen.add(id)) offers.add(Offer(id,title,p,type,OfferRules.rfStatus(title),OfferRules.editionType(title,name)))
            }
        }
        }
        return offers.distinctBy { it.id }.sortedBy { it.price }
    }
}

object WatchScheduler {
    fun checkNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<PriceWatchWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork("plati_manual_price_check", ExistingWorkPolicy.REPLACE, request)
    }
    fun start(context: Context) {
        val request = PeriodicWorkRequestBuilder<PriceWatchWorker>(1, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("plati_game_price_watch", ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

class PriceWatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return synchronized(WatchStore.lock) { runChecks() }
    }
    private fun runChecks(): Result {
        val watches = WatchStore.read(applicationContext)
        val ignored = IgnoredStore.ids(applicationContext)
        var failed = false
        setProgressAsync(workDataOf("done" to 0, "total" to watches.length(), "game" to "Подготовка"))
        for (i in 0 until watches.length()) {
            val watch = watches.optJSONObject(i) ?: continue
            val name = watch.optString("name")
            setProgressAsync(workDataOf("done" to i, "total" to watches.length(), "game" to name))
            try {
                val kind = watch.optString("kind", "Все")
                val catalog = CatalogCheck.find(name, CatalogPageSettings.pages(applicationContext))
                val reference = SteamGameReference.lookup(applicationContext, name)
                val rules = GameRuleStore.read(applicationContext, name)
                val offers = catalog.filter { offer ->
                    offer.id !in ignored && OfferRules.eligible(offer, "Игра", kind, reference, rules, allowUnconfirmed = false)
                }
                // Catalog prices for selectable editions/regions may be teaser prices.
                // Display them, but do not use them as verified price-alert triggers.
                val candidates = offers.filterNot { OfferRules.variablePrice(it.name) }
                    .filter { SteamGameReference.isMainGame(it, reference, rules) }
                    .sortedBy { it.price }
                // Scan up to 20 cheapest candidates until three in-stock offers are found.
                // A fixed first-five limit missed available offers further down the list.
                val reliableOffers = mutableListOf<Offer>()
                for (candidate in candidates.take(20)) {
                    if (isStopped) break
                    if (OfferAvailability.confirmedInStock(candidate.id)) {
                        reliableOffers.add(candidate)
                        if (reliableOffers.size >= 3) break
                    }
                }
                val cheapest = reliableOffers.minByOrNull { it.price }
                val checkedAt = System.currentTimeMillis()
                watch.put("last", cheapest?.price ?: -1.0)
                watch.put("checked", checkedAt)
                watch.put("totalCount", catalog.size)
                watch.put("eligibleCount", offers.size)
                watch.put("verifiedCount", reliableOffers.size)
                watch.put("bestId", cheapest?.id ?: "")
                val topOffers = JSONArray()
                reliableOffers.take(3).forEach { offer ->
                    topOffers.put(JSONObject().put("id", offer.id).put("name", offer.name)
                        .put("price", offer.price).put("type", offer.type))
                }
                watch.put("topOffers", topOffers)
                // Store one real marketplace minimum per successful check, locally on the device.
                // A null price means the search succeeded but no eligible offer was found.
                val oldHistory = watch.optJSONArray("history") ?: JSONArray()
                val history = JSONArray()
                val cutoff = checkedAt - 90L * 24 * 60 * 60 * 1000
                for (h in 0 until oldHistory.length()) {
                    val sample = oldHistory.optJSONObject(h) ?: continue
                    if (sample.optLong("at", 0L) >= cutoff) history.put(sample)
                }
                val point = JSONObject().put("at", checkedAt).put("count", offers.size)
                if (cheapest != null) point.put("price", cheapest.price)
                history.put(point)
                // Limit stored data if the user triggers many manual checks.
                val capped = JSONArray()
                for (h in maxOf(0, history.length() - 400) until history.length()) capped.put(history.get(h))
                watch.put("history", capped)
                watch.remove("error")
                val limit = watch.optDouble("limit", 0.0)
                if (limit > 0) {
                    val already = watch.optJSONArray("notified") ?: JSONArray()
                    val known = (0 until already.length()).map { already.optString(it) }.toSet()
                    val newOffers = reliableOffers.filter { it.price <= limit && it.id !in known }
                    if (newOffers.isNotEmpty()) {
                        val best = newOffers.minByOrNull { it.price }!!
                        // Keep a history even if Android notifications are disabled.
                        newOffers.forEach { AlertJournal.record(applicationContext, name, it, limit) }
                        sendNotification(best, name, i)
                        // Mark all qualifying offers, preventing repeated notifications on every run.
                        newOffers.forEach { already.put(it.id) }
                        watch.put("notified", already)
                    }
                }
            } catch (e: Exception) {
                failed = true
                watch.put("error", e.message ?: "Ошибка сети")
                watch.put("failedAt", System.currentTimeMillis())
            }
            WatchStore.write(applicationContext, watches)
            setProgressAsync(workDataOf("done" to (i + 1), "total" to watches.length(), "game" to name))
        }
        WatchStore.write(applicationContext,watches)
        return if (failed) Result.retry() else Result.success()
    }
    private fun sendNotification(offer: Offer, game: String, index: Int) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel("price_alerts", "Снижение цен Plati", NotificationManager.IMPORTANCE_DEFAULT))
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://plati.market/itm/${offer.id}"))
        val pending = PendingIntent.getActivity(applicationContext, offer.id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, "price_alerts")
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("$game — ${String.format(Locale("ru","RU"), "%.0f ₽",offer.price)}")
            .setContentText("${offer.type} · ${offer.name}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "${offer.type} · ${offer.name}\nЦена: ${String.format(Locale("ru","RU"), "%.2f ₽", offer.price)}\nНажмите, чтобы открыть предложение на Plati."))
            .setContentIntent(pending).setAutoCancel(true).build()
        manager.notify(offer.id.hashCode(), notification)
    }
}
