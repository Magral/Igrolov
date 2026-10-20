package ru.igrolov.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MonitorPolicyTest {
    private fun offer(price:Double=100.0)=Offer("123456","Stellaris Steam Key РФ",price,"Key","РФ указана в названии","Игра",detailsChecked=true,available=true,priceVerified=true)
    @Test fun alertsOnceAtTargetThenAgainOnlyOnFurtherDrop() {
        val first=MonitorPolicy.alerts(listOf(offer()),110.0,JSONObject(),JSONArray())
        assertEquals(1,first.offers.size)
        assertTrue(MonitorPolicy.alerts(listOf(offer()),110.0,first.prices,JSONArray()).offers.isEmpty())
        assertTrue(MonitorPolicy.alerts(listOf(offer(105.0)),110.0,first.prices,JSONArray()).offers.isEmpty())
        val drop=MonitorPolicy.alerts(listOf(offer(99.99)),110.0,first.prices,JSONArray())
        assertEquals(1,drop.offers.size)
        assertTrue(MonitorPolicy.alerts(listOf(offer(100.0)),110.0,drop.prices,JSONArray()).offers.isEmpty())
        assertTrue(MonitorPolicy.alerts(listOf(offer(99.99)),110.0,drop.prices,JSONArray()).offers.isEmpty())
        assertTrue(MonitorPolicy.alerts(listOf(offer()),90.0,JSONObject(),JSONArray()).offers.isEmpty())
    }
    @Test fun legacyNotificationIdsMigrateWithoutSpammingButCanReportLaterDrop() {
        val migrated=MonitorPolicy.alerts(listOf(offer()),110.0,JSONObject(),JSONArray().put("123456"))
        assertTrue(migrated.offers.isEmpty());assertEquals(100.0,migrated.prices.getDouble("123456"),0.0)
        assertEquals(1,MonitorPolicy.alerts(listOf(offer(90.0)),110.0,migrated.prices,JSONArray()).offers.size)
    }
    @Test fun automaticAlertsRequireMoreEvidenceThanManualBrowsing() {
        assertTrue(MonitorPolicy.eligible(offer(),"Все"))
        assertTrue(MonitorPolicy.eligible(offer().copy(rfStatus="РФ выбрана в параметрах"),"Key"))
        for(bad in listOf(offer().copy(type="Не указан"),offer().copy(available=null),offer().copy(priceVerified=false),offer().copy(edition="Не подтверждено"),offer().copy(edition="DLC"),offer().copy(rfStatus="Регион РФ не подтвержден")))
            assertFalse(bad.toString(),MonitorPolicy.eligible(bad,"Все"))
    }
    @Test fun historyKeepsMissingOffersAsGapsAndRejectsInvalidPrices() {
        val now=2000000000000L
        val old=JSONArray().put(JSONObject().put("at",now-100L*86400000).put("price",10))
            .put(JSONObject().put("at",now-2).put("price",100)).put(JSONObject().put("at",now-1).put("price",0))
        val history=WatchHistory.append(old,now,null,0)
        val points=WatchHistory.points(history)
        assertEquals(3,points.size);assertEquals(100.0,points[0].price!!,0.0)
        assertNull(points[1].price);assertNull(points[2].price)
        var many=JSONArray();for(i in 0..405)many=WatchHistory.append(many,now+i,100.0,1)
        assertEquals(400,many.length())
        assertNull(WatchHistory.points(WatchHistory.append(JSONArray(),now,Double.NaN,0)).single().price)
    }
}
