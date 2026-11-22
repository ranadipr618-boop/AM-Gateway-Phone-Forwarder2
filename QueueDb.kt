package com.amgateway.smsforwarder

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Offline queue: an SMS is stored here first, and only removed after the gateway confirmed it (so nothing is lost without internet). */
class QueueDb private constructor(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "queue.db", null, 1) {

    class Item(val id: Long, val sender: String, val body: String, val recv: Long)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE q(id INTEGER PRIMARY KEY AUTOINCREMENT, sender TEXT NOT NULL, body TEXT NOT NULL, recv INTEGER NOT NULL, created INTEGER NOT NULL, UNIQUE(sender, body, recv))")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    @Synchronized
    fun add(sender: String, body: String, recv: Long): Boolean {
        val now = System.currentTimeMillis()
        writableDatabase.delete("q", "created < ?", arrayOf((now - 7L * 86400000L).toString()))   // an SMS older than a week is useless to the gateway
        val cv = ContentValues()
        cv.put("sender", sender)
        cv.put("body", body)
        cv.put("recv", recv)
        cv.put("created", now)
        return writableDatabase.insertWithOnConflict("q", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    @Synchronized
    fun next(limit: Int): List<Item> {
        val out = ArrayList<Item>()
        readableDatabase.rawQuery("SELECT id, sender, body, recv FROM q ORDER BY id ASC LIMIT ?", arrayOf(limit.toString())).use { c ->
            while (c.moveToNext()) out.add(Item(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3)))
        }
        return out
    }

    @Synchronized
    fun remove(id: Long) { writableDatabase.delete("q", "id = ?", arrayOf(id.toString())) }

    @Synchronized
    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM q", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    companion object {
        @Volatile private var inst: QueueDb? = null
        fun get(ctx: Context): QueueDb = inst ?: synchronized(this) { inst ?: QueueDb(ctx).also { inst = it } }
    }
}
