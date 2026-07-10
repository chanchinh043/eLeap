// TtsRemotePackScheduler.kt
// Đặt tại: com/eleap/eleap/core/tts/remote/TtsRemotePackScheduler.kt
//
// Nơi DUY NHẤT gọi WorkManager để enqueue TtsRemotePackWorker — cùng phong
// cách với TtsPregenScheduler.kt, nhưng KHÁC ở chỗ: mỗi (readingId, sid) có
// 1 tên unique work RIÊNG (thay vì 1 tên unique DUY NHẤT cho toàn bộ app
// như pregen/) — vì đây là việc tải 1 gói CỤ THỂ, nhiều gói khác nhau có
// thể cần tải song song (vd người dùng mở nhanh 2 bài khác nhau), không nên
// việc tải bài A chặn mất việc tải bài B.
//
// CÓ networkConstraint (KHÁC pregen/ — không cần) — vì đây là việc BẮT BUỘC
// phải có mạng, enqueue mà chưa có mạng thì WorkManager tự giữ lại, tự chạy
// ngay khi có mạng trở lại, không cần tự viết logic chờ mạng.
//
// Singleton thủ công, KHÔNG dùng Hilt/DI — cùng phong cách với
// TtsPregenScheduler.
package com.eleap.eleap.core.tts.remote

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

object TtsRemotePackScheduler {

    private const val UNIQUE_WORK_PREFIX = "tts_remote_pack_"

    private fun uniqueWorkName(readingId: String, sid: Int) = "$UNIQUE_WORK_PREFIX${readingId}_$sid"

    // ── Enqueue 1 lượt tải cho ĐÚNG (readingId, sid) — gọi ngay khi người
    // dùng mở 1 bài đọc (biết ngay readingId + sid Kokoro đang chọn, xem
    // TtsForegroundReading.set()/TtsVoiceSnapshot.currentTargetSid() ở nơi
    // gọi). KEEP — nếu đã có lượt tải đang chạy/đang chờ mạng cho ĐÚNG cặp
    // này, không tạo bản sao chạy song song; nếu lượt trước đã XONG (thành
    // công hay thất bại đều là "xong"), KEEP vẫn cho enqueue lại bình
    // thường.
    fun enqueueDownload(context: Context, readingId: String, sid: Int) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val inputData = workDataOf(
            TtsRemotePackWorker.KEY_READING_ID to readingId,
            TtsRemotePackWorker.KEY_SID to sid,
        )

        val request = OneTimeWorkRequestBuilder<TtsRemotePackWorker>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueWorkName(readingId, sid),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}