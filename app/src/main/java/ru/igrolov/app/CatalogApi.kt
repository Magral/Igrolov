package ru.igrolov.app

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Retry policy shared by search and product detail requests to respect temporary Digiseller throttling. */
object CatalogRetryPolicy {
    fun delayMillis(statusCode: Int, retryAfter: String?, attempt: Int): Long {
        val index = attempt.coerceAtLeast(0)
        if (statusCode == 429) {
            val serverDelay = retryAfter?.trim()?.toLongOrNull()?.times(1000L)
            if (serverDelay != null && serverDelay > 0) return serverDelay.coerceIn(1_000L, 10_000L)
            return 1_200L * (index + 1)
        }
        if (statusCode in 500..599) return 500L * (index + 1)
        return 400L * (index + 1)
    }

    fun isMissingProduct(statusCode: Int): Boolean = statusCode == 404 || statusCode == 410

    fun isSourceTransportFailure(path: String, requestFailed: Boolean): Boolean =
        requestFailed && !path.startsWith("/api/categories")
}

/** Read-only catalog calls. A missing product is not a catalog transport failure. */
object CatalogApi {
    fun get(path: String, strict: Boolean = false): JSONObject? {
        var failure: Exception? = null
        repeat(3) { attempt ->
            if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
            var connection: HttpURLConnection? = null
            var statusCode = 0
            var retryAfter: String? = null
            var nonRetryableHttpError = false
            try {
                connection = URL("https://api.digiseller.com$path").openConnection() as HttpURLConnection
                connection.connectTimeout = 3000
                connection.readTimeout = 5000
                connection.setRequestProperty("Accept", "application/json")
                statusCode = connection.responseCode
                if (statusCode in 200..299) {
                    return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                }
                if (CatalogRetryPolicy.isMissingProduct(statusCode)) return null
                retryAfter = connection.getHeaderField("Retry-After")
                failure = IOException("HTTP $statusCode")
                nonRetryableHttpError = statusCode != 429 && statusCode !in 500..599
            } catch (e: Exception) {
                failure = e
            } finally {
                connection?.disconnect()
            }
            if (nonRetryableHttpError) {
                if (strict) throw failure ?: IOException("Ошибка каталога")
                return null
            }
            if (attempt < 2 && !Thread.currentThread().isInterrupted) {
                Thread.sleep(CatalogRetryPolicy.delayMillis(statusCode, retryAfter, attempt))
            }
        }
        if (strict) throw failure ?: IOException("Нет ответа каталога")
        return null
    }
}
