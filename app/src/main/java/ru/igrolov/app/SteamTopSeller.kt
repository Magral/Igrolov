package ru.igrolov.app

import org.json.JSONObject

enum class SteamRuAvailability { AVAILABLE, UNAVAILABLE, UNKNOWN }

enum class SteamLeaderSection(val label: String) {
    TOP_SELLERS("Топ продаж"),
    POPULAR_NEW_RELEASES("Популярные новинки"),
    DISCOUNTED_TOP_SELLERS("Топ со скидками");

    fun filter(games: List<SteamTopSeller>): List<SteamTopSeller> =
        when (this) {
            DISCOUNTED_TOP_SELLERS -> games.filter { !it.isFree && it.discountPercent > 0 }
            else -> games.filter { !it.isFree }
        }
}

data class SteamTopSeller(
    val appId: Int,
    val title: String,
    val steamPrice: String = "",
    val steamInitialPrice: String = "",
    val discountPercent: Int = 0,
    val availability: SteamRuAvailability = SteamRuAvailability.UNKNOWN,
    val isGame: Boolean = true,
    val isFree: Boolean = false,
    val headerImageUrl: String = "",
    val description: String = ""
)

data class SteamRankingPage(
    val games: List<SteamTopSeller>,
    val nextStart: Int,
    val totalCount: Int
) {
    val hasMore: Boolean get() = nextStart < totalCount
}

data class LeaderOfferPrices(
    val checking: Boolean = false,
    val platiMinimum: Double? = null,
    val ggselMinimum: Double? = null,
    val checkedAt: Long? = null,
    val message: String = ""
)

object LeaderOfferSelector {
    fun minimumConfirmedByMarketplace(
        offers: List<Offer>,
        filters: ManualSearchFilters,
        rules: JSONObject,
        ignored: Set<String>
    ): Map<String, Double> {
        val strictFilters = filters.copy(type = "Все", edition = "Игра", confirmedRf = true)
        return offers.asSequence()
            .filter { it.type == "Gift" || it.type == "Key" }
            .filter { ManualSearchFilter.rejection(it, strictFilters, rules, ignored) == null }
            .filter { it.available == true && it.priceVerified && it.price > 0.0 }
            .groupBy { it.marketplace }
            .mapValues { (_, matches) -> matches.minOf { it.price } }
    }
}

/** Parsing Steam Store search rows and regional RU evidence. */
object SteamTopSellerParser {
    private val row = Regex("(?is)<a\\b[^>]*class=[\"'][^\"']*search_result_row[^\"']*[\"'][^>]*>.*?</a>")
    private val appId = Regex("(?i)data-ds-appid=[\"']([0-9]{1,10})[\"']")
    private val title = Regex("(?is)<span\\b[^>]*class=[\"'][^\"']*title[^\"']*[\"'][^>]*>(.*?)</span>")

    fun parseRankingHtml(html: String): List<SteamTopSeller> {
        val result = linkedMapOf<Int, SteamTopSeller>()
        row.findAll(html).forEach { match ->
            val markup = match.value
            val id = appId.find(markup)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 } ?: return@forEach
            val nameHtml = title.find(markup)?.groupValues?.get(1) ?: return@forEach
            val name = nameHtml.replace(Regex("<[^>]*>"), " ").replace("&amp;", "&")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
                .replace(Regex("\\s+"), " ").trim()
            if (name.isNotBlank()) result.putIfAbsent(id, SteamTopSeller(id, name))
        }
        return result.values.toList()
    }

    /** Parses one Steam Store search page and the total needed for on-demand pagination. */
    fun parseSearchPage(json: String, requestedStart: Int): SteamRankingPage {
        val root = runCatching { JSONObject(json) }.getOrNull()
            ?: return SteamRankingPage(emptyList(), requestedStart, requestedStart)
        val games = parseRankingHtml(root.optString("results_html", ""))
        val nextStart = requestedStart + games.size
        val totalCount = root.optInt("total_count", nextStart).coerceAtLeast(nextStart)
        return SteamRankingPage(games, nextStart, totalCount)
    }

    /** Steam's featured "new releases" shelf, localized to the requested market. */
    fun parseFeaturedCategory(json: String, categoryKey: String = "new_releases"): List<SteamTopSeller> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val category = root.opt(categoryKey)
        val items = when (category) {
            is JSONObject -> category.optJSONArray("items")
            is org.json.JSONArray -> category
            else -> null
        } ?: return emptyList()
        val result = linkedMapOf<Int, SteamTopSeller>()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val id = item.optInt("id", item.optInt("appid", 0)).takeIf { it > 0 } ?: continue
            val name = item.optString("name").trim().takeIf { it.isNotBlank() } ?: continue
            result.putIfAbsent(id, SteamTopSeller(id, name))
        }
        return result.values.toList()
    }

    /** Fills artwork and description from the global store without changing RU evidence. */
    fun applyGlobalMetadata(game: SteamTopSeller, json: String): SteamTopSeller {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return game
        val entry = root.optJSONObject(game.appId.toString()) ?: return game
        if (!entry.optBoolean("success")) return game
        val data = entry.optJSONObject("data") ?: return game
        if (data.optString("type") != "game") return game
        return game.copy(
            title = game.title.ifBlank { data.optString("name").trim() },
            headerImageUrl = game.headerImageUrl.ifBlank { data.optString("header_image").trim() },
            description = game.description.ifBlank {
                data.optString("short_description").ifBlank { data.optString("about_the_game") }.trim()
            }
        )
    }

    /** Store search visibility does not prove RU purchase availability; only positive regional price/free evidence does. */
    fun applyRuDetails(game: SteamTopSeller, json: String, ruSearchContainsApp: Boolean?): SteamTopSeller {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return game
        val entry = root.optJSONObject(game.appId.toString()) ?: return game
        if (!entry.has("success")) return game
        if (!entry.optBoolean("success")) {
            val status = when (ruSearchContainsApp) {
                // Regional search can still list products that Steam says cannot be purchased in this region.
                true, null -> SteamRuAvailability.UNKNOWN
                false -> SteamRuAvailability.UNAVAILABLE
            }
            return game.copy(availability = status)
        }
        val data = entry.optJSONObject("data") ?: return game
        if (data.optString("type") != "game") return game.copy(availability = SteamRuAvailability.UNKNOWN, isGame = false)
        val price = data.optJSONObject("price_overview")
        val finalFormatted = price?.optString("final_formatted", "")?.trim().orEmpty()
        val isFree = data.optBoolean("is_free", false)
        val available = isFree || finalFormatted.isNotBlank()
        return game.copy(
            title = data.optString("name").trim().ifBlank { game.title },
            steamPrice = if (isFree) "Бесплатно" else if (available) finalFormatted else "",
            steamInitialPrice = if (available) price?.optString("initial_formatted", "").orEmpty() else "",
            discountPercent = if (available && !isFree) price?.optInt("discount_percent", 0)?.coerceIn(0, 100) ?: 0 else 0,
            availability = if (available) SteamRuAvailability.AVAILABLE else SteamRuAvailability.UNKNOWN,
            isFree = isFree,
            headerImageUrl = data.optString("header_image").trim(),
            description = data.optString("short_description").ifBlank { data.optString("about_the_game") }.trim()
        )
    }
}
