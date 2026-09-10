package com.sleepguard.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class SleepEvent(
    val id: Long,
    val type: String,          // "SNORE" | "APNEA"
    val startEpochMs: Long,
    val durationMs: Long,
    val wavPath: String,
    val score: Float
)

/** 事件库：SQLite，双端字段与鸿蒙 RDB 完全一致 */
class EventStore(context: Context) : SQLiteOpenHelper(context, "events.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "type TEXT, startEpochMs INTEGER, durationMs INTEGER," +
                "wavPath TEXT, score REAL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) = Unit

    fun insert(type: String, startMs: Long, durMs: Long, path: String, score: Float): Long {
        val cv = ContentValues().apply {
            put("type", type); put("startEpochMs", startMs)
            put("durationMs", durMs); put("wavPath", path); put("score", score.toDouble())
        }
        return writableDatabase.insert("events", null, cv)
    }

    fun queryTonight(dayStartMs: Long): List<SleepEvent> {
        val out = mutableListOf<SleepEvent>()
        readableDatabase.rawQuery(
            "SELECT id,type,startEpochMs,durationMs,wavPath,score FROM events " +
                "WHERE startEpochMs>=? ORDER BY startEpochMs DESC", arrayOf(dayStartMs.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    SleepEvent(
                        c.getLong(0), c.getString(1), c.getLong(2),
                        c.getLong(3), c.getString(4), c.getFloat(5)
                    )
                )
            }
        }
        return out
    }

    fun countsSince(dayStartMs: Long): Pair<Int, Int> {
        var snore = 0; var apnea = 0
        readableDatabase.rawQuery(
            "SELECT type,COUNT(*) FROM events WHERE startEpochMs>=? GROUP BY type",
            arrayOf(dayStartMs.toString())
        ).use { c ->
            while (c.moveToNext()) {
                if (c.getString(0) == "SNORE") snore = c.getInt(1) else apnea = c.getInt(1)
            }
        }
        return snore to apnea
    }
}
