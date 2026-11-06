package ru.igrolov.app

import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** One bounded parallel snapshot. A partial check must not replace a confirmed minimum. */
object MonitorSourceCheck {
    data class Outcome<T>(val values:Map<String,T>,val failed:Set<String>,val error:String)
    fun <T> run(active: () -> Boolean, sources: Map<String, (() -> Boolean) -> T>,
                timeoutMillis: Long = 45_000L): Map<String, T> {
        val result=snapshot(active,sources,timeoutMillis)
        if(result.failed.isNotEmpty())throw IOException(result.error)
        return result.values
    }
    fun <T> snapshot(active: () -> Boolean,sources:Map<String,(() -> Boolean)->T>,
        timeoutMillis:Long=45_000L):Outcome<T> {
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
            return Outcome(result.values,result.failedSources,failures.toSortedMap().values.firstOrNull()?.message
                ?: "Не удалось обновить все цены; предыдущий результат сохранён")
        } finally { coordinator.close() }
    }
}

