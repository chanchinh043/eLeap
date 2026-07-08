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
// 0. Sync status — 4 trạng thái, dùng cho bảng readings (myreading.db) khi
//    đồng bộ với bảng my_readings trên Supabase. Ý nghĩa từng trạng thái
//    GIỐNG HỆT object SyncStatus bên feature/vocab/data/VocabDatabase.kt —
//    khai báo riêng ở đây (không import chéo) để feature/myreading không
//    phải phụ thuộc vào feature/vocab.
//
//    PENDING_CREATE → dòng CHƯA từng lên server. Nếu bị xoá lúc còn ở trạng
//                      thái này thì HARD DELETE ngay tại local, không cần
//                      báo server (vì server không hề biết dòng này tồn tại).
//    PENDING_UPDATE → dòng ĐÃ có trên server (từng SYNCED), vừa bị sửa ở
//                      local (title_vi, phrases/words do AI xử lý xong,...)
//                      và cần đẩy (upsert) lại lên server.
//    PENDING_DELETE → dòng đã có trên server, user xoá ở local → cần soft
//                      delete (deleted_at) rồi chờ sync gửi cập nhật lên
//                      server (tombstone).
//    SYNCED         → dòng đã đồng bộ xong, không có thay đổi cục bộ nào
//                      chưa gửi lên server.
//
//    QUY TẮC CHUYỂN TRẠNG THÁI (không được hạ cấp):
//    - Đang PENDING_CREATE mà bị sửa (vd AI ghi xong phrase/word) → GIỮ
//      NGUYÊN PENDING_CREATE (server chưa có gì để "update").
//    - Đang PENDING_DELETE thì không được ghi đè trở lại.
//    - Chỉ khi đang SYNCED mới hạ xuống PENDING_UPDATE khi có sửa đổi.
// ─────────────────────────────────────────────────────────────────────────────

object MyReadingSyncStatus {
    const val PENDING_CREATE = "pending_create"
    const val PENDING_UPDATE = "pending_update"
    const val PENDING_DELETE = "pending_delete"
    const val SYNCED         = "synced"
}

// ─────────────────────────────────────────────────────────────────────────────
// 1. UUID v7 — time-ordered UUID (RFC 9562 draft), dùng làm primary key
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
// 2. Tách nội dung thành câu / từ
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
// 3. SQLiteOpenHelper — mở/tạo myreading.db, độc lập với readings.db và users.db
//
//    DB_VERSION 2 → 3: thêm 2 cột sync metadata vào bảng readings —
//    deleted_at (soft delete) và sync_status (4 trạng thái, xem
//    MyReadingSyncStatus ở trên) — chuẩn bị cho việc đồng bộ bảng readings
//    lên Supabase (bảng my_readings), đơn vị đồng bộ là CẢ 1 bài đọc.
//
//    ⚠️ Dự án CHƯA có người dùng thật nào ngoài kia, nên không cần migration
//    bảo toàn dữ liệu phức tạp — dùng ALTER TABLE ADD COLUMN đơn giản là đủ.
// ─────────────────────────────────────────────────────────────────────────────

private const val DB_NAME    = "myreading.db"
private const val DB_VERSION = 3

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
                -- Định danh
                reading_id       TEXT PRIMARY KEY,
                user_id          TEXT,

                -- Nội dung
                title_en         TEXT,
                title_vi         TEXT,
                level            TEXT,
                topic            TEXT,

                -- Trạng thái xử lý
                is_ai_processed  INTEGER DEFAULT 0,

                -- Sync metadata (đồng bộ với Supabase — bảng my_readings)
                -- 4 trạng thái: pending_create / pending_update / pending_delete / synced
                created_at       TEXT,
                updated_at       TEXT,
                deleted_at       TEXT,
                sync_status      TEXT NOT NULL DEFAULT 'pending_create'
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

        createUpdatedAtTrigger(db)

        Log.d(TAG, "onCreate: đã tạo schema myreading.db (version $DB_VERSION)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE readings ADD COLUMN user_id TEXT")
            db.execSQL("ALTER TABLE readings ADD COLUMN is_ai_processed INTEGER DEFAULT 0")
            Log.d(TAG, "onUpgrade $oldVersion→$newVersion: đã thêm cột user_id, is_ai_processed")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE readings ADD COLUMN deleted_at TEXT")
            db.execSQL(
                "ALTER TABLE readings ADD COLUMN sync_status TEXT NOT NULL DEFAULT 'pending_create'"
            )
            createUpdatedAtTrigger(db)
            Log.d(TAG, "onUpgrade $oldVersion→$newVersion: đã thêm cột deleted_at, sync_status + trigger trg_myreading_updated_at")
        }
    }

    // ── Trigger tự động cập nhật updated_at khi readings bị UPDATE mà
    // updated_at không tự đổi trong chính câu UPDATE đó — copy đúng logic
    // trg_vocab_updated_at bên VocabDatabase.kt, áp dụng cho bảng readings.
    private fun createUpdatedAtTrigger(db: SQLiteDatabase) {
        db.execSQL("DROP TRIGGER IF EXISTS trg_myreading_updated_at")
        db.execSQL(
            """
            CREATE TRIGGER trg_myreading_updated_at
            AFTER UPDATE ON readings
            FOR EACH ROW
            WHEN NEW.updated_at IS OLD.updated_at
            BEGIN
                UPDATE readings
                SET updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
                WHERE reading_id = NEW.reading_id;
            END;
            """.trimIndent()
        )
    }
}