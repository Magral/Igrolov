package ru.igrolov.app

import org.junit.Assert.*
import org.junit.Test

class PriceCheckBatchTest {
    @Test fun deduplicatesBoundsAndKeepsRequestedOrder() {
        val requested=listOf(PriceCheckTarget(1,"First"),PriceCheckTarget(1,"Duplicate"),PriceCheckTarget(0,"Invalid"))+
            (2..15).map {PriceCheckTarget(it,"Game $it")}
        val batch=PriceCheckBatch.start(requested)
        assertEquals((1..10).toList(),batch.pending.map {it.appId})
        assertEquals(10,batch.total)
    }
    @Test fun unclaimedCompletionIsIgnoredAndCancellationStopsFurtherItems() {
        val first=PriceCheckTarget(1,"One")
        val second=PriceCheckTarget(2,"Two")
        val initial=PriceCheckBatch.start(listOf(first,second),ManualSearchFilters(type="Key"),3)
        assertEquals(initial,initial.advance(second))
        val next=initial.claim().advance(first)
        assertEquals(second,next.current)
        assertEquals(1,next.completed)
        assertEquals("Key",next.filters.type)
        assertEquals(3,next.pages)
        val stopped=next.cancel()
        assertFalse(stopped.running)
        assertEquals(stopped,stopped.advance(second))
        assertTrue(stopped.pending.isEmpty())
    }
    private val targets=(1..12).map {PriceCheckTarget(it,"Game $it","profile")}

    @Test fun twoSlotsAdvanceIndependentlyWithoutRestartingSlowGame() {
        var batch=PriceCheckBatch.start(targets)
        assertEquals(10,batch.total)
        assertEquals(targets.take(2),batch.ready)
        batch=batch.claim()
        assertEquals(targets.take(2),batch.active)
        assertTrue(batch.ready.isEmpty())
        batch=batch.advance(targets[1])
        assertEquals(listOf(targets[0]),batch.active)
        assertEquals(listOf(targets[2]),batch.ready)
        assertEquals(1,batch.completed)
        batch=batch.claim()
        assertEquals(listOf(targets[0],targets[2]),batch.active)
        assertEquals(batch,batch.advance(targets[1]))
        assertEquals(batch,batch.advance(targets[9]))
        while(batch.running) {
            batch=batch.claim()
            assertTrue(batch.active.size<=2)
            batch=batch.advance(batch.active.last())
        }
        assertEquals(10,batch.completed)
        assertTrue(batch.active.isEmpty())
        assertTrue(batch.ready.isEmpty())
    }

    @Test fun cancellationClearsBothSlotsAndLateCompletionCannotAdvance() {
        val started=PriceCheckBatch.start(targets).claim()
        val cancelled=started.cancel()
        assertTrue(cancelled.cancelled)
        assertFalse(cancelled.running)
        assertTrue(cancelled.active.isEmpty())
        assertTrue(cancelled.ready.isEmpty())
        assertEquals(cancelled,cancelled.advance(targets[0]))
        assertNotEquals(started.runId,PriceCheckBatch.start(targets).runId)
    }

    @Test fun selectionKeepsFiltersPageLimitAndDistinctProfileIdentities() {
        val filters=ManualSearchFilters(type="Key",maxPrice="2000",confirmedRf=true)
        val selected=listOf(targets[0],targets[0],targets[0].copy(profileId="other"),PriceCheckTarget(0,"Invalid"),PriceCheckTarget(2,""))
        val batch=PriceCheckBatch.start(selected,filters,3)
        assertEquals(2,batch.total)
        assertEquals(filters,batch.filters)
        assertEquals(3,batch.pages)
    }
}
