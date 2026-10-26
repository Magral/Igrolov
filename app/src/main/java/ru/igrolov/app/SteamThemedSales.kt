package ru.igrolov.app

import org.json.JSONObject

data class SteamThemedSaleEvent(
    val name: String,
    val url: String,
    val body: String = "",
    val headerImageUrl: String = "",
    val appIds: List<Int> = emptyList()
)

/** Reads current sale spotlights from Steam's public regional storefront responses. */
object SteamThemedSales {
    private val appUrl = Regex("(?i)/app/([0-9]{1,10})(?:/|$|[?#])")
    private val appIdAttribute = Regex("(?i)(?:data-ds-appid|data-appid)=[\"']([0-9]{1,10})[\"']")
    private val htmlTitle = Regex(
        "(?is)<(?:div|span|a)[^>]*class=[\"'][^\"']*(?:salepreview_item_name|search_result_row|title|game_name)[^\"']*[\"'][^>]*>(.*?)</(?:div|span|a)>"
    )
    private val saleTerm = Regex("(?i)(?:sale|fest|festival|распрод|фест|скидк|акци)")

    fun parseFeaturedCategories(raw: String): List<SteamThemedSaleEvent> {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val events = mutableListOf<SteamThemedSaleEvent>()
        (0..7).forEach { index ->
            val group = root.optJSONObject(index.toString()) ?: return@forEach
            val items = group.optJSONArray("items") ?: return@forEach
            for (itemIndex in 0 until items.length()) {
                val item = items.optJSONObject(itemIndex) ?: continue
                eventFrom(item, group.optString("name"))?.let(events::add)
            }
        }
        return events.distinctBy { it.url }
    }

    fun parseDiscountsAndEvents(raw: String): List<SteamThemedSaleEvent> {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val items = root.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { index ->
            eventFrom(items.optJSONObject(index) ?: return@mapNotNull null, "")?.takeIf {
                it.url.contains("/sale/", ignoreCase = true)
            }
        }.distinctBy { it.url }
    }

    private fun eventFrom(item: JSONObject, fallbackName: String): SteamThemedSaleEvent? {
        val url = sequenceOf("url", "sale_url", "target_url")
            .map { item.optString(it).trim() }
            .firstOrNull { it.contains("store.steampowered.com", ignoreCase = true) && it.contains("/sale/", ignoreCase = true) }
            ?: return null
        val name = sequenceOf("title", "name", "alt")
            .map { item.optString(it).trim() }
            .firstOrNull { it.isNotBlank() && saleTerm.containsMatchIn(it) }
            ?: fallbackName.takeIf { saleTerm.containsMatchIn(it) }
            ?: url.substringAfter("/sale/").substringBefore("?").substringBefore("#").replace(Regex("[_/-]+"), " ").trim()
                .takeIf { saleTerm.containsMatchIn(it) }
            ?: return null
        val ids = sequenceOf("appid", "app_id")
            .map { item.optInt(it, 0) }
            .firstOrNull { it > 0 }
            ?.let(::listOf)
            ?: appUrl.find(url)?.groupValues?.get(1)?.toIntOrNull()?.let(::listOf)
            ?: emptyList()
        return SteamThemedSaleEvent(
            name = name,
            url = url,
            body = sequenceOf("body", "description", "discount_text").map { item.optString(it).trim() }
                .firstOrNull { it.isNotBlank() }.orEmpty(),
            headerImageUrl = sequenceOf("header_image", "image", "banner")
                .map { item.optString(it).trim() }.firstOrNull { it.startsWith("https://") }.orEmpty(),
            appIds = ids
        )
    }

    fun parseSalePageGames(html: String, eventName: String): List<SteamTopSeller> {
        val found = linkedMapOf<Int, SteamTopSeller>()
        SteamTopSellerParser.parseRankingHtml(html).forEach { game ->
            found.putIfAbsent(game.appId, game.copy(saleEventName = eventName))
        }
        appIdAttribute.findAll(html).forEach { match ->
            val id = match.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: return@forEach
            if (found.containsKey(id)) return@forEach
            val start = (match.range.first - 500).coerceAtLeast(0)
            val end = (match.range.last + 1800).coerceAtMost(html.length)
            val snippet = html.substring(start, end)
            val nameHtml = htmlTitle.find(snippet)?.groupValues?.get(1)
                ?: Regex("(?is)<img[^>]+alt=[\"']([^\"']+)[\"']").find(snippet)?.groupValues?.get(1)
                ?: return@forEach
            val name = decodeText(nameHtml)
            if (name.isNotBlank()) found[id] = SteamTopSeller(id, name, saleEventName = eventName)
        }
        return found.values.toList()
    }

    private fun decodeText(value: String): String = value
        .replace(Regex("<[^>]*>"), " ")
        .replace("&amp;", "&").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
        .replace(Regex("\\s+"), " ").trim()
}
