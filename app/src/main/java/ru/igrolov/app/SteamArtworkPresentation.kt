package ru.igrolov.app

import org.json.JSONObject

/** Steam may return portrait library artwork only through its hashed Store asset URL format. */
object SteamArtworkPresentation {
    private val storeAssetHosts = listOf(
        "https://shared.steamstatic.com/store_item_assets/",
        "https://shared.akamai.steamstatic.com/store_item_assets/",
        "https://shared.fastly.steamstatic.com/store_item_assets/"
    )

    fun isPortrait(url: String): Boolean =
        url.contains("/library_600x900", ignoreCase = true) ||
            (url.contains("cdn2.steamgriddb.com", ignoreCase = true) && url.contains("/grid/", ignoreCase = true))

    fun matchesAspect(url: String, portrait: Boolean): Boolean = isPortrait(url) == portrait

    fun filterForAspect(urls: List<String>, portrait: Boolean): List<String> =
        urls.filter { matchesAspect(it, portrait) }.distinct()

    fun cacheKey(appId: Int, portrait: Boolean): String =
        "${if (portrait) "portrait" else "landscape"}_$appId"

    /** Alternate Steam CDN paths; the caller still keeps the requested artwork aspect ratio. */
    fun directFallbackUrls(appId: Int, portrait: Boolean): List<String> {
        if (appId <= 0) return emptyList()
        val filenames = if (portrait) {
            listOf("library_600x900.jpg", "library_600x900_2x.jpg")
        } else {
            listOf("header.jpg", "capsule_616x353.jpg", "capsule_467x181.jpg")
        }
        return filenames.flatMap { filename ->
            listOf(
                "https://cdn.cloudflare.steamstatic.com/steam/apps/$appId/$filename",
                "https://cdn.akamai.steamstatic.com/steam/apps/$appId/$filename",
                "https://steamcdn-a.akamaihd.net/steam/apps/$appId/$filename"
            )
        }.distinct()
    }

    fun libraryCapsuleUrls(responseJson: String, appId: Int): List<String> = runCatching {
        val items = JSONObject(responseJson).optJSONObject("response")?.optJSONArray("store_items")
            ?: return emptyList()
        val item = (0 until items.length())
            .mapNotNull { items.optJSONObject(it) }
            .firstOrNull { it.optInt("appid") == appId && it.optInt("success", 1) == 1 }
            ?: return emptyList()
        val assets = item.optJSONObject("assets") ?: return emptyList()
        val template = assets.optString("asset_url_format").trim()
        if (template.isBlank()) return emptyList()
        val filenameToken = "\${FILENAME}"
        listOf("library_capsule_2x", "library_capsule")
            .map { assets.optString(it).trim() }
            .filter { it.isNotBlank() }
            .flatMap { filename ->
                val resolved = template.replace(filenameToken, filename)
                if (resolved.startsWith("https://", ignoreCase = true)) listOf(resolved)
                else storeAssetHosts.map { host -> host + resolved.trimStart('/') }
            }
            .distinct()
    }.getOrDefault(emptyList())
}
