// TtsPregenScheduler.kt
// Đặt tại: com/eleap/eleap/core/tts/pregen/TtsPregenScheduler.kt
//
// Nơi DUY NHẤT gọi WorkManager để enqueue TtsPregenWorker — các nơi khác
// trong app (MainActivity, TtsForegroundReading, TtsVoiceSnapshot) chỉ gọi
// vào đây, không tự đụng WorkManager trực tiếp. Cùng phong cách với
// SyncScheduler.kt/MyReadingSyncScheduler.kt.
//
// ⚠️ KHÁC với SyncScheduler (không có lịch định kỳ + push tức thời riêng
// biệt) — TtsPregenWorker chỉ có 1 kiểu duy nhất: "hãy chạy nếu chưa chạy",
// vì bản thân Worker đã tự chạy LIÊN TỤC cho tới khi hết việc (xem thiết kế
// ở TtsPregenWorker.kt, mục 6e) chứ không phải kiểu "làm 1 chút rồi nghỉ,
// đợi lịch kế tiếp" như SyncPushWorker. Vì vậy chỉ cần enqueueUniqueWork với
// ExistingWorkPolicy.KEEP — nếu đã có 1 lượt đang chạy, TUYỆT ĐỐI không tạo
// bản sao chạy song song (2 Worker cùng generate audio, cùng ghi file, dễ
// giẫm chân nhau vô ích); nếu lượt trước ĐÃ CHẠY XONG (hết việc, trả về
// Result.success()), KEEP vẫn cho phép enqueue lại bình thường vì lúc đó
// không còn work nào "đang chờ/đang chạy" dưới tên unique này nữa.
//
// enqueueWork() được gọi lại (an toàn gọi nhiều lần, nhờ KEEP) từ 3 điểm:
//   a. Lúc khởi tạo app (MainActivity.onCreate()) — để Worker tự "resume"
//      generate dở dang từ phiên trước, kể cả khi chưa mở bài đọc nào.
//   b. Ngay sau TtsForegroundReading.set(readingId) khi mở 1 bài đọc — đảm
//      bảo có 1 lượt chạy đang "biết" ưu tiên bài vừa mở (nếu lượt cũ đã
//      chạy xong/không còn chạy nữa, cần enqueue lại; nếu đang chạy dở,
//      Worker tự phát hiện đổi bài qua vòng lặp kiểm tra trước mỗi item,
//      không cần enqueue mới cũng tự đúng, nhưng gọi thêm ở đây không hại
//      gì nhờ KEEP).
//   c. Ngay sau khi giọng Kokoro bị đổi (trong TtsVoiceSnapshot cập nhật)
//      — cùng lý do như (b), đảm bảo luôn có 1 lượt chạy "sống" để nhận ra
//      thay đổi này càng sớm càng tốt nếu Worker trước đó đã chạy xong.
//
// Không cần networkConstraint (khác SyncScheduler) — pre-cache TTS là công
// việc hoàn toàn OFFLINE (đọc SQLite local + chạy model Kokoro on-device),
// không gọi mạng nên không cần đợi có kết nối.
//
// Singleton thủ công, KHÔNG dùng Hilt/DI — cùng phong cách với SyncScheduler.
package com.eleap.eleap.core.tts.pregen

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

object TtsPregenScheduler {

    private const val UNIQUE_WORK_NAME = "tts_pregen_work"

    // ── Enqueue 1 lượt chạy TtsPregenWorker nếu chưa có lượt nào đang chạy
    // dưới tên unique này. KEEP thay vì REPLACE — cố tình KHÔNG huỷ lượt
    // đang chạy dở để enqueue mới, vì bản thân Worker đang chạy đã TỰ kiểm
    // tra lại trạng thái (bài đang mở/giọng đã đổi) trước mỗi item rồi, huỷ
    // đi enqueue lại chỉ tổ mất công chạy lại từ đầu init Kokoro (có thể
    // mất vài giây) một cách không cần thiết.
    fun enqueueWork(context: Context) {
        val workManager = WorkManager.getInstance(context)
        val request = OneTimeWorkRequestBuilder<TtsPregenWorker>().build()
        workManager.enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    // ── Huỷ lượt đang chạy (nếu có) — KHÔNG bắt buộc dùng ở bước này, chỉ
    // để dọn dẹp nếu sau này cần (vd nút "tạm dừng pre-cache" trong màn cài
    // đặt debug). Không có nơi nào gọi hàm này trong thiết kế hiện tại.
    fun cancelWork(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }
}