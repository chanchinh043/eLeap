// MyReadingSyncScheduler.kt
// Đặt tại: feature/myreading/sync/MyReadingSyncScheduler.kt
//
// Nơi DUY NHẤT gọi WorkManager để lên lịch/enqueue công việc sync cho
// MyReading — bản tương đương core/sync/SyncScheduler.kt (dùng cho
// user_vocabulary) nhưng cho bảng my_readings. Các nơi khác trong app
// (AddMyReadingScreen, MyReadingListScreen, MainActivity, LoginScreen) chỉ
// gọi vào đây, không tự đụng WorkManager trực tiếp.
//
// Tần suất đúng theo thiết kế đã chốt (giống hệt bộ vocab):
//   - Update: batch mỗi 3 tiếng          → MyReadingSyncPushWorker định kỳ 3h
//   - Delta/Full pull: mỗi 5 tiếng        → MyReadingSyncPullWorker định kỳ 5h
//     (MyReadingSyncPullWorker tự quyết định delta hay full bên trong
//     MyReadingSyncEngine)
//   - Create/Delete: đồng bộ NGAY LẬP TỨC → enqueueImmediatePush() — 1 lần,
//     one-time work request, KHÔNG đợi tới chu kỳ 3h.
//
// ⚠️ 3 tên unique work BẮT BUỘC khác hẳn với core/sync/SyncScheduler.kt
// ("myreading_sync_push_periodic" thay vì "sync_push_periodic", v.v.) — để
// WorkManager không nhầm lẫn 2 lịch của 2 bộ sync độc lập (vocab và
// myreading) thành 1, dẫn đến ghi đè/huỷ nhầm lịch của nhau.
//
// Singleton thủ công, KHÔNG dùng Hilt/DI — cùng phong cách với
// core/sync/SyncScheduler.kt.
package com.eleap.eleap.feature.myreading.sync

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

object MyReadingSyncScheduler {

    private const val PERIODIC_PUSH_NAME = "myreading_sync_push_periodic"
    private const val PERIODIC_PULL_NAME = "myreading_sync_pull_periodic"
    private const val IMMEDIATE_PUSH_NAME = "myreading_sync_push_immediate"

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    // ── Gọi 1 lần ở MainActivity.onCreate() — đăng ký 2 lịch chạy nền ────────
    // KEEP: nếu app khởi động lại nhiều lần, không tạo chồng lịch mới, giữ
    // nguyên lịch đã đăng ký từ trước (đúng ý "chạy nền liên tục", không phải
    // "chạy lại từ đầu mỗi lần mở app").
    fun schedulePeriodicWork(context: Context) {
        val workManager = WorkManager.getInstance(context)

        val pushRequest = PeriodicWorkRequestBuilder<MyReadingSyncPushWorker>(3, TimeUnit.HOURS)
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_PUSH_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            pushRequest
        )

        val pullRequest = PeriodicWorkRequestBuilder<MyReadingSyncPullWorker>(5, TimeUnit.HOURS)
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_PULL_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            pullRequest
        )
    }

    // ── Gọi ngay sau khi tạo/xoá 1 bài đọc (AddMyReadingScreen, ReadingViewModel) ──
    // REPLACE: nếu tạo/xoá liên tiếp nhiều bài trong thời gian ngắn, chỉ cần
    // 1 lần push cuối cùng chạy là đủ gom hết — không cần xếp hàng nhiều
    // request giống hệt nhau.
    fun enqueueImmediatePush(context: Context) {
        val workManager = WorkManager.getInstance(context)
        val request = OneTimeWorkRequestBuilder<MyReadingSyncPushWorker>()
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
    // (Không bắt buộc — MyReadingSyncPushWorker/MyReadingSyncPullWorker tự bỏ
    // qua khi guest, nên không huỷ cũng không sao, chỉ là chạy "không việc gì"
    // mỗi chu kỳ.)
    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(PERIODIC_PUSH_NAME)
            cancelUniqueWork(PERIODIC_PULL_NAME)
            cancelUniqueWork(IMMEDIATE_PUSH_NAME)
        }
    }
}