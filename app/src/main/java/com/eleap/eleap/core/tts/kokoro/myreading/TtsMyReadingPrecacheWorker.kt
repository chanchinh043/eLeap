// TtsMyReadingPrecacheWorker.kt
// Đặt tại: com/eleap/eleap/core/tts/kokoro/myreading/TtsMyReadingPrecacheWorker.kt
//
// Worker chạy nền bằng WorkManager — mục đích DUY NHẤT là "chủ động tải
// TRƯỚC khi người dùng mở bài", thay vì đợi TtsMyReadingDownloadGate tự hỏi
// lúc mở bài (xem TtsMyReadingDownloadGate.kt). KHÔNG BẮT BUỘC phải có —
// nếu bỏ qua worker này, tính năng vẫn hoạt động đúng (chỉ là audio pre-
// cache xuất hiện MUỘN HƠN, đúng lúc user mở bài lần kế thay vì trước đó).
//
// Mỗi chu kỳ: đọc toàn bộ job đang chờ từ TtsMyReadingPendingStore, hỏi lại
// status cho từng job —
//   READY     → enqueue TtsKokoroPackScheduler.enqueueEnsureReadingSynced()
//               NGAY (KHÔNG qua TtsMyReadingDownloadGate — đã biết chắc
//               READY từ chính lần hỏi này, gate chỉ cần thiết khi hỏi Drive
//               TRỰC TIẾP mà chưa xác nhận qua server), rồi xoá khỏi store.
//   FAILED    → xoá khỏi store — server báo lỗi vĩnh viễn cho bộ
//               (readingId, sid, contentHash) này, không có gì để chờ thêm.
//               Nếu sau này nội dung bài đổi, TtsMyReadingSyncTrigger sẽ tự
//               tạo job MỚI với contentHash khác.
//   PENDING/
//   PROCESSING → giữ nguyên trong store, hỏi lại ở chu kỳ sau.
//   UNKNOWN    → giữ nguyên (có thể chỉ là lỗi mạng tạm thời), hỏi lại ở
//               chu kỳ sau — KHÔNG xoá, tránh mất dấu job chỉ vì 1 lần gọi
//               mạng thất bại.
//
// Không dùng Hilt — WorkManager dùng default WorkerFactory (constructor
// rỗng), Worker tự gọi init() của các singleton cần dùng phòng trường hợp
// process bị hệ thống khởi động lại chạy Worker mà chưa qua
// MainActivity.onCreate() — cùng phong cách MyReadingSyncWorker.kt.
package com.eleap.eleap.core.tts.kokoro.myreading

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.eleap.eleap.core.tts.TtsVoiceSnapshot
import com.eleap.eleap.core.tts.kokoro.TtsKokoroPackScheduler

private const val TAG = "TtsMyReadingPrecacheWorker"

class TtsMyReadingPrecacheWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        TtsMyReadingPendingStore.init(applicationContext)
        TtsVoiceSnapshot.init(applicationContext)

        val baseUrl = TtsMyReadingConfig.baseUrl()
        if (baseUrl == null) {
            // Chưa cấu hình — không có gì để hỏi, coi như xong việc (KHÔNG
            // retry, vì retry cũng không giúp gì khi thiếu cấu hình).
            return Result.success()
        }

        val pending = TtsMyReadingPendingStore.getAll()
        if (pending.isEmpty()) {
            return Result.success()
        }

        Log.d(TAG, "doWork: kiểm tra ${pending.size} job đang chờ")
        val client = TtsMyReadingRequestClient(baseUrl)

        for (entry in pending) {
            try {
                val status = client.checkStatus(
                    readingId   = entry.readingId,
                    sid         = entry.sid,
                    contentHash = entry.contentHash,
                )

                when (status) {
                    TtsMyReadingJobStatus.READY -> {
                        Log.d(TAG, "doWork: reading_id=${entry.readingId} sid=${entry.sid} đã READY, enqueue tải")
                        TtsKokoroPackScheduler.enqueueEnsureReadingSynced(
                            applicationContext, entry.readingId, entry.sid
                        )
                        TtsMyReadingPendingStore.remove(entry.readingId, entry.sid)
                    }
                    TtsMyReadingJobStatus.FAILED -> {
                        Log.w(TAG, "doWork: reading_id=${entry.readingId} sid=${entry.sid} server báo FAILED, bỏ theo dõi")
                        TtsMyReadingPendingStore.remove(entry.readingId, entry.sid)
                    }
                    TtsMyReadingJobStatus.PENDING, TtsMyReadingJobStatus.PROCESSING -> {
                        // Giữ nguyên, hỏi lại chu kỳ sau — không log ồn ào
                        // mỗi chu kỳ cho trường hợp bình thường này.
                    }
                    TtsMyReadingJobStatus.UNKNOWN, null -> {
                        Log.d(TAG, "doWork: reading_id=${entry.readingId} sid=${entry.sid} chưa rõ status, thử lại chu kỳ sau")
                    }
                }
            } catch (e: Exception) {
                // 1 job lỗi không chặn các job còn lại trong cùng chu kỳ.
                Log.e(TAG, "doWork: lỗi kiểm tra reading_id=${entry.readingId} sid=${entry.sid}", e)
            }
        }

        return Result.success()
    }
}