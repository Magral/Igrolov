package ru.igrolov.app

import org.junit.Assert.*
import org.junit.Test

class MonitorBackgroundReportTest {
    @Test fun onlySuccessfulFinishUpdatesLastSuccessAndPartialFailureRetainsIt() {
        var report=MonitorBackgroundReport.begin(BackgroundMonitorReport(),"first",1000)
        assertEquals("running",report.status)
        report=MonitorBackgroundReport.finish(report,"first",2000,false)
        assertEquals(2000L,report.lastSuccessAt)
        report=MonitorBackgroundReport.begin(report,"second",3000)
        report=MonitorBackgroundReport.finish(report,"second",4000,true)
        assertEquals("partial",report.status)
        assertEquals(2000L,report.lastSuccessAt)
        assertEquals(4000L,report.finishedAt)
        assertEquals(report,MonitorBackgroundReport.decode(MonitorBackgroundReport.encode(report)))
    }
    @Test fun staleFinishesCannotOverwriteANewerAttemptAndInterruptionIsNotSuccess() {
        var report=MonitorBackgroundReport.begin(BackgroundMonitorReport(),"new",1000)
        assertEquals(report,MonitorBackgroundReport.finish(report,"old",2000,false))
        report=MonitorBackgroundReport.finish(report,"new",3000,false,interrupted=true)
        assertEquals("interrupted",report.status)
        assertEquals(0L,report.lastSuccessAt)
        assertEquals(report,MonitorBackgroundReport.finish(report,"new",4000,false))
    }
    @Test fun abandonedAttemptDoesNotLookLikePermanentRunningCheck() {
        val report=MonitorBackgroundReport.begin(BackgroundMonitorReport(),"one",1000)
        assertEquals("Выполняется фоновая проверка",MonitorBackgroundReport.caption(report,2000))
        assertTrue(MonitorBackgroundReport.caption(report,1000L+11*60_000L).startsWith("Фоновая проверка не завершилась"))
        assertEquals("Фоновая проверка ещё не выполнялась",MonitorBackgroundReport.caption(BackgroundMonitorReport(),2000))
        assertEquals(BackgroundMonitorReport(),MonitorBackgroundReport.decode("invalid"))
        assertEquals(BackgroundMonitorReport(),MonitorBackgroundReport.decode("{\"status\":\"unknown\"}"))
        assertEquals(BackgroundMonitorReport(),MonitorBackgroundReport.decode("{\"status\":\"success\"}"))
    }
}
