package ru.igrolov.app

import org.junit.Assert.*
import org.junit.Test

class MonitorRunStateTest {
    @Test fun queuedManualAndScheduledPeriodicDoNotAnimate() {
        val state=MonitorRunState.reduce(listOf(MonitorRunState.Job("ENQUEUED")), listOf(MonitorRunState.Job("ENQUEUED")))
        assertNull(state.active); assertTrue(state.waiting); assertFalse(state.finished)
        assertFalse(MonitorRunState.reduce(emptyList(),listOf(MonitorRunState.Job("ENQUEUED"))).waiting)
    }
    @Test fun savedLastGameStopsAnimationBeforeWorkManagerTerminalEvent() {
        val state=MonitorRunState.reduce(listOf(MonitorRunState.Job("RUNNING",3,3)),emptyList())
        assertNull(state.active); assertFalse(state.waiting); assertTrue(state.finished)
        assertNull(MonitorRunState.reduce(listOf(MonitorRunState.Job("RUNNING",complete=true)),emptyList()).active)
    }
    @Test fun completedManualDoesNotHideAnActualPeriodicCheck() {
        val periodic=MonitorRunState.Job("RUNNING",1,3,game="Stellaris")
        val state=MonitorRunState.reduce(listOf(MonitorRunState.Job("SUCCEEDED",3,3)),listOf(periodic))
        assertEquals(periodic,state.active); assertFalse(state.finished)
    }
    @Test fun completionCancellationAndRepeatedStartClearOldProgress() {
        assertNull(MonitorRunState.reduce(listOf(MonitorRunState.Job("CANCELLED",1,3)),emptyList()).active)
        assertNull(MonitorRunState.reduce(listOf(MonitorRunState.Job("FAILED",1,3)),emptyList()).active)
        val fresh=MonitorRunState.Job("RUNNING",0,3)
        assertEquals(fresh,MonitorRunState.reduce(listOf(MonitorRunState.Job("SUCCEEDED",3,3),fresh),emptyList()).active)
    }
}
