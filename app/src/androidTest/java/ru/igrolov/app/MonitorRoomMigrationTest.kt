package ru.igrolov.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MonitorRoomMigrationTest {
    @Test fun legacyWatchAndPriceGapsSurviveRoomReopenAndFutureUpdates() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "monitor-room-test-${UUID.randomUUID()}.db"
        val previous = JSONObject()
            .put("name", "Stellaris")
            .put("kind", "Gift")
            .put("limit", 750.0)
            .put("rf", true)
            .put("steamAppId", 281990)
            .put("notified", JSONArray().put("12345"))
            .put("topOffers", JSONArray().put(JSONObject().put("id", "12345").put("price", 450.0)))
            .put("history", JSONArray()
                .put(JSONObject().put("at", 1792468800000L).put("price", 500.0).put("count", 1))
                .put(JSONObject().put("at", 1792472400000L).put("count", 0)))
        val legacy = JSONArray().put(previous)

        val first = Room.databaseBuilder(context, MonitorDatabase::class.java, databaseName)
            .allowMainThreadQueries().build()
        MonitorRoomStore.writeSnapshot(first, legacy)
        first.close()

        val reopened = Room.databaseBuilder(context, MonitorDatabase::class.java, databaseName)
            .allowMainThreadQueries().build()
        val migrated = MonitorRoomStore.readSnapshot(reopened)
        val watch = migrated.getJSONObject(0)
        assertEquals(1, migrated.length())
        assertEquals("Stellaris", watch.getString("name"))
        assertEquals(281990, watch.getInt("steamAppId"))
        assertEquals("12345", watch.getJSONArray("notified").getString(0))
        assertEquals("12345", watch.getJSONArray("topOffers").getJSONObject(0).getString("id"))
        assertEquals(2, watch.getJSONArray("history").length())
        assertEquals(500.0, watch.getJSONArray("history").getJSONObject(0).getDouble("price"), 0.001)
        assertFalse(watch.getJSONArray("history").getJSONObject(1).has("price"))

        val updated = JSONObject(watch.toString())
        updated.getJSONArray("history").put(JSONObject().put("at", 1792476000000L).put("count", 0))
        MonitorRoomStore.writeSnapshot(reopened, JSONArray().put(updated))
        assertEquals(3, MonitorRoomStore.readSnapshot(reopened).getJSONObject(0).getJSONArray("history").length())
        MonitorRoomStore.writeSnapshot(reopened, JSONArray())
        assertEquals(0, MonitorRoomStore.readSnapshot(reopened).length())
        reopened.close()
        context.deleteDatabase(databaseName)
    }
}
