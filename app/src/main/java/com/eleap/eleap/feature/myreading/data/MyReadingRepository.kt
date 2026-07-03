// MyReadingRepository.kt
// Đặt tại: feature/myreading/data/MyReadingRepository.kt
//
// Tách từ file gốc cùng tên — giờ chỉ còn tầng public API (Repository), mỏng
// gọn, expose ra ngoài cho ReadingViewModel / VocabRepository dùng. Toàn bộ
// SQL nằm ở MyReadingDao.kt, schema/helper nằm ở MyReadingSchema.kt.
package com.eleap.eleap.feature.myreading.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.eleap.eleap.core.auth.CurrentUser
import com.eleap.eleap.feature.reading.data.Reading
import com.eleap.eleap.feature.reading.data.ReadingSentence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "MyReadingRepository"

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

    /**
     * Tra nhanh danh sách sentence_id thuộc 1 reading_id trong myreading.db —
     * dùng cho VocabRepository.getVocabByReadingId() khi bài đọc là MyReading
     * (readings.db không có reading_id này, phải fallback sang đây).
     */
    suspend fun getSentenceIds(readingId: String): List<String> =
        withContext(Dispatchers.IO) { dao.getSentenceIdsByReadingId(readingId) }

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
                dao.deleteReadingById(readingId, CurrentUser.userId.value).also { ok ->
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

    // ─────────────────────────────────────────────────────────────────────
    // AI processing — dùng bởi MyReadingAiProcessor. KHÔNG lọc theo
    // CurrentUser vì đây là watchdog quét NGẦM cho mọi user_id != null,
    // không chỉ user đang đăng nhập hiện tại.
    // ─────────────────────────────────────────────────────────────────────

    suspend fun getPendingAiReadings(): List<Pair<String, String>> =
        withContext(Dispatchers.IO) { dao.getPendingAiReadings() }

    suspend fun getSentencesForAi(readingId: String): List<Pair<Int, String>> =
        withContext(Dispatchers.IO) { dao.getSentenceOrdersAndText(readingId) }

    suspend fun writeAiResult(readingId: String, aiData: MyAiReading): Boolean =
        withContext(Dispatchers.IO) {
            dao.writeAiResult(readingId, aiData).also { ok ->
                if (ok) sentenceCache.remove(readingId)
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