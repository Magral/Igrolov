package ru.igrolov.app

import org.json.JSONObject
import java.util.Locale

/** Presentation-only filtering and ordering for the local price-monitor list. */
object WatchListPresentation {
    data class Entry(val originalIndex: Int, val game: JSONObject)

    enum class Filter { ALL, BELOW_TARGET, ERRORS }
    enum class Sort { NAME, LOWEST_PRICE, ATTENTION }

    fun arrange(entries: List<Entry>, filter: Filter, sort: Sort): List<Entry> {
        val visible = entries.filter { entry ->
            when (filter) {
                Filter.ALL -> true
                Filter.BELOW_TARGET -> {
                    val price = entry.game.optDouble("last", -1.0)
                    price > 0.0 && price <= entry.game.optDouble("limit", Double.NEGATIVE_INFINITY)
                }
                Filter.ERRORS -> hasError(entry.game)
            }
        }
        return when (sort) {
            Sort.NAME -> visible.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.game.optString("name") })
            Sort.LOWEST_PRICE -> visible.sortedWith(compareBy<Entry> {
                val price = it.game.optDouble("last", -1.0)
                if (price > 0.0) price else Double.POSITIVE_INFINITY
            }.thenBy { it.game.optString("name").lowercase(Locale.ROOT) })
            Sort.ATTENTION -> visible.sortedWith(compareBy<Entry> { attentionRank(it.game) }
                .thenBy { it.game.optString("name").lowercase(Locale.ROOT) })
        }
    }

    fun hasError(game: JSONObject): Boolean = game.has("error") ||
        game.optLong("failedAt", 0L) > game.optLong("checked", 0L)

    private fun attentionRank(game: JSONObject): Int = when {
        hasError(game) -> 0
        game.optDouble("last", -1.0) > 0.0 && game.optDouble("last") <= game.optDouble("limit", Double.NEGATIVE_INFINITY) -> 1
        game.optDouble("last", -1.0) <= 0.0 -> 2
        else -> 3
    }
}
