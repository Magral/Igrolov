package ru.igrolov.app

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.Collections

class PriceSearchCoordinatorTest {
    private fun CountDownLatch.ready() = assertTrue("Timed out waiting for search event", await(3, TimeUnit.SECONDS))

    @Test fun slowMarketplaceDoesNotBlockPartialPricesOrAnotherGame() {
        val coordinator = PriceSearchCoordinator<Int>(5000)
        val release = CountDownLatch(1)
        val partial = CountDownLatch(1)
        val otherGame = CountDownLatch(1)
        val finished = CountDownLatch(1)
        try {
            coordinator.start("game:1", sources = mapOf(
                "Plati" to { _: () -> Boolean -> release.await(); 500 },
                "GGSEL" to { _: () -> Boolean -> 400 }
            )) { event ->
                if (!event.finished && event.values["GGSEL"] == 400) partial.countDown()
                if (event.finished) finished.countDown()
            }
            partial.ready()
            coordinator.start("game:2", sources = mapOf("Plati" to { _: () -> Boolean -> 300 })) {
                if (it.finished && it.values["Plati"] == 300) otherGame.countDown()
            }
            otherGame.ready()
            release.countDown()
            finished.ready()
        } finally { release.countDown(); coordinator.close() }
    }

    @Test fun deadlineKeepsCompletedPriceAndTerminatesOnce() {
        val coordinator = PriceSearchCoordinator<Int>(500)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<PriceSearchResult<Int>>())
        try {
            coordinator.start("game", sources = mapOf(
                "Plati" to { _: () -> Boolean -> try { release.await() } catch (_: InterruptedException) {} ; 999 },
                "GGSEL" to { _: () -> Boolean -> 450 }
            )) { event -> events.add(event); if (event.finished) done.countDown() }
            done.ready()
            val terminal = events.single { it.finished }
            assertTrue(terminal.timedOut)
            assertEquals(450, terminal.values["GGSEL"])
            assertEquals(setOf("Plati"), terminal.failedSources)
            release.countDown()
            assertEquals(1, events.count { it.finished })
        } finally { release.countDown(); coordinator.close() }
    }

    @Test fun cancellationIsPerGameAndAllowsRetry() {
        val coordinator = PriceSearchCoordinator<Int>(5000)
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val retried = CountDownLatch(1)
        try {
            coordinator.start("game", sources = mapOf("Plati" to { active: () -> Boolean ->
                entered.countDown()
                while (active()) Thread.sleep(10)
                100
            })) { if (it.cancelled) cancelled.countDown() }
            entered.ready()
            coordinator.cancel("game")
            cancelled.ready()
            coordinator.start("game", sources = mapOf("Plati" to { _: () -> Boolean -> 200 })) {
                if (it.finished && it.values["Plati"] == 200) retried.countDown()
            }
            retried.ready()
        } finally { coordinator.close() }
    }

    @Test fun aFailedMarketplaceDoesNotDiscardSuccessfulMarketplace() {
        val coordinator = PriceSearchCoordinator<Int>()
        val done = CountDownLatch(1)
        val terminal = AtomicReference<PriceSearchResult<Int>>()
        try {
            coordinator.start("game", sources = mapOf(
                "Plati" to { _: () -> Boolean -> error("offline") },
                "GGSEL" to { _: () -> Boolean -> 350 }
            )) { if (it.finished) { terminal.set(it); done.countDown() } }
            done.ready()
            assertEquals(mapOf("GGSEL" to 350), terminal.get().values)
            assertEquals(setOf("Plati"), terminal.get().failedSources)
        } finally { coordinator.close() }
    }

    @Test fun interactiveCheckRunsBeforeQueuedBatchCheck() {
        val coordinator = PriceSearchCoordinator<Int>(5000, parallelism = 1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(2)
        val order = Collections.synchronizedList(mutableListOf<String>())
        try {
            coordinator.start("occupy", sources = mapOf("one" to { _: () -> Boolean -> entered.countDown(); release.await(); 0 })) {}
            entered.ready()
            coordinator.start("batch", background = true, sources = mapOf("one" to { _: () -> Boolean -> order.add("batch"); 1 })) {
                if (it.finished) done.countDown()
            }
            coordinator.start("interactive", sources = mapOf("one" to { _: () -> Boolean -> order.add("interactive"); 2 })) {
                if (it.finished) done.countDown()
            }
            release.countDown()
            done.ready()
            assertEquals(listOf("interactive", "batch"), order)
        } finally { release.countDown(); coordinator.close() }
    }
}
