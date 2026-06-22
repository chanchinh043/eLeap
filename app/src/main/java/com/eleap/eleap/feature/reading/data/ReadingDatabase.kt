package com.eleap.eleap.feature.reading.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.io.FileOutputStream

/**
 * Mở readings.db từ assets bằng SQLite thuần — không dùng Room
 * để tránh schema validation conflict (VARCHAR vs TEXT, DATETIME vs TEXT).
 */
class ReadingDatabase private constructor(context: Context) {

    val db: SQLiteDatabase

    init {
        val dbFile = File(context.getDatabasePath("readings.db").absolutePath)
        if (!dbFile.exists()) {
            dbFile.parentFile?.mkdirs()
            context.assets.open("databases/readings.db").use { input ->
                FileOutputStream(dbFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
        db = SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )
    }

    companion object {
        @Volatile private var INSTANCE: ReadingDatabase? = null

        fun getInstance(context: Context): ReadingDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ReadingDatabase(context.applicationContext).also { INSTANCE = it }
            }
    }
}