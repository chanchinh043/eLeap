// TtsRemotePackWorker.kt
// Đặt tại: com/eleap/eleap/core/tts/remote/TtsRemotePackWorker.kt
//
// CoroutineWorker chạy nền — xử lý ĐÚNG 1 (readingId, sid) mỗi lần chạy,
// KHÁC với TtsPregenWorker (chạy liên tục quét toàn bộ lịch sử bài đọc).
// Lý do khác nhau: pregen/ là công việc NỀN DÀI HẠN, tự sinh audio cho mọi
// bài từng mở, còn remote/ chỉ cần tải ĐÚNG bài/giọng người dùng đang mở
// NGAY LÚC NÀY — mỗi lần mở 1 bài khác/đổi giọng khác, enqueue 1 Worker mới
// cho đúng cặp đó (xem TtsRemotePackScheduler.kt), không cần 1 Worker chạy
// mãi quét hết mọi thứ.
//
// KHÔNG retry nhiều lần nếu thất bại — mất mạng/server lỗi là tình huống
// BÌNH THƯỜNG (offline-first), pregen/ vẫn tự sinh audio làm lưới an toàn
// nếu tải về không có/lỗi. Vì vậy luôn trả Result.success() dù tải được hay
// không — Result.failure()/retry() chỉ dành cho lỗi THỰC SỰ bất thường
// (không áp dụng ở đây, mọi nhánh thất bại đều đã được coi là "bình
// thường" ngay trong TtsRemotePackDownloader).
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

        // ⚠️ SỬA: trước đây gọi THẲNG downloadAndExtract(), bỏ qua hoàn
        // toàn gate 24h (isPackUpToDate/isPackSynced) — dẫn tới tải lại
        // nguyên gói .zip từ Drive MỖI LẦN người dùng mở lại 1 bài đã có
        // cache local từ trước, dù nội dung trên Drive không hề đổi (xác
        // nhận qua log thực tế 2026-07-10). Giờ gọi qua syncIfNeeded() —
        // đúng gate DÙNG CHUNG với TtsPregenWorker.ensureRemotePackSynced()
        // (xem TtsRemotePackDownloader.syncIfNeeded()), tự bỏ qua nếu gói
        // đã đồng bộ và chưa tới hạn check lại, chỉ gọi Drive khi thật sự
        // cần thiết.
        val ok = TtsRemotePackDownloader.syncIfNeeded(applicationContext, readingId, sid)
        Log.d(TAG, "doWork: reading=$readingId sid=$sid kết quả đồng bộ=$ok")
        return Result.success()
    }
}