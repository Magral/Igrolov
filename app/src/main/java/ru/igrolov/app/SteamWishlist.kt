package ru.igrolov.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class WishlistGame(val appId: Int, val title: String = "")

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

    fun parsePublicProfileId(xml: String): String? =
        Regex("<steamID64>\\s*([0-9]{17})\\s*</steamID64>", RegexOption.IGNORE_CASE)
            .find(xml)?.groupValues?.get(1)

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
            game.copy(title = data?.optString("name")?.trim()?.takeIf { it.isNotBlank() } ?: game.title)
        }
    }

    fun encode(games: List<WishlistGame>): String = JSONArray().apply {
        games.distinctBy { it.appId }.take(500).forEach { game ->
            put(JSONObject().put("appId", game.appId).put("title", game.title))
        }
    }.toString()

    fun decode(raw: String?): List<WishlistGame> = try {
        val array = JSONArray(raw ?: "[]")
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val appId = item.optInt("appId", 0).takeIf { it > 0 } ?: return@mapNotNull null
            WishlistGame(appId, item.optString("title", ""))
        }.distinctBy { it.appId }.take(500)
    } catch (_: Exception) { emptyList() }

    fun fetch(input: String): List<WishlistGame> {
        val id = profileId(input) ?: vanityName(input)?.let(::resolveVanity)
            ?: throw IllegalArgumentException("Укажите SteamID64 или ссылку на открытый профиль Steam")
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
            return games.chunked(80).flatMap { batch ->
                val ids = batch.joinToString(",") { it.appId.toString() }
                val details = runCatching {
                    val detailConnection = URL("https://store.steampowered.com/api/appdetails?appids=$ids&filters=basic&cc=ru&l=russian")
                        .openConnection() as HttpURLConnection
                    detailConnection.connectTimeout = 10000
                    detailConnection.readTimeout = 15000
                    detailConnection.setRequestProperty("Accept", "application/json")
                    try {
                        if (detailConnection.responseCode !in 200..299) error("Steam details HTTP ${detailConnection.responseCode}")
                        detailConnection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    } finally { detailConnection.disconnect() }
                }.getOrNull()
                if (details == null) batch else runCatching { applyTitles(batch, details) }.getOrDefault(batch)
            }
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
