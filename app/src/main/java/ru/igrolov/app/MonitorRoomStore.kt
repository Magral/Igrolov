package ru.igrolov.app

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

@Entity(tableName = "tracked_watches")
data class StoredWatch(
    @PrimaryKey val watchId: String,
    val position: Int,
    val name: String,
    val kind: String,
    val payload: String
)

@Entity(
    tableName = "watch_history",
    primaryKeys = ["watchId", "checkedAt"],
    foreignKeys = [ForeignKey(
        entity = StoredWatch::class,
        parentColumns = ["watchId"],
        childColumns = ["watchId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("watchId")]
)
data class StoredPricePoint(
    val watchId: String,
    val checkedAt: Long,
    val price: Double?,
    val offerCount: Int
)

@Entity(tableName = "monitor_migrations")
data class MonitorMigrationMarker(@PrimaryKey val migrationKey: String)

@Dao
interface MonitorDao {
    @Query("SELECT EXISTS(SELECT 1 FROM monitor_migrations WHERE migrationKey = :key)")
    fun isMigrationComplete(key: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun markMigrationComplete(marker: MonitorMigrationMarker)

    @Query("SELECT * FROM tracked_watches ORDER BY position")
    fun watches(): List<StoredWatch>

    @Query("SELECT * FROM watch_history ORDER BY checkedAt")
    fun history(): List<StoredPricePoint>

    @Query("DELETE FROM watch_history")
    fun clearHistory()

    @Query("DELETE FROM tracked_watches")
    fun clearWatches()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putWatches(rows: List<StoredWatch>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putHistory(rows: List<StoredPricePoint>)
}

@Database(entities = [StoredWatch::class, StoredPricePoint::class, MonitorMigrationMarker::class], version = 1, exportSchema = false)
abstract class MonitorDatabase : RoomDatabase() {
    abstract fun monitorDao(): MonitorDao
}

/** Room-backed watch/history snapshot with an idempotent import from the 1.0.6 preferences. */
internal object MonitorRoomStore {
    private const val DATABASE = "igrolov-monitor.db"
    private const val PREFS = "game_watches"
    private const val JSON_KEY = "watches"
    private const val MIGRATION_KEY = "shared_preferences_watch_snapshot_v1"
    private const val ROOM_FAILED_KEY = "room_watch_history_v1_failed"
    @Volatile private var instance: MonitorDatabase? = null

    private fun db(context: Context): MonitorDatabase = instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(
            context.applicationContext, MonitorDatabase::class.java, DATABASE
        ).allowMainThreadQueries().build().also { instance = it }
    }

    private fun legacy(context: Context): JSONArray = try {
        JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(JSON_KEY, "[]"))
    } catch (_: Exception) { JSONArray() }

    fun read(context: Context): JSONArray = synchronized(WatchStore.lock) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(ROOM_FAILED_KEY, false)) return@synchronized legacy(context)
        try {
            val database = db(context)
            if (!database.monitorDao().isMigrationComplete(MIGRATION_KEY)) {
                val dao = database.monitorDao()
                if (dao.watches().isEmpty()) writeSnapshot(database, legacy(context))
                dao.markMigrationComplete(MonitorMigrationMarker(MIGRATION_KEY))
            }
            readSnapshot(database)
        } catch (_: Exception) {
            prefs.edit().putBoolean(ROOM_FAILED_KEY, true).apply()
            legacy(context)
        }
    }

    fun write(context: Context, array: JSONArray): Unit = synchronized(WatchStore.lock) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        try {
            writeSnapshot(db(context), array)
            prefs.edit().putString(JSON_KEY, array.toString())
                .remove(ROOM_FAILED_KEY).commit()
        } catch (_: Exception) {
            // Keep the 1.0.6-compatible copy usable if the local database cannot be opened.
            prefs.edit().putString(JSON_KEY, array.toString()).putBoolean(ROOM_FAILED_KEY, true).commit()
        }
        Unit
    }

    internal fun writeSnapshot(database: MonitorDatabase, array: JSONArray) {
        val watches = mutableListOf<StoredWatch>()
        val points = mutableListOf<StoredPricePoint>()
        for (position in 0 until array.length()) {
            val source = array.optJSONObject(position) ?: continue
            val watchId = identity(source)
            val json = JSONObject(source.toString())
            json.put("watchId", watchId)
            val history = json.optJSONArray("history") ?: JSONArray()
            json.remove("history")
            watches += StoredWatch(watchId, position, source.optString("name"), source.optString("kind"), json.toString())
            for (i in 0 until history.length()) {
                val item = history.optJSONObject(i) ?: continue
                val at = item.optLong("at", 0L)
                if (at <= 0L) continue
                val value = item.optDouble("price", Double.NaN)
                    .takeIf { it.isFinite() && it > 0.0 }
                points += StoredPricePoint(watchId, at, value, item.optInt("count", 0).coerceAtLeast(0))
            }
        }
        database.runInTransaction(Runnable {
            val dao = database.monitorDao()
            dao.clearHistory()
            dao.clearWatches()
            if (watches.isNotEmpty()) dao.putWatches(watches)
            if (points.isNotEmpty()) dao.putHistory(points)
        })
    }

    internal fun readSnapshot(database: MonitorDatabase): JSONArray {
        val histories = database.monitorDao().history().groupBy { it.watchId }
        val result = JSONArray()
        for (row in database.monitorDao().watches()) {
            val json = JSONObject(row.payload)
            val rows = histories[row.watchId].orEmpty()
            if (rows.isNotEmpty()) {
                val history = JSONArray()
                rows.forEach { point ->
                    val item = JSONObject().put("at", point.checkedAt).put("count", point.offerCount)
                    point.price?.let { item.put("price", it) }
                    history.put(item)
                }
                json.put("history", history)
            }
            result.put(json)
        }
        return result
    }

    private fun identity(watch: JSONObject): String = watch.optString("watchId").ifBlank {
        UUID.nameUUIDFromBytes((watch.optString("name").lowercase(Locale.ROOT) + "|" + watch.optString("kind"))
            .toByteArray(Charsets.UTF_8)).toString()
    }
}
