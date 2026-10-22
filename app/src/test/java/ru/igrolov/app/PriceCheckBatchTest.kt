package ru.igrolov.app

import org.junit.Assert.*
import org.junit.Test

class PriceCheckBatchTest {
    @Test fun deduplicatesBoundsAndKeepsRequestedOrder() {
        val targets=listOf(PriceCheckTarget(1,"First"),PriceCheckTarget(1,"Duplicate"),PriceCheckTarget(0,"Invalid"))+
            (2..15).map {PriceCheckTarget(it,"Game $it")}
        val batch=PriceCheckBatch.start(targets)
        assertEquals((1..10).toList(),batch.pending.map {it.appId})
        assertEquals(10,batch.total)
    }
    @Test fun completionMustBelongToCurrentItemAndCancellationStopsFurtherItems() {
        val first=PriceCheckTarget(1,"One")
        val second=PriceCheckTarget(2,"Two")
        val initial=PriceCheckBatch.start(listOf(first,second),ManualSearchFilters(type="Key"),3)
        assertEquals(initial,initial.advance(second))
        val next=initial.advance(first)
        assertEquals(second,next.current)
        assertEquals(1,next.completed)
        assertEquals("Key",next.filters.type)
        assertEquals(3,next.pages)
        val stopped=next.cancel()
        assertFalse(stopped.running)
        assertEquals(stopped,stopped.advance(second))
        assertTrue(stopped.pending.isEmpty())
    }
}
