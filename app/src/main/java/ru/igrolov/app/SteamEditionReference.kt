package ru.igrolov.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Purchase options exposed for a Steam game, verified to include that game's app ID. */
object SteamEditionReference {
    data class Edition(val appId: Int, val packageId: Int, val name: String, val searchTitle: String, val isBase: Boolean)

    private const val MAX_PACKAGE_LOOKUPS = 24
    private const val MAX_PARALLEL_PACKAGE_LOOKUPS = 4
    private const val LOOKUP_TIMEOUT_SECONDS = 30L
    private const val CACHE_TTL_MILLIS = 30 * 60 * 1000L
    private data class CachedEditions(val savedAt: Long, val editions: List<Edition>)

    private val packageLookupExecutor = Executors.newFixedThreadPool(MAX_PARALLEL_PACKAGE_LOOKUPS) { task ->
        Thread(task, "steam-edition-lookup").apply { isDaemon = true }
    }
    private val cache = ConcurrentHashMap<Int, CachedEditions>()

    fun discover(appId: Int): List<Edition> {
        require(appId > 0)
        val now = System.currentTimeMillis()
        cache[appId]?.takeIf { now - it.savedAt < CACHE_TTL_MILLIS }?.let { return it.editions }
        val editions = discover(appId, ::getJson)
        if (editions.isNotEmpty()) cache[appId] = CachedEditions(now, editions)
        return editions
    }

    /** Injectable, uncached path keeps package lookup behavior deterministic in tests. */
    internal fun discover(appId: Int, fetchJson: (String) -> JSONObject): List<Edition> {
        require(appId > 0)
        val app = fetchJson("https://store.steampowered.com/api/appdetails?appids=$appId&l=english&cc=us")
            .optJSONObject(appId.toString())?.takeIf { it.optBoolean("success") }?.optJSONObject("data")
            ?: return emptyList()
        if (app.optString("type") != "game") return emptyList()
        val canonical = app.optString("name").trim().takeIf { it.isNotBlank() } ?: return emptyList()
        val packageIds = packageIds(app).take(MAX_PACKAGE_LOOKUPS)
        if (packageIds.isEmpty()) return emptyList()
        val requests = packageIds.map { packageId -> Callable {
            runCatching {
                val detail = fetchJson("https://store.steampowered.com/api/packagedetails?packageids=$packageId&cc=us&l=english")
                    .optJSONObject(packageId.toString())?.takeIf { it.optBoolean("success") }?.optJSONObject("data")
                    ?: return@runCatching null
                val apps = detail.optJSONArray("apps") ?: return@runCatching null
                if ((0 until apps.length()).none { apps.optJSONObject(it)?.optInt("id") == appId }) return@runCatching null
                val name = detail.optString("name").trim().takeIf { it.isNotBlank() } ?: return@runCatching null
                Edition(appId, packageId, name, name, normalize(name) == normalize(canonical))
            }.getOrNull()
        } }
        val futures = try {
            packageLookupExecutor.invokeAll(requests, LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return emptyList()
        }
        val verified = futures.mapNotNull { future ->
            if (future.isCancelled) null else runCatching { future.get() }.getOrNull()
        }
        return verified.distinctBy { normalize(it.name) }.sortedWith(compareByDescending<Edition> { it.isBase }.thenBy { it.name.lowercase(Locale.ROOT) })
    }

    /** Pure parser used by regression tests; package contents must explicitly contain the app. */
    fun parse(appId: Int, canonical: String, appDetails: String, packageApps: Map<Int, Set<Int>>): List<Edition> {
        val root = JSONObject(appDetails)
        val data = root.optJSONObject(appId.toString())?.takeIf { it.optBoolean("success") }?.optJSONObject("data")
            ?.takeIf { it.optString("type") == "game" } ?: return emptyList()
        val title = data.optString("name").ifBlank { canonical }
        return packageIds(data).filter { appId in packageApps[it].orEmpty() }.map { id ->
            val option = packageName(data, id) ?: title
            Edition(appId, id, option, option, normalize(option) == normalize(title))
        }.distinctBy { normalize(it.name) }.sortedWith(compareByDescending<Edition> { it.isBase }.thenBy { it.name.lowercase(Locale.ROOT) })
    }

    private fun packageIds(data: JSONObject): List<Int> {
        val groups = data.optJSONArray("package_groups") ?: return emptyList()
        return (0 until groups.length()).flatMap { groups.optJSONObject(it)?.optJSONArray("subs")?.let { subs ->
            (0 until subs.length()).mapNotNull { subs.optJSONObject(it)?.optInt("packageid")?.takeIf { id -> id > 0 } }
        }.orEmpty() }.distinct()
    }

    private fun packageName(data: JSONObject, packageId: Int): String? {
        val groups = data.optJSONArray("package_groups") ?: return null
        for (i in 0 until groups.length()) {
            val subs = groups.optJSONObject(i)?.optJSONArray("subs") ?: continue
            for (j in 0 until subs.length()) {
                val sub = subs.optJSONObject(j) ?: continue
                if (sub.optInt("packageid") == packageId) {
                    val option = sub.optString("option_text").trim()
                    return option.replace(Regex("\\s+-\\s+(?:[$€£₽]|USD|EUR|GBP|RUB)\\s*[0-9][0-9.,]*.*$", RegexOption.IGNORE_CASE), "")
                        .trim().takeIf { it.isNotBlank() }
                }
            }
        }
        return null
    }

    private fun normalize(value: String) = GameSearchResolver.normalized(value)

    private fun getJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 6000
        connection.readTimeout = 6000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "Igrolov Android")
        try {
            if (connection.responseCode !in 200..299) throw java.io.IOException("Steam HTTP ${connection.responseCode}")
            return JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally { connection.disconnect() }
    }
}
