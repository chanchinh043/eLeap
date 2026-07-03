// VocabRepository.kt
// Đặt tại: com/eleap/eleap/feature/vocab/data/VocabRepository.kt
package com.eleap.eleap.feature.vocab.data

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.eleap.eleap.core.auth.CurrentUser
import com.eleap.eleap.feature.myreading.data.MyReadingRepository
import com.eleap.eleap.feature.reading.ui.UserDatabase
import com.eleap.eleap.feature.reading.ui.UserVocabularyEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class VocabDictEntry(
    val word: String,
    val ipa: String?,
    val ipaVi: String?,
    val meaning: String?,
    val shortMeaning: String?,
)

class VocabRepository private constructor(
    private val userDb: UserDatabase,
    private val dictDb: SQLiteDatabase,
    private val readingsDb: SQLiteDatabase,
    private val myReadingRepository: MyReadingRepository,
) {
    private val dictCache = mutableMapOf<String, VocabDictEntry>()

    // ══ users.db ══════════════════════════════════════════════════════════════

    suspend fun getAllVocabulary(userId: String = CurrentUser.userId.value): List<UserVocabularyEntry> =
        withContext(Dispatchers.IO) { userDb.getAllVocabulary(userId) }

    // userId mặc định lấy CurrentUser.userId.value TẠI THỜI ĐIỂM GỌI (không phải
    // lúc khai báo default param) — do Kotlin luôn evaluate default param mỗi lần
    // hàm được gọi mà không truyền đối số, nên vẫn an toàn khi user đổi tài khoản.
    // Thêm "AND user_id = ?" để id (dù là UUID) không thể vô tình xoá/sửa
    // nhầm dòng của user khác.
    // Lưu ý: đổi từ userDb.deleteWord(id) (hàm có sẵn trong class UserDatabase,
    // định nghĩa trong SaveWord.kt) sang xoá trực tiếp qua userDb.db kèm điều
    // kiện user_id — không cần đụng tới SaveWord.kt. Nếu UserDatabase.deleteWord()
    // còn được gọi ở nơi khác trong code, nên sửa luôn bên đó theo cùng cách này.
    suspend fun deleteWord(id: String, userId: String = CurrentUser.userId.value): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val rows = userDb.db.delete(
                    "user_vocabulary", "id = ? AND user_id = ?", arrayOf(id, userId)
                )
                rows > 0
            } catch (e: Exception) {
                Log.e("VocabRepository", "deleteWord error", e)
                false
            }
        }

    suspend fun incrementCount(id: String, userId: String = CurrentUser.userId.value) = withContext(Dispatchers.IO) {
        try {
            userDb.db.execSQL(
                "UPDATE user_vocabulary SET count = count + 1 WHERE id = ? AND user_id = ?",
                arrayOf(id, userId)
            )
        } catch (e: Exception) {
            Log.e("VocabRepository", "incrementCount error", e)
        }
    }

    suspend fun updateSelected(
        id: String,
        selected: Int,
        userId: String = CurrentUser.userId.value,
    ): Boolean =
        withContext(Dispatchers.IO) {
            return@withContext try {
                val cv = ContentValues().apply { put("selected", selected) }
                val rows = userDb.db.update(
                    "user_vocabulary", cv, "id = ? AND user_id = ?", arrayOf(id, userId)
                )
                rows > 0
            } catch (e: Exception) {
                Log.e("VocabRepository", "updateSelected error", e)
                false
            }
        }

    /**
     * Chuyển toàn bộ từ vựng đã lưu lúc còn là guest (user_id = "guest")
     * sang user thật vừa đăng nhập. Gọi sau khi CurrentUser.setUser() phát
     * hiện lượt đăng nhập đầu tiên (guest → user thật) và người dùng chọn
     * "Có" ở dialog migrate tại MainScreen. Trả về số dòng đã chuyển.
     *
     * Ghi trực tiếp qua userDb.db (bypass UserDatabase) — cùng cách các hàm
     * deleteWord/incrementCount/updateSelected ở trên đang làm, vì
     * UserDatabase chưa có sẵn hàm migrateOwnership().
     */
    suspend fun migrateGuestDataTo(newUserId: String): Int =
        withContext(Dispatchers.IO) {
            try {
                val cv = ContentValues().apply { put("user_id", newUserId) }
                userDb.db.update(
                    "user_vocabulary", cv, "user_id = ?", arrayOf(CurrentUser.GUEST_ID)
                )
            } catch (e: Exception) {
                Log.e("VocabRepository", "migrateGuestDataTo error", e)
                0
            }
        }

    suspend fun getSelectedVocabulary(userId: String = CurrentUser.userId.value): List<UserVocabularyEntry> =
        withContext(Dispatchers.IO) {
            val list = mutableListOf<UserVocabularyEntry>()
            val cursor = userDb.db.rawQuery(
                "SELECT * FROM user_vocabulary WHERE user_id = ? AND selected = 1 ORDER BY created_at DESC",
                arrayOf(userId)
            )
            cursor.use {
                while (it.moveToNext()) {
                    fun nullableString(col: String): String? {
                        val idx = it.getColumnIndexOrThrow(col)
                        return if (it.isNull(idx)) null else it.getString(idx)
                    }
                    list.add(
                        UserVocabularyEntry(
                            id               = it.getString(it.getColumnIndexOrThrow("id")),
                            userId           = it.getString(it.getColumnIndexOrThrow("user_id")),
                            sourceSentenceId = nullableString("source_sentence_id"),
                            sourceWordId     = nullableString("source_word_id"),
                            sourcePhraseId   = nullableString("source_phrase_id"),
                            textEn           = it.getString(it.getColumnIndexOrThrow("text_en")),
                            textVi           = it.getString(it.getColumnIndexOrThrow("text_vi")),
                            selected         = it.getInt(it.getColumnIndexOrThrow("selected")),
                            createdAt        = it.getString(it.getColumnIndexOrThrow("created_at")),
                            count            = it.getInt(it.getColumnIndexOrThrow("count")),
                            score            = it.getInt(it.getColumnIndexOrThrow("score")),
                            phraseTextEn     = nullableString("phrase_text_en"),
                            phraseTextVi     = nullableString("phrase_text_vi"),
                            sentenceTextEn   = nullableString("sentence_text_en"),
                            sentenceTextVi   = nullableString("sentence_text_vi"),
                        )
                    )
                }
            }
            list
        }

    suspend fun getVocabByReadingId(
        readingId: String,
        userId: String = CurrentUser.userId.value
    ): List<UserVocabularyEntry> =
        withContext(Dispatchers.IO) {
            val sentenceIds = mutableListOf<String>()
            val cursor = readingsDb.rawQuery(
                "SELECT sentence_id FROM reading_sentences WHERE reading_id = ?",
                arrayOf(readingId)
            )
            cursor.use {
                while (it.moveToNext()) {
                    sentenceIds.add(it.getString(0))
                }
            }

            // ── Bài "Bài đọc của tôi" (MyReading): reading_id không tồn tại
            //    trong readings.db → sentenceIds rỗng ở trên. Fallback sang
            //    myreading.db (nơi thực sự chứa reading_sentences của bài này).
            if (sentenceIds.isEmpty()) {
                sentenceIds.addAll(myReadingRepository.getSentenceIds(readingId))
            }

            if (sentenceIds.isEmpty()) return@withContext emptyList()

            val placeholders = sentenceIds.joinToString(",") { "?" }
            val args = (listOf(userId) + sentenceIds).toTypedArray()
            val list = mutableListOf<UserVocabularyEntry>()
            val vocabCursor = userDb.db.rawQuery(
                """SELECT * FROM user_vocabulary
                   WHERE user_id = ?
                   AND source_sentence_id IN ($placeholders)
                   ORDER BY created_at DESC""",
                args
            )
            vocabCursor.use {
                while (it.moveToNext()) {
                    fun nullableString(col: String): String? {
                        val idx = it.getColumnIndexOrThrow(col)
                        return if (it.isNull(idx)) null else it.getString(idx)
                    }
                    list.add(
                        UserVocabularyEntry(
                            id               = it.getString(it.getColumnIndexOrThrow("id")),
                            userId           = it.getString(it.getColumnIndexOrThrow("user_id")),
                            sourceSentenceId = nullableString("source_sentence_id"),
                            sourceWordId     = nullableString("source_word_id"),
                            sourcePhraseId   = nullableString("source_phrase_id"),
                            textEn           = it.getString(it.getColumnIndexOrThrow("text_en")),
                            textVi           = it.getString(it.getColumnIndexOrThrow("text_vi")),
                            selected         = it.getInt(it.getColumnIndexOrThrow("selected")),
                            createdAt        = it.getString(it.getColumnIndexOrThrow("created_at")),
                            count            = it.getInt(it.getColumnIndexOrThrow("count")),
                            score            = it.getInt(it.getColumnIndexOrThrow("score")),
                            phraseTextEn     = nullableString("phrase_text_en"),
                            phraseTextVi     = nullableString("phrase_text_vi"),
                            sentenceTextEn   = nullableString("sentence_text_en"),
                            sentenceTextVi   = nullableString("sentence_text_vi"),
                        )
                    )
                }
            }
            list
        }

    suspend fun preloadDict(words: List<String>) = withContext(Dispatchers.IO) {
        val keysToLoad = words
            .mapNotNull { normalizeWord(it) }
            .distinct()
            .filterNot { dictCache.containsKey(it) }

        if (keysToLoad.isEmpty()) return@withContext

        keysToLoad.chunked(500).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            val cursor = dictDb.rawQuery(
                "SELECT * FROM dict WHERE word IN ($placeholders)",
                chunk.toTypedArray()
            )
            cursor.use {
                while (it.moveToNext()) {
                    val entry = VocabDictEntry(
                        word         = it.getString(it.getColumnIndexOrThrow("word")),
                        ipa          = it.getString(it.getColumnIndexOrThrow("ipa")),
                        ipaVi        = it.getString(it.getColumnIndexOrThrow("ipa_vi")),
                        meaning      = it.getString(it.getColumnIndexOrThrow("meaning")),
                        shortMeaning = it.getString(it.getColumnIndexOrThrow("short_meaning")),
                    )
                    dictCache[entry.word] = entry
                }
            }
        }
        Log.d("VocabRepository", "preloaded ${keysToLoad.size} dict entries into RAM")
    }

    suspend fun getDictEntry(textEn: String?): VocabDictEntry? =
        withContext(Dispatchers.IO) {
            val key = normalizeWord(textEn) ?: return@withContext null
            dictCache[key]?.let { return@withContext it }
            val cursor = dictDb.rawQuery(
                "SELECT * FROM dict WHERE word = ? LIMIT 1", arrayOf(key)
            )
            cursor.use {
                if (it.moveToFirst()) {
                    VocabDictEntry(
                        word         = it.getString(it.getColumnIndexOrThrow("word")),
                        ipa          = it.getString(it.getColumnIndexOrThrow("ipa")),
                        ipaVi        = it.getString(it.getColumnIndexOrThrow("ipa_vi")),
                        meaning      = it.getString(it.getColumnIndexOrThrow("meaning")),
                        shortMeaning = it.getString(it.getColumnIndexOrThrow("short_meaning")),
                    ).also { entry -> dictCache[key] = entry }
                } else null
            }
        }

    private fun normalizeWord(text: String?): String? =
        text?.trim()?.lowercase()
            ?.replace(Regex("^[^a-z']+|[^a-z']+$"), "")
            ?.ifEmpty { null }

    companion object {
        @Volatile private var INSTANCE: VocabRepository? = null

        fun getInstance(context: Context): VocabRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val userDb     = UserDatabase.getInstance(context)
                    val dictDb     = openDictDb(context.applicationContext)
                    val readingsDb = openReadingsDb(context.applicationContext)
                    // Dùng lại singleton — cùng instance với ReadingViewModel/AddMyReadingScreen,
                    // tránh mở thêm SQLiteOpenHelper thứ 2 cho myreading.db.
                    val myRepo     = MyReadingRepository.getInstance(context.applicationContext)
                    VocabRepository(userDb, dictDb, readingsDb, myRepo).also { INSTANCE = it }
                }
            }

        private fun openReadingsDb(context: Context): SQLiteDatabase {
            val dbFile = File(context.getDatabasePath("readings.db").absolutePath)
            if (!dbFile.exists()) {
                dbFile.parentFile?.mkdirs()
                context.assets.open("databases/readings.db").use { input ->
                    FileOutputStream(dbFile).use { output -> input.copyTo(output) }
                }
            }
            return SQLiteDatabase.openDatabase(
                dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY
            )
        }

        private fun openDictDb(context: Context): SQLiteDatabase {
            val dbFile = File(context.getDatabasePath("dict.db").absolutePath)
            if (!dbFile.exists()) {
                dbFile.parentFile?.mkdirs()
                context.assets.open("databases/dict.db").use { input ->
                    FileOutputStream(dbFile).use { output -> input.copyTo(output) }
                }
            }
            return SQLiteDatabase.openDatabase(
                dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY
            )
        }
    }
}