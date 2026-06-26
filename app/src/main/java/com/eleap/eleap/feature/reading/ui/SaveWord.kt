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
import java.text.SimpleDateFormat
import java.util.*

// ─────────────────────────────────────────────────────────────────────────────
// 1. Entity
// ─────────────────────────────────────────────────────────────────────────────

data class UserVocabularyEntry(
    val id: Int = 0,
    val userId: Int = 0,
    val sourceSentenceId: Int?,
    val sourceWordId: Int?,
    val sourcePhraseId: Int?,
    val textEn: String?,
    val textVi: String?,
    val selected: Int = 1,
    val createdAt: String,
    val count: Int = 0,
    val score: Int = 0,
)

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
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE user_vocabulary ADD COLUMN count  INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE user_vocabulary ADD COLUMN score INTEGER NOT NULL DEFAULT 0")
            }
        }

        companion object { const val DB_VERSION = 2 }
    }

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

    fun isWordSaved(wordId: Int): Boolean {
        val cursor = db.rawQuery(
            "SELECT 1 FROM user_vocabulary WHERE source_word_id = ? LIMIT 1",
            arrayOf(wordId.toString())
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
                fun nullableInt(col: String): Int? {
                    val idx = it.getColumnIndexOrThrow(col)
                    return if (it.isNull(idx)) null else it.getInt(idx)
                }
                list.add(
                    UserVocabularyEntry(
                        id                = it.getInt(it.getColumnIndexOrThrow("id")),
                        userId            = it.getInt(it.getColumnIndexOrThrow("user_id")),
                        sourceSentenceId  = nullableInt("source_sentence_id"),
                        sourceWordId      = nullableInt("source_word_id"),
                        sourcePhraseId    = nullableInt("source_phrase_id"),
                        textEn            = it.getString(it.getColumnIndexOrThrow("text_en")),
                        textVi            = it.getString(it.getColumnIndexOrThrow("text_vi")),
                        selected          = it.getInt(it.getColumnIndexOrThrow("selected")),
                        createdAt         = it.getString(it.getColumnIndexOrThrow("created_at")),
                        count             = it.getInt(it.getColumnIndexOrThrow("count")),
                        score             = it.getInt(it.getColumnIndexOrThrow("score")),
                    )
                )
            }
        }
        return list
    }

    fun deleteWord(id: Int): Boolean {
        return try {
            db.delete("user_vocabulary", "id = ?", arrayOf(id.toString())) > 0
        } catch (e: Exception) {
            Log.e("UserDB", "deleteWord error", e)
            false
        }
    }

    fun unsaveWord(wordId: Int): Boolean {
        return try {
            val rows = db.delete("user_vocabulary", "source_word_id = ?", arrayOf(wordId.toString()))
            Log.d("UserDB", "unsaveWord: wordId=$wordId → $rows row(s) deleted")
            rows > 0
        } catch (e: Exception) {
            Log.e("UserDB", "unsaveWord error", e)
            false
        }
    }

    fun getAllSavedWordIds(): Set<Int> {
        val set = mutableSetOf<Int>()
        val cursor = db.rawQuery(
            "SELECT source_word_id FROM user_vocabulary WHERE source_word_id IS NOT NULL",
            null
        )
        cursor.use {
            while (it.moveToNext()) {
                set.add(it.getInt(0))
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

/**
 * @param word               Từ đang hiển thị trong popup
 * @param phrase             Cụm từ chứa từ đó (có thể null)
 * @param onSaveStateChanged Callback gọi sau khi lưu hoặc bỏ lưu thành công —
 *                           dùng để ViewModel refresh savedWordIds → màu từ đổi ngay
 */
@Composable
fun SaveWordButton(
    word: SentenceWord,
    phrase: SentencePhrase?,
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