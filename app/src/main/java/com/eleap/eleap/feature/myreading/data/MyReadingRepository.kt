// MyReadingRepository.kt
// Đặt tại: feature/myreading/data/MyReadingRepository.kt
package com.eleap.eleap.feature.myreading.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val TAG = "MyReadingRepository"

// ─────────────────────────────────────────────────────────────────────────────
// 0a. user_id placeholder — TẠM THỜI, chưa có Auth/Sync
//
// Hiện tại app chưa có đăng nhập, nên mọi bài đọc tạo trong MyReading đều gán
// user_id = GUEST_USER_ID (chuỗi cố định, KHÔNG phải UUID).
//
// SAU NÀY khi có Auth/Sync với Supabase:
//   - Khi user đăng nhập → Supabase trả về user_id thật (UUID v7 dạng String).
//   - Cần 1 bước migrate: UPDATE readings SET user_id = '<uuid_that_from_server>'
//     WHERE user_id = GUEST_USER_ID  (gán lại toàn bộ bài đọc "guest" hiện có
//     cho tài khoản vừa đăng nhập).
//   - Sau bước migrate đó, insertReadingWithSentences() bên dưới cần đổi để
//     nhận user_id thật từ session hiện tại (thay vì hard-code GUEST_USER_ID),
//     ví dụ: nhận thêm param `currentUserId: String?` từ nơi gọi (ViewModel/
//     AuthManager), fallback về GUEST_USER_ID nếu chưa đăng nhập.
// ─────────────────────────────────────────────────────────────────────────────

const val GUEST_USER_ID = "guest"

// ─────────────────────────────────────────────────────────────────────────────
// 0. UUID v7 — time-ordered UUID (RFC 9562 draft), dùng làm primary key
//    thay vì INTEGER AUTOINCREMENT, để id sinh ra ở client vẫn sort được
//    theo thời gian tạo.
// ─────────────────────────────────────────────────────────────────────────────

object UuidV7 {
    private val random = SecureRandom()

    fun generate(): String {
        val unixMillis = System.currentTimeMillis()
        val rand = ByteArray(10).also { random.nextBytes(it) }

        val buf = ByteArray(16)
        // 48 bit: unix_ts_ms
        buf[0] = (unixMillis shr 40).toByte()
        buf[1] = (unixMillis shr 32).toByte()
        buf[2] = (unixMillis shr 24).toByte()
        buf[3] = (unixMillis shr 16).toByte()
        buf[4] = (unixMillis shr 8).toByte()
        buf[5] = unixMillis.toByte()
        // 4 bit version (0111) + 12 bit random
        buf[6] = (0x70 or (rand[0].toInt() and 0x0F)).toByte()
        buf[7] = rand[1]
        // 2 bit variant (10) + 62 bit random
        buf[8] = (0x80 or (rand[2].toInt() and 0x3F)).toByte()
        buf[9] = rand[3]
        for (i in 0..5) buf[10 + i] = rand[4 + i]

        val hex = buf.joinToString("") { "%02x".format(it) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
                "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 0b. Timestamp ISO 8601 (UTC)
// ─────────────────────────────────────────────────────────────────────────────

private fun nowIso8601(): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    sdf.timeZone = TimeZone.getTimeZone("UTC")
    return sdf.format(Date())
}

// ─────────────────────────────────────────────────────────────────────────────
// 1. Tách nội dung thành câu / từ — giống logic bên UserReadingData,
//    lặp lại ở đây để module myreading tự chứa, không phụ thuộc
//    feature.userreading.
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
// 2. Model đọc ra (dùng cho MyReadingListScreen sau này)
// ─────────────────────────────────────────────────────────────────────────────

data class MyReading(
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

// ─────────────────────────────────────────────────────────────────────────────
// 3. SQLiteOpenHelper — mở/tạo myreading.db, HOÀN TOÀN riêng biệt với users.db
//    và readings.db, tự quản lý version/migration.
// ─────────────────────────────────────────────────────────────────────────────

private const val DB_NAME    = "myreading.db"
private const val DB_VERSION = 2

private class MyReadingDbHelper(context: Context) :
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
            // version 1 → 2: thêm user_id + is_ai_processed vào bảng readings
            // (khớp với schema readings.db mới)
            db.execSQL("ALTER TABLE readings ADD COLUMN user_id TEXT")
            db.execSQL("ALTER TABLE readings ADD COLUMN is_ai_processed INTEGER DEFAULT 0")
            Log.d(TAG, "onUpgrade $oldVersion→$newVersion: đã thêm cột user_id, is_ai_processed")
        }
        // Các migration sau sẽ bổ sung tiếp ở đây khi tăng DB_VERSION.
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 4. DAO — insert / delete
// ─────────────────────────────────────────────────────────────────────────────

private class MyReadingDao(private val db: SQLiteDatabase) {

    /**
     * Insert 1 bài đọc + toàn bộ câu + từng word trong 1 transaction.
     * Trả về reading_id (UUID v7) mới, hoặc null nếu thất bại.
     */
    fun insertReadingWithSentences(
        titleEn: String,
        sentences: List<MyParsedSentence>,
    ): String? {
        val readingId = UuidV7.generate()
        val now = nowIso8601()

        db.beginTransaction()
        try {
            val cv = ContentValues().apply {
                put("reading_id", readingId)
                // TODO(auth-sync): thay GUEST_USER_ID bằng user_id thật (UUID v7
                // từ Supabase) khi đã có Auth. Xem ghi chú ở GUEST_USER_ID phía trên.
                put("user_id",    GUEST_USER_ID)
                put("title_en",   titleEn)
                put("created_at", now)
                put("updated_at", now)
                // Bài mới tạo trong app → chưa qua xử lý AI, luôn để false.
                // Sẽ được đổi thành true ở bước xử lý AI riêng sau này.
                put("is_ai_processed", false)
                // title_vi, level, topic: chưa có → để NULL (chưa qua xử lý AI)
            }
            val readingRowId = db.insert("readings", null, cv)
            Log.d(TAG, "insert reading: \"$titleEn\" → reading_id=$readingId (rowId=$readingRowId)")

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
                    // text_vi, sentence_explanation: chưa có → để NULL
                }
                val sentenceRowId = db.insert("reading_sentences", null, scv)
                Log.d(
                    TAG,
                    "  sentence #${s.sentenceOrder} (para ${s.paragraphOrder}) → sentence_id=$sentenceId"
                )

                if (sentenceRowId == -1L) return@forEach

                val wordTokens = splitMyWords(s.text)
                wordTokens.forEachIndexed { index, token ->
                    val wcv = ContentValues().apply {
                        put("word_id",     UuidV7.generate())
                        put("sentence_id", sentenceId)
                        put("text_en",     token)
                        put("word_order",  index + 1)
                        // phrase_id, text_vi, word_explanation, pos, lemma,
                        // word_form_explanation: chưa có → để NULL
                    }
                    val wordRowId = db.insert("sentence_words", null, wcv)
                    if (wordRowId == -1L) {
                        Log.w(TAG, "    word #${index + 1} \"$token\" insert thất bại")
                    }
                }
                Log.d(TAG, "    → đã tách ${wordTokens.size} word(s)")
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return readingId
    }

    /**
     * Xoá hoàn toàn 1 bài đọc: sentence_words → sentence_phrases →
     * reading_sentences → readings (thứ tự FK, giống UserReadingDao).
     */
    fun deleteReadingById(readingId: String): Boolean {
        db.beginTransaction()
        return try {
            val sentenceIds = mutableListOf<String>()
            db.rawQuery(
                "SELECT sentence_id FROM reading_sentences WHERE reading_id = ?",
                arrayOf(readingId)
            ).use { c ->
                while (c.moveToNext()) sentenceIds.add(c.getString(0))
            }
            Log.d(TAG, "deleteReading: readingId=$readingId, sentences=${sentenceIds.size}")

            sentenceIds.forEach { sid ->
                val wRows = db.delete("sentence_words", "sentence_id = ?", arrayOf(sid))
                val pRows = db.delete("sentence_phrases", "sentence_id = ?", arrayOf(sid))
                Log.d(TAG, "  sentence_id=$sid → xoá $wRows words, $pRows phrases")
            }

            val sRows = db.delete("reading_sentences", "reading_id = ?", arrayOf(readingId))
            Log.d(TAG, "  → xoá $sRows sentences")

            val rRows = db.delete("readings", "reading_id = ?", arrayOf(readingId))
            Log.d(TAG, "  → xoá $rRows reading row(s)")

            db.setTransactionSuccessful()
            rRows > 0
        } catch (e: Exception) {
            Log.e(TAG, "deleteReadingById error", e)
            false
        } finally {
            db.endTransaction()
        }
    }

    fun getAllReadings(): List<MyReading> {
        val list = mutableListOf<MyReading>()
        db.rawQuery(
            "SELECT * FROM readings ORDER BY created_at DESC", null
        ).use { c ->
            fun nullableString(col: String): String? {
                val idx = c.getColumnIndexOrThrow(col)
                return if (c.isNull(idx)) null else c.getString(idx)
            }
            while (c.moveToNext()) {
                val userIdIdx = c.getColumnIndexOrThrow("user_id")
                list.add(
                    MyReading(
                        readingId     = c.getString(c.getColumnIndexOrThrow("reading_id")),
                        userId        = if (c.isNull(userIdIdx)) null else c.getString(userIdIdx),
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
}

// ─────────────────────────────────────────────────────────────────────────────
// 5. Repository — public API dùng từ Compose (AddMyReadingScreen, ...)
// ─────────────────────────────────────────────────────────────────────────────

class MyReadingRepository private constructor(context: Context) {

    private val dbHelper = MyReadingDbHelper(context)
    private val dao: MyReadingDao by lazy { MyReadingDao(dbHelper.writableDatabase) }

    /**
     * Tách nội dung thành câu rồi lưu vào myreading.db.
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
                val id = dao.insertReadingWithSentences(
                    titleEn   = title.trim(),
                    sentences = sentences,
                )
                if (id != null) {
                    Log.d(TAG, "saveMyReading OK: reading_id=$id, sentences=${sentences.size}")
                }
                id
            } catch (e: Exception) {
                Log.e(TAG, "saveMyReading error", e)
                null
            }
        }

    suspend fun deleteMyReading(readingId: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                dao.deleteReadingById(readingId)
            } catch (e: Exception) {
                Log.e(TAG, "deleteMyReading error", e)
                false
            }
        }

    suspend fun getAllReadings(): List<MyReading> =
        withContext(Dispatchers.IO) {
            try {
                dao.getAllReadings()
            } catch (e: Exception) {
                Log.e(TAG, "getAllReadings error", e)
                emptyList()
            }
        }

    companion object {
        @Volatile private var INSTANCE: MyReadingRepository? = null

        fun getInstance(context: Context): MyReadingRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: MyReadingRepository(context.applicationContext).also { INSTANCE = it }
            }
    }
}