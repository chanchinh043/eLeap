// MyReadingAiProcessor.kt
// Đặt tại: feature/myreading/data/MyReadingAiProcessor.kt
//
// Cùng package với MyReadingRepository.kt / MyReadingAiApiClient.kt để dùng
// thẳng MyAiReading, splitMyWords, MyReadingRepository mà không cần import.
package com.eleap.eleap.feature.myreading.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "MyReadingAiProcessor"

// Mutex toàn cục — chỉ 1 coroutine xử lý AI cho MyReading tại 1 thời điểm.
private val processingMutex = Mutex()

// ─────────────────────────────────────────────────────────────────────────────
// Cooldown chống "bão request" khi 1 bài lỗi liên tục (API key sai, AI trả
// JSON sai cấu trúc/không đạt rule phrase, mạng down...). Backoff tăng dần
// theo số lần lỗi LIÊN TIẾP của từng readingId: lần 1 → 30s, lần 2 → 1 phút,
// ..., tối đa 10 phút. Chỉ lưu trong RAM — mất khi app bị kill, chấp nhận
// được vì chỉ nhằm chặn spam trong 1 phiên chạy.
// ─────────────────────────────────────────────────────────────────────────────

private data class FailureState(val consecutiveFailures: Int, val retryAfterMs: Long)

private val failureState = mutableMapOf<String, FailureState>()
private const val FAILURE_COOLDOWN_BASE_MS = 30_000L        // 30 giây
private const val FAILURE_COOLDOWN_MAX_MS  = 10 * 60_000L   // tối đa 10 phút

private fun cooldownRemainingMs(readingId: String): Long {
    val state = failureState[readingId] ?: return 0L
    return (state.retryAfterMs - System.currentTimeMillis()).coerceAtLeast(0L)
}

private fun isInCooldown(readingId: String): Boolean = cooldownRemainingMs(readingId) > 0L

private fun recordFailure(readingId: String) {
    val attempts = (failureState[readingId]?.consecutiveFailures ?: 0) + 1
    val delayMs = (FAILURE_COOLDOWN_BASE_MS * (1L shl (attempts - 1).coerceAtMost(10)))
        .coerceAtMost(FAILURE_COOLDOWN_MAX_MS)
    failureState[readingId] = FailureState(attempts, System.currentTimeMillis() + delayMs)
    Log.w(TAG, "reading_id=$readingId lỗi lần thứ $attempts liên tiếp → tạm ngừng ${delayMs / 1000}s")
}

private fun clearFailure(readingId: String) {
    if (failureState.remove(readingId) != null) {
        Log.d(TAG, "reading_id=$readingId xử lý thành công → xoá cooldown trước đó")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Pipeline xử lý 1 bài: lấy câu → build prompt → gọi AI → parse → validate
// (phrase phủ kín câu, >= 2 từ/phrase) → ghi DB qua MyReadingRepository.
// ─────────────────────────────────────────────────────────────────────────────

private suspend fun processOneMyReading(
    repo: MyReadingRepository,
    readingId: String,
    titleEn: String,
    onStatus: suspend (String) -> Unit,
) {
    val label = "reading_id=$readingId"
    val sentences = repo.getSentencesForAi(readingId)
    if (sentences.isEmpty()) {
        Log.w(TAG, "$label không có câu nào, bỏ qua.")
        return
    }

    Log.d(TAG, "▶▶▶ BẮT ĐẦU xử lý AI cho $label '$titleEn' (${sentences.size} câu)")

    val prompt  = buildMyReadingPrompt(titleEn, sentences)
    val rawJson = callMyReadingOpenAI(prompt, logLabel = label)
    val aiData  = parseMyReadingAiResponse(rawJson)

    // Số từ THẬT của mỗi câu, lấy từ DB (qua splitMyWords) — không tin số AI tự đếm.
    val wordCounts = sentences.associate { (order, text) -> order to splitMyWords(text).size }

    val validationError = validateMyAiReading(aiData, wordCounts)
    if (validationError != null) {
        throw IllegalStateException("Dữ liệu AI không hợp lệ: $validationError")
    }

    val written = repo.writeAiResult(readingId, aiData)
    if (!written) {
        throw IllegalStateException("Ghi DB thất bại cho $label")
    }

    clearFailure(readingId)
    onStatus("✓ Đã dịch xong: ${aiData.titleVi ?: titleEn}")
    Log.d(TAG, "◀◀◀ HOÀN TẤT xử lý AI cho $label")
}

// ─────────────────────────────────────────────────────────────────────────────
// Entry point — watchdog quét toàn bộ bài của user (user_id != null) chưa
// được AI xử lý (is_ai_processed = 0). Dùng tryLock: nếu đang có 1 lượt quét
// chạy rồi thì bỏ qua lần gọi này (an toàn khi watchdog gọi lặp lại nhiều lần).
//
// onStatus: cập nhật trạng thái đang xử lý (vd hiện snackbar) — luôn được
//           gọi trên Main thread nếu nơi gọi cần update UI trực tiếp; ở đây
//           để nguyên suspend, caller tự quyết định dispatcher.
// onUpdated: gọi sau khi có ÍT NHẤT 1 bài ghi DB thành công — để ViewModel
//            refresh lại danh sách myReadings hiển thị cho người dùng.
// ─────────────────────────────────────────────────────────────────────────────

suspend fun processUnhandledMyReadings(
    context: Context,
    onStatus: suspend (String) -> Unit = {},
    onUpdated: suspend () -> Unit = {},
) {
    if (!processingMutex.tryLock()) {
        Log.d(TAG, "processUnhandledMyReadings: đang bận, bỏ qua lần gọi này.")
        return
    }

    try {
        val repo = MyReadingRepository.getInstance(context)
        val allPending = repo.getPendingAiReadings()
        val pending = allPending.filterNot { (readingId, _) -> isInCooldown(readingId) }
        val skippedByCooldown = allPending.size - pending.size

        if (skippedByCooldown > 0) {
            Log.d(TAG, "processUnhandledMyReadings: bỏ qua $skippedByCooldown bài đang cooldown: " +
                    allPending.filter { (readingId, _) -> isInCooldown(readingId) }
                        .joinToString { (readingId, _) -> "$readingId(còn ${cooldownRemainingMs(readingId) / 1000}s)" })
        }

        if (pending.isEmpty()) {
            Log.d(TAG, "processUnhandledMyReadings: không có bài nào tồn đọng (ngoài cooldown).")
            return
        }
        Log.d(TAG, "processUnhandledMyReadings: ${pending.size} bài tồn đọng: ${pending.map { it.first }}")

        var anySuccess = false
        for ((readingId, titleEn) in pending) {
            onStatus("Đang dịch bài: $titleEn…")
            try {
                processOneMyReading(repo, readingId, titleEn, onStatus)
                anySuccess = true
            } catch (e: Exception) {
                Log.e(TAG, "✗✗✗ processUnhandledMyReadings LỖI: reading_id=$readingId | ${e.javaClass.simpleName}: ${e.message}", e)
                recordFailure(readingId)
                val waitSec = cooldownRemainingMs(readingId) / 1000
                onStatus("Lỗi dịch bài: $titleEn (thử lại sau ${waitSec}s)")
            }
        }

        if (anySuccess) onUpdated()
    } finally {
        processingMutex.unlock()
    }
}