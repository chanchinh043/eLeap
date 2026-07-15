// TtsMyReadingPrecacheScheduler.kt
// Đặt tại: com/eleap/eleap/core/tts/kokoro/myreading/TtsMyReadingPrecacheScheduler.kt
//
// Nơi DUY NHẤT gọi WorkManager để lên lịch TtsMyReadingPrecacheWorker — cùng
// phong cách MyReadingSyncScheduler.kt/SyncScheduler.kt.
//
// Chu kỳ 15 phút: đủ nhanh để trải nghiệm "audio đã sẵn sàng gần như ngay
// sau khi mở lại app" (server tổng hợp Kokoro cho 1 bài thường chỉ mất vài
// chục giây tới vài phút), nhưng không quá dày để tốn pin/mạng — WorkManager
// còn tự cộng thêm độ trễ do gộp lịch hệ thống (Doze/App Standby), 15 phút
// là khoảng tối thiểu WorkManager cho phép với PeriodicWorkRequest.
package com.eleap.eleap.core.tts.kokoro.myreading

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object TtsMyReadingPrecacheScheduler {

    private const val PERIODIC_NAME = "tts_myreading_precache_periodic"

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    // ── Gọi 1 lần ở MainActivity.onCreate() — KEEP: không tạo chồng lịch
    // mới nếu app khởi động lại nhiều lần.
    fun schedulePeriodicWork(context: Context) {
        val request = PeriodicWorkRequestBuilder<TtsMyReadingPrecacheWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    // ── Huỷ lịch — không bắt buộc gọi (worker tự no-op khi store rỗng/chưa
    // cấu hình), chỉ cung cấp cho đối xứng với MyReadingSyncScheduler.cancelAll().
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_NAME)
    }
}