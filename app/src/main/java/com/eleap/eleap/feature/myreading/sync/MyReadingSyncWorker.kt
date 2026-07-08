// MyReadingSyncWorker.kt
// Đặt tại: feature/myreading/sync/MyReadingSyncWorker.kt
//
// 2 Worker chạy nền bằng WorkManager, đều chỉ gọi lại MyReadingSyncEngine —
// không tự viết logic sync ở đây. Bản tương đương core/sync/SyncWorker.kt
// (dùng cho user_vocabulary) nhưng cho bảng my_readings:
//
//   MyReadingSyncPushWorker — chạy định kỳ mỗi 3 tiếng, đẩy toàn bộ pending
//                             create/update/delete lên server. Cũng dùng lại
//                             cho việc "đẩy ngay lập tức" khi tạo/xoá bài
//                             (enqueue 1 lần qua
//                             MyReadingSyncScheduler.enqueueImmediatePush()).
//   MyReadingSyncPullWorker — chạy định kỳ mỗi 5 tiếng. Gọi
//                             MyReadingSyncEngine.syncNow() — hàm này TỰ
//                             flush pending trước, rồi tự chọn delta hay
//                             full pull theo
//                             MyReadingSyncCursor.shouldRunFullPull().
//
// Không dùng Hilt — WorkManager dùng default WorkerFactory (constructor
// rỗng), Worker tự gọi MyReadingSyncEngine.init(applicationContext) phòng
// trường hợp process bị hệ thống khởi động lại chạy Worker mà chưa qua
// MainActivity.onCreate().
package com.eleap.eleap.feature.myreading.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.eleap.eleap.core.auth.CurrentUser

class MyReadingSyncPushWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            MyReadingSyncCursor.init(applicationContext)
            MyReadingSyncEngine.init(applicationContext)

            val userId = CurrentUser.userId.value
            if (userId == CurrentUser.GUEST_ID) return Result.success()

            val pushed = MyReadingSyncEngine.pushPending(userId)
            Log.d("MyReadingSyncPushWorker", "đã đẩy $pushed bài")
            Result.success()
        } catch (e: Exception) {
            Log.e("MyReadingSyncPushWorker", "lỗi push, sẽ retry", e)
            Result.retry()
        }
    }
}

class MyReadingSyncPullWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            MyReadingSyncCursor.init(applicationContext)
            MyReadingSyncEngine.init(applicationContext)

            val userId = CurrentUser.userId.value
            if (userId == CurrentUser.GUEST_ID) return Result.success()

            val outcome = MyReadingSyncEngine.syncNow(userId)
            if (outcome.error != null) {
                Log.e("MyReadingSyncPullWorker", "lỗi pull: ${outcome.error}, sẽ retry")
                return Result.retry()
            }
            Log.d(
                "MyReadingSyncPullWorker",
                "gửi ${outcome.pushedCount}, nhận ${outcome.pulledCount}, full=${outcome.ranFullPull}"
            )
            Result.success()
        } catch (e: Exception) {
            Log.e("MyReadingSyncPullWorker", "lỗi pull, sẽ retry", e)
            Result.retry()
        }
    }
}