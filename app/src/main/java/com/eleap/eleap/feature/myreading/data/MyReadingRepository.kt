// MyReadingRepository.kt
// Đặt tại: feature/myreading/data/MyReadingRepository.kt
//
// Tầng Repository DUY NHẤT bọc quanh MyReadingDao (myreading.db) — mọi nơi
// khác trong app (ReadingRepository, ReadingViewModel, VocabRepository,
// MyReadingAiProcessor, MainActivity/MainScreen, MyReadingSyncEngine/
// MyReadingSyncRealtime) chỉ gọi qua đây, không tự cầm SQLiteDatabase/
// MyReadingDao. Reading/ReadingSentence/SentencePhrase/SentenceWord/DictEntry
// được định nghĩa DUY NHẤT ở feature/reading/data/ReadingRepository.kt — file
// này CHỈ import lại, KHÔNG được định nghĩa lại (trùng khai báo cùng gói sẽ
// làm biên dịch lỗi Redeclaration).
//
// Cache RAM (sentenceCache/listCache) giữ nguyên tinh thần readingCache/
// readingListCache bên ReadingRepository.kt — tự xoá cache đúng dòng bị ảnh
// hưởng sau mỗi lần ghi (save/delete/AI xử lý xong/migrate/áp dữ liệu server).
package com.eleap.eleap.feature.myreading.data

import android.content.Context
import com.eleap.eleap.core.auth.CurrentUser
import com.eleap.eleap.feature.reading.data.Reading
import com.eleap.eleap.feature.reading.data.ReadingSentence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MyReadingRepository(private val dao: MyReadingDao) {

    // ── Cache RAM ─────────────────────────────────────────────────────────────
    // key = readingId, value = danh sách sentence (đã gắn phrases + words) —
    // tương đương readingCache bên ReadingRepository.kt nhưng chỉ cho phạm vi
    // bài đọc của myreading.db.
    private val sentenceCache = mutableMapOf<String, List<ReadingSentence>>()

    // Danh sách bài đọc của CurrentUser hiện tại — invalidate mỗi khi có
    // save/delete/migrate/áp dữ liệu server làm thay đổi tập hợp bài.
    private var listCache: List<Reading>? = null

    // ── Flow 2: danh sách bài đọc của user hiện tại (userId != null) ─────────
    // ReadingRepository.getAllReadings() gọi hàm này KHÔNG kèm forceRefresh
    // (chỉ tự forceRefresh ở tầng của chính nó) — giữ tham số mặc định false
    // để các nơi khác (nếu cần) vẫn ép refresh được.
    suspend fun getAllReadings(forceRefresh: Boolean = false): List<Reading> =
        withContext(Dispatchers.IO) {
            if (forceRefresh) listCache = null
            listCache ?: dao.getAllReadings(CurrentUser.userId.value).also { listCache = it }
        }

    // ── Flow 3: chi tiết 1 bài (sentences kèm phrases/words) ─────────────────
    // Route đích danh cho bài MyReading — ReadingRepository chỉ gọi hàm này
    // khi đã biết readingId thuộc userId != null (xem buildReading() vs
    // myReadingRepository.getReading() ở ReadingRepository.kt).
    suspend fun getReading(readingId: String): List<ReadingSentence> =
        withContext(Dispatchers.IO) {
            sentenceCache[readingId] ?: dao.getFullSentencesForSync(readingId).also {
                sentenceCache[readingId] = it
            }
        }

    /** sentence_id của 1 bài — dùng bởi VocabRepository để đối chiếu
     *  source_sentence_id đã lưu ở users.db khi bài đó là MyReading. */
    suspend fun getSentenceIds(readingId: String): List<String> =
        withContext(Dispatchers.IO) { dao.getSentenceIdsByReadingId(readingId) }

    // ── Tạo/xoá bài đọc (gọi từ ReadingViewModel.addMyReading/deleteMyReading) ──
    suspend fun saveMyReading(titleEn: String, content: String): String? =
        withContext(Dispatchers.IO) {
            val userId = CurrentUser.userId.value
            val sentences = parseMyContent(content)
            val readingId = dao.insertReadingWithSentences(userId, titleEn, sentences)
            if (readingId != null) {
                // Bài mới → danh sách đổi, invalidate để lần getAllReadings()
                // kế tiếp nạp lại từ DB.
                listCache = null
            }
            readingId
        }

    suspend fun deleteMyReading(readingId: String): Boolean =
        withContext(Dispatchers.IO) {
            val userId = CurrentUser.userId.value
            dao.deleteReadingById(readingId, userId).also { ok ->
                if (ok) {
                    listCache = null
                    sentenceCache.remove(readingId)
                }
            }
        }

    /** Chuyển toàn bộ bài đọc guest → user thật vừa đăng nhập — gọi từ
     *  MainActivity/MainScreen ngay sau khi CurrentUser.setUser() thành công. */
    suspend fun migrateGuestDataTo(newUserId: String): Int =
        withContext(Dispatchers.IO) {
            dao.migrateOwnership(CurrentUser.GUEST_ID, newUserId).also { moved ->
                if (moved > 0) listCache = null
            }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // ── AI processing — dùng bởi MyReadingAiProcessor.kt ──
    // ─────────────────────────────────────────────────────────────────────────

    /** (sentence_order, text_en) của 1 bài — dùng để build prompt gửi AI. */
    suspend fun getSentencesForAi(readingId: String): List<Pair<Int, String>> =
        withContext(Dispatchers.IO) { dao.getSentenceOrdersAndText(readingId) }

    /** Danh sách (reading_id, title_en) của các bài user chưa được AI xử lý. */
    suspend fun getPendingAiReadings(): List<Pair<String, String>> =
        withContext(Dispatchers.IO) { dao.getPendingAiReadings() }

    suspend fun writeAiResult(readingId: String, aiData: MyAiReading): Boolean =
        withContext(Dispatchers.IO) {
            dao.writeAiResult(readingId, aiData).also { ok ->
                // Nội dung câu/phrase/word vừa đổi → xoá cache chi tiết của
                // đúng bài này, không cần xoá listCache (title_vi/is_ai_processed
                // đổi không ảnh hưởng tới việc bài có xuất hiện trong danh sách
                // hay không).
                if (ok) sentenceCache.remove(readingId)
            }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // ── Dùng cho MyReadingSyncEngine / MyReadingSyncRealtime ──
    // Đơn vị đồng bộ là CẢ 1 bài đọc. KHÔNG lọc theo CurrentUser.userId.value
    // — nhận userId/readingId trực tiếp qua tham số, vì các hàm này được gọi
    // từ tầng sync chạy nền (WorkManager Worker / Realtime), có thể không
    // chạy trong đúng ngữ cảnh CurrentUser hiện tại của UI.
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun getPendingReadings(userId: String): List<Reading> =
        withContext(Dispatchers.IO) { dao.getPendingReadings(userId) }

    suspend fun markSynced(readingIds: List<String>) =
        withContext(Dispatchers.IO) {
            dao.markSynced(readingIds)
            readingIds.forEach { sentenceCache.remove(it) }
        }

    /** Đủ dữ liệu (dòng readings + toàn bộ sentences/phrases/words) để
     *  MyReadingSyncEngine build payload JSON khi push lên server. */
    suspend fun getReadingForSync(readingId: String): Pair<Reading, List<ReadingSentence>>? =
        withContext(Dispatchers.IO) {
            val row = dao.getReadingRow(readingId) ?: return@withContext null
            row to dao.getFullSentencesForSync(readingId)
        }

    suspend fun applyServerReading(
        reading: Reading,
        sentences: List<ReadingSentence>,
        isTombstone: Boolean,
        // Thời điểm xoá thật từ server — xem ghi chú ở MyReadingDao.applyServerReading().
        deletedAt: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        dao.applyServerReading(reading, sentences, isTombstone, deletedAt).also { ok ->
            if (ok) {
                sentenceCache.remove(reading.readingId)
                // Tombstone/nội dung mới có thể làm bài xuất hiện/biến mất
                // khỏi danh sách — invalidate để lần getAllReadings() kế tiếp
                // nạp lại đúng trạng thái.
                listCache = null
            }
        }
    }

    companion object {
        @Volatile private var INSTANCE: MyReadingRepository? = null

        fun getInstance(context: Context): MyReadingRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val db = MyReadingDbHelper(context.applicationContext).writableDatabase
                    val dao = MyReadingDao(db)
                    MyReadingRepository(dao).also { INSTANCE = it }
                }
            }
    }
}