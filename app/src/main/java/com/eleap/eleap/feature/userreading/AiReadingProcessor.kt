// AiReadingProcessor.kt
package com.eleap.eleap.feature.userreading

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.eleap.eleap.feature.reading.ReadingViewModel
import com.eleap.eleap.feature.reading.data.ReadingDatabase
import com.eleap.eleap.feature.reading.data.ReadingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "AiReadingProcessor"

// Mutex toàn cục — đảm bảo chỉ 1 coroutine xử lý AI tại bất kỳ thời điểm nào,
// dù được gọi từ processSingleReading hay processUnhandledReadings.
private val processingMutex = Mutex()

// ─────────────────────────────────────────────────────────────────────────────
// 0. Cooldown chống "bão request" khi 1 bài cứ lỗi liên tục
// ─────────────────────────────────────────────────────────────────────────────
//
// Watchdog ở MainScreen gọi processUnhandledReadings mỗi ~15s. Không có cơ
// chế này, 1 bài lỗi mãi (API key sai, AI luôn trả JSON sai cấu trúc, mạng
// down kéo dài...) sẽ bị gọi lại OpenAI MỖI 15 GIÂY vô thời hạn → tốn phí +
// token vô ích, đồng thời snackbar lỗi cứ hiện liên tục gây phiền.
//
// Backoff tăng dần (exponential) theo số lần lỗi LIÊN TIẾP của từng
// readingId: lần 1 → chờ 30s, lần 2 → 1 phút, lần 3 → 2 phút, ..., tối đa
// 10 phút. Chỉ lưu trong RAM (mất khi app bị kill) — chấp nhận được vì mục
// đích chỉ là chặn spam trong 1 phiên chạy, không cần bền vững qua các lần
// mở app (mở lại app thì được thử lại ngay, không bị "phạt" oan).
private data class FailureState(val consecutiveFailures: Int, val retryAfterMs: Long)

private val failureState = mutableMapOf<Int, FailureState>()
private const val FAILURE_COOLDOWN_BASE_MS = 30_000L        // 30 giây
private const val FAILURE_COOLDOWN_MAX_MS  = 10 * 60_000L   // tối đa 10 phút

private fun cooldownRemainingMs(readingId: Int): Long {
    val state = failureState[readingId] ?: return 0L
    return (state.retryAfterMs - System.currentTimeMillis()).coerceAtLeast(0L)
}

private fun isInCooldown(readingId: Int): Boolean = cooldownRemainingMs(readingId) > 0L

private fun recordFailure(readingId: Int) {
    val attempts = (failureState[readingId]?.consecutiveFailures ?: 0) + 1
    val delayMs = (FAILURE_COOLDOWN_BASE_MS * (1L shl (attempts - 1).coerceAtMost(10)))
        .coerceAtMost(FAILURE_COOLDOWN_MAX_MS)
    failureState[readingId] = FailureState(attempts, System.currentTimeMillis() + delayMs)
    Log.w(TAG, "reading_id=$readingId lỗi lần thứ $attempts liên tiếp → tạm ngừng thử lại bài này trong ${delayMs / 1000}s")
}

private fun clearFailure(readingId: Int) {
    if (failureState.remove(readingId) != null) {
        Log.d(TAG, "reading_id=$readingId xử lý thành công → đã xoá trạng thái cooldown trước đó")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 1. DB helpers: đọc dữ liệu cần thiết từ readings.db
// ─────────────────────────────────────────────────────────────────────────────

private data class PendingReading(
    val readingId: Int,
    val titleEn: String,
)

private fun getPendingReadings(db: SQLiteDatabase): List<PendingReading> {
    val list = mutableListOf<PendingReading>()
    db.rawQuery(
        "SELECT reading_id, title_en FROM readings WHERE is_ai_processed = 0 AND user_id IS NOT NULL",
        null
    ).use { c ->
        while (c.moveToNext()) {
            list.add(
                PendingReading(
                    readingId = c.getInt(c.getColumnIndexOrThrow("reading_id")),
                    titleEn   = c.getString(c.getColumnIndexOrThrow("title_en")) ?: "",
                )
            )
        }
    }
    return list
}

private fun getSentencesForReading(db: SQLiteDatabase, readingId: Int): List<Pair<Int, String>> {
    val list = mutableListOf<Pair<Int, String>>()
    db.rawQuery(
        "SELECT sentence_order, text_en FROM reading_sentences WHERE reading_id = ? ORDER BY sentence_order ASC",
        arrayOf(readingId.toString())
    ).use { c ->
        while (c.moveToNext()) {
            val order = c.getInt(0)
            val text  = c.getString(1) ?: ""
            if (text.isNotBlank()) list.add(order to text)
        }
    }
    return list
}

// ─────────────────────────────────────────────────────────────────────────────
// 2. DB writer: ghi kết quả AI vào readings / reading_sentences /
//              sentence_phrases / sentence_words trong 1 transaction
// ─────────────────────────────────────────────────────────────────────────────

private fun writeAiResultToDb(
    db: SQLiteDatabase,
    readingId: Int,
    aiData: AiReading,
) {
    val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    db.beginTransaction()
    try {
        // Update readings
        val rcv = ContentValues().apply {
            aiData.titleVi?.let { put("title_vi", it) }
            aiData.level?.let   { put("level", it) }
            aiData.topic?.let   { put("topic", it) }
            put("is_ai_processed", 1)
            put("updated_at", now)
        }
        val rUpdated = db.update("readings", rcv, "reading_id = ?", arrayOf(readingId.toString()))
        Log.d(TAG, "readings updated: $rUpdated row(s) for reading_id=$readingId")

        for (aiSentence in aiData.sentences) {
            val sentenceId: Int? = db.rawQuery(
                "SELECT sentence_id FROM reading_sentences WHERE reading_id = ? AND sentence_order = ?",
                arrayOf(readingId.toString(), aiSentence.sentenceOrder.toString())
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else null }

            if (sentenceId == null) {
                Log.w(TAG, "Không tìm thấy câu #${aiSentence.sentenceOrder} trong reading_id=$readingId")
                continue
            }

            // Update reading_sentences
            val scv = ContentValues().apply {
                aiSentence.textVi?.let      { put("text_vi", it) }
                aiSentence.explanation?.let { put("sentence_explanation", it) }
            }
            db.update("reading_sentences", scv, "sentence_id = ?", arrayOf(sentenceId.toString()))

            // Reset phrases cho câu này rồi insert lại
            db.delete("sentence_phrases", "sentence_id = ?", arrayOf(sentenceId.toString()))

            val phraseIdMap = mutableMapOf<String, Int>()
            for (aiPhrase in aiSentence.phrases) {
                val pcv = ContentValues().apply {
                    put("sentence_id",      sentenceId)
                    put("text_en",          aiPhrase.textEn)
                    aiPhrase.textVi?.let       { put("text_vi", it) }
                    aiPhrase.explanation?.let  { put("phrase_explanation", it) }
                    put("start_word_order", aiPhrase.startWordOrder)
                    put("end_word_order",   aiPhrase.endWordOrder)
                }
                val phraseRowId = db.insert("sentence_phrases", null, pcv)
                if (phraseRowId != -1L) {
                    phraseIdMap[aiPhrase.id] = phraseRowId.toInt()
                    Log.d(TAG, "  phrase '${aiPhrase.id}' → phrase_id=$phraseRowId")
                }
            }

            // Update sentence_words
            for (aiWord in aiSentence.words) {
                val dbPhraseId: Int? = aiWord.phraseId?.let { phraseIdMap[it] }
                val wcv = ContentValues().apply {
                    aiWord.textVi?.let          { put("text_vi", it) }
                    aiWord.pos?.let             { put("pos", it) }
                    aiWord.lemma?.let           { put("lemma", it) }
                    aiWord.explanation?.let     { put("word_explanation", it) }
                    aiWord.formExplanation?.let { put("word_form_explanation", it) }
                    if (dbPhraseId != null) put("phrase_id", dbPhraseId)
                    else putNull("phrase_id")
                }
                val wUpdated = db.update(
                    "sentence_words",
                    wcv,
                    "sentence_id = ? AND word_order = ?",
                    arrayOf(sentenceId.toString(), aiWord.wordOrder.toString())
                )
                if (wUpdated == 0) {
                    Log.w(TAG, "  word #${aiWord.wordOrder} '${aiWord.textEn}' không tìm thấy để update")
                }
            }
        }

        db.setTransactionSuccessful()
        Log.d(TAG, "writeAiResultToDb SUCCESS: reading_id=$readingId")
    } finally {
        db.endTransaction()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 3. Shared helpers
// ─────────────────────────────────────────────────────────────────────────────

// Lấy connection ghi-được DUY NHẤT mà toàn app dùng chung (ReadingDatabase.db,
// đã mở readWrite + WAL). KHÔNG tự mở SQLiteDatabase.openDatabase() riêng tới
// cùng file readings.db — làm vậy sẽ tạo ra 2 connection tranh khoá nhau,
// gây lỗi hoặc đọc dữ liệu nửa-vá khi user đang mở bài đọc cùng lúc AI ghi.
private suspend fun <T> withWritableDb(
    context: Context,
    block: suspend (SQLiteDatabase) -> T,
): T = withContext(Dispatchers.IO) {
    val db = ReadingDatabase.getInstance(context.applicationContext).db
    block(db)
}

// Notify ViewModel hoặc invalidate cache thủ công khi AI xử lý xong 1 bài
private suspend fun notifyCompleted(readingId: Int) = withContext(Dispatchers.Main) {
    ReadingViewModel.Factory.getInstance()?.notifyAiCompleted(readingId)
        ?: ReadingRepository.instance?.let { repo ->
            repo.invalidateListCache()
            repo.invalidateReadingCache(readingId)
        }
}

// Pipeline dùng chung: lấy câu → gọi API → parse → ghi DB → notify
// Được dùng bởi cả processSingleReading lẫn processUnhandledReadings.
private suspend fun processOneReading(
    db: SQLiteDatabase,
    readingId: Int,
    titleEn: String,
    onStatus: suspend (String) -> Unit,
) {
    val label = "reading_id=$readingId"
    val sentences = getSentencesForReading(db, readingId)
    if (sentences.isEmpty()) {
        Log.w(TAG, "$label không có câu nào, bỏ qua.")
        return
    }

    Log.d(TAG, "▶▶▶ BẮT ĐẦU xử lý AI cho $label '$titleEn' (${sentences.size} câu)")

    val prompt  = buildPrompt(titleEn, sentences)
    val rawJson = callOpenAI(prompt, logLabel = label)
    Log.d(TAG, "AI response length=${rawJson.length} for $label")

    val aiData = parseAiResponse(rawJson)
    writeAiResultToDb(db, readingId, aiData)
    clearFailure(readingId)
    notifyCompleted(readingId)

    withContext(Dispatchers.Main) {
        onStatus("✓ Đã dịch xong: ${aiData.titleVi ?: titleEn}")
    }
    Log.d(TAG, "◀◀◀ HOÀN TẤT xử lý AI cho $label")
}

// ─────────────────────────────────────────────────────────────────────────────
// 4. Entry point 1: xử lý 1 bài cụ thể ngay sau khi insert
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Xử lý DUY NHẤT 1 bài vừa được insert (đã biết sẵn readingId).
 *
 * - Gọi API đúng 1 lần cho bài đó, không scan toàn bảng.
 * - Dùng withLock (CHỜ) thay vì tryLock (bỏ qua) → không bao giờ bỏ sót.
 * - Kiểm tra is_ai_processed trước khi gọi API → idempotent, an toàn khi retry.
 * - Phải gọi từ viewModelScope để không bị cancel khi navigate.
 */
suspend fun processSingleReading(
    context: Context,
    readingId: Int,
    onStatus: suspend (String) -> Unit = {},
) = withContext(Dispatchers.IO) {
    Log.d(TAG, "processSingleReading() được GỌI cho reading_id=$readingId (đang chờ mutex nếu có tác vụ khác đang chạy)")
    processingMutex.withLock {
        withWritableDb(context) { db ->
            // Idempotent check — tránh gọi API 2 lần nếu bài đã xử lý
            val alreadyDone = db.rawQuery(
                "SELECT is_ai_processed FROM readings WHERE reading_id = ?",
                arrayOf(readingId.toString())
            ).use { c -> c.moveToFirst() && c.getInt(0) == 1 }

            if (alreadyDone) {
                Log.d(TAG, "processSingleReading: reading_id=$readingId đã xử lý, bỏ qua.")
                return@withWritableDb
            }

            // Cooldown check — nếu bài này vừa lỗi liên tiếp gần đây, chưa
            // đến giờ thử lại thì bỏ qua (watchdog sẽ tự thử lại sau khi hết
            // cooldown, không cần làm gì thêm ở đây).
            if (isInCooldown(readingId)) {
                val remainingSec = cooldownRemainingMs(readingId) / 1000
                Log.d(TAG, "processSingleReading: reading_id=$readingId đang cooldown (còn ${remainingSec}s) do lỗi liên tiếp trước đó, bỏ qua.")
                return@withWritableDb
            }

            val titleEn = db.rawQuery(
                "SELECT title_en FROM readings WHERE reading_id = ?",
                arrayOf(readingId.toString())
            ).use { c -> if (c.moveToFirst()) c.getString(0) ?: "" else "" }

            Log.d(TAG, "processSingleReading: bắt đầu reading_id=$readingId '$titleEn'")
            withContext(Dispatchers.Main) { onStatus("Đang dịch bài: $titleEn…") }

            try {
                processOneReading(db, readingId, titleEn, onStatus)
            } catch (e: Exception) {
                Log.e(TAG, "✗✗✗ processSingleReading LỖI: reading_id=$readingId | ${e.javaClass.simpleName}: ${e.message}", e)
                recordFailure(readingId)
                val waitSec = cooldownRemainingMs(readingId) / 1000
                withContext(Dispatchers.Main) { onStatus("Lỗi dịch bài, sẽ thử lại sau ${waitSec}s.") }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 5. Entry point 2: safety-net — xử lý tất cả bài còn tồn đọng
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Quét toàn bộ bảng readings, xử lý tất cả bài có is_ai_processed = 0.
 * Dùng làm safety-net trong ReadingListScreen.LaunchedEffect để xử lý các bài
 * bị bỏ sót (app crash, mất mạng, v.v.).
 *
 * Dùng tryLock (bỏ qua nếu đang bận) vì đây chỉ là fallback — bài mới nhất
 * đã được processSingleReading xử lý trực tiếp rồi.
 *
 * Phải gọi từ viewModelScope để không bị cancel khi navigate.
 */
suspend fun processUnhandledReadings(
    context: Context,
    onStatus: suspend (String) -> Unit = {},
) = withContext(Dispatchers.IO) {
    if (!processingMutex.tryLock()) {
        Log.d(TAG, "processUnhandledReadings: đang bận, bỏ qua lần gọi này.")
        return@withContext
    }

    try {
        withWritableDb(context) { db ->
            val allPending = getPendingReadings(db)
            // Lọc bỏ những bài đang trong cooldown do lỗi liên tiếp trước đó —
            // tránh gọi OpenAI lặp lại vô ích cho bài chắc chắn sẽ lỗi tiếp.
            val pending = allPending.filterNot { isInCooldown(it.readingId) }
            val skippedByCooldown = allPending.size - pending.size

            if (skippedByCooldown > 0) {
                Log.d(TAG, "processUnhandledReadings: bỏ qua $skippedByCooldown bài đang cooldown do lỗi liên tiếp: " +
                        allPending.filter { isInCooldown(it.readingId) }
                            .joinToString { "${it.readingId}(còn ${cooldownRemainingMs(it.readingId) / 1000}s)" })
            }

            if (pending.isEmpty()) {
                Log.d(TAG, "processUnhandledReadings: không có bài nào tồn đọng (ngoài cooldown).")
                return@withWritableDb
            }
            Log.d(TAG, "processUnhandledReadings: ${pending.size} bài tồn đọng: ${pending.map { it.readingId }}")

            for (pr in pending) {
                Log.d(TAG, "Bắt đầu xử lý reading_id=${pr.readingId} '${pr.titleEn}'")
                withContext(Dispatchers.Main) { onStatus("Đang dịch bài: ${pr.titleEn}…") }

                try {
                    processOneReading(db, pr.readingId, pr.titleEn, onStatus)
                } catch (e: Exception) {
                    Log.e(TAG, "✗✗✗ processUnhandledReadings LỖI: reading_id=${pr.readingId} | ${e.javaClass.simpleName}: ${e.message}", e)
                    recordFailure(pr.readingId)
                    val waitSec = cooldownRemainingMs(pr.readingId) / 1000
                    withContext(Dispatchers.Main) { onStatus("Lỗi dịch bài: ${pr.titleEn} (thử lại sau ${waitSec}s)") }
                    continue
                }
            }
        }
    } finally {
        processingMutex.unlock()
    }
}