package com.eleap.eleap.feature.reading.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream

/**
 * Mở readings.db và dict.db từ assets bằng SQLite thuần — không dùng Room
 * để tránh schema validation conflict (VARCHAR vs TEXT, DATETIME vs TEXT).
 */
class ReadingDatabase private constructor(context: Context) {

    val db: SQLiteDatabase       // readings.db
    val dictDb: SQLiteDatabase   // dict.db

    init {
        db     = openDatabase(context, "readings.db")
        dictDb = openDatabase(context, "dict.db")
    }

    private fun openDatabase(context: Context, fileName: String): SQLiteDatabase {
        val dbFile = File(context.getDatabasePath(fileName).absolutePath)
        if (!dbFile.exists()) {
            dbFile.parentFile?.mkdirs()
            context.assets.open("databases/$fileName").use { input ->
                FileOutputStream(dbFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
        return SQLiteDatabase.openDatabase(
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