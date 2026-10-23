package ru.igrolov.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Purchase options exposed for a Steam game, verified to include that game's app ID. */
object SteamEditionReference {
    data class Edition(val appId: Int, val packageId: Int, val name: String, val searchTitle: String, val isBase: Boolean)

    fun discover(appId: Int): List<Edition> {
        require(appId > 0)
        val app = getJson("https://store.steampowered.com/api/appdetails?appids=$appId&l=english&cc=us")
            .optJSONObject(appId.toString())?.takeIf { it.optBoolean("success") }?.optJSONObject("data")
            ?: return emptyList()
        if (app.optString("type") != "game") return emptyList()
        val canonical = app.optString("name").trim().takeIf { it.isNotBlank() } ?: return emptyList()
        val packageIds = packageIds(app).take(12)
        if (packageIds.isEmpty()) return emptyList()
        val verified = packageIds.mapNotNull { packageId ->
            runCatching {
                val detail = getJson("https://store.steampowered.com/api/packagedetails?packageids=$packageId&cc=us&l=english")
                    .optJSONObject(packageId.toString())?.takeIf { it.optBoolean("success") }?.optJSONObject("data")
                    ?: return@runCatching null
                val apps = detail.optJSONArray("apps") ?: return@runCatching null
                if ((0 until apps.length()).none { apps.optJSONObject(it)?.optInt("id") == appId }) return@runCatching null
                val name = detail.optString("name").trim().takeIf { it.isNotBlank() } ?: return@runCatching null
                Edition(appId, packageId, name, name, normalize(name) == normalize(canonical))
            }.getOrNull()
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
