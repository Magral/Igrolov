package ru.igrolov.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MonitorRoomSchemaMigrationTest {
    @Test fun versionOneDatabaseUpgradesWithoutLosingWatchHistory() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "monitor-schema-v1-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        val old = SQLiteDatabase.openOrCreateDatabase(file, null)
        old.execSQL("CREATE TABLE tracked_watches (watchId TEXT NOT NULL, position INTEGER NOT NULL, name TEXT NOT NULL, kind TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(watchId))")
        old.execSQL("CREATE TABLE watch_history (watchId TEXT NOT NULL, checkedAt INTEGER NOT NULL, price REAL, offerCount INTEGER NOT NULL, PRIMARY KEY(watchId, checkedAt), FOREIGN KEY(watchId) REFERENCES tracked_watches(watchId) ON UPDATE NO ACTION ON DELETE CASCADE)")
        old.execSQL("CREATE INDEX index_watch_history_watchId ON watch_history(watchId)")
        old.execSQL("CREATE TABLE monitor_migrations (migrationKey TEXT NOT NULL, PRIMARY KEY(migrationKey))")
        old.execSQL("INSERT INTO tracked_watches VALUES ('watch-1', 0, 'Stellaris', 'Gift', '{\"name\":\"Stellaris\",\"kind\":\"Gift\",\"limit\":700.0}')")
        old.execSQL("INSERT INTO watch_history VALUES ('watch-1', 1792472400000, 512.5, 2)")
        old.version = 1
        old.close()

        val upgraded = Room.databaseBuilder(context, MonitorDatabase::class.java, name).build()
        val dao = upgraded.monitorDao()
        assertEquals(1, dao.watches().size)
        assertEquals("Stellaris", dao.watches().single().name)
        assertEquals(512.5, dao.history().single().price!!, 0.001)
        dao.putTrackedProduct(StoredTrackedProduct("123456", "{\"proofSchema\":106}"))
        assertEquals("{\"proofSchema\":106}", dao.trackedProduct("123456"))
        dao.putAlert(StoredPriceAlert(payload = "{\"id\":\"123456\"}"))
        assertNotNull(dao.alerts().singleOrNull())
        upgraded.close()
        context.deleteDatabase(name)
    }
}
