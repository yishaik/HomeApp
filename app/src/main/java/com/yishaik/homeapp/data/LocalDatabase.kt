package com.yishaik.homeapp.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.yishaik.homeapp.domain.HomeItem

class LocalDatabase(context: Context) : SQLiteOpenHelper(context, "homeapp.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE items(id TEXT PRIMARY KEY, payload TEXT NOT NULL, updated_at TEXT NOT NULL, archived INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun readItems(): List<HomeItem> = readableDatabase.query("items", arrayOf("payload"), null, null, null, null, "updated_at ASC").use { c ->
        buildList { while (c.moveToNext()) runCatching { JsonCodec.decode(c.getString(0)) }.getOrNull()?.let(::add) }
    }

    /** Single-row read, used by the pending-push loop so it can decide under the write lock. */
    fun readItem(id: String): HomeItem? = readableDatabase.query("items", arrayOf("payload"), "id=?", arrayOf(id), null, null, null).use { c ->
        if (c.moveToFirst()) runCatching { JsonCodec.decode(c.getString(0)) }.getOrNull() else null
    }

    fun upsert(item: HomeItem) {
        writableDatabase.insertWithOnConflict("items", null, ContentValues().apply {
            put("id", item.id); put("payload", JsonCodec.encode(item)); put("updated_at", item.updatedAt.toString())
            put("archived", if (item.status.name == "ARCHIVED") 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun upsertAll(items: List<HomeItem>) = writableDatabase.transaction { items.forEach(::upsert) }

    fun replaceAll(items: List<HomeItem>) = writableDatabase.transaction {
        delete("items", null, null)
        items.forEach(::upsert)
    }

    fun clearAll() = writableDatabase.transaction { delete("items", null, null) }

    /** Truncates items AND metadata — used on logout so no state (preferences, notifications-seen,
     *  pending-push ids) of the departing member survives for whoever activates next. */
    fun clearEverything() = writableDatabase.transaction {
        delete("items", null, null)
        delete("metadata", null, null)
    }

    fun deleteItem(id: String) { writableDatabase.delete("items", "id=?", arrayOf(id)) }

    fun getMetadata(key: String): String? = readableDatabase.query("metadata", arrayOf("value"), "key=?", arrayOf(key), null, null, null).use {
        if (it.moveToFirst()) it.getString(0) else null
    }

    fun setMetadata(key: String, value: String) {
        writableDatabase.insertWithOnConflict("metadata", null, ContentValues().apply { put("key", key); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE)
    }
}

private inline fun SQLiteDatabase.transaction(block: SQLiteDatabase.() -> Unit) {
    beginTransaction()
    try { block(); setTransactionSuccessful() } finally { endTransaction() }
}
