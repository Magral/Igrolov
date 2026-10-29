package ru.igrolov.app

import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** One bounded parallel snapshot. A partial check must not replace a confirmed minimum. */
object MonitorSourceCheck {
    fun <T> run(active: () -> Boolean, sources: Map<String, (() -> Boolean) -> T>,
                timeoutMillis: Long = 45_000L): Map<String, T> {
        if (!active()) throw CancellationException()
        val done = CountDownLatch(1)
        var snapshot: PriceSearchResult<T>? = null
        val failures = ConcurrentHashMap<String, Exception>()
        val coordinator = PriceSearchCoordinator<T>(timeoutMillis, sources.size.coerceAtLeast(1))
        try {
            coordinator.start("monitor", background = true, sources = sources.mapValues { (source, load) ->
                { running: () -> Boolean ->
                    try { load { running() && active() } }
                    catch (failure: Exception) { failures[source] = failure; throw failure }
                }
            }) { result ->
                if (result.finished) { snapshot = result; done.countDown() }
            }
            while (!done.await(100, TimeUnit.MILLISECONDS)) {
                if (!active()) throw CancellationException()
            }
            if (!active()) throw CancellationException()
            val result = snapshot ?: throw IOException("Проверка не завершена")
            if (result.failedSources.isNotEmpty() || result.cancelled || result.timedOut)
                throw IOException(failures.toSortedMap().values.firstOrNull()?.message
                    ?: "Не удалось обновить все цены; предыдущий результат сохранён")
            return result.values
        } finally { coordinator.close() }
    }
}
