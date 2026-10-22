package ru.igrolov.app

import org.json.JSONObject

enum class SteamRuAvailability { AVAILABLE, UNAVAILABLE, UNKNOWN }

data class SteamTopSeller(
    val appId: Int,
    val title: String,
    val steamPrice: String = "",
    val steamInitialPrice: String = "",
    val discountPercent: Int = 0,
    val availability: SteamRuAvailability = SteamRuAvailability.UNKNOWN,
    val isGame: Boolean = true
)

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

/** Parsing and RU-region evidence for Steam's paid top-seller rows. */
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
        val isFree = data.optBoolean("is_free", false)
        val available = isFree || price != null
        return game.copy(
            title = data.optString("name").trim().ifBlank { game.title },
            steamPrice = if (isFree) "Бесплатно" else price?.optString("final_formatted", "").orEmpty(),
            steamInitialPrice = price?.optString("initial_formatted", "").orEmpty(),
            discountPercent = price?.optInt("discount_percent", 0)?.coerceIn(0, 100) ?: 0,
            availability = if (available) SteamRuAvailability.AVAILABLE else SteamRuAvailability.UNKNOWN
        )
    }
}
