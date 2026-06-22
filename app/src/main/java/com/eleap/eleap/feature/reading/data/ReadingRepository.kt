package com.eleap.eleap.feature.reading.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ReadingRepository(private val dao: ReadingDao) {

    suspend fun getAllReadings(): List<Entities> = withContext(Dispatchers.IO) {
        dao.getAllReadings()
    }
}