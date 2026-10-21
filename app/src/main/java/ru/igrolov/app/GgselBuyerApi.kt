package ru.igrolov.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/** Public buyer-catalog access shared by manual search and scheduled price checks. */
object GgselBuyerApi {
    private const val CATEGORY_QUERY = "elastic/goods/query-categories"
    private const val CATEGORY_GOODS = "elastic/goods/categories"

    /** The injected request makes category selection and provider failures testable without live GGSEL calls. */
    fun find(
        game: ManualGameReference.Game,
        active: () -> Boolean = { true },
        post: (String, JSONObject) -> JSONObject = ::postJson
    ): List<Offer> {
        if (game.appId <= 0) throw IOException("Steam не подтвердил основную игру для поиска GGSEL")
        if (!active()) return emptyList()
        val categories = post(CATEGORY_QUERY, JSONObject()
            .put("search_term", game.canonical)
            .put("lang", "ru")
            .put("limit", 10))
        if (!active()) return emptyList()
        val category = GgselCatalog.chooseCategory(
            GgselCatalog.categories(categories), listOf(game.canonical) + game.aliases
        ) ?: return emptyList()
        val products = post(CATEGORY_GOODS, JSONObject()
            .put("digi_catalog", category.id)
            .put("url", category.url)
            .put("limit", 100)
            .put("content_type_ids", JSONArray())
            .put("search_after", JSONArray())
            .put("with_filters", false)
            .put("is_preorders", false)
            .put("ab_test_without_emoji", true)
            .put("sort", "sortByRec")
            .put("query_string", "")
            .put("with_forbidden", false)
            .put("min_price", "")
            .put("max_price", "")
            .put("currency", "wmr")
            .put("lang", "ru")
            .put("platforms", JSONArray().put("Steam"))
            .put("page", 1))
        return if (active()) GgselCatalog.offers(products, game) else emptyList()
    }

    private fun postJson(path: String, body: JSONObject): JSONObject {
        var last: Exception? = null
        for (attempt in 0..2) {
            try {
                val connection = URL("https://api.ggsel.com/v2/$path").openConnection() as HttpURLConnection
                connection.connectTimeout = 12000
                connection.readTimeout = 18000
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("User-Agent", "Igrolov Android")
                try {
                    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    val status = connection.responseCode
                    if (status !in 200..299) {
                        if (status == 429 || status in 500..599) throw IOException("GGSEL HTTP $status")
                        throw IOException("GGSEL HTTP $status")
                    }
                    return JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                val retryable = e is UnknownHostException || e is SocketTimeoutException || e is ConnectException ||
                    (e is IOException && (e.message == "GGSEL HTTP 429" || e.message?.startsWith("GGSEL HTTP 5") == true))
                if (!retryable || attempt == 2) throw e
                last = e
                Thread.sleep(300L * (attempt + 1))
            }
        }
        throw last ?: IOException("GGSEL не ответил")
    }
}
