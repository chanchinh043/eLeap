// ReadingRepository.kt
// File gộp từ: Entities.kt, ReadingDao.kt, ReadingDatabase.kt, ReadingRepository.kt
// Vẫn nằm trong package com.eleap.eleap.feature.reading.data (thư mục data/)
// → Các file khác (ReadingViewModel, ReadingListScreen, ReadingScreen, WordPopup, SaveWord,
//   SentencePopup) KHÔNG cần đổi import gì cả.
package com.eleap.eleap.feature.reading.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ═════════════════════════════════════════════════════════════════════════════
// 1. ENTITIES (trước đây ở Entities.kt)
// ═════════════════════════════════════════════════════════════════════════════

// ── readings ──────────────────────────────────────────────────────────────────
data class Reading(
    val readingId: String,
    val userId: String?,
    val titleEn: String?,
    val titleVi: String?,
    val level: String?,
    val topic: String?,
    val isAiProcessed: Boolean,
    val createdAt: String?,
    val updatedAt: String?,
)

// ── reading_sentences ─────────────────────────────────────────────────────────
data class ReadingSentence(
    val sentenceId: String,
    val readingId: String,
    val textEn: String?,
    val textVi: String?,
    val sentenceExplanation: String?,
    val sentenceOrder: Int,
    val phrases: List<SentencePhrase> = emptyList(),
    val words: List<SentenceWord>   = emptyList(),
)

// ── sentence_phrases ──────────────────────────────────────────────────────────
data class SentencePhrase(
    val phraseId: String,
    val sentenceId: String,
    val textEn: String?,
    val textVi: String?,
    val phraseExplanation: String?,
    val startWordOrder: Int,
    val endWordOrder: Int,
)

// ── sentence_words ────────────────────────────────────────────────────────────
data class SentenceWord(
    val wordId: String,
    val sentenceId: String,
    val phraseId: String?,
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
            val userIdIdx = it.getColumnIndexOrThrow("user_id")
            while (it.moveToNext()) {
                list.add(
                    Reading(
                        readingId     = it.getString(it.getColumnIndexOrThrow("reading_id")),
                        userId        = if (it.isNull(userIdIdx)) null else it.getString(userIdIdx),
                        titleEn       = it.getString(it.getColumnIndexOrThrow("title_en")),
                        titleVi       = it.getString(it.getColumnIndexOrThrow("title_vi")),
                        level         = it.getString(it.getColumnIndexOrThrow("level")),
                        topic         = it.getString(it.getColumnIndexOrThrow("topic")),
                        isAiProcessed = it.getInt(it.getColumnIndexOrThrow("is_ai_processed")) != 0,
                        createdAt     = it.getString(it.getColumnIndexOrThrow("created_at")),
                        updatedAt     = it.getString(it.getColumnIndexOrThrow("updated_at")),
                    )
                )
            }
        }
        return list
    }

    // ── Flow 3: load sentences của 1 bài ─────────────────────────────────────
    fun getSentencesByReadingId(readingId: String): List<ReadingSentence> {
        val list = mutableListOf<ReadingSentence>()
        val cursor = db.rawQuery(
            "SELECT * FROM reading_sentences WHERE reading_id = ? ORDER BY sentence_order ASC",
            arrayOf(readingId)
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    ReadingSentence(
                        sentenceId          = it.getString(it.getColumnIndexOrThrow("sentence_id")),
                        readingId           = it.getString(it.getColumnIndexOrThrow("reading_id")),
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
    fun getPhrasesBySentenceId(sentenceId: String): List<SentencePhrase> {
        val list = mutableListOf<SentencePhrase>()
        val cursor = db.rawQuery(
            "SELECT * FROM sentence_phrases WHERE sentence_id = ?",
            arrayOf(sentenceId)
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    SentencePhrase(
                        phraseId           = it.getString(it.getColumnIndexOrThrow("phrase_id")),
                        sentenceId         = it.getString(it.getColumnIndexOrThrow("sentence_id")),
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
    fun getWordsBySentenceId(sentenceId: String): List<SentenceWord> {
        val list = mutableListOf<SentenceWord>()
        val cursor = db.rawQuery(
            "SELECT * FROM sentence_words WHERE sentence_id = ? ORDER BY word_order ASC",
            arrayOf(sentenceId)
        )
        cursor.use {
            while (it.moveToNext()) {
                val phraseIdIdx = it.getColumnIndexOrThrow("phrase_id")
                list.add(
                    SentenceWord(
                        wordId              = it.getString(it.getColumnIndexOrThrow("word_id")),
                        sentenceId          = it.getString(it.getColumnIndexOrThrow("sentence_id")),
                        phraseId            = if (it.isNull(phraseIdIdx)) null else it.getString(phraseIdIdx),
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
 * Mở readings.db và dict.db từ assets bằng SQLite thuần — không dùng Room.
 *
 * Cơ chế tự động cập nhật:
 *   - Tính MD5 của file trong assets và file đang dùng trên thiết bị.
 *   - Nếu khác nhau (DB đã được thay bằng file mới) → tự động copy đè, không cần xóa app.
 *   - Hash được lưu trong SharedPreferences để tránh tính lại mỗi lần khởi động.
 *
 * Lưu ý: readings.db và dict.db là DB chỉ đọc từ assets — không chứa dữ liệu người dùng.
 * Dữ liệu người dùng (từ đã lưu, v.v.) nằm trong users.db — không bị ảnh hưởng.
 */
class ReadingDatabase private constructor(context: Context) {

    val db: SQLiteDatabase       // readings.db
    val dictDb: SQLiteDatabase   // dict.db

    init {
        db     = openDatabase(context, "readings.db")
        dictDb = openDatabase(context, "dict.db")
    }

    /**
     * Mở một DB từ assets.
     * Tự động copy lại từ assets nếu:
     *   1. File chưa tồn tại trên thiết bị (lần đầu cài app), hoặc
     *   2. MD5 của file assets khác với MD5 đã lưu (DB đã được cập nhật trong APK mới).
     */
    private fun openDatabase(context: Context, fileName: String): SQLiteDatabase {
        val prefs      = context.getSharedPreferences("db_checksums", Context.MODE_PRIVATE)
        val prefKey    = "md5_$fileName"
        val assetPath  = "databases/$fileName"
        val dbFile     = File(context.getDatabasePath(fileName).absolutePath)

        // ── Tính MD5 của file trong assets ───────────────────────────────────
        val assetMd5 = context.assets.open(assetPath).use { md5OfStream(it) }
        Log.d("ReadingDB", "$fileName | asset MD5  = $assetMd5")

        val savedMd5 = prefs.getString(prefKey, null)
        Log.d("ReadingDB", "$fileName | saved MD5  = $savedMd5")

        val needsCopy = !dbFile.exists() || assetMd5 != savedMd5

        if (needsCopy) {
            Log.d("ReadingDB", "$fileName | DB thay đổi → copy lại từ assets")
            dbFile.parentFile?.mkdirs()
            // Đóng DB cũ nếu đang mở (trường hợp singleton bị tái tạo)
            try { SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).close() }
            catch (_: Exception) { }

            context.assets.open(assetPath).use { input ->
                FileOutputStream(dbFile).use { output -> input.copyTo(output) }
            }
            prefs.edit().putString(prefKey, assetMd5).apply()
            Log.d("ReadingDB", "$fileName | copy hoàn tất, MD5 đã lưu")
        } else {
            Log.d("ReadingDB", "$fileName | DB đã mới nhất, bỏ qua copy")
        }

        return SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )
    }

    /** Tính MD5 của một InputStream, trả về chuỗi hex 32 ký tự. */
    private fun md5OfStream(stream: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(8192)
        var read: Int
        while (stream.read(buffer).also { read = it } != -1) {
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
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
    private val readingCache = mutableMapOf<String, List<ReadingSentence>>()

    // key = word đã normalize (lowercase, trim), value = DictEntry (dict.db)
    private val dictCache = mutableMapOf<String, DictEntry>()

    // ── Flow 2 ────────────────────────────────────────────────────────────────
    suspend fun getAllReadings(): List<Reading> = withContext(Dispatchers.IO) {
        readingListCache ?: dao.getAllReadings().also { readingListCache = it }
    }

    // ── Flow 3 ────────────────────────────────────────────────────────────────
    suspend fun getReading(readingId: String): List<ReadingSentence> =
        withContext(Dispatchers.IO) {
            readingCache[readingId] ?: buildReading(readingId).also {
                readingCache[readingId] = it
            }
        }

    private fun buildReading(readingId: String): List<ReadingSentence> {
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