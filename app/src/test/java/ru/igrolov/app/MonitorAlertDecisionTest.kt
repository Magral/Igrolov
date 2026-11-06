package ru.igrolov.app

import org.junit.Assert.*
import org.junit.Test

class MonitorAlertDecisionTest {
    @Test fun targetAndPercentageDropUseSeparateTriggers() {
        assertEquals("target",MonitorAlertDecision.evaluate(500.0,500.0,0,1000.0,Double.NaN))
        assertNull(MonitorAlertDecision.evaluate(900.01,500.0,10,1000.0,Double.NaN))
        assertEquals("drop",MonitorAlertDecision.evaluate(900.0,500.0,10,1000.0,Double.NaN))
        assertEquals("drop",MonitorAlertDecision.evaluate(950.0,500.0,5,1000.0,Double.NaN))
        assertEquals("drop",MonitorAlertDecision.evaluate(800.0,500.0,20,1000.0,Double.NaN))
    }
    @Test fun duplicateMinimumNeverRepeatsEvenIfSellerChangesOrPriceRebounds() {
        for(price in listOf(900.0,1000.0,900.001))assertNull(MonitorAlertDecision.evaluate(price,1500.0,10,1000.0,900.0))
        assertEquals("target",MonitorAlertDecision.evaluate(850.0,1500.0,10,900.0,900.0))
        assertNull(MonitorAlertDecision.evaluate(850.0,500.0,10,900.0,900.0))
        assertEquals("drop",MonitorAlertDecision.evaluate(810.0,500.0,10,900.0,900.0))
    }
    @Test fun unknownAndInvalidPricesCannotTriggerNotifications() {
        for(price in listOf(null,0.0,-1.0,Double.NaN,Double.POSITIVE_INFINITY))
            assertNull(MonitorAlertDecision.evaluate(price,1000.0,10,1000.0,Double.NaN))
        assertNull(MonitorAlertDecision.evaluate(500.0,100.0,10,Double.NaN,Double.NaN))
        assertNull(MonitorAlertDecision.evaluate(500.0,100.0,0,1000.0,Double.NaN))
    }
}
