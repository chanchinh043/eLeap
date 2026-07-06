// SyncWorker.kt
// Đặt tại: com/eleap/eleap/core/sync/SyncWorker.kt
//
// 2 Worker chạy nền bằng WorkManager, đều chỉ gọi lại SyncEngine — không tự
// viết logic sync ở đây:
//
//   SyncPushWorker — chạy định kỳ mỗi 3 tiếng, đẩy toàn bộ pending_create/
//                    update/delete lên server. Cũng được dùng lại cho việc
//                    "đẩy ngay lập tức" khi tạo/xoá từ (enqueue 1 lần qua
//                    SyncScheduler.enqueueImmediatePush()).
//   SyncPullWorker — chạy định kỳ mỗi 5 tiếng. Gọi SyncEngine.syncNow() —
//                    hàm này TỰ flush pending trước (đúng nguyên tắc "flush
//                    phụ trước mỗi lần pull"), rồi tự chọn delta hay full
//                    pull theo SyncCursor.shouldRunFullPull().
//
// Không dùng Hilt — WorkManager dùng default WorkerFactory (constructor
// rỗng), Worker tự gọi SyncEngine.init(applicationContext) phòng trường hợp
// process bị hệ thống khởi động lại chạy Worker mà chưa qua MainActivity.onCreate().
package com.eleap.eleap.core.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.eleap.eleap.core.auth.CurrentUser

class SyncPushWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            SyncCursor.init(applicationContext)
            SyncEngine.init(applicationContext)

            val userId = CurrentUser.userId.value
            if (userId == CurrentUser.GUEST_ID) return Result.success()

            val pushed = SyncEngine.pushPending(userId)
            Log.d("SyncPushWorker", "đã đẩy $pushed dòng")
            Result.success()
        } catch (e: Exception) {
            Log.e("SyncPushWorker", "lỗi push, sẽ retry", e)
            Result.retry()
        }
    }
}

class SyncPullWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            SyncCursor.init(applicationContext)
            SyncEngine.init(applicationContext)

            val userId = CurrentUser.userId.value
            if (userId == CurrentUser.GUEST_ID) return Result.success()

            val outcome = SyncEngine.syncNow(userId)
            if (outcome.error != null) {
                Log.e("SyncPullWorker", "lỗi pull: ${outcome.error}, sẽ retry")
                return Result.retry()
            }
            Log.d(
                "SyncPullWorker",
                "gửi ${outcome.pushedCount}, nhận ${outcome.pulledCount}, full=${outcome.ranFullPull}"
            )
            Result.success()
        } catch (e: Exception) {
            Log.e("SyncPullWorker", "lỗi pull, sẽ retry", e)
            Result.retry()
        }
    }
}