package ru.igrolov.app

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

/** Reads portrait cover links from SteamGridDB's public game page; no API or account key is used. */
object SteamGridDbArtwork {
    private const val EMPTY_CACHE_TTL_MS = 6 * 60 * 60 * 1000L
    private const val POSTER_CACHE_TTL_MS = 30 * 24 * 60 * 60 * 1000L

    /** Returns community portrait cover links from the public Steam-ID page, without API routes. */
    fun fetchPortraitUrls(context: Context, appId: Int): List<String> {
        if (appId <= 0) return emptyList()
        val prefs = context.getSharedPreferences("steam_artwork_urls", Context.MODE_PRIVATE)
        val cacheKey = "sgdb_public_page_portrait_$appId"
        val cachedAtKey = "${cacheKey}_at"
        val now = System.currentTimeMillis()
        val cachedAt = prefs.getLong(cachedAtKey, 0L)
        val cachedUrls = prefs.getString(cacheKey, null)?.split('\n')?.filter(::isSafeGridUrl)
        if (cachedAt > 0L && cachedUrls != null) {
            val ttl = if (cachedUrls.isEmpty()) EMPTY_CACHE_TTL_MS else POSTER_CACHE_TTL_MS
            if (now - cachedAt in 0..ttl) return cachedUrls
        }
        val urls = runCatching { parsePortraitUrls(request(publicGridPageUrl(appId))) }.getOrDefault(emptyList())
        prefs.edit().putString(cacheKey, urls.joinToString("\n")).putLong(cachedAtKey, now).apply()
        return urls
    }

    internal fun publicGridPageUrl(appId: Int): String = "https://www.steamgriddb.com/steam/$appId/grids"

    /** Only take image links from page cards explicitly labelled with Steam's vertical 600x900 dimensions. */
    internal fun parsePortraitUrls(html: String): List<String> {
        val cards = Regex("(?is)<div\\s+class=\"asset\\b.*?(?=<div\\s+class=\"asset\\b|$)")
            .findAll(html)
            .map { it.value }
        val links = Regex("(?i)href=[\"'](https://cdn2\\.steamgriddb\\.com/grid/[a-f0-9]+\\.(?:png|jpe?g|webp))[\"']")
        return cards
            .filter { card -> card.contains("600×900") || card.contains("600x900", ignoreCase = true) }
            .flatMap { card -> links.findAll(card).map { it.groupValues[1] }.toList() }
            .map { it.trim() }
            .filter(::isSafeGridUrl)
            .distinct()
            .toList()
    }

    private fun isSafeGridUrl(value: String): Boolean = runCatching {
        val url = URL(value)
        url.protocol.equals("https", ignoreCase = true) &&
            url.host.equals("cdn2.steamgriddb.com", ignoreCase = true) &&
            url.path.matches(Regex("/grid/[A-Za-z0-9._-]+\\.(png|jpe?g|webp)", RegexOption.IGNORE_CASE))
    }.getOrDefault(false)

    private fun request(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 7000
        connection.readTimeout = 9000
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36 Igrolov/1.5.7")
        connection.setRequestProperty("Referer", "https://www.steamgriddb.com/")
        try {
            if (connection.responseCode !in 200..299) throw java.io.IOException("SteamGridDB page HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
