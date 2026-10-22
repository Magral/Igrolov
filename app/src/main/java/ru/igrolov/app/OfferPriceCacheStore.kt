package ru.igrolov.app

import android.content.Context

object OfferPriceCacheStore {
    private val lock = Any()
    private fun prefs(context: Context) = context.getSharedPreferences("confirmed_offer_price_cache_v1", Context.MODE_PRIVATE)
    fun readAll(context: Context): Map<String, CachedGamePrices> = synchronized(lock) {
        prefs(context).all.mapNotNull { (key, value) ->
            if (!key.matches(Regex("[a-f0-9]{64}"))) null
            else OfferPriceCache.decode(value as? String)?.let { key to it }
        }.toMap()
    }
    fun update(context: Context, key: String, minima: Map<String, Double>, failed: Set<String>,
               startedAt: Long, finishedAt: Long, reason: String): CachedGamePrices = synchronized(lock) {
        val preferences = prefs(context)
        val merged = OfferPriceCache.merge(OfferPriceCache.decode(preferences.all[key] as? String),
            minima, failed, startedAt, finishedAt, reason)
        val entries = readAll(context) + (key to merged)
        val keep = entries.entries.sortedByDescending { it.value.updatedAt }.take(200).map { it.key }.toSet()
        val edit = preferences.edit().putString(key, OfferPriceCache.encode(merged))
        entries.keys.filterNot { it in keep }.forEach { edit.remove(it) }
        edit.apply()
        merged
    }
}
