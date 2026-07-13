// TtsRemotePackWorker.kt
// Đặt tại: com/eleap/eleap/core/tts/remote/TtsRemotePackWorker.kt
//
// CoroutineWorker chạy nền — xử lý ĐÚNG 1 (readingId, sid) mỗi lần chạy —
// chỉ cần tải ĐÚNG bài/giọng người dùng đang mở NGAY LÚC NÀY. Mỗi lần mở 1
// bài khác/đổi giọng khác, enqueue 1 Worker mới cho đúng cặp đó (xem
// TtsRemotePackScheduler.kt).
//
// KHÔNG retry nhiều lần nếu thất bại — mất mạng/server lỗi là tình huống
// BÌNH THƯỜNG. Vì vậy luôn trả Result.success() dù tải được hay không —
// Result.failure()/retry() chỉ dành cho lỗi THỰC SỰ bất thường (không áp
// dụng ở đây, mọi nhánh thất bại đều đã được coi là "bình thường" ngay
// trong TtsRemotePackDownloader).
//
// ⚠️ QUAN TRỌNG: khác thiết kế cũ khi còn Kokoro — hiện KHÔNG có worker
// pregen/ nào tự sinh audio làm lưới an toàn nếu Worker này thất bại. Nếu
// tải lỗi, audio đơn giản là chưa có, TtsPlaybackRouter sẽ fallback Android
// TTS cho tới khi 1 lượt tải sau đó (mở lại bài, hoặc có mạng trở lại)
// thành công.
package com.eleap.eleap.core.tts.remote

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

private const val TAG = "TtsRemotePackWorker"

class TtsRemotePackWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_READING_ID = "reading_id"
        const val KEY_SID = "sid"
    }

    override suspend fun doWork(): Result {
        val readingId = inputData.getString(KEY_READING_ID)
        val sid = inputData.getInt(KEY_SID, -1)

        if (readingId.isNullOrBlank() || sid < 0) {
            Log.w(TAG, "doWork: thiếu readingId/sid hợp lệ, bỏ qua")
            return Result.success()
        }

        // Gọi qua syncIfNeeded() — có gate 24h, tự tránh tải lại nguyên gói
        // .zip mỗi lần người dùng mở lại 1 bài đã có cache local từ trước,
        // dù nội dung trên Drive không hề đổi.
        val ok = TtsRemotePackDownloader.syncIfNeeded(applicationContext, readingId, sid)
        Log.d(TAG, "doWork: reading=$readingId sid=$sid kết quả đồng bộ=$ok")
        return Result.success()
    }
}