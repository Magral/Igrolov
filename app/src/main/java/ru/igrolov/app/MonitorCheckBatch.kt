package ru.igrolov.app

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** At most two independent games in flight; slow stores cannot serialize the whole watchlist. */
object MonitorCheckBatch {
    fun <T> run(items: List<T>, active: () -> Boolean, progress: (Int, Int, T?) -> Unit,
                check: (T, () -> Boolean) -> Boolean): Boolean {
        if (items.isEmpty() || !active()) return false
        val running = AtomicBoolean(true)
        val next = AtomicInteger()
        val failed = AtomicBoolean()
        val progressLock = Any()
        var completed = 0
        val workers = Executors.newFixedThreadPool(minOf(2, items.size))
        val alive = { running.get() && active() && !Thread.currentThread().isInterrupted }
        try {
            val futures = workers.invokeAll((0 until minOf(2, items.size)).map {
                Callable {
                    while (alive()) {
                        val index = next.getAndIncrement()
                        if (index >= items.size) break
                        val item = items[index]
                        synchronized(progressLock) { if (alive()) progress(completed, items.size, item) }
                        if (!alive()) break
                        if (check(item, alive)) failed.set(true)
                        synchronized(progressLock) {
                            completed++
                            if (alive()) progress(completed, items.size, item)
                        }
                    }
                }
            })
            futures.forEach { it.get() }
            return failed.get()
        } finally {
            running.set(false)
            workers.shutdownNow()
        }
    }
}
