// VocabRepository.kt
// Đặt tại: com/eleap/eleap/feature/vocab/data/VocabRepository.kt
package com.eleap.eleap.feature.vocab.data

import android.content.Context
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.eleap.eleap.core.auth.CurrentUser
import com.eleap.eleap.feature.myreading.data.MyReadingRepository
// UserDatabase, UserVocabularyEntry, nowUtcIso, generateUuidV7, SyncStatus giờ
// cùng package (định nghĩa ở VocabDatabase.kt) — không cần import chéo nữa.
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

    // ══ users.db — bảng user_vocabulary ═══════════════════════════════════════
    // ĐÂY LÀ NƠI DUY NHẤT được ghi/đọc bảng user_vocabulary trong toàn app.
    // SaveWordButton, VocabViewModel, ReadingViewModel đều gọi vào đây.

    private fun nullableString(cursor: Cursor, col: String): String? {
        val idx = cursor.getColumnIndexOrThrow(col)
        return if (cursor.isNull(idx)) null else cursor.getString(idx)
    }

    // Parse 1 dòng cursor → UserVocabularyEntry — dùng chung cho mọi hàm SELECT
    // bên dưới để không lặp lại code 3-4 lần như trước.
    private fun entryFromCursor(it: Cursor): UserVocabularyEntry = UserVocabularyEntry(
        // Định danh
        id               = it.getString(it.getColumnIndexOrThrow("id")),
        userId           = it.getString(it.getColumnIndexOrThrow("user_id")),

        // Nguồn gốc từ (sentence/word/phrase)
        sourceSentenceId = nullableString(it, "source_sentence_id"),
        sourceWordId     = nullableString(it, "source_word_id"),
        sourcePhraseId   = nullableString(it, "source_phrase_id"),

        // Nội dung
        textEn           = it.getString(it.getColumnIndexOrThrow("text_en")),
        textVi           = it.getString(it.getColumnIndexOrThrow("text_vi")),
        phraseTextEn     = nullableString(it, "phrase_text_en"),
        phraseTextVi     = nullableString(it, "phrase_text_vi"),
        sentenceTextEn   = nullableString(it, "sentence_text_en"),
        sentenceTextVi   = nullableString(it, "sentence_text_vi"),

        // Trạng thái học tập
        selected         = it.getInt(it.getColumnIndexOrThrow("selected")),
        count            = it.getInt(it.getColumnIndexOrThrow("count")),
        score            = it.getInt(it.getColumnIndexOrThrow("score")),

        // Sync metadata (đồng bộ với Supabase)
        createdAt        = it.getString(it.getColumnIndexOrThrow("created_at")),
        updatedAt        = nullableString(it, "updated_at"),
        deletedAt        = nullableString(it, "deleted_at"),
        syncStatus       = it.getString(it.getColumnIndexOrThrow("sync_status")),
    )

    // ── Lưu từ mới ────────────────────────────────────────────────────────
    // Nhận các trường NỘI DUNG thô (không nhận id/createdAt/updatedAt/syncStatus
    // từ ngoài) — Repository là nơi DUY NHẤT quyết định các cột sync này, để
    // UI (SaveWordButton) không phải biết/tự tay gán, tránh gõ sai giá trị.
    // Luôn là INSERT (dòng hoàn toàn mới) → sync_status = PENDING_CREATE,
    // vì server chưa hề biết đến dòng này.
    suspend fun saveWord(
        userId: String = CurrentUser.userId.value,
        sourceSentenceId: String?,
        sourceWordId: String?,
        sourcePhraseId: String?,
        textEn: String?,
        textVi: String?,
        phraseTextEn: String? = null,
        phraseTextVi: String? = null,
        sentenceTextEn: String? = null,
        sentenceTextVi: String? = null,
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val id  = generateUuidV7()
                val now = nowUtcIso()
                val cv = ContentValues().apply {
                    // Định danh
                    put("id", id)
                    put("user_id", userId)

                    // Nguồn gốc từ (sentence/word/phrase)
                    put("source_sentence_id", sourceSentenceId)
                    put("source_word_id", sourceWordId)
                    put("source_phrase_id", sourcePhraseId)

                    // Nội dung
                    put("text_en", textEn)
                    put("text_vi", textVi)
                    put("phrase_text_en", phraseTextEn)
                    put("phrase_text_vi", phraseTextVi)
                    put("sentence_text_en", sentenceTextEn)
                    put("sentence_text_vi", sentenceTextVi)

                    // Trạng thái học tập — giá trị khởi tạo mặc định
                    put("selected", 1)
                    put("count", 0)
                    put("score", 0)

                    // Sync metadata (đồng bộ với Supabase) — Repository tự sinh
                    put("created_at", now)
                    put("updated_at", now)
                    put("sync_status", SyncStatus.PENDING_CREATE)
                }
                val rowId = userDb.db.insert("user_vocabulary", null, cv)
                Log.d("VocabRepository", "saveWord: \"$textEn\" → rowId=$rowId")
                rowId != -1L
            } catch (e: Exception) {
                Log.e("VocabRepository", "saveWord error", e)
                false
            }
        }

    // ── Logic dùng chung cho unsaveWord() và deleteWord(): ──────────────────
    //    - Nếu dòng đang ở sync_status = PENDING_CREATE (chưa từng lên server)
    //      → xoá CỨNG luôn, vì server không có gì để "biết mà xoá theo".
    //    - Ngược lại (SYNCED, PENDING_UPDATE, hoặc kể cả PENDING_DELETE cũ)
    //      → SOFT DELETE (set deleted_at + sync_status = PENDING_DELETE) để
    //        lần sync tiếp theo phát hiện và xoá tương ứng trên server.
    //      Không hạ cấp từ PENDING_CREATE sang PENDING_UPDATE/DELETE bao giờ.
    private suspend fun deleteOrSoftDelete(
        whereClause: String,
        whereArgs: Array<String>,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Bước 1: đọc sync_status hiện tại của dòng (bỏ qua dòng đã xoá từ trước)
            val fullWhere = "$whereClause AND deleted_at IS NULL"
            val cursor = userDb.db.rawQuery(
                "SELECT sync_status FROM user_vocabulary WHERE $fullWhere LIMIT 1",
                whereArgs
            )
            val syncStatus = cursor.use { if (it.moveToFirst()) it.getString(0) else null }
                ?: return@withContext false   // không tìm thấy dòng nào phù hợp

            // Bước 2: rẽ nhánh theo sync_status
            if (syncStatus == SyncStatus.PENDING_CREATE) {
                val rows = userDb.db.delete("user_vocabulary", fullWhere, whereArgs)
                Log.d("VocabRepository", "deleteOrSoftDelete: pending_create → hard delete, $rows row(s)")
                rows > 0
            } else {
                val cv = ContentValues().apply {
                    put("deleted_at", nowUtcIso())
                    put("sync_status", SyncStatus.PENDING_DELETE)
                }
                val rows = userDb.db.update("user_vocabulary", cv, fullWhere, whereArgs)
                Log.d("VocabRepository", "deleteOrSoftDelete: $syncStatus → soft delete, $rows row(s)")
                rows > 0
            }
        } catch (e: Exception) {
            Log.e("VocabRepository", "deleteOrSoftDelete error", e)
            false
        }
    }

    // ── Bỏ lưu 1 từ theo source_word_id (gọi từ SaveWordButton khi đọc bài) ──
    suspend fun unsaveWord(wordId: String, userId: String = CurrentUser.userId.value): Boolean =
        deleteOrSoftDelete(
            whereClause = "source_word_id = ? AND user_id = ?",
            whereArgs   = arrayOf(wordId, userId),
        )

    // ── Kiểm tra 1 từ (theo source_word_id) đã lưu chưa — bỏ qua dòng đã xoá ──
    suspend fun isWordSaved(wordId: String, userId: String = CurrentUser.userId.value): Boolean =
        withContext(Dispatchers.IO) {
            val cursor = userDb.db.rawQuery(
                """SELECT 1 FROM user_vocabulary
                   WHERE source_word_id = ? AND user_id = ? AND deleted_at IS NULL LIMIT 1""",
                arrayOf(wordId, userId)
            )
            cursor.use { it.moveToFirst() }
        }

    // ── Toàn bộ word_id đã lưu (dùng cho highlight trong ReadingScreen) ─────
    suspend fun getAllSavedWordIds(userId: String = CurrentUser.userId.value): Set<String> =
        withContext(Dispatchers.IO) {
            val set = mutableSetOf<String>()
            val cursor = userDb.db.rawQuery(
                """SELECT source_word_id FROM user_vocabulary
                   WHERE source_word_id IS NOT NULL AND user_id = ? AND deleted_at IS NULL""",
                arrayOf(userId)
            )
            cursor.use { while (it.moveToNext()) set.add(it.getString(0)) }
            set
        }

    suspend fun getAllVocabulary(userId: String = CurrentUser.userId.value): List<UserVocabularyEntry> =
        withContext(Dispatchers.IO) {
            val list = mutableListOf<UserVocabularyEntry>()
            val cursor = userDb.db.rawQuery(
                "SELECT * FROM user_vocabulary WHERE user_id = ? AND deleted_at IS NULL ORDER BY created_at DESC",
                arrayOf(userId)
            )
            cursor.use { while (it.moveToNext()) list.add(entryFromCursor(it)) }
            list
        }

    // ── Xoá từ theo id (dùng ở VocabScreen/VocabReadingScreen) ──────────────
    //    Cùng logic pending_create→hard delete / khác→soft delete như
    //    unsaveWord(), chỉ khác khoá tra cứu: ở đây là "id" (khoá của chính
    //    dòng vocab entry) thay vì "source_word_id" (khoá của từ trong câu
    //    đang đọc).
    suspend fun deleteWord(id: String, userId: String = CurrentUser.userId.value): Boolean =
        deleteOrSoftDelete(
            whereClause = "id = ? AND user_id = ?",
            whereArgs   = arrayOf(id, userId),
        )

    // ── Tăng count mỗi lần từ xuất hiện khi quay flashcard ──────────────────
    // sync_status chỉ hạ từ SYNCED xuống PENDING_UPDATE. Nếu đang
    // PENDING_CREATE (chưa từng lên server) hoặc PENDING_DELETE thì GIỮ
    // NGUYÊN — tăng count không được biến 1 bản ghi "cần tạo mới" thành
    // "cần update", vì server chưa có gì để update.
    suspend fun incrementCount(id: String, userId: String = CurrentUser.userId.value) = withContext(Dispatchers.IO) {
        try {
            // updated_at do trigger trg_vocab_updated_at tự lo.
            userDb.db.execSQL(
                """UPDATE user_vocabulary
                   SET count = count + 1,
                       sync_status = CASE WHEN sync_status = '${SyncStatus.SYNCED}'
                                          THEN '${SyncStatus.PENDING_UPDATE}'
                                          ELSE sync_status END
                   WHERE id = ? AND user_id = ?""",
                arrayOf(id, userId)
            )
        } catch (e: Exception) {
            Log.e("VocabRepository", "incrementCount error", e)
        }
    }

    // ── Đổi trạng thái "selected" ─────────────────────────────────────────
    // Cùng quy tắc chuyển trạng thái như incrementCount(): chỉ hạ từ SYNCED
    // xuống PENDING_UPDATE, không đụng vào PENDING_CREATE/PENDING_DELETE.
    suspend fun updateSelected(
        id: String,
        selected: Int,
        userId: String = CurrentUser.userId.value,
    ): Boolean =
        withContext(Dispatchers.IO) {
            return@withContext try {
                userDb.db.execSQL(
                    """UPDATE user_vocabulary
                       SET selected = ?,
                           sync_status = CASE WHEN sync_status = '${SyncStatus.SYNCED}'
                                              THEN '${SyncStatus.PENDING_UPDATE}'
                                              ELSE sync_status END
                       WHERE id = ? AND user_id = ?""",
                    arrayOf(selected.toString(), id, userId)
                )
                // execSQL không trả về số dòng ảnh hưởng — kiểm tra lại bằng
                // truy vấn nhỏ để giữ đúng contract Boolean của hàm này.
                val cursor = userDb.db.rawQuery(
                    "SELECT selected FROM user_vocabulary WHERE id = ? AND user_id = ? LIMIT 1",
                    arrayOf(id, userId)
                )
                cursor.use { it.moveToFirst() && it.getInt(0) == selected }
            } catch (e: Exception) {
                Log.e("VocabRepository", "updateSelected error", e)
                false
            }
        }

    /**
     * Chuyển toàn bộ từ vựng đã lưu lúc còn là guest (user_id = "guest")
     * sang user thật vừa đăng nhập. Trả về số dòng đã chuyển.
     *
     * Dữ liệu guest chỉ tồn tại cục bộ, chưa từng lên server dưới user thật
     * này, nên đánh dấu PENDING_CREATE để lần sync tiếp theo đẩy lên như
     * bản ghi hoàn toàn mới — kể cả khi trước đó nó đã từng SYNCED lúc còn
     * là guest (guest không có tài khoản thật trên server để "update" vào).
     */
    suspend fun migrateGuestDataTo(newUserId: String): Int =
        withContext(Dispatchers.IO) {
            try {
                val cv = ContentValues().apply {
                    put("user_id", newUserId)
                    put("sync_status", SyncStatus.PENDING_CREATE)
                }
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
                """SELECT * FROM user_vocabulary
                   WHERE user_id = ? AND selected = 1 AND deleted_at IS NULL
                   ORDER BY created_at DESC""",
                arrayOf(userId)
            )
            cursor.use { while (it.moveToNext()) list.add(entryFromCursor(it)) }
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
                   AND deleted_at IS NULL
                   AND source_sentence_id IN ($placeholders)
                   ORDER BY created_at DESC""",
                args
            )
            vocabCursor.use { while (it.moveToNext()) list.add(entryFromCursor(it)) }
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