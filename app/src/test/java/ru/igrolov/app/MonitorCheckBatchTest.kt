package ru.igrolov.app

import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class MonitorCheckBatchTest {
    @Test fun secondAndThirdGamesFinishWhileFirstIsSlowWithOnlyTwoChecksInFlight() {
        val release=CountDownLatch(1)
        val firstStarted=CountDownLatch(1)
        val otherGamesDone=CountDownLatch(2)
        val active=AtomicInteger()
        val maximum=AtomicInteger()
        val progress=Collections.synchronizedList(mutableListOf<Int>())
        val executor=Executors.newSingleThreadExecutor()
        try {
            val result=executor.submit<Boolean> {
                MonitorCheckBatch.run(listOf(1,2,3),{true},{done,_,_->progress.add(done)}) {game,_->
                    val count=active.incrementAndGet()
                    maximum.updateAndGet {maxOf(it,count)}
                    try {
                        if(game==1) {firstStarted.countDown();check(release.await(3,TimeUnit.SECONDS))}
                        else {check(firstStarted.await(2,TimeUnit.SECONDS));otherGamesDone.countDown()}
                        game==2
                    } finally {active.decrementAndGet()}
                }
            }
            assertTrue(otherGamesDone.await(2,TimeUnit.SECONDS))
            assertFalse("Slow first game must still be in flight",result.isDone)
            release.countDown()
            assertTrue("One failed game must be reported without dropping other results",result.get(3,TimeUnit.SECONDS))
            assertEquals(2,maximum.get())
            assertEquals(3,progress.last())
            assertTrue(progress.zipWithNext().all {(a,b)->a<=b})
        } finally {release.countDown();executor.shutdownNow()}
    }

    @Test fun cancellationStopsActiveGamesAndDoesNotStartQueuedGames() {
        val active=AtomicBoolean(true)
        val started=CountDownLatch(2)
        val calls=Collections.synchronizedList(mutableListOf<Int>())
        val executor=Executors.newSingleThreadExecutor()
        try {
            val result=executor.submit<Boolean> {
                MonitorCheckBatch.run(listOf(1,2,3,4),{active.get()},{_,_,_->}) {game,alive->
                    calls.add(game);started.countDown()
                    while(alive())Thread.sleep(5)
                    false
                }
            }
            assertTrue(started.await(2,TimeUnit.SECONDS))
            active.set(false)
            assertFalse(result.get(2,TimeUnit.SECONDS))
            assertEquals(setOf(1,2),calls.toSet())
        } finally {active.set(false);executor.shutdownNow()}
    }

    @Test fun emptyAndAlreadyCancelledBatchesDoNotPerformChecks() {
        assertFalse(MonitorCheckBatch.run(emptyList<Int>(),{true},{_,_,_->}) {_,_->error("unexpected check")})
        assertFalse(MonitorCheckBatch.run(listOf(1),{false},{_,_,_->}) {_,_->error("unexpected check")})
    }
}
