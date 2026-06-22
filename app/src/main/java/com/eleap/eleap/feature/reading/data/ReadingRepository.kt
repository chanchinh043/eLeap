// ReadingRepository.kt
package com.eleap.eleap.feature.reading.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ReadingRepository(private val dao: ReadingDao) {

    // ── Cache RAM ─────────────────────────────────────────────────────────────
    private var readingListCache: List<Reading>? = null

    // key = readingId, value = danh sách sentence (đã gắn phrases + words)
    private val readingCache = mutableMapOf<Int, List<ReadingSentence>>()

    // ── Flow 2 ────────────────────────────────────────────────────────────────
    suspend fun getAllReadings(): List<Reading> = withContext(Dispatchers.IO) {
        readingListCache ?: dao.getAllReadings().also { readingListCache = it }
    }

    // ── Flow 3 ────────────────────────────────────────────────────────────────
    suspend fun getReading(readingId: Int): List<ReadingSentence> =
        withContext(Dispatchers.IO) {
            readingCache[readingId] ?: buildReading(readingId).also {
                readingCache[readingId] = it
            }
        }

    private fun buildReading(readingId: Int): List<ReadingSentence> {
        val sentences = dao.getSentencesByReadingId(readingId)
        return sentences.map { sentence ->
            val phrases = dao.getPhrasesBySentenceId(sentence.sentenceId)
            val words   = dao.getWordsBySentenceId(sentence.sentenceId)
            sentence.copy(phrases = phrases, words = words)
        }
    }
}