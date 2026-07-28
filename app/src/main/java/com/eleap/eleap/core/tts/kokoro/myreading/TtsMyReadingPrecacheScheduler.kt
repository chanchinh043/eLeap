// TtsMyReadingPrecacheScheduler.kt
// Đặt tại: com/eleap/eleap/core/tts/kokoro/myreading/TtsMyReadingPrecacheScheduler.kt
//
// ⚠️ ĐÃ ĐỔI TỪ PeriodicWorkRequest SANG TỰ RE-ENQUEUE OneTimeWorkRequest:
// WorkManager KHÔNG cho phép PeriodicWorkRequest chạy dưới 15 phút
// (PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS — giới hạn CỨNG của hệ
// điều hành, không phải do code cũ tự đặt; nếu build PeriodicWorkRequest với
// giá trị nhỏ hơn, WorkManager tự kẹp lên 15 phút mà KHÔNG báo lỗi gì).
// Muốn chu kỳ NGẮN HƠN 15 phút (hiện là 5 phút), Worker phải tự enqueue lại
// CHÍNH NÓ (OneTimeWorkRequest với initialDelay) ngay sau khi doWork() chạy
// xong — xem TtsMyReadingPrecacheWorker.scheduleNextRun().
//
// unique work name giữ NGUYÊN qua mọi lượt re-enqueue (REPLACE policy ở
// scheduleNextRun()) — mỗi lượt kế tiếp thay thế đúng lượt vừa chạy xong,
// không bao giờ có 2 chuỗi chạy song song.
//
// ⚠️ HẠN CHẾ ĐÃ BIẾT: cancel() gọi cancelUniqueWork() để dừng chuỗi, nhưng
// nếu ĐÚNG LÚC gọi cancel() có 1 lượt Worker đang chạy dở, lượt đó vẫn tự
// enqueue tiếp lượt kế (scheduleNextRun() không tự biết mình vừa bị huỷ) —
// chuỗi sẽ tự dừng hẳn ở lượt SAU đó (không phải ngay lập tức). Chấp nhận
// được vì cancel() hiện không có nơi gọi (đặt sẵn để đối xứng, giống
// TtsMyReadingSyncScheduler.cancelAll()).
package com.eleap.eleap.core.tts.kokoro.myreading

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object TtsMyReadingPrecacheScheduler {

    // Dùng chung tên unique work cho MỌI lượt trong chuỗi (lượt đầu tiên từ
    // schedulePeriodicWork() lẫn mọi lượt tự re-enqueue từ
    // TtsMyReadingPrecacheWorker.scheduleNextRun()) — đây là điểm mấu chốt
    // để cancel() luôn nhắm đúng chuỗi hiện tại, bất kể nó đã tự re-enqueue
    // bao nhiêu lần.
    const val UNIQUE_WORK_NAME = "tts_myreading_precache"

    // Khoảng cách giữa 2 lần chạy — 5 phút. Đủ nhanh để bắt kịp lúc server
    // báo READY mà không tốn quá nhiều pin/mạng. Đổi giá trị này (và dùng
    // lại ở TtsMyReadingPrecacheWorker.scheduleNextRun()) nếu cần chu kỳ
    // khác — KHÔNG bị giới hạn 15 phút như PeriodicWorkRequest vì đây là
    // initialDelay của OneTimeWorkRequest, không phải interval của
    // PeriodicWorkRequest.
    const val REPEAT_INTERVAL_MINUTES = 5L

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    // ── Gọi 1 lần ở MainActivity.onCreate() — KEEP: nếu app khởi động lại
    // nhiều lần trong khi 1 chuỗi re-enqueue từ trước vẫn còn đang chờ chạy
    // (initialDelay chưa hết), không tạo bản sao mới, chỉ để chuỗi cũ tự
    // tiếp diễn bình thường.
    fun schedulePeriodicWork(context: Context) {
        val request = OneTimeWorkRequestBuilder<TtsMyReadingPrecacheWorker>()
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    // ── Huỷ chuỗi — xem ghi chú ⚠️ HẠN CHẾ ĐÃ BIẾT ở đầu file. CHƯA có nơi
    // gọi ở bước này, đặt sẵn cho đối xứng, cùng tinh thần
    // MyReadingSyncScheduler.cancelAll().
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }
}