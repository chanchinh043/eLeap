// ReadingRepository.kt
// File gộp từ: Entities.kt, ReadingDao.kt, ReadingDatabase.kt, ReadingRepository.kt
// Vẫn nằm trong package com.eleap.eleap.feature.reading.data (thư mục data/)
// → Các file khác (ReadingViewModel, ReadingListScreen, ReadingScreen, WordPopup, SaveWord,
//   SentencePopup) KHÔNG cần đổi import gì cả.
package com.eleap.eleap.feature.reading.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ═════════════════════════════════════════════════════════════════════════════
// 1. ENTITIES (trước đây ở Entities.kt)
// ═════════════════════════════════════════════════════════════════════════════

// ── readings ──────────────────────────────────────────────────────────────────
data class Reading(
    val readingId: Int,
    val titleEn: String?,
    val titleVi: String?,
    val level: String?,
    val topic: String?,
    val createdAt: String?,
    val updatedAt: String?,
)

// ── reading_sentences ─────────────────────────────────────────────────────────
data class ReadingSentence(
    val sentenceId: Int,
    val readingId: Int,
    val textEn: String?,
    val textVi: String?,
    val sentenceExplanation: String?,
    val sentenceOrder: Int,
    val phrases: List<SentencePhrase> = emptyList(),
    val words: List<SentenceWord>   = emptyList(),
)

// ── sentence_phrases ──────────────────────────────────────────────────────────
data class SentencePhrase(
    val phraseId: Int,
    val sentenceId: Int,
    val textEn: String?,
    val textVi: String?,
    val phraseExplanation: String?,
    val startWordOrder: Int,
    val endWordOrder: Int,
)

// ── sentence_words ────────────────────────────────────────────────────────────
data class SentenceWord(
    val wordId: Int,
    val sentenceId: Int,
    val phraseId: Int?,
    val textEn: String?,
    val textVi: String?,
    val wordExplanation: String?,
    val wordOrder: Int,
    val pos: String?,
    val lemma: String?,
    val wordFormExplanation: String?,
)

// ── dict (dict.db) ────────────────────────────────────────────────────────────
data class DictEntry(
    val word: String,
    val ipa: String?,
    val ipaVi: String?,
    val meaning: String?,
    val shortMeaning: String?,
)

// ═════════════════════════════════════════════════════════════════════════════
// 2. DAO (trước đây ở ReadingDao.kt)
// ═════════════════════════════════════════════════════════════════════════════

class ReadingDao(
    private val db: SQLiteDatabase,
    private val dictDb: SQLiteDatabase,
) {

    // ── Flow 2: danh sách bài đọc ─────────────────────────────────────────────
    fun getAllReadings(): List<Reading> {
        val list = mutableListOf<Reading>()
        val cursor = db.rawQuery("SELECT * FROM readings ORDER BY reading_id ASC", null)
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    Reading(
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

    // ── Flow 3: load sentences của 1 bài ─────────────────────────────────────
    fun getSentencesByReadingId(readingId: Int): List<ReadingSentence> {
        val list = mutableListOf<ReadingSentence>()
        val cursor = db.rawQuery(
            "SELECT * FROM reading_sentences WHERE reading_id = ? ORDER BY sentence_order ASC",
            arrayOf(readingId.toString())
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    ReadingSentence(
                        sentenceId          = it.getInt(it.getColumnIndexOrThrow("sentence_id")),
                        readingId           = it.getInt(it.getColumnIndexOrThrow("reading_id")),
                        textEn              = it.getString(it.getColumnIndexOrThrow("text_en")),
                        textVi              = it.getString(it.getColumnIndexOrThrow("text_vi")),
                        sentenceExplanation = it.getString(it.getColumnIndexOrThrow("sentence_explanation")),
                        sentenceOrder       = it.getInt(it.getColumnIndexOrThrow("sentence_order")),
                    )
                )
            }
        }
        return list
    }

    // ── Flow 3: load phrases của 1 sentence ──────────────────────────────────
    fun getPhrasesBySentenceId(sentenceId: Int): List<SentencePhrase> {
        val list = mutableListOf<SentencePhrase>()
        val cursor = db.rawQuery(
            "SELECT * FROM sentence_phrases WHERE sentence_id = ?",
            arrayOf(sentenceId.toString())
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    SentencePhrase(
                        phraseId           = it.getInt(it.getColumnIndexOrThrow("phrase_id")),
                        sentenceId         = it.getInt(it.getColumnIndexOrThrow("sentence_id")),
                        textEn             = it.getString(it.getColumnIndexOrThrow("text_en")),
                        textVi             = it.getString(it.getColumnIndexOrThrow("text_vi")),
                        phraseExplanation  = it.getString(it.getColumnIndexOrThrow("phrase_explanation")),
                        startWordOrder     = it.getInt(it.getColumnIndexOrThrow("start_word_order")),
                        endWordOrder       = it.getInt(it.getColumnIndexOrThrow("end_word_order")),
                    )
                )
            }
        }
        return list
    }

    // ── Flow 3: load words của 1 sentence ────────────────────────────────────
    fun getWordsBySentenceId(sentenceId: Int): List<SentenceWord> {
        val list = mutableListOf<SentenceWord>()
        val cursor = db.rawQuery(
            "SELECT * FROM sentence_words WHERE sentence_id = ? ORDER BY word_order ASC",
            arrayOf(sentenceId.toString())
        )
        cursor.use {
            while (it.moveToNext()) {
                val phraseIdIdx = it.getColumnIndexOrThrow("phrase_id")
                list.add(
                    SentenceWord(
                        wordId              = it.getInt(it.getColumnIndexOrThrow("word_id")),
                        sentenceId          = it.getInt(it.getColumnIndexOrThrow("sentence_id")),
                        phraseId            = if (it.isNull(phraseIdIdx)) null else it.getInt(phraseIdIdx),
                        textEn              = it.getString(it.getColumnIndexOrThrow("text_en")),
                        textVi              = it.getString(it.getColumnIndexOrThrow("text_vi")),
                        wordExplanation     = it.getString(it.getColumnIndexOrThrow("word_explanation")),
                        wordOrder           = it.getInt(it.getColumnIndexOrThrow("word_order")),
                        pos                 = it.getString(it.getColumnIndexOrThrow("pos")),
                        lemma               = it.getString(it.getColumnIndexOrThrow("lemma")),
                        wordFormExplanation = it.getString(it.getColumnIndexOrThrow("word_form_explanation")),
                    )
                )
            }
        }
        return list
    }

    // ── Background: tra nghĩa từ điển cho danh sách từ (dict.db) ─────────────
    fun getDictEntries(words: List<String>): List<DictEntry> {
        if (words.isEmpty()) return emptyList()
        val list = mutableListOf<DictEntry>()

        // Chia batch để không vượt giới hạn placeholder của SQLite (~999)
        words.chunked(500).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            val cursor = dictDb.rawQuery(
                "SELECT * FROM dict WHERE word IN ($placeholders)",
                chunk.toTypedArray()
            )
            cursor.use {
                while (it.moveToNext()) {
                    list.add(
                        DictEntry(
                            word         = it.getString(it.getColumnIndexOrThrow("word")),
                            ipa          = it.getString(it.getColumnIndexOrThrow("ipa")),
                            ipaVi        = it.getString(it.getColumnIndexOrThrow("ipa_vi")),
                            meaning      = it.getString(it.getColumnIndexOrThrow("meaning")),
                            shortMeaning = it.getString(it.getColumnIndexOrThrow("short_meaning")),
                        )
                    )
                }
            }
        }
        return list
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// 3. DATABASE (trước đây ở ReadingDatabase.kt)
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Mở readings.db và dict.db từ assets bằng SQLite thuần — không dùng Room
 * để tránh schema validation conflict (VARCHAR vs TEXT, DATETIME vs TEXT).
 *
 * Mỗi DB có 1 "schema version" riêng lưu trong SharedPreferences.
 * Khi tăng version → file cũ bị xoá và copy lại từ assets.
 * Chỉ tăng version khi schema thay đổi (thêm/xoá cột, đổi tên bảng, v.v.).
 */
class ReadingDatabase private constructor(context: Context) {

    val db: SQLiteDatabase       // readings.db
    val dictDb: SQLiteDatabase   // dict.db

    init {
        db     = openDatabase(context, "readings.db", schemaVersion = 1)
        dictDb = openDatabase(context, "dict.db",     schemaVersion = 2) // tăng khi thêm ipa, ipa_vi
    }

    private fun openDatabase(context: Context, fileName: String, schemaVersion: Int): SQLiteDatabase {
        val prefs   = context.getSharedPreferences("db_versions", Context.MODE_PRIVATE)
        val prefKey = "version_$fileName"
        val dbFile  = File(context.getDatabasePath(fileName).absolutePath)

        val savedVersion = prefs.getInt(prefKey, 0)
        if (savedVersion < schemaVersion && dbFile.exists()) {
            // Schema đã thay đổi → xoá bản cũ, sẽ copy lại từ assets
            dbFile.delete()
        }

        if (!dbFile.exists()) {
            dbFile.parentFile?.mkdirs()
            context.assets.open("databases/$fileName").use { input ->
                FileOutputStream(dbFile).use { output ->
                    input.copyTo(output)
                }
            }
            prefs.edit().putInt(prefKey, schemaVersion).apply()
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

// ═════════════════════════════════════════════════════════════════════════════
// 4. REPOSITORY (trước đây ở ReadingRepository.kt)
// ═════════════════════════════════════════════════════════════════════════════

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