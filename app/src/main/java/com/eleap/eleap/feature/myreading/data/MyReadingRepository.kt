// MyReadingRepository.kt
// Đặt tại: feature/myreading/data/MyReadingRepository.kt
package com.eleap.eleap.feature.myreading.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.eleap.eleap.core.auth.CurrentUser
import com.eleap.eleap.feature.reading.data.Reading
import com.eleap.eleap.feature.reading.data.ReadingSentence
import com.eleap.eleap.feature.reading.data.SentencePhrase
import com.eleap.eleap.feature.reading.data.SentenceWord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val TAG = "MyReadingRepository"

// ─────────────────────────────────────────────────────────────────────────────
// 0. UUID v7 — time-ordered UUID (RFC 9562 draft), dùng làm primary key
// ─────────────────────────────────────────────────────────────────────────────

object UuidV7 {
    private val random = SecureRandom()

    fun generate(): String {
        val unixMillis = System.currentTimeMillis()
        val rand = ByteArray(10).also { random.nextBytes(it) }

        val buf = ByteArray(16)
        buf[0] = (unixMillis shr 40).toByte()
        buf[1] = (unixMillis shr 32).toByte()
        buf[2] = (unixMillis shr 24).toByte()
        buf[3] = (unixMillis shr 16).toByte()
        buf[4] = (unixMillis shr 8).toByte()
        buf[5] = unixMillis.toByte()
        buf[6] = (0x70 or (rand[0].toInt() and 0x0F)).toByte()
        buf[7] = rand[1]
        buf[8] = (0x80 or (rand[2].toInt() and 0x3F)).toByte()
        buf[9] = rand[3]
        for (i in 0..5) buf[10 + i] = rand[4 + i]

        val hex = buf.joinToString("") { "%02x".format(it) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
                "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
    }
}

private fun nowIso8601(): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    sdf.timeZone = TimeZone.getTimeZone("UTC")
    return sdf.format(Date())
}

// ─────────────────────────────────────────────────────────────────────────────
// 1. Tách nội dung thành câu / từ
// ─────────────────────────────────────────────────────────────────────────────

data class MyParsedSentence(
    val text: String,
    val sentenceOrder: Int,
    val paragraphOrder: Int,
)

fun parseMyContent(content: String): List<MyParsedSentence> {
    val result = mutableListOf<MyParsedSentence>()
    var sentenceOrder = 1

    val paragraphs = content.split(Regex("\n+"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    paragraphs.forEachIndexed { paraIndex, paragraph ->
        val paragraphOrder = paraIndex + 1

        val sentences = paragraph
            .split(Regex("(?<=[.?!])(?=\\s|$)"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        sentences.forEach { sentence ->
            result.add(
                MyParsedSentence(
                    text           = sentence,
                    sentenceOrder  = sentenceOrder,
                    paragraphOrder = paragraphOrder,
                )
            )
            sentenceOrder++
        }
    }

    return result
}

fun splitMyWords(sentenceText: String): List<String> =
    sentenceText
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }

// ─────────────────────────────────────────────────────────────────────────────
// 2. SQLiteOpenHelper — mở/tạo myreading.db, độc lập với readings.db và users.db
// ─────────────────────────────────────────────────────────────────────────────

private const val DB_NAME    = "myreading.db"
private const val DB_VERSION = 2

class MyReadingDbHelper(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE readings (
                reading_id       TEXT PRIMARY KEY,
                user_id          TEXT,
                title_en         TEXT,
                title_vi         TEXT,
                level            TEXT,
                topic            TEXT,
                is_ai_processed  INTEGER DEFAULT 0,
                created_at       TEXT,
                updated_at       TEXT
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE reading_sentences (
                sentence_id           TEXT PRIMARY KEY,
                reading_id            TEXT NOT NULL REFERENCES readings(reading_id),
                text_en               TEXT,
                text_vi               TEXT,
                sentence_explanation  TEXT,
                sentence_order        INTEGER,
                paragraph_order       INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_sentences_reading_id ON reading_sentences(reading_id)")

        db.execSQL(
            """
            CREATE TABLE sentence_phrases (
                phrase_id           TEXT PRIMARY KEY,
                sentence_id         TEXT NOT NULL REFERENCES reading_sentences(sentence_id),
                text_en             TEXT,
                text_vi             TEXT,
                phrase_explanation  TEXT,
                start_word_order    INTEGER,
                end_word_order      INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_phrases_sentence_id ON sentence_phrases(sentence_id)")

        db.execSQL(
            """
            CREATE TABLE sentence_words (
                word_id                 TEXT PRIMARY KEY,
                sentence_id             TEXT NOT NULL REFERENCES reading_sentences(sentence_id),
                phrase_id               TEXT REFERENCES sentence_phrases(phrase_id),
                text_en                 TEXT,
                text_vi                 TEXT,
                word_explanation        TEXT,
                word_order              INTEGER,
                pos                     TEXT,
                lemma                   TEXT,
                word_form_explanation   TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_words_sentence_id ON sentence_words(sentence_id)")
        db.execSQL("CREATE INDEX idx_words_phrase_id ON sentence_words(phrase_id)")

        Log.d(TAG, "onCreate: đã tạo schema myreading.db (version $DB_VERSION)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE readings ADD COLUMN user_id TEXT")
            db.execSQL("ALTER TABLE readings ADD COLUMN is_ai_processed INTEGER DEFAULT 0")
            Log.d(TAG, "onUpgrade $oldVersion→$newVersion: đã thêm cột user_id, is_ai_processed")
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 3. DAO — đọc + ghi, LUÔN thao tác gắn với 1 user_id cụ thể do caller truyền vào
//    (Repository sẽ luôn truyền CurrentUser.userId.value tại thời điểm gọi)
// ─────────────────────────────────────────────────────────────────────────────

class MyReadingDao(private val db: SQLiteDatabase) {

    fun getAllReadings(userId: String): List<Reading> {
        val list = mutableListOf<Reading>()
        db.rawQuery(
            "SELECT * FROM readings WHERE user_id = ? ORDER BY created_at DESC",
            arrayOf(userId)
        ).use { c ->
            fun nullableString(col: String): String? {
                val idx = c.getColumnIndexOrThrow(col)
                return if (c.isNull(idx)) null else c.getString(idx)
            }
            while (c.moveToNext()) {
                list.add(
                    Reading(
                        readingId     = c.getString(c.getColumnIndexOrThrow("reading_id")),
                        userId        = nullableString("user_id"),
                        titleEn       = nullableString("title_en"),
                        titleVi       = nullableString("title_vi"),
                        level         = nullableString("level"),
                        topic         = nullableString("topic"),
                        isAiProcessed = c.getInt(c.getColumnIndexOrThrow("is_ai_processed")) != 0,
                        createdAt     = nullableString("created_at"),
                        updatedAt     = nullableString("updated_at"),
                    )
                )
            }
        }
        return list
    }

    fun getSentencesByReadingId(readingId: String): List<ReadingSentence> {
        val list = mutableListOf<ReadingSentence>()
        db.rawQuery(
            "SELECT * FROM reading_sentences WHERE reading_id = ? ORDER BY sentence_order ASC",
            arrayOf(readingId)
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    ReadingSentence(
                        sentenceId          = c.getString(c.getColumnIndexOrThrow("sentence_id")),
                        readingId           = c.getString(c.getColumnIndexOrThrow("reading_id")),
                        textEn              = c.getString(c.getColumnIndexOrThrow("text_en")),
                        textVi              = c.getString(c.getColumnIndexOrThrow("text_vi")),
                        sentenceExplanation = c.getString(c.getColumnIndexOrThrow("sentence_explanation")),
                        sentenceOrder       = c.getInt(c.getColumnIndexOrThrow("sentence_order")),
                    )
                )
            }
        }
        return list
    }

    fun getPhrasesBySentenceId(sentenceId: String): List<SentencePhrase> {
        val list = mutableListOf<SentencePhrase>()
        db.rawQuery(
            "SELECT * FROM sentence_phrases WHERE sentence_id = ?",
            arrayOf(sentenceId)
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    SentencePhrase(
                        phraseId          = c.getString(c.getColumnIndexOrThrow("phrase_id")),
                        sentenceId        = c.getString(c.getColumnIndexOrThrow("sentence_id")),
                        textEn            = c.getString(c.getColumnIndexOrThrow("text_en")),
                        textVi            = c.getString(c.getColumnIndexOrThrow("text_vi")),
                        phraseExplanation = c.getString(c.getColumnIndexOrThrow("phrase_explanation")),
                        startWordOrder    = c.getInt(c.getColumnIndexOrThrow("start_word_order")),
                        endWordOrder      = c.getInt(c.getColumnIndexOrThrow("end_word_order")),
                    )
                )
            }
        }
        return list
    }

    fun getWordsBySentenceId(sentenceId: String): List<SentenceWord> {
        val list = mutableListOf<SentenceWord>()
        db.rawQuery(
            "SELECT * FROM sentence_words WHERE sentence_id = ? ORDER BY word_order ASC",
            arrayOf(sentenceId)
        ).use { c ->
            val phraseIdIdx = c.getColumnIndexOrThrow("phrase_id")
            while (c.moveToNext()) {
                list.add(
                    SentenceWord(
                        wordId              = c.getString(c.getColumnIndexOrThrow("word_id")),
                        sentenceId          = c.getString(c.getColumnIndexOrThrow("sentence_id")),
                        phraseId            = if (c.isNull(phraseIdIdx)) null else c.getString(phraseIdIdx),
                        textEn              = c.getString(c.getColumnIndexOrThrow("text_en")),
                        textVi              = c.getString(c.getColumnIndexOrThrow("text_vi")),
                        wordExplanation     = c.getString(c.getColumnIndexOrThrow("word_explanation")),
                        wordOrder           = c.getInt(c.getColumnIndexOrThrow("word_order")),
                        pos                 = c.getString(c.getColumnIndexOrThrow("pos")),
                        lemma               = c.getString(c.getColumnIndexOrThrow("lemma")),
                        wordFormExplanation = c.getString(c.getColumnIndexOrThrow("word_form_explanation")),
                    )
                )
            }
        }
        return list
    }

    /**
     * Insert 1 bài đọc + toàn bộ câu + từng word trong 1 transaction.
     * Trả về reading_id (UUID v7) mới, hoặc null nếu thất bại.
     */
    fun insertReadingWithSentences(
        userId: String,
        titleEn: String,
        sentences: List<MyParsedSentence>,
    ): String? {
        val readingId = UuidV7.generate()
        val now = nowIso8601()

        db.beginTransaction()
        try {
            val cv = ContentValues().apply {
                put("reading_id", readingId)
                put("user_id",    userId)
                put("title_en",   titleEn)
                put("created_at", now)
                put("updated_at", now)
                put("is_ai_processed", false)
            }
            val readingRowId = db.insert("readings", null, cv)
            Log.d(TAG, "insert reading: \"$titleEn\" (user=$userId) → reading_id=$readingId (rowId=$readingRowId)")

            if (readingRowId == -1L) {
                db.endTransaction()
                return null
            }

            sentences.forEach { s ->
                val sentenceId = UuidV7.generate()
                val scv = ContentValues().apply {
                    put("sentence_id",     sentenceId)
                    put("reading_id",      readingId)
                    put("text_en",         s.text)
                    put("sentence_order",  s.sentenceOrder)
                    put("paragraph_order", s.paragraphOrder)
                }
                val sentenceRowId = db.insert("reading_sentences", null, scv)
                if (sentenceRowId == -1L) return@forEach

                val wordTokens = splitMyWords(s.text)
                wordTokens.forEachIndexed { index, token ->
                    val wcv = ContentValues().apply {
                        put("word_id",     UuidV7.generate())
                        put("sentence_id", sentenceId)
                        put("text_en",     token)
                        put("word_order",  index + 1)
                    }
                    db.insert("sentence_words", null, wcv)
                }
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return readingId
    }

    /**
     * Xoá hoàn toàn 1 bài đọc: sentence_words → sentence_phrases →
     * reading_sentences → readings.
     */
    fun deleteReadingById(readingId: String): Boolean {
        db.beginTransaction()
        return try {
            val sentenceIds = mutableListOf<String>()
            db.rawQuery(
                "SELECT sentence_id FROM reading_sentences WHERE reading_id = ?",
                arrayOf(readingId)
            ).use { c -> while (c.moveToNext()) sentenceIds.add(c.getString(0)) }

            sentenceIds.forEach { sid ->
                db.delete("sentence_words", "sentence_id = ?", arrayOf(sid))
                db.delete("sentence_phrases", "sentence_id = ?", arrayOf(sid))
            }
            db.delete("reading_sentences", "reading_id = ?", arrayOf(readingId))
            val rRows = db.delete("readings", "reading_id = ?", arrayOf(readingId))

            db.setTransactionSuccessful()
            rRows > 0
        } catch (e: Exception) {
            Log.e(TAG, "deleteReadingById error", e)
            false
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Chuyển toàn bộ bài đọc từ 1 user_id (thường là guest) sang user_id khác
     * (thường là uuid thật vừa đăng nhập). Trả về số dòng đã cập nhật.
     */
    fun migrateOwnership(fromUserId: String, toUserId: String): Int {
        val cv = ContentValues().apply { put("user_id", toUserId) }
        val rows = db.update("readings", cv, "user_id = ?", arrayOf(fromUserId))
        Log.d(TAG, "migrateOwnership: $fromUserId → $toUserId, $rows row(s)")
        return rows
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 4. Repository — public API. Mọi hàm đọc/ghi đều gắn với CurrentUser.userId
//    tại thời điểm gọi — không nhận userId từ tham số để tránh nơi gọi quên
//    truyền đúng, hoặc truyền nhầm user khác.
// ─────────────────────────────────────────────────────────────────────────────

class MyReadingRepository private constructor(myDb: SQLiteDatabase) {

    private val dao = MyReadingDao(myDb)

    // key = readingId, value = sentences (đã gắn phrases + words)
    private val sentenceCache = mutableMapOf<String, List<ReadingSentence>>()

    suspend fun getAllReadings(): List<Reading> =
        withContext(Dispatchers.IO) { dao.getAllReadings(CurrentUser.userId.value) }

    suspend fun getReading(readingId: String): List<ReadingSentence> =
        withContext(Dispatchers.IO) {
            sentenceCache[readingId] ?: buildReading(readingId).also {
                sentenceCache[readingId] = it
            }
        }

    private fun buildReading(readingId: String): List<ReadingSentence> {
        val sentences = dao.getSentencesByReadingId(readingId)
        return sentences.map { s ->
            s.copy(
                phrases = dao.getPhrasesBySentenceId(s.sentenceId),
                words   = dao.getWordsBySentenceId(s.sentenceId),
            )
        }
    }

    /**
     * Tách nội dung thành câu rồi lưu vào myreading.db, gắn với user hiện tại.
     * Trả về reading_id mới (UUID v7), hoặc null nếu thất bại.
     */
    suspend fun saveMyReading(title: String, content: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val sentences = parseMyContent(content)
                if (sentences.isEmpty()) {
                    Log.w(TAG, "Nội dung không có câu nào sau khi tách")
                    return@withContext null
                }
                dao.insertReadingWithSentences(
                    userId    = CurrentUser.userId.value,
                    titleEn   = title.trim(),
                    sentences = sentences,
                )
            } catch (e: Exception) {
                Log.e(TAG, "saveMyReading error", e)
                null
            }
        }

    suspend fun deleteMyReading(readingId: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                dao.deleteReadingById(readingId).also { ok ->
                    if (ok) sentenceCache.remove(readingId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "deleteMyReading error", e)
                false
            }
        }

    /**
     * Gọi sau khi đăng nhập/đăng ký Supabase thành công (sau CurrentUser.setUser(newId)):
     * chuyển toàn bộ bài đã tạo lúc còn là guest sang tài khoản vừa đăng nhập.
     */
    suspend fun migrateGuestDataTo(newUserId: String): Int =
        withContext(Dispatchers.IO) {
            dao.migrateOwnership(CurrentUser.GUEST_ID, newUserId).also {
                sentenceCache.clear()
            }
        }

    companion object {
        @Volatile private var INSTANCE: MyReadingRepository? = null

        fun getInstance(context: Context): MyReadingRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: MyReadingRepository(
                    MyReadingDbHelper(context.applicationContext).writableDatabase
                ).also { INSTANCE = it }
            }
    }
}