// MyReadingSchema.kt
// Đặt tại: feature/myreading/data/MyReadingSchema.kt
//
// Tách từ MyReadingRepository.kt — phần hạ tầng KHÔNG phụ thuộc vào 1 instance
// SQLiteDatabase cụ thể: UUID v7 generator, hàm tách câu/từ, và định nghĩa
// schema (SQLiteOpenHelper). Ít thay đổi nhất trong 3 file, tách riêng để sửa
// DAO/Repository không phải kéo theo khối tạo bảng.
package com.eleap.eleap.feature.myreading.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
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

fun nowIso8601(): String {
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