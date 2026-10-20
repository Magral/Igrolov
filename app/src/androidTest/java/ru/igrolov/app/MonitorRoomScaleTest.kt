package ru.igrolov.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MonitorRoomScaleTest {
    @Test fun largeCacheAndAlertJournalStayBoundedAndQueryable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "monitor-scale-${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, MonitorDatabase::class.java, name)
            .allowMainThreadQueries().build()
        val dao = db.monitorDao()
        val cacheRows = (1..5000).map { id ->
            StoredTrackedProduct(id.toString(), "{\"proofSchema\":106,\"price\":${id + 10}}")
        }
        val alerts = (0 until 250).map { index ->
            StoredPriceAlert(payload = JSONObject().put("id", index).put("at", index.toLong()).toString())
        }
        db.runInTransaction(Runnable {
            dao.putTrackedProducts(cacheRows)
            dao.putAlerts(alerts)
            dao.trimAlerts()
        })
        assertEquals(5000, dao.trackedProductCount())
        assertEquals("{\"proofSchema\":106,\"price\":5010}", dao.trackedProduct("5000"))
        assertEquals(200, dao.alertCount())
        assertEquals(249, JSONObject(dao.alerts().first()).getInt("id"))
        assertEquals(50, JSONObject(dao.alerts().last()).getInt("id"))
        dao.deleteTrackedProduct("5000")
        assertNull(dao.trackedProduct("5000"))
        db.close()
        context.deleteDatabase(name)
    }
}
