package ru.igrolov.app

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

/** Reads portrait cover links from SteamGridDB's public game page; no API or account key is used. */
object SteamGridDbArtwork {
    private const val EMPTY_CACHE_TTL_MS = 30 * 60 * 1000L
    private const val POSTER_CACHE_TTL_MS = 30 * 24 * 60 * 60 * 1000L

    /** Returns community portrait cover links from the public Steam-ID page, without API routes. */
    fun fetchPortraitUrls(context: Context, appId: Int): List<String> {
        if (appId <= 0) return emptyList()
        val prefs = context.getSharedPreferences("steam_artwork_urls", Context.MODE_PRIVATE)
        // Bump the key to discard empty results saved by the previous brittle parser.
        val cacheKey = "sgdb_public_page_portrait_v2_$appId"
        val cachedAtKey = "${cacheKey}_at"
        val now = System.currentTimeMillis()
        val cachedAt = prefs.getLong(cachedAtKey, 0L)
        val cachedUrls = prefs.getString(cacheKey, null)?.split('\n')?.filter(::isSafeGridUrl)
        if (cachedAt > 0L && cachedUrls != null) {
            val ttl = if (cachedUrls.isEmpty()) EMPTY_CACHE_TTL_MS else POSTER_CACHE_TTL_MS
            if (now - cachedAt in 0..ttl) return cachedUrls
        }
        // A timeout, DNS error, or anti-bot response is transient. Do not cache it as
        // "this game has no artwork"; otherwise one bad request hides covers for hours.
        val html = runCatching { request(publicGridPageUrl(appId)) }.getOrNull() ?: return emptyList()
        val urls = parsePortraitUrls(html)
        prefs.edit().putString(cacheKey, urls.joinToString("\n")).putLong(cachedAtKey, now).apply()
        return urls
    }

    internal fun publicGridPageUrl(appId: Int): String = "https://www.steamgriddb.com/steam/$appId/grids"

    /** Only take image links from page cards explicitly labelled with Steam's vertical 600x900 dimensions. */
    internal fun parsePortraitUrls(html: String): List<String> {
        val cards = Regex("(?is)<div\\b(?=[^>]*\\bclass\\s*=\\s*[\"'][^\"']*\\basset\\b)[^>]*>.*?(?=<div\\b(?=[^>]*\\bclass\\s*=\\s*[\"'][^\"']*\\basset\\b)|$)")
            .findAll(html)
            .map { it.value }
        val links = Regex("(?i)(?:href|src|data-src|data-original)\\s*=\\s*[\"'](https://cdn2\\.steamgriddb\\.com/grid/[a-f0-9]+\\.(?:png|jpe?g|webp))[\"']")
        val dimensions = Regex("600\\s*[×x]\\s*900", RegexOption.IGNORE_CASE)
        val fromCards = cards
            .filter(dimensions::containsMatchIn)
            .flatMap { card -> links.findAll(card).map { it.groupValues[1] }.toList() }
        // SteamGridDB has changed its card markup before. If its asset class changes,
        // still recover the URL from the same compact HTML region as the 600x900 label.
        val fromPage = if (fromCards.isNotEmpty()) emptyList() else {
            val urls = links.findAll(html).toList()
            urls.filter { candidate ->
                val start = (candidate.range.first - 900).coerceAtLeast(0)
                val end = (candidate.range.last + 901).coerceAtMost(html.length)
                dimensions.containsMatchIn(html.substring(start, end))
            }.map { it.groupValues[1] }
        }
        return (fromCards + fromPage).map { it.trim() }.filter(::isSafeGridUrl).distinct()
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
