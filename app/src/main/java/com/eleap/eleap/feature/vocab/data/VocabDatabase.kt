// VocabDatabase.kt
// Đặt tại: com/eleap/eleap/feature/vocab/data/VocabDatabase.kt
//
// Chuyển từ SaveWord.kt (feature/reading/ui) sang đây — vì đây là dữ liệu
// vocab (entity + schema), không phải UI của reading. VocabRepository.kt
// (cùng package) là nơi duy nhất đọc/ghi bảng user_vocabulary.
package com.eleap.eleap.feature.vocab.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.eleap.eleap.core.auth.CurrentUser
import java.text.SimpleDateFormat
import java.util.*

// ─────────────────────────────────────────────────────────────────────────────
// 0. Sync status — 4 trạng thái thay cho "pending" chung chung trước đây.
//    Ý nghĩa từng trạng thái (dùng để worker sync sau này quyết định gọi
//    API nào — POST tạo mới / PATCH cập nhật / DELETE):
//
//    PENDING_CREATE → dòng CHƯA từng lên server. Nếu bị xoá lúc còn ở trạng
//                      thái này thì HARD DELETE ngay tại local, không cần
//                      báo server (vì server không hề biết dòng này tồn tại).
//    PENDING_UPDATE → dòng ĐÃ có trên server (từng SYNCED), vừa bị sửa ở
//                      local (count/selected/...) và cần PATCH lên server.
//    PENDING_DELETE → dòng đã có trên server, user xoá ở local → cần soft
//                      delete (deleted_at) rồi chờ sync gửi DELETE lên server.
//    SYNCED         → dòng đã đồng bộ xong, không có thay đổi cục bộ nào
//                      chưa gửi lên server.
//
//    QUY TẮC CHUYỂN TRẠNG THÁI (không được hạ cấp):
//    - Đang PENDING_CREATE mà bị sửa (increment count, đổi selected...)
//      → GIỮ NGUYÊN PENDING_CREATE (server chưa có gì để "update").
//    - Đang PENDING_DELETE thì không được ghi đè trở lại (nhưng thực tế các
//      hàm update đều lọc deleted_at IS NULL nên trường hợp này không xảy ra).
//    - Chỉ khi đang SYNCED mới hạ xuống PENDING_UPDATE khi có sửa đổi.
// ─────────────────────────────────────────────────────────────────────────────

object SyncStatus {
    const val PENDING_CREATE = "pending_create"
    const val PENDING_UPDATE = "pending_update"
    const val PENDING_DELETE = "pending_delete"
    const val SYNCED         = "synced"
}

// ─────────────────────────────────────────────────────────────────────────────
// 1. Entity
// ─────────────────────────────────────────────────────────────────────────────

data class UserVocabularyEntry(
    // Định danh
    val id: String = "",
    val userId: String = CurrentUser.GUEST_ID,

    // Nguồn gốc từ (sentence/word/phrase)
    val sourceSentenceId: String?,
    val sourceWordId: String?,
    val sourcePhraseId: String?,

    // Nội dung
    val textEn: String?,
    val textVi: String?,
    val phraseTextEn: String? = null,
    val phraseTextVi: String? = null,
    val sentenceTextEn: String? = null,
    val sentenceTextVi: String? = null,

    // Trạng thái học tập
    val selected: Int = 1,
    val count: Int = 0,
    val score: Int = 0,

    // Sync metadata (đồng bộ với Supabase)
    val createdAt: String,
    val updatedAt: String? = null,
    val deletedAt: String? = null,
    val syncStatus: String = SyncStatus.PENDING_CREATE,
)

// ─────────────────────────────────────────────────────────────────────────────
// UUID v7 — dùng để sinh id cho user_vocabulary, đồng bộ định dạng với readings.db
// ─────────────────────────────────────────────────────────────────────────────

fun generateUuidV7(): String {
    val timestamp = System.currentTimeMillis()
    val rand = java.security.SecureRandom()
    val randomBytes = ByteArray(10)
    rand.nextBytes(randomBytes)

    val buffer = java.nio.ByteBuffer.allocate(16)
    buffer.put((timestamp shr 40).toByte())
    buffer.put((timestamp shr 32).toByte())
    buffer.put((timestamp shr 24).toByte())
    buffer.put((timestamp shr 16).toByte())
    buffer.put((timestamp shr 8).toByte())
    buffer.put(timestamp.toByte())
    buffer.put((0x70 or (randomBytes[0].toInt() and 0x0F)).toByte())
    buffer.put(randomBytes[1])
    buffer.put((0x80 or (randomBytes[2].toInt() and 0x3F)).toByte())
    buffer.put(randomBytes[3])
    buffer.put(randomBytes[4])
    buffer.put(randomBytes[5])
    buffer.put(randomBytes[6])
    buffer.put(randomBytes[7])
    buffer.put(randomBytes[8])
    buffer.put(randomBytes[9])

    buffer.flip()
    return java.util.UUID(buffer.long, buffer.long).toString()
}

/** Giờ UTC dạng ISO8601 — dùng thống nhất cho created_at/updated_at/deleted_at
 *  để sync giữa nhiều thiết bị không bị lệch timezone. */
fun nowUtcIso(): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date())

// ─────────────────────────────────────────────────────────────────────────────
// 2. Database
//    CHỈ còn schema + migration ở đây. Mọi đọc/ghi user_vocabulary đi qua
//    VocabRepository (feature/vocab/data/VocabRepository.kt) — không còn hàm
//    nghiệp vụ nào (saveWord/unsaveWord/isWordSaved/deleteWord/...) trong
//    class này, để đảm bảo mọi write đều qua 1 điểm duy nhất và luôn set
//    đúng updated_at/deleted_at/sync_status cho sync với Supabase sau này.
//
//    ⚠️ LƯU Ý: DB_VERSION hiện đang reset về 1 vì dự án CHƯA có người dùng
//    thật nào ngoài kia. onUpgrade() hiện dùng cách DROP + tạo lại — CHỈ
//    chấp nhận được ở giai đoạn này. Ngay khi app có bản release đầu tiên
//    cho người dùng thật, PHẢI đổi onUpgrade() sang kiểu tăng dần từng bước
//    (if (oldVersion < X) { ALTER TABLE ... }) để không xoá mất dữ liệu đã
//    lưu của người dùng khi họ update app.
//
//    DB_VERSION 1 → 2: đổi sync_status từ 1 giá trị "pending" chung chung
//    sang 4 giá trị (pending_create/pending_update/pending_delete/synced).
//    Bump version để các máy dev cũ có sẵn dữ liệu "pending" lỗi thời được
//    dọn sạch qua DROP + tạo lại, thay vì lẫn vào 4 trạng thái mới.
// ─────────────────────────────────────────────────────────────────────────────

class UserDatabase private constructor(context: Context) {

    val db: SQLiteDatabase

    init {
        db = Helper(context.applicationContext).writableDatabase
        Log.d("UserDB", "DB path: ${db.path}")
    }

    private class Helper(context: Context) :
        SQLiteOpenHelper(context, "users.db", null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS users (
                    user_id TEXT PRIMARY KEY DEFAULT 'guest'
                )
                """.trimIndent()
            )
            db.execSQL("INSERT OR IGNORE INTO users (user_id) VALUES ('guest')")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS user_vocabulary (
                    -- Định danh
                    id                 TEXT PRIMARY KEY,
                    user_id            TEXT NOT NULL DEFAULT 'guest',

                    -- Nguồn gốc từ (sentence/word/phrase)
                    source_sentence_id TEXT,
                    source_word_id     TEXT,
                    source_phrase_id   TEXT,

                    -- Nội dung
                    text_en            TEXT,
                    text_vi            TEXT,
                    phrase_text_en     TEXT,
                    phrase_text_vi     TEXT,
                    sentence_text_en   TEXT,
                    sentence_text_vi   TEXT,

                    -- Trạng thái học tập
                    selected           INTEGER NOT NULL DEFAULT 1,
                    count              INTEGER NOT NULL DEFAULT 0,
                    score              INTEGER NOT NULL DEFAULT 0,

                    -- Sync metadata (đồng bộ với Supabase)
                    -- 4 trạng thái: pending_create / pending_update / pending_delete / synced
                    created_at         TEXT NOT NULL,
                    updated_at         TEXT,
                    deleted_at         TEXT,
                    sync_status        TEXT NOT NULL DEFAULT 'pending_create',

                    FOREIGN KEY (user_id) REFERENCES users(user_id)
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS idx_vocab_user_deleted
                ON user_vocabulary(user_id, deleted_at)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS idx_vocab_source_word
                ON user_vocabulary(source_word_id, user_id)
                """.trimIndent()
            )

            createUpdatedAtTrigger(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS user_vocabulary")
            onCreate(db)
        }

        private fun createUpdatedAtTrigger(db: SQLiteDatabase) {
            db.execSQL("DROP TRIGGER IF EXISTS trg_vocab_updated_at")
            db.execSQL(
                """
                CREATE TRIGGER trg_vocab_updated_at
                AFTER UPDATE ON user_vocabulary
                FOR EACH ROW
                WHEN NEW.updated_at IS OLD.updated_at
                BEGIN
                    UPDATE user_vocabulary
                    SET updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
                    WHERE id = NEW.id;
                END;
                """.trimIndent()
            )
        }
    }

    val dbPath: String get() = db.path

    companion object {
        private const val DB_VERSION = 2
        @Volatile private var INSTANCE: UserDatabase? = null

        fun getInstance(context: Context): UserDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: UserDatabase(context.applicationContext).also { INSTANCE = it }
            }
    }
}