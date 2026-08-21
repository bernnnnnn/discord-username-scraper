package com.noctra.scout

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Entry(val name: String, val available: Boolean, val ts: Long)

/**
 * Plain SQLite log of every name that has been checked.
 * `available = 1` feeds the Available tab, everything feeds the Tried tab.
 */
class Db private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "noctra.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE results (" +
                "name TEXT PRIMARY KEY NOT NULL, " +
                "available INTEGER NOT NULL, " +
                "ts INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX idx_available_ts ON results(available, ts DESC)")
        db.execSQL("CREATE INDEX idx_ts ON results(ts DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    /** Writes a batch in a single transaction; returns how many rows were new. */
    fun insertBatch(entries: List<Entry>): Int {
        if (entries.isEmpty()) return 0
        val db = writableDatabase
        var inserted = 0
        db.beginTransaction()
        try {
            val stmt = db.compileStatement(
                "INSERT OR IGNORE INTO results (name, available, ts) VALUES (?, ?, ?)"
            )
            for (e in entries) {
                stmt.clearBindings()
                stmt.bindString(1, e.name)
                stmt.bindLong(2, if (e.available) 1L else 0L)
                stmt.bindLong(3, e.ts)
                if (stmt.executeInsert() != -1L) inserted++
            }
            stmt.close()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return inserted
    }

    /** Newest first. [availableOnly] selects the Available tab. */
    fun page(availableOnly: Boolean, limit: Int, offset: Int): List<Entry> {
        val sql = if (availableOnly) {
            "SELECT name, available, ts FROM results WHERE available = 1 ORDER BY ts DESC, name ASC LIMIT ? OFFSET ?"
        } else {
            "SELECT name, available, ts FROM results ORDER BY ts DESC, name ASC LIMIT ? OFFSET ?"
        }
        val out = ArrayList<Entry>(limit)
        readableDatabase.rawQuery(sql, arrayOf(limit.toString(), offset.toString())).use { c ->
            while (c.moveToNext()) {
                out.add(Entry(c.getString(0), c.getInt(1) == 1, c.getLong(2)))
            }
        }
        return out
    }

    /** All available names, oldest first — used by Export. */
    fun allAvailable(): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery(
            "SELECT name FROM results WHERE available = 1 ORDER BY ts ASC", null
        ).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    /** Pair of (total checked, total available). Only run on a background thread. */
    fun counts(): Pair<Long, Long> {
        readableDatabase.rawQuery(
            "SELECT COUNT(*), IFNULL(SUM(available), 0) FROM results", null
        ).use { c ->
            return if (c.moveToFirst()) c.getLong(0) to c.getLong(1) else 0L to 0L
        }
    }

    fun contains(name: String): Boolean {
        readableDatabase.rawQuery(
            "SELECT 1 FROM results WHERE name = ? LIMIT 1", arrayOf(name)
        ).use { c -> return c.moveToFirst() }
    }

    fun clearAll() {
        writableDatabase.execSQL("DELETE FROM results")
    }

    fun clearTaken() {
        writableDatabase.delete("results", "available = 0", null)
    }

    /** Marks a previously-available name as taken (used by the re-verify action). */
    fun setAvailable(name: String, available: Boolean) {
        val cv = ContentValues().apply {
            put("available", if (available) 1 else 0)
            put("ts", System.currentTimeMillis())
        }
        writableDatabase.update("results", cv, "name = ?", arrayOf(name))
    }

    companion object {
        @Volatile
        private var instance: Db? = null

        fun get(context: Context): Db = instance ?: synchronized(this) {
            instance ?: Db(context).also { instance = it }
        }
    }
}
