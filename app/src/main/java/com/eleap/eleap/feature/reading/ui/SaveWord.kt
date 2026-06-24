// SaveWord.kt
// Đặt tại: com/eleap/eleap/feature/reading/ui/SaveWord.kt
//
// File duy nhất cần thêm cho tính năng "Lưu từ".
// Chứa: UserVocabularyEntry (entity), UserDatabase (DB creation), SaveWordButton (UI).
//
// Thay đổi ở file khác (tối thiểu):
//   1. ReadingViewModel.kt  → thêm hàm saveWord() (xem comment cuối file)
//   2. WordPopup.kt         → thêm SaveWordButton vào Column (xem comment cuối file)

package com.eleap.eleap.feature.reading.ui

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import android.util.Log
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.eleap.eleap.feature.reading.data.SentenceWord
import com.eleap.eleap.feature.reading.data.SentencePhrase
import java.text.SimpleDateFormat
import java.util.*

// ─────────────────────────────────────────────────────────────────────────────
// 1. Entity
// ─────────────────────────────────────────────────────────────────────────────

data class UserVocabularyEntry(
    val userId: Int = 0,
    val sourceSentenceId: Int?,
    val sourceWordId: Int?,
    val sourcePhraseId: Int?,
    val textEn: String?,
    val textVi: String?,
    val selected: Int = 1,          // 1 = đang học, 0 = bỏ qua
    val createdAt: String,
    val count: Int = 0,       // số lần người dùng đã ôn lại từ này
    val score: Int = 0,      // điểm thuộc từ: +1 khi trả lời đúng, -1 khi sai
)

// ─────────────────────────────────────────────────────────────────────────────
// 2. Database  (users.db — cùng thư mục với readings.db / dict.db)
// ─────────────────────────────────────────────────────────────────────────────

class UserDatabase private constructor(context: Context) {

    val db: SQLiteDatabase

    init {
        db = Helper(context.applicationContext).writableDatabase
        Log.d("UserDB", "DB path: ${db.path}")
    }

    // ── SQLiteOpenHelper tự tạo + migrate schema ──────────────────────────────
    private class Helper(context: Context) :
        SQLiteOpenHelper(context, "users.db", null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS users (
                    user_id   INTEGER PRIMARY KEY DEFAULT 0
                )
                """.trimIndent()
            )
            // Đảm bảo luôn tồn tại dòng user mặc định (user_id = 0)
            db.execSQL("INSERT OR IGNORE INTO users (user_id) VALUES (0)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS user_vocabulary (
                    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id            INTEGER NOT NULL DEFAULT 0,
                    source_sentence_id INTEGER,
                    source_word_id     INTEGER,
                    source_phrase_id   INTEGER,
                    text_en            TEXT,
                    text_vi            TEXT,
                    selected           INTEGER NOT NULL DEFAULT 1,
                    created_at         TEXT NOT NULL,
                    count       INTEGER NOT NULL DEFAULT 0,
                    score      INTEGER NOT NULL DEFAULT 0,
                    FOREIGN KEY (user_id) REFERENCES users(user_id)
                )
                """.trimIndent()
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Chỉ thêm cột mới nếu cần — không DROP TABLE để giữ dữ liệu người dùng
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE user_vocabulary ADD COLUMN count  INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE user_vocabulary ADD COLUMN score INTEGER NOT NULL DEFAULT 0")
            }
        }

        companion object { const val DB_VERSION = 2 }
    }

    // ── Lưu 1 từ vào user_vocabulary ─────────────────────────────────────────
    fun saveWord(entry: UserVocabularyEntry): Boolean {
        return try {
            val cv = ContentValues().apply {
                put("user_id",            entry.userId)
                put("source_sentence_id", entry.sourceSentenceId)
                put("source_word_id",     entry.sourceWordId)
                put("source_phrase_id",   entry.sourcePhraseId)
                put("text_en",            entry.textEn)
                put("text_vi",            entry.textVi)
                put("selected",           entry.selected)
                put("created_at",         entry.createdAt)
                put("count",       entry.count)
                put("score",      entry.score)
            }
            val rowId = db.insert("user_vocabulary", null, cv)
            Log.d("UserDB", "saveWord: \"${entry.textEn}\" → rowId=$rowId")
            rowId != -1L
        } catch (e: Exception) {
            Log.e("UserDB", "saveWord error", e)
            false
        }
    }

    // ── Kiểm tra từ đã được lưu chưa (theo source_word_id) ───────────────────
    fun isWordSaved(wordId: Int): Boolean {
        val cursor = db.rawQuery(
            "SELECT 1 FROM user_vocabulary WHERE source_word_id = ? LIMIT 1",
            arrayOf(wordId.toString())
        )
        return cursor.use { it.moveToFirst() }
    }

    /** Đường dẫn thật của file users.db trên thiết bị — dùng để debug */
    val dbPath: String get() = db.path

    companion object {
        @Volatile private var INSTANCE: UserDatabase? = null

        fun getInstance(context: Context): UserDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: UserDatabase(context.applicationContext).also { INSTANCE = it }
            }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 3. Composable nút "Lưu từ" — dùng trong WordPopup
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Nút lưu từ vào users.db.
 *
 * Cách dùng trong WordPopup.kt — thêm vào cuối Column (trước dấu đóng `}`):
 *
 *     HorizontalDivider()
 *     SaveWordButton(word = word, phrase = phrase)
 *
 * @param word   Từ đang hiển thị trong popup
 * @param phrase Cụm từ chứa từ đó (có thể null)
 */
@Composable
fun SaveWordButton(
    word: SentenceWord,
    phrase: SentencePhrase?,
) {
    val context = LocalContext.current
    val userDb  = remember { UserDatabase.getInstance(context) }

    // Kiểm tra trạng thái lưu ngay khi popup mở
    var isSaved by remember(word.wordId) {
        mutableStateOf(userDb.isWordSaved(word.wordId))
    }

    TextButton(onClick = {
        if (!isSaved) {
            val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(Date())
            val entry = UserVocabularyEntry(
                userId           = 0,
                sourceSentenceId = word.sentenceId,
                sourceWordId     = word.wordId,
                sourcePhraseId   = phrase?.phraseId,
                textEn           = word.textEn,
                textVi           = word.textVi,
                selected         = 1,
                createdAt        = now,
            )
            isSaved = userDb.saveWord(entry)
        }
    }) {
        Text(if (isSaved) "✓ Đã lưu" else "Lưu từ")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// HƯỚNG DẪN THAY ĐỔI TỐI THIỂU Ở CÁC FILE KHÁC
// ─────────────────────────────────────────────────────────────────────────────
//
// ── WordPopup.kt ─────────────────────────────────────────────────────────────
// Thêm import:
//   import com.eleap.eleap.feature.reading.ui.SaveWordButton
//
// Trong Column { ... } của WordPopup, thêm vào SAU khối "// ── Từ điển ──":
//
//     // ── Lưu từ ───────────────────────────────────────────────────────────
//     HorizontalDivider()
//     SaveWordButton(word = word, phrase = phrase)
//
// Không cần thêm tham số mới vào WordPopup — word và phrase đã có sẵn.
//
// ─────────────────────────────────────────────────────────────────────────────
// Không cần thay đổi ReadingViewModel, ReadingScreen, hay bất kỳ file nào khác.
// ─────────────────────────────────────────────────────────────────────────────