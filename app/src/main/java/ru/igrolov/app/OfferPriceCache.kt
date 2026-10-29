package ru.igrolov.app

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

data class SourcePriceCheck(
    val price: Double? = null, val checkedAt: Long = 0L,
    val failedAt: Long = 0L, val error: String = "", val requestStartedAt: Long = 0L
) {
    val hasData: Boolean get() = price != null || checkedAt > 0 || failedAt > 0
}
data class CachedGamePrices(
    val plati: SourcePriceCheck = SourcePriceCheck(),
    val ggsel: SourcePriceCheck = SourcePriceCheck()
) {
    val updatedAt: Long get() = maxOf(maxOf(plati.checkedAt, plati.failedAt), maxOf(ggsel.checkedAt, ggsel.failedAt))
}

object OfferPriceCache {
    fun leaderFilters(filters: ManualSearchFilters) = filters.copy(type = "Все", edition = "Игра", confirmedRf = true)

    fun key(appId: Int, filters: ManualSearchFilters, rules: JSONObject, ignored: Set<String>,
            pages: Int, aliases: List<String>): String {
        require(appId > 0)
        val config = JSONObject().put("schema", 1).put("appid", appId).put("type", filters.type)
            .put("edition", filters.edition).put("rf", filters.confirmedRf)
            .put("max", filters.amount?.toString().orEmpty()).put("pages", pages)
            .put("rules", canonical(rules)).put("ignored", JSONArray(ignored.sorted()))
            .put("aliases", JSONArray(aliases.map { it.trim().lowercase(java.util.Locale.ROOT) }.filter { it.isNotBlank() }.distinct().sorted()))
        return MessageDigest.getInstance("SHA-256").digest(canonical(config).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun canonical(value: Any?): String = when(value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.opt(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.opt(it)) }
        null, JSONObject.NULL -> "null"
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }

    fun minima(offers: List<Offer>, filters: ManualSearchFilters, rules: JSONObject, ignored: Set<String>): Map<String, Double> =
        offers.asSequence().filter { it.type == "Gift" || it.type == "Key" }.filter { ManualSearchFilter.rejection(it, filters, rules, ignored) == null }
            .filter { it.available == true && it.priceVerified && it.price.isFinite() && it.price > 0 }
            .groupBy { it.marketplace }.mapValues { (_, matches) -> matches.minOf { it.price } }

    /** A failed source keeps its old price AND its old successful-check time. */
    fun merge(previous: CachedGamePrices?, minima: Map<String, Double>, failed: Set<String>,
              startedAt: Long, finishedAt: Long, reason: String, sources: Set<String> = setOf("Plati", "GGSEL")): CachedGamePrices {
        val old = previous ?: CachedGamePrices()
        fun source(name: String, before: SourcePriceCheck): SourcePriceCheck {
            if (name !in sources || startedAt < before.requestStartedAt) return before
            if (name in failed) return before.copy(failedAt = finishedAt, error = reason.take(240), requestStartedAt = startedAt)
            return SourcePriceCheck(minima[name]?.takeIf { it.isFinite() && it > 0 }, finishedAt, requestStartedAt = startedAt)
        }
        return CachedGamePrices(source("Plati", old.plati), source("GGSEL", old.ggsel))
    }

    fun encode(value: CachedGamePrices): String {
        fun source(s: SourcePriceCheck) = JSONObject().put("price", s.price ?: JSONObject.NULL)
            .put("checked", s.checkedAt).put("failed", s.failedAt).put("error", s.error).put("started", s.requestStartedAt)
        return JSONObject().put("schema", 1).put("Plati", source(value.plati)).put("GGSEL", source(value.ggsel)).toString()
    }
    fun decode(raw: String?): CachedGamePrices? = runCatching {
        val root = JSONObject(raw ?: return null)
        if (root.optInt("schema") != 1) return null
        fun source(name: String): SourcePriceCheck {
            val s = root.optJSONObject(name) ?: return SourcePriceCheck()
            val price = s.optDouble("price", Double.NaN).takeIf { it.isFinite() && it > 0 }
            return SourcePriceCheck(price, s.optLong("checked").coerceAtLeast(0), s.optLong("failed").coerceAtLeast(0),
                s.optString("error").take(240), s.optLong("started").coerceAtLeast(0))
        }
        CachedGamePrices(source("Plati"), source("GGSEL")).takeIf { it.plati.hasData || it.ggsel.hasData }
    }.getOrNull()
}

