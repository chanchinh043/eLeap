// ReadingRepository.kt
package com.eleap.eleap.feature.reading.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ReadingRepository(private val dao: ReadingDao) {

    // ── Cache RAM ─────────────────────────────────────────────────────────────
    private var readingListCache: List<Reading>? = null

    // key = readingId, value = danh sách sentence (đã gắn phrases + words)
    private val readingCache = mutableMapOf<Int, List<ReadingSentence>>()

    // key = word đã normalize (lowercase, trim), value = DictEntry (dict.db)
    private val dictCache = mutableMapOf<String, DictEntry>()

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

    // ── Background: nạp Dict RAM cho các từ xuất hiện trong bài đọc ──────────
    // Gọi sau khi bài đọc đã hiển thị, không chặn UI.
    suspend fun preloadDictForReading(sentences: List<ReadingSentence>) =
        withContext(Dispatchers.IO) {
            val keysToLoad = sentences
                .flatMap { it.words }
                .mapNotNull { normalizeWord(it.textEn) }
                .distinct()
                .filterNot { dictCache.containsKey(it) }

            if (keysToLoad.isEmpty()) return@withContext

            dao.getDictEntries(keysToLoad).forEach { entry ->
                normalizeWord(entry.word)?.let { key -> dictCache[key] = entry }
            }
        }

    // ── Flow 6: lookup nghĩa từ điển từ Dict RAM (không truy cập DB) ─────────
    fun getDictEntry(textEn: String?): DictEntry? =
        normalizeWord(textEn)?.let { dictCache[it] }

    // ── Chuẩn hoá từ để tra cứu: lowercase, bỏ khoảng trắng + dấu câu ở 2 đầu ──
    private fun normalizeWord(text: String?): String? {
        val cleaned = text
            ?.trim()
            ?.lowercase()
            ?.replace(Regex("^[^a-z']+|[^a-z']+$"), "")
        return cleaned?.ifEmpty { null }
    }
}