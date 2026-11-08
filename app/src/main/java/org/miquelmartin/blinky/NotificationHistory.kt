package org.miquelmartin.blinky

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class NotificationHistory(context: Context, databaseName: String = "blinky_history.db") :
    SQLiteOpenHelper(context, databaseName, null, 1) {
    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "title TEXT NOT NULL, body TEXT NOT NULL, received_at INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Unsupported history database upgrade from $oldVersion to $newVersion.")
    }

    fun append(title: String, body: String, receivedAt: Long) {
        writableDatabase.insertOrThrow("messages", null, ContentValues().apply {
            put("title", title)
            put("body", body)
            put("received_at", receivedAt)
        })
    }

    fun read(): List<ReceivedNotification> {
        val messages = mutableListOf<ReceivedNotification>()
        readableDatabase.query(
            "messages", arrayOf("id", "title", "body", "received_at"),
            null, null, null, null, "received_at DESC, id DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                messages.add(ReceivedNotification(
                    cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3)
                ))
            }
        }
        return messages
    }
}
