package com.eleap.eleap.feature.reading.data

import android.database.sqlite.SQLiteDatabase

class ReadingDao(private val db: SQLiteDatabase) {

    fun getAllReadings(): List<Entities> {
        val list = mutableListOf<Entities>()
        val cursor = db.rawQuery("SELECT * FROM readings ORDER BY reading_id ASC", null)
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    Entities(
                        readingId = it.getInt(it.getColumnIndexOrThrow("reading_id")),
                        titleEn   = it.getString(it.getColumnIndexOrThrow("title_en")),
                        titleVi   = it.getString(it.getColumnIndexOrThrow("title_vi")),
                        level     = it.getString(it.getColumnIndexOrThrow("level")),
                        topic     = it.getString(it.getColumnIndexOrThrow("topic")),
                        createdAt = it.getString(it.getColumnIndexOrThrow("created_at")),
                        updatedAt = it.getString(it.getColumnIndexOrThrow("updated_at")),
                    )
                )
            }
        }
        return list
    }
}