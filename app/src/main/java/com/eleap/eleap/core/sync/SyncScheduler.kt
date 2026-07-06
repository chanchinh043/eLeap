// SyncScheduler.kt
// Đặt tại: com/eleap/eleap/core/sync/SyncScheduler.kt
//
// Nơi DUY NHẤT gọi WorkManager để lên lịch/enqueue công việc sync. Các nơi
// khác trong app (SaveWordButton, VocabViewModel, MainActivity) chỉ gọi vào
// đây, không tự đụng WorkManager trực tiếp.
//
// Tần suất đúng theo thiết kế đã chốt:
//   - Update: batch mỗi 3 tiếng          → SyncPushWorker định kỳ 3h
//   - Delta/Full pull: mỗi 5 tiếng        → SyncPullWorker định kỳ 5h
//     (SyncPullWorker tự quyết định delta hay full bên trong SyncEngine)
//   - Create/Delete: đồng bộ NGAY LẬP TỨC → enqueueImmediatePush() — 1 lần,
//     one-time work request, KHÔNG đợi tới chu kỳ 3h.
//
// Singleton thủ công, KHÔNG dùng Hilt/DI.
package com.eleap.eleap.core.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object SyncScheduler {

    private const val PERIODIC_PUSH_NAME = "sync_push_periodic"
    private const val PERIODIC_PULL_NAME = "sync_pull_periodic"
    private const val IMMEDIATE_PUSH_NAME = "sync_push_immediate"

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    // ── Gọi 1 lần ở MainActivity.onCreate() — đăng ký 2 lịch chạy nền ────────
    // KEEP: nếu app khởi động lại nhiều lần, không tạo chồng lịch mới, giữ
    // nguyên lịch đã đăng ký từ trước (đúng ý "chạy nền liên tục", không phải
    // "chạy lại từ đầu mỗi lần mở app").
    fun schedulePeriodicWork(context: Context) {
        val workManager = WorkManager.getInstance(context)

        val pushRequest = PeriodicWorkRequestBuilder<SyncPushWorker>(3, TimeUnit.HOURS)
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_PUSH_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            pushRequest
        )

        val pullRequest = PeriodicWorkRequestBuilder<SyncPullWorker>(5, TimeUnit.HOURS)
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_PULL_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            pullRequest
        )
    }

    // ── Gọi ngay sau khi tạo/xoá 1 từ (SaveWordButton, VocabViewModel) ───────
    // REPLACE: nếu bấm lưu/xoá liên tiếp nhiều từ trong thời gian ngắn, chỉ
    // cần 1 lần push cuối cùng chạy là đủ gom hết — không cần xếp hàng nhiều
    // request giống hệt nhau.
    fun enqueueImmediatePush(context: Context) {
        val workManager = WorkManager.getInstance(context)
        val request = OneTimeWorkRequestBuilder<SyncPushWorker>()
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniqueWork(
            IMMEDIATE_PUSH_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    // ── Huỷ toàn bộ lịch nền — gọi khi đăng xuất nếu muốn dừng hẳn sync ─────
    // (Không bắt buộc — SyncPushWorker/SyncPullWorker tự bỏ qua khi guest,
    // nên không huỷ cũng không sao, chỉ là chạy "không việc gì" mỗi chu kỳ.)
    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(PERIODIC_PUSH_NAME)
            cancelUniqueWork(PERIODIC_PULL_NAME)
            cancelUniqueWork(IMMEDIATE_PUSH_NAME)
        }
    }
}