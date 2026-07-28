// TtsMyReadingPrecacheWorker.kt
// Đặt tại: com/eleap/eleap/core/tts/kokoro/myreading/TtsMyReadingPrecacheWorker.kt
//
// Worker chạy nền — mục đích DUY NHẤT là "chủ động tải TRƯỚC khi người dùng
// mở bài", thay vì đợi TtsMyReadingDownloadGate tự hỏi lúc mở bài (xem
// TtsMyReadingDownloadGate.kt). KHÔNG BẮT BUỘC phải có — nếu bỏ qua worker
// này, tính năng vẫn hoạt động đúng (chỉ là audio pre-cache xuất hiện MUỘN
// HƠN, đúng lúc user mở bài lần kế thay vì trước đó).
//
// ⚠️ TỰ RE-ENQUEUE MỖI 5 PHÚT (không còn PeriodicWorkRequest 15 phút) — xem
// ghi chú ở TtsMyReadingPrecacheScheduler.kt. scheduleNextRun() PHẢI được
// gọi ở MỌI đường thoát của doWork() (kể cả khi baseUrl null/pending rỗng/
// có exception) — dùng `finally` để đảm bảo điều này, KHÔNG đặt rải rác ở
// từng `return` để tránh sót 1 nhánh nào đó vô tình làm đứt chuỗi.
//
// Mỗi chu kỳ: đọc toàn bộ job đang chờ từ TtsMyReadingPendingStore, hỏi lại
// status cho từng job —
//   READY     → CHỈ xoá khỏi store nếu isPackSynced() xác nhận cache CỤC BỘ
//               đã có thật (xem ghi chú ⚠️ bên dưới) — enqueue tải
//               (enqueueDownload(), per-sid) trước khi kiểm tra, rồi VẪN
//               GIỮ trong store nếu cache chưa có, để chu kỳ SAU tự kiểm
//               tra + tải lại cho tới khi thành công.
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
// ⚠️ VÌ SAO PHẢI KIỂM TRA isPackSynced() TRƯỚC KHI XOÁ KHỎI STORE (bug đã
// sửa): enqueueDownload() chỉ "bắn rồi quên" — enqueue 1 TtsKokoroPackWorker
// chạy bất đồng bộ, KHÔNG có retry (luôn trả Result.success() dù tải được
// hay không, coi mất mạng là bình thường — xem TtsKokoroPackSync.kt). Nếu
// xoá khỏi store NGAY sau khi gọi enqueueDownload() (như bản cũ), mà lượt
// tải đó THẤT BẠI vì bất kỳ lý do gì (race lúc app cold-start khi
// TtsKokoroConfig.registerIfConfigured() chưa kịp chạy, mất mạng đúng lúc,
// Drive lỗi tạm thời...) — job đã biến mất khỏi store, KHÔNG còn chu kỳ nào
// tự phát hiện lại để thử tải tiếp, audio MyReading đó sẽ không bao giờ tự
// xuất hiện cho tới khi người dùng tự mở lại đúng bài (kích hoạt
// TtsMyReadingDownloadGate → enqueueEnsureReadingSynced(), cơ chế CÓ retry
// thật). isPackSynced() (đọc 1 file marker cục bộ, không tốn mạng) là cách
// rẻ để xác nhận "đã thật sự có cache" trước khi coi job là xong.
//
// ⚠️ VÌ SAO enqueueDownload() (per-sid) — KHÔNG PHẢI enqueueEnsureReadingSynced():
// enqueueEnsureReadingSynced() dùng cơ chế "đã tải ĐỦ toàn bộ giọng, đánh
// dấu VĨNH VIỄN" (xem TtsKokoroPackDownloader.ensureReadingFullySynced() —
// READING_FULLY_SYNCED_MARKER, cấp CẢ BÀI, ghi 1 lần rồi không bao giờ kiểm
// tra lại Drive nữa) — đúng cho bài HỆ THỐNG (Drive có sẵn TOÀN BỘ giọng từ
// đầu), nhưng SAI cho MyReading (server tổng hợp TỪNG GIỌNG THEO YÊU CẦU,
// có thể có giọng MỚI xuất hiện SAU KHI giọng khác đã được đánh dấu "tải đủ
// vĩnh viễn"). Dùng enqueueDownload() (per-sid, gate theo syncIfNeeded() 24h
// — không có marker cả bài) để mỗi giọng MyReading được kiểm tra/tải độc
// lập, không bị chặn bởi việc giọng khác đã "xong" từ trước.
//
// Không dùng Hilt — WorkManager dùng default WorkerFactory (constructor
// rỗng), Worker tự gọi init() của các singleton cần dùng phòng trường hợp
// process bị hệ thống khởi động lại chạy Worker mà chưa qua
// MainActivity.onCreate() — cùng phong cách MyReadingSyncWorker.kt.
package com.eleap.eleap.core.tts.kokoro.myreading

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.eleap.eleap.core.tts.TtsVoiceSnapshot
import com.eleap.eleap.core.tts.kokoro.TtsKokoroPackDownloader
import com.eleap.eleap.core.tts.kokoro.TtsKokoroPackScheduler
import java.util.concurrent.TimeUnit

private const val TAG = "TtsMyReadingPrecacheWorker"

class TtsMyReadingPrecacheWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        try {
            TtsMyReadingPendingStore.init(applicationContext)
            TtsMyReadingSentRequestStore.init(applicationContext)
            TtsVoiceSnapshot.init(applicationContext)

            val baseUrl = TtsMyReadingConfig.baseUrl()
            if (baseUrl == null) {
                // Chưa cấu hình — không có gì để hỏi, coi như xong việc.
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
                            // ── Đã có cache CỤC BỘ thật sự (kiểm tra không tốn
                            // mạng) — job này xong việc, xoá khỏi store. ───────
                            if (TtsKokoroPackDownloader.isPackSynced(applicationContext, entry.readingId, entry.sid)) {
                                Log.d(TAG, "doWork: reading_id=${entry.readingId} sid=${entry.sid} đã có cache, bỏ theo dõi")
                                TtsMyReadingPendingStore.remove(entry.readingId, entry.sid)
                            } else {
                                // READY ở server nhưng CHƯA có cache cục bộ (lượt
                                // tải trước có thể đã thất bại vì race/mất mạng) —
                                // chủ động enqueue tải lại, nhưng KHÔNG xoá khỏi
                                // store — để chu kỳ SAU tự kiểm tra lại
                                // isPackSynced(), tới khi nào thật sự có cache mới
                                // thôi (xem ghi chú ⚠️ ở đầu file).
                                Log.d(TAG, "doWork: reading_id=${entry.readingId} sid=${entry.sid} READY nhưng chưa có cache, enqueue tải lại")
                                TtsKokoroPackScheduler.enqueueDownload(
                                    applicationContext, entry.readingId, entry.sid
                                )
                            }
                        }
                        TtsMyReadingJobStatus.FAILED -> {
                            Log.w(TAG, "doWork: reading_id=${entry.readingId} sid=${entry.sid} server báo FAILED, bỏ theo dõi")
                            TtsMyReadingPendingStore.remove(entry.readingId, entry.sid)
                            // ── Dọn luôn TtsMyReadingSentRequestStore — job đã
                            // FAILED VĨNH VIỄN cho đúng contentHash này, "đã gửi"
                            // không còn ý nghĩa gì để tiếp tục chặn gửi lại.
                            TtsMyReadingSentRequestStore.remove(entry.readingId, entry.sid)
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
        } finally {
            // ⚠️ LUÔN tự enqueue lượt kế tiếp, bất kể nhánh trên return ở đâu
            // (kể cả baseUrl null/pending rỗng/exception) — xem ghi chú ở
            // TtsMyReadingPrecacheScheduler.kt về lý do không dùng
            // PeriodicWorkRequest (giới hạn cứng 15 phút của WorkManager).
            scheduleNextRun(applicationContext)
        }
    }

    private fun scheduleNextRun(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<TtsMyReadingPrecacheWorker>()
            .setConstraints(constraints)
            .setInitialDelay(
                TtsMyReadingPrecacheScheduler.REPEAT_INTERVAL_MINUTES,
                TimeUnit.MINUTES,
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()

        // REPLACE — lượt này vừa chạy xong, thay thế chính nó bằng lượt kế
        // tiếp trong chuỗi. Cùng unique work name với
        // TtsMyReadingPrecacheScheduler.schedulePeriodicWork() để cancel()
        // luôn nhắm đúng chuỗi hiện tại.
        WorkManager.getInstance(context).enqueueUniqueWork(
            TtsMyReadingPrecacheScheduler.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}