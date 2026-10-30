package ru.igrolov.app

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class PriceSearchResult<T>(
    val values: Map<String, T>, val failedSources: Set<String>,
    val finished: Boolean, val cancelled: Boolean = false, val timedOut: Boolean = false,
    val completedSources:Set<String> = values.keys + failedSources
)

/** Independent source checks; a request can never keep its card busy indefinitely. */
class PriceSearchCoordinator<T>(private val timeoutMillis: Long = 45_000L, parallelism: Int = 6) : AutoCloseable {
    private class Task(val priority: Int, val order: Long, work: () -> Unit) : FutureTask<Unit>({ work(); Unit })
    private val sequence = AtomicLong()
    private val workers = ThreadPoolExecutor(parallelism, parallelism, 0L, TimeUnit.MILLISECONDS,
        PriorityBlockingQueue<Runnable>(11, Comparator { left, right ->
            val a = left as Task; val b = right as Task
            val priority = a.priority.compareTo(b.priority)
            if (priority != 0) priority else if (a.priority == 0) b.order.compareTo(a.order) else a.order.compareTo(b.order)
        }))
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val requests = ConcurrentHashMap<String, Request>()
    @Volatile private var closed = false

    private inner class Request(val key: String, val sources: Set<String>, val update: (PriceSearchResult<T>) -> Unit) {
        val values = linkedMapOf<String, T>()
        val failed = linkedSetOf<String>()
        val completed = linkedSetOf<String>()
        val tasks = mutableListOf<Future<*>>()
        var deadline: ScheduledFuture<*>? = null
        @Volatile var finished = false

        @Synchronized fun partial(source:String,value:T) {
            if(finished || closed || source in completed)return
            values[source]=value
            update(PriceSearchResult(values.toMap(),failed.toSet(),false,completedSources=completed.toSet()))
        }

        @Synchronized fun publish(source: String, value: T?, success: Boolean) {
            if (finished || closed) return
            if (success) { @Suppress("UNCHECKED_CAST") values[source] = value as T } else failed.add(source)
            completed.add(source)
            val done = completed.containsAll(sources)
            if (done) finish(false, false) else update(PriceSearchResult(values.toMap(), failed.toSet(), false,completedSources=completed.toSet()))
        }

        @Synchronized fun finish(cancelled: Boolean, timedOut: Boolean, notify: Boolean = true) {
            if (finished) return
            finished = true
            deadline?.cancel(false)
            tasks.forEach { it.cancel(true) }
            workers.purge()
            requests.remove(key, this)
            if (notify && !closed) update(PriceSearchResult(values.toMap(),
                failed + (sources - completed), true, cancelled, timedOut,completed.toSet() + (sources - completed)))
        }
    }

    fun start(key: String, background: Boolean = false,
              sources: Map<String, (active: () -> Boolean) -> T>, update: (PriceSearchResult<T>) -> Unit) {
        startStreaming(key,background,sources.mapValues {(_,load)->
            {active:()->Boolean,_:(T)->Unit->load(active)}
        },update)
    }

    fun startStreaming(key:String,background:Boolean=false,
        sources:Map<String,(active:()->Boolean,publish:(T)->Unit)->T>,update:(PriceSearchResult<T>)->Unit) {
        check(!closed)
        require(sources.isNotEmpty())
        cancel(key)
        val request = Request(key, sources.keys.toSet(), update)
        requests[key] = request
        synchronized(request) {
            request.deadline = timer.schedule({ request.finish(false, true) }, timeoutMillis, TimeUnit.MILLISECONDS)
            sources.forEach { (name, load) ->
                val task = Task(if (background) 1 else 0, sequence.incrementAndGet()) {
                    if (!request.finished && !closed) {
                        val result = runCatching { load(
                            { !request.finished && !closed && !Thread.currentThread().isInterrupted },
                            {value->request.partial(name,value)}) }
                        request.publish(name, result.getOrNull(), result.isSuccess)
                    }
                }
                request.tasks.add(task)
                workers.execute(task)
            }
        }
    }

    fun cancel(key: String) { requests[key]?.finish(true, false) }
    override fun close() {
        closed = true
        requests.values.toList().forEach { it.finish(true, false, false) }
        timer.shutdownNow()
        workers.shutdownNow()
    }
}

