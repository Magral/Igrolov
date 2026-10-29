package ru.igrolov.app

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MonitorSourceCheckTest {
    @Test fun startsBothSourcesBeforeEitherFinishes() {
        val started=CountDownLatch(2)
        val sources=listOf("Plati","GGSEL").associateWith { name ->
            { _: () -> Boolean ->
                started.countDown()
                check(started.await(2,TimeUnit.SECONDS))
                name
            }
        }
        assertEquals(mapOf("Plati" to "Plati","GGSEL" to "GGSEL"),
            MonitorSourceCheck.run({true},sources,3000))
    }

    @Test fun timeoutRejectsPartialSnapshotAndStopsSlowSource() {
        val stopped=CountDownLatch(1)
        try {
            MonitorSourceCheck.run({true},mapOf(
                "Plati" to { _: () -> Boolean -> 100 },
                "GGSEL" to { active: () -> Boolean ->
                    try { while(active()) Thread.sleep(10); 50 }
                    finally { stopped.countDown() }
                }
            ),150)
            fail("Partial minimum must not replace a confirmed price")
        } catch (_:IOException) { }
        assertTrue(stopped.await(2,TimeUnit.SECONDS))
    }

    @Test fun sourceFailureRejectsWholeSnapshot() {
        try {
            MonitorSourceCheck.run({true},mapOf(
                "Plati" to { _: () -> Boolean -> 100 },
                "GGSEL" to { _: () -> Boolean -> throw IOException("offline") }
            ),1000)
            fail("Failed source cannot be treated as an empty successful catalog")
        } catch (_:IOException) { }
    }

    @Test fun cancellationStopsBothSources() {
        val active=AtomicBoolean(true)
        val started=CountDownLatch(2)
        val stopped=CountDownLatch(2)
        val cancel=Thread {
            if(started.await(2,TimeUnit.SECONDS)) active.set(false)
        }
        cancel.start()
        try {
            MonitorSourceCheck.run({active.get()},listOf("Plati","GGSEL").associateWith {
                { running: () -> Boolean ->
                    started.countDown()
                    try { while(running()) Thread.sleep(10); 100 }
                    finally { stopped.countDown() }
                }
            },3000)
            fail("Cancelled check cannot update saved prices")
        } catch (_:CancellationException) { }
        finally { cancel.join(2500) }
        assertTrue(stopped.await(2,TimeUnit.SECONDS))
    }
}
