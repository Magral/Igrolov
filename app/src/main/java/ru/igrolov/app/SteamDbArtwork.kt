package ru.igrolov.app

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

/** Reads DLC-to-parent relationships from SteamDB's public HTML page without an API key. */
object SteamDbArtwork {
    private const val CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000

    fun parentAppId(context: Context, appId: Int): Int? {
        if (appId <= 0) return null
        val prefs = context.getSharedPreferences("steam_artwork_urls", Context.MODE_PRIVATE)
        val key = "steamdb_parent_app_$appId"
        val timeKey = "${key}_at"
        val now = System.currentTimeMillis()
        val cachedAt = prefs.getLong(timeKey, 0L)
        if (cachedAt > 0L && now - cachedAt in 0..CACHE_TTL_MS) {
            return prefs.getInt(key, 0).takeIf { it > 0 && it != appId }
        }

        val result = runCatching { parseParentAppId(request("https://steamdb.info/app/$appId/"), appId) }
            .getOrElse {
                SteamArtworkDiagnostics.record(appId, "https://steamdb.info/app/$appId/", it)
                null
            }
        prefs.edit().putInt(key, result ?: 0).putLong(timeKey, now).apply()
        return result
    }

    internal fun parseParentAppId(html: String, childAppId: Int): Int? {
        val parentRow = Regex(
            """(?is)<tr\b[^>]*>\s*<td[^>]*>\s*Parent\s+App\s*</td>\s*<td[^>]*>(.*?)</td>\s*</tr>"""
        ).find(html)?.groupValues?.get(1) ?: return null
        val parent = Regex("""(?i)data-appid=["']([0-9]{1,10})["']""")
            .find(parentRow)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return parent.takeIf { it > 0 && it != childAppId }
    }

    private fun request(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 7000
        connection.readTimeout = 9000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
        connection.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36 Igrolov/1.5.7"
        )
        try {
            if (connection.responseCode !in 200..299) throw java.io.IOException("SteamDB page HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
