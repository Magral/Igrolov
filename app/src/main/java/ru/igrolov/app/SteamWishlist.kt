package ru.igrolov.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.Executors

data class WishlistGame(
    val appId: Int,
    val title: String = "",
    val steamPrice: String = "",
    val steamInitialPrice: String = "",
    val discountPercent: Int = 0,
    val lowestPlatiPrice: Double? = null,
    val lowestGgselPrice: Double? = null,
    val features: SteamGameFeatures = SteamGameFeatures()
)

data class WishlistProfile(
    val steamId64: String,
    val displayName: String,
    val input: String,
    val games: List<WishlistGame> = emptyList(),
    val importedAt: Long = 0L
)

data class WishlistSnapshot(val steamId64: String, val displayName: String, val games: List<WishlistGame>)

/** Small boundary around Steam's currently undocumented public wishlist endpoint. */
object SteamWishlist {
    private val id64 = Regex("[0-9]{17}")

    fun profileId(input: String): String? {
        val value = input.trim()
        if (id64.matches(value)) return value
        val url = runCatching { URL(value) }.getOrNull() ?: return null
        if (url.host.lowercase() !in setOf("steamcommunity.com", "www.steamcommunity.com", "store.steampowered.com")) return null
        val parts = url.path.split('/').filter { it.isNotBlank() }
        val marker = parts.indexOfFirst { it.equals("profiles", true) }
        if (marker >= 0) return parts.getOrNull(marker + 1)?.takeIf(id64::matches)
        return null
    }

    fun vanityName(input: String): String? {
        val url = runCatching { URL(input.trim()) }.getOrNull() ?: return null
        if (url.host.lowercase() !in setOf("steamcommunity.com", "www.steamcommunity.com")) return null
        val parts = url.path.split('/').filter { it.isNotBlank() }
        if (parts.size < 2 || !parts[0].equals("id", true)) return null
        return parts[1].takeIf { it.matches(Regex("[A-Za-z0-9_-]{2,64}")) }
    }

    fun parsePublicProfile(xml: String): Pair<String?, String?> {
        val id = Regex("<steamID64>\\s*([0-9]{17})\\s*</steamID64>", RegexOption.IGNORE_CASE)
            .find(xml)?.groupValues?.get(1)
        val name = Regex("<steamID(?:\\s[^>]*)?>(.*?)</steamID>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(xml)?.groupValues?.get(1)?.let(::decodeXml)?.trim()?.takeIf { it.isNotBlank() }
        return id to name
    }

    fun parsePublicProfileId(xml: String): String? = parsePublicProfile(xml).first

    private fun decodeXml(value: String): String {
        val trimmed = value.trim()
        if (trimmed.startsWith("<![CDATA[") && trimmed.endsWith("]]>")) {
            return trimmed.substring(9, trimmed.length - 3)
        }
        return trimmed
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'")
            .replace(Regex("&#(\\d+);")) { match ->
                match.groupValues[1].toIntOrNull()?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: match.value
            }
            .replace(Regex("&#x([0-9a-fA-F]+);", RegexOption.IGNORE_CASE)) { match ->
                match.groupValues[1].toIntOrNull(16)?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: match.value
            }
    }

    fun parseWishlist(json: String): List<WishlistGame> {
        val root = JSONObject(json)
        val response = root.optJSONObject("response") ?: root
        val rows = response.optJSONArray("items") ?: response.optJSONArray("wishlist")
            ?: throw IllegalArgumentException("Steam вернул неизвестный формат списка желаемого")
        val result = LinkedHashMap<Int, WishlistGame>()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val appId = row.optInt("appid", row.optInt("app_id", 0))
            if (appId <= 0) continue
            val title = row.optString("name", row.optString("title", "")).trim()
            result.putIfAbsent(appId, WishlistGame(appId, title))
            if (result.size >= 500) break
        }
        return result.values.toList()
    }

    fun applyTitles(games: List<WishlistGame>, json: String): List<WishlistGame> {
        val root = JSONObject(json)
        return games.map { game ->
            val entry = root.optJSONObject(game.appId.toString())
            val data = entry?.takeIf { it.optBoolean("success") }?.optJSONObject("data")
            val price = data?.optJSONObject("price_overview")
            game.copy(
                title = data?.optString("name")?.trim()?.takeIf { it.isNotBlank() } ?: game.title,
                steamPrice = if (data?.optBoolean("is_free", false) == true) "Бесплатно"
                    else price?.optString("final_formatted", "")?.takeIf { it.isNotBlank() } ?: game.steamPrice,
                steamInitialPrice = price?.optString("initial_formatted", "")?.takeIf { it.isNotBlank() } ?: game.steamInitialPrice,
                discountPercent = price?.optInt("discount_percent", game.discountPercent)?.coerceIn(0, 100) ?: game.discountPercent,
                features = data?.let { game.features.withFallback(SteamGameFeatures.fromDetails(it)) } ?: game.features
            )
        }
    }

    fun encode(games: List<WishlistGame>): String = JSONArray().apply {
        games.distinctBy { it.appId }.take(500).forEach { game ->
            put(JSONObject().put("appId", game.appId).put("title", game.title)
                .put("steamPrice", game.steamPrice).put("steamInitialPrice", game.steamInitialPrice)
                .put("discountPercent", game.discountPercent).put("lowestPlatiPrice", game.lowestPlatiPrice)
                .put("lowestGgselPrice", game.lowestGgselPrice)
                .put("russianLocalization", game.features.russianLocalization.name)
                .put("controllerSupport", game.features.controllerSupport.name))
        }
    }.toString()

    fun decode(raw: String?): List<WishlistGame> = try {
        val array = JSONArray(raw ?: "[]")
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val appId = item.optInt("appId", 0).takeIf { it > 0 } ?: return@mapNotNull null
            WishlistGame(appId, item.optString("title", ""), item.optString("steamPrice", ""),
                item.optString("steamInitialPrice", ""), item.optInt("discountPercent", 0).coerceIn(0, 100),
                item.optDouble("lowestPlatiPrice").takeIf { item.has("lowestPlatiPrice") && it > 0.0 },
                item.optDouble("lowestGgselPrice").takeIf { item.has("lowestGgselPrice") && it > 0.0 },
                SteamGameFeatures(
                    runCatching { SteamRussianLocalization.valueOf(item.optString("russianLocalization")) }.getOrDefault(SteamRussianLocalization.UNKNOWN),
                    runCatching { SteamControllerSupport.valueOf(item.optString("controllerSupport")) }.getOrDefault(SteamControllerSupport.UNKNOWN)
                ))
        }.distinctBy { it.appId }.take(500)
    } catch (_: Exception) { emptyList() }

    fun encodeProfiles(profiles: List<WishlistProfile>): String = JSONArray().apply {
        profiles.distinctBy { it.steamId64 }.take(20).forEach { profile ->
            put(JSONObject().put("steamId64", profile.steamId64).put("displayName", profile.displayName)
                .put("input", profile.input).put("games", JSONArray(encode(profile.games)))
                .put("importedAt", profile.importedAt))
        }
    }.toString()

    fun decodeProfiles(raw: String?): List<WishlistProfile> = try {
        val rows = JSONArray(raw ?: "[]")
        (0 until rows.length()).mapNotNull { index ->
            val item = rows.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("steamId64", "").takeIf(id64::matches) ?: return@mapNotNull null
            WishlistProfile(id, item.optString("displayName", "").ifBlank { "Steam " + id },
                item.optString("input", id), decode(item.optJSONArray("games")?.toString()), item.optLong("importedAt", 0L))
        }.distinctBy { it.steamId64 }.take(20)
    } catch (_: Exception) { emptyList() }

    fun fetch(input: String): List<WishlistGame> = fetchSnapshot(input).games

    fun fetchSnapshot(input: String): WishlistSnapshot {
        val id = profileId(input) ?: vanityName(input)?.let(::resolveVanity)
            ?: throw IllegalArgumentException("Укажите SteamID64 или ссылку на открытый профиль Steam")
        val profileName = fetchProfileMetadata(id)
        val encoded = URLEncoder.encode(id, "UTF-8")
        val connection = URL("https://api.steampowered.com/IWishlistService/GetWishlist/v1/?steamid=$encoded")
            .openConnection() as HttpURLConnection
        connection.connectTimeout = 12000
        connection.readTimeout = 20000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "Igrolov Android")
        try {
            if (connection.responseCode !in 200..299) throw java.io.IOException("Steam HTTP ${connection.responseCode}")
            val games = parseWishlist(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            val pool = Executors.newFixedThreadPool(4)
            val detailed = try {
                games.chunked(4).flatMap { batch ->
                    pool.invokeAll(batch.map { game -> Callable { runCatching { fetchAppDetails(game) }.getOrDefault(game) } })
                        .mapNotNull { task -> runCatching { task.get() }.getOrNull() }
                }
            } finally { pool.shutdownNow() }
            return WishlistSnapshot(id, profileName ?: "Steam " + id, detailed)
        } finally { connection.disconnect() }
    }

    private fun fetchAppDetails(game: WishlistGame): WishlistGame {
        val regional = fetchAppDetailsJson(game.appId, "ru", "russian") ?: return game
        val enriched = runCatching { applyTitles(listOf(game), regional).first() }.getOrDefault(game)
        if (!enriched.features.needsRussianLocalizationFallback) return enriched

        // A regional response can omit the language list. Fetch global metadata only in that
        // case, and use it solely to fill unknown badges without replacing RU price/title data.
        val global = runCatching { fetchAppDetailsJson(game.appId, "us", "english") }.getOrNull() ?: return enriched
        return fillUnknownFeatures(enriched, global)
    }

    internal fun fillUnknownFeatures(game: WishlistGame, appDetailsJson: String): WishlistGame {
        val data = runCatching {
            JSONObject(appDetailsJson).optJSONObject(game.appId.toString())
                ?.takeIf { it.optBoolean("success") }?.optJSONObject("data")
        }.getOrNull() ?: return game
        return game.copy(features = game.features.withFallback(SteamGameFeatures.fromDetails(data)))
    }

    private fun fetchAppDetailsJson(appId: Int, country: String, language: String): String? {
        val connection = URL("https://store.steampowered.com/api/appdetails?appids=$appId&cc=$country&l=$language")
            .openConnection() as HttpURLConnection
        connection.connectTimeout = 10000
        connection.readTimeout = 15000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "Igrolov Android")
        return try {
            if (connection.responseCode !in 200..299) null
            else connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { connection.disconnect() }
    }

    private fun fetchProfileMetadata(id: String): String? {
        val connection = URL("https://steamcommunity.com/profiles/" + id + "/?xml=1")
            .openConnection() as HttpURLConnection
        connection.connectTimeout = 12000
        connection.readTimeout = 15000
        connection.setRequestProperty("User-Agent", "Igrolov Android")
        try {
            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return parsePublicProfile(body).second
        } catch (_: Exception) {
            return null
        } finally { connection.disconnect() }
    }

    private fun resolveVanity(vanity: String): String {
        val connection = URL("https://steamcommunity.com/id/${URLEncoder.encode(vanity, "UTF-8")}/?xml=1")
            .openConnection() as HttpURLConnection
        connection.connectTimeout = 12000
        connection.readTimeout = 20000
        connection.setRequestProperty("User-Agent", "Igrolov Android")
        try {
            if (connection.responseCode !in 200..299) throw java.io.IOException("Профиль Steam недоступен (${connection.responseCode})")
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return parsePublicProfileId(body) ?: throw IllegalArgumentException("Не удалось определить SteamID64. Проверьте ссылку и открытость профиля.")
        } finally { connection.disconnect() }
    }
}

