// SaveWord.kt
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
import com.eleap.eleap.feature.reading.data.ReadingSentence
import com.eleap.eleap.feature.reading.ReadingViewModel
import java.text.SimpleDateFormat
import java.util.*

// ─────────────────────────────────────────────────────────────────────────────
// 1. Entity
// ─────────────────────────────────────────────────────────────────────────────

data class UserVocabularyEntry(
    val id: String = "",
    val userId: Int = 0,
    val sourceSentenceId: String?,
    val sourceWordId: String?,
    val sourcePhraseId: String?,
    val textEn: String?,
    val textVi: String?,
    val selected: Int = 1,
    val createdAt: String,
    val count: Int = 0,
    val score: Int = 0,
    // ── Snapshot ngữ cảnh tại thời điểm lưu — dùng để hiện trong VocabPopup ──
    val phraseTextEn: String? = null,
    val phraseTextVi: String? = null,
    val sentenceTextEn: String? = null,
    val sentenceTextVi: String? = null,
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
    // 48-bit timestamp (ms since epoch)
    buffer.put((timestamp shr 40).toByte())
    buffer.put((timestamp shr 32).toByte())
    buffer.put((timestamp shr 24).toByte())
    buffer.put((timestamp shr 16).toByte())
    buffer.put((timestamp shr 8).toByte())
    buffer.put(timestamp.toByte())
    // 4-bit version (0111) + 12-bit random
    buffer.put((0x70 or (randomBytes[0].toInt() and 0x0F)).toByte())
    buffer.put(randomBytes[1])
    // 2-bit variant (10) + 62-bit random
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

// ─────────────────────────────────────────────────────────────────────────────
// 2. Database
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
                    user_id   INTEGER PRIMARY KEY DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL("INSERT OR IGNORE INTO users (user_id) VALUES (0)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS user_vocabulary (
                    id                 TEXT PRIMARY KEY,
                    user_id            INTEGER NOT NULL DEFAULT 0,
                    source_sentence_id TEXT,
                    source_word_id     TEXT,
                    source_phrase_id   TEXT,
                    text_en            TEXT,
                    text_vi            TEXT,
                    selected           INTEGER NOT NULL DEFAULT 1,
                    created_at         TEXT NOT NULL,
                    count       INTEGER NOT NULL DEFAULT 0,
                    score      INTEGER NOT NULL DEFAULT 0,
                    phrase_text_en     TEXT,
                    phrase_text_vi     TEXT,
                    sentence_text_en   TEXT,
                    sentence_text_vi   TEXT,
                    FOREIGN KEY (user_id) REFERENCES users(user_id)
                )
                """.trimIndent()
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE user_vocabulary ADD COLUMN count  INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE user_vocabulary ADD COLUMN score INTEGER NOT NULL DEFAULT 0")
            }
            if (oldVersion < 3) {
                db.execSQL("ALTER TABLE user_vocabulary ADD COLUMN phrase_text_en   TEXT")
                db.execSQL("ALTER TABLE user_vocabulary ADD COLUMN phrase_text_vi   TEXT")
                db.execSQL("ALTER TABLE user_vocabulary ADD COLUMN sentence_text_en TEXT")
                db.execSQL("ALTER TABLE user_vocabulary ADD COLUMN sentence_text_vi TEXT")
            }
            if (oldVersion < 4) {
                // readings.db đã đổi toàn bộ id sang uuid v7 → id kiểu INTEGER cũ của
                // user_vocabulary (và các source_*_id trỏ vào readings.db) không còn
                // hợp lệ. SQLite không ALTER được kiểu cột / kiểu PK, nên phải dựng lại
                // bảng: giữ nguyên nội dung đã lưu (text_en, text_vi, các snapshot...),
                // gán id mới (uuid v7) cho từng dòng, và xoá các source_*_id cũ (vì
                // trỏ vào id không còn tồn tại trong readings.db mới).
                db.execSQL("ALTER TABLE user_vocabulary RENAME TO user_vocabulary_old")
                db.execSQL(
                    """
                    CREATE TABLE user_vocabulary (
                        id                 TEXT PRIMARY KEY,
                        user_id            INTEGER NOT NULL DEFAULT 0,
                        source_sentence_id TEXT,
                        source_word_id     TEXT,
                        source_phrase_id   TEXT,
                        text_en            TEXT,
                        text_vi            TEXT,
                        selected           INTEGER NOT NULL DEFAULT 1,
                        created_at         TEXT NOT NULL,
                        count       INTEGER NOT NULL DEFAULT 0,
                        score      INTEGER NOT NULL DEFAULT 0,
                        phrase_text_en     TEXT,
                        phrase_text_vi     TEXT,
                        sentence_text_en   TEXT,
                        sentence_text_vi   TEXT,
                        FOREIGN KEY (user_id) REFERENCES users(user_id)
                    )
                    """.trimIndent()
                )
                val cursor = db.rawQuery("SELECT * FROM user_vocabulary_old", null)
                cursor.use {
                    while (it.moveToNext()) {
                        val cv = ContentValues().apply {
                            put("id", generateUuidV7())
                            put("user_id", it.getInt(it.getColumnIndexOrThrow("user_id")))
                            // source_sentence_id / source_word_id / source_phrase_id: bỏ qua
                            // (id cũ không map được sang uuid mới)
                            put("text_en", it.getString(it.getColumnIndexOrThrow("text_en")))
                            put("text_vi", it.getString(it.getColumnIndexOrThrow("text_vi")))
                            put("selected", it.getInt(it.getColumnIndexOrThrow("selected")))
                            put("created_at", it.getString(it.getColumnIndexOrThrow("created_at")))
                            put("count", it.getInt(it.getColumnIndexOrThrow("count")))
                            put("score", it.getInt(it.getColumnIndexOrThrow("score")))
                            put("phrase_text_en", it.getString(it.getColumnIndexOrThrow("phrase_text_en")))
                            put("phrase_text_vi", it.getString(it.getColumnIndexOrThrow("phrase_text_vi")))
                            put("sentence_text_en", it.getString(it.getColumnIndexOrThrow("sentence_text_en")))
                            put("sentence_text_vi", it.getString(it.getColumnIndexOrThrow("sentence_text_vi")))
                        }
                        db.insert("user_vocabulary", null, cv)
                    }
                }
                db.execSQL("DROP TABLE user_vocabulary_old")
            }
        }

        companion object { const val DB_VERSION = 4 }
    }

    fun saveWord(entry: UserVocabularyEntry): Boolean {
        return try {
            val id = entry.id.ifBlank { generateUuidV7() }
            val cv = ContentValues().apply {
                put("id",                 id)
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
                put("phrase_text_en",     entry.phraseTextEn)
                put("phrase_text_vi",     entry.phraseTextVi)
                put("sentence_text_en",   entry.sentenceTextEn)
                put("sentence_text_vi",   entry.sentenceTextVi)
            }
            val rowId = db.insert("user_vocabulary", null, cv)
            Log.d("UserDB", "saveWord: \"${entry.textEn}\" → rowId=$rowId")
            rowId != -1L
        } catch (e: Exception) {
            Log.e("UserDB", "saveWord error", e)
            false
        }
    }

    fun isWordSaved(wordId: String): Boolean {
        val cursor = db.rawQuery(
            "SELECT 1 FROM user_vocabulary WHERE source_word_id = ? LIMIT 1",
            arrayOf(wordId)
        )
        return cursor.use { it.moveToFirst() }
    }

    fun getAllVocabulary(userId: Int = 0): List<UserVocabularyEntry> {
        val list = mutableListOf<UserVocabularyEntry>()
        val cursor = db.rawQuery(
            "SELECT * FROM user_vocabulary WHERE user_id = ? ORDER BY created_at DESC",
            arrayOf(userId.toString())
        )
        cursor.use {
            while (it.moveToNext()) {
                fun nullableString(col: String): String? {
                    val idx = it.getColumnIndexOrThrow(col)
                    return if (it.isNull(idx)) null else it.getString(idx)
                }
                list.add(
                    UserVocabularyEntry(
                        id                = it.getString(it.getColumnIndexOrThrow("id")),
                        userId            = it.getInt(it.getColumnIndexOrThrow("user_id")),
                        sourceSentenceId  = nullableString("source_sentence_id"),
                        sourceWordId      = nullableString("source_word_id"),
                        sourcePhraseId    = nullableString("source_phrase_id"),
                        textEn            = it.getString(it.getColumnIndexOrThrow("text_en")),
                        textVi            = it.getString(it.getColumnIndexOrThrow("text_vi")),
                        selected          = it.getInt(it.getColumnIndexOrThrow("selected")),
                        createdAt         = it.getString(it.getColumnIndexOrThrow("created_at")),
                        count             = it.getInt(it.getColumnIndexOrThrow("count")),
                        score             = it.getInt(it.getColumnIndexOrThrow("score")),
                        phraseTextEn      = nullableString("phrase_text_en"),
                        phraseTextVi      = nullableString("phrase_text_vi"),
                        sentenceTextEn    = nullableString("sentence_text_en"),
                        sentenceTextVi    = nullableString("sentence_text_vi"),
                    )
                )
            }
        }
        return list
    }

    fun deleteWord(id: String): Boolean {
        return try {
            db.delete("user_vocabulary", "id = ?", arrayOf(id)) > 0
        } catch (e: Exception) {
            Log.e("UserDB", "deleteWord error", e)
            false
        }
    }

    fun unsaveWord(wordId: String): Boolean {
        return try {
            val rows = db.delete("user_vocabulary", "source_word_id = ?", arrayOf(wordId))
            Log.d("UserDB", "unsaveWord: wordId=$wordId → $rows row(s) deleted")
            rows > 0
        } catch (e: Exception) {
            Log.e("UserDB", "unsaveWord error", e)
            false
        }
    }

    fun getAllSavedWordIds(): Set<String> {
        val set = mutableSetOf<String>()
        val cursor = db.rawQuery(
            "SELECT source_word_id FROM user_vocabulary WHERE source_word_id IS NOT NULL",
            null
        )
        cursor.use {
            while (it.moveToNext()) {
                set.add(it.getString(0))
            }
        }
        return set
    }

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
// 3. SaveWordButton
// ─────────────────────────────────────────────────────────────────────────────

// ── Tự lấy text_en/text_vi của câu chứa word, giống hệt cách `phrase` đang ──
// lấy dữ liệu: tìm trong danh sách `sentences` đã có sẵn trong RAM của
// ReadingViewModel (đã load khi mở bài đọc), KHÔNG query lại DB.
// Dùng khi caller không truyền sẵn `sentence` — tránh phải sửa WordPopup.kt
// hay bất kỳ nơi nào khác đang gọi SaveWordButton.
private fun findSentenceTexts(context: Context, sentenceId: String): Pair<String?, String?>? {
    return try {
        val readingVm = ReadingViewModel.Factory(context).create(ReadingViewModel::class.java)
        readingVm.sentences.value
            .find { it.sentenceId == sentenceId }
            ?.let { it.textEn to it.textVi }
    } catch (e: Exception) {
        Log.e("SaveWordButton", "findSentenceTexts error", e)
        null
    }
}

/**
 * @param word               Từ đang hiển thị trong popup
 * @param phrase             Cụm từ chứa từ đó (có thể null)
 * @param sentence           Câu chứa từ đó (có thể null) — nếu không truyền,
 *                           sẽ tự lấy từ readings.db theo word.sentenceId
 * @param onSaveStateChanged Callback gọi sau khi lưu hoặc bỏ lưu thành công —
 *                           dùng để ViewModel refresh savedWordIds → màu từ đổi ngay
 */
@Composable
fun SaveWordButton(
    word: SentenceWord,
    phrase: SentencePhrase?,
    sentence: ReadingSentence? = null,     // ← mặc định null, ưu tiên dùng nếu có
    onSaveStateChanged: () -> Unit = {},   // ← mới, mặc định rỗng để không break code cũ
) {
    val context = LocalContext.current
    val userDb  = remember { UserDatabase.getInstance(context) }

    var isSaved by remember(word.wordId) {
        mutableStateOf(userDb.isWordSaved(word.wordId))
    }

    TextButton(onClick = {
        if (isSaved) {
            val removed = userDb.unsaveWord(word.wordId)
            if (removed) {
                isSaved = false
                onSaveStateChanged()   // ← notify ViewModel
            }
        } else {
            val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date())
            // Ưu tiên sentence được truyền sẵn; nếu không có thì tự tìm trong
            // sentences (RAM) của ReadingViewModel theo word.sentenceId —
            // giống hệt cách phrase đang lấy dữ liệu, không cần sửa nơi gọi.
            val sentenceTexts = sentence?.let { it.textEn to it.textVi }
                ?: findSentenceTexts(context, word.sentenceId)
            val entry = UserVocabularyEntry(
                userId           = 0,
                sourceSentenceId = word.sentenceId,
                sourceWordId     = word.wordId,
                sourcePhraseId   = phrase?.phraseId,
                textEn           = word.textEn,
                textVi           = word.textVi,
                selected         = 1,
                createdAt        = now,
                phraseTextEn     = phrase?.textEn,
                phraseTextVi     = phrase?.textVi,
                sentenceTextEn   = sentenceTexts?.first,
                sentenceTextVi   = sentenceTexts?.second,
            )
            val saved = userDb.saveWord(entry)
            if (saved) {
                isSaved = true
                onSaveStateChanged()   // ← notify ViewModel
            }
        }
    }) {
        Text(if (isSaved) "Bỏ lưu" else "Lưu từ")
    }
}