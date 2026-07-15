// TtsMyReadingSentRequestStore.kt
// Đặt tại: com/eleap/eleap/core/tts/kokoro/myreading/TtsMyReadingSentRequestStore.kt
//
// Lưu XUỐNG ĐĨA (SharedPreferences) contentHash CUỐI CÙNG mà 1 (readingId,
// sid) đã gửi lên server VÀ ĐƯỢC SERVER XÁC NHẬN ĐÃ LƯU (HTTP 200 từ POST
// /tts/myreading/request — xem TtsMyReadingRequestClient.requestSynthesis(),
// và ghi chú ở main.py phía server về việc trả lỗi 503 thay vì status giả
// khi ghi DB thất bại) — để TtsMyReadingSyncTrigger KHÔNG gửi lại request
// cho đúng (readingId, sid, contentHash) đã gửi thành công trước đó.
//
// ⚠️ PHÂN BIỆT VỚI TtsMyReadingPendingStore (KHÁC MỤC ĐÍCH, KHÔNG THAY THẾ
// NHAU):
//   - TtsMyReadingPendingStore: "job nào ĐANG CHỜ ready" — dùng bởi
//     TtsMyReadingPrecacheWorker để biết cần HỎI LẠI status cho job nào.
//     Entry bị XOÁ khi job ready/failed (không cần hỏi lại nữa).
//   - TtsMyReadingSentRequestStore (file này): "(readingId, sid) này đã
//     GỬI request cho đúng contentHash nào rồi" — dùng bởi
//     TtsMyReadingSyncTrigger để biết CÓ CẦN GỬI request mới hay không.
//     Entry KHÔNG bị xoá khi job ready/failed — chỉ bị GHI ĐÈ khi nội dung
//     bài đổi (contentHash mới khác hẳn), vì lúc đó rõ ràng cần gửi lại.
//   Cả 2 file cùng tồn tại song song, phục vụ 2 câu hỏi khác nhau trong
//   cùng pipeline.
//
// ⚠️ "ĐÃ XÁC NHẬN" nghĩa là gì: chỉ tính là "đã gửi" nếu server trả về HTTP
// 200 kèm 1 trong 4 status hợp lệ (pending/processing/ready/failed) — nghĩa
// là request ĐÃ ĐƯỢC GHI VÀO DB CỦA SERVER (xem main.py: lỗi ghi DB giờ trả
// 503, KHÔNG còn trả status giả). Nếu gọi mạng thất bại (timeout, mất
// mạng, server trả 503...) — TtsMyReadingRequestClient.requestSynthesis()
// trả về null — TUYỆT ĐỐI KHÔNG được markSent() trong trường hợp đó, vì
// server CÓ THỂ chưa hề nhận được gì, ghi nhớ "đã gửi" lúc này sẽ làm bài
// đó mãi mãi không bao giờ có audio (Trigger sẽ luôn nghĩ là đã gửi rồi).
//
// ⚠️ MẤT DỮ LIỆU Ở ĐÂY KHÔNG NGHIÊM TRỌNG: nếu SharedPreferences bị xoá
// (gỡ cài đặt, xoá dữ liệu app), hậu quả DUY NHẤT là Trigger sẽ tưởng
// "chưa từng gửi" và gửi lại — server tự dedup theo (readingId, sid,
// contentHash) ở tầng DB (xem jobs.py: PRIMARY KEY 3 cột này), nên gửi lại
// vô hại, chỉ tốn 1 lượt gọi mạng không cần thiết, không tạo job trùng.
//
// Singleton thủ công, KHÔNG dùng Hilt/DI — cùng phong cách với
// TtsMyReadingPendingStore.kt/TtsVoiceSnapshot.kt.
package com.eleap.eleap.core.tts.kokoro.myreading

import android.content.Context
import android.content.SharedPreferences

private const val PREFS_NAME = "tts_myreading_sent_requests"

object TtsMyReadingSentRequestStore {

    private lateinit var prefs: SharedPreferences

    // Gọi 1 lần ở MainActivity.onCreate(), cùng chỗ với
    // TtsMyReadingPendingStore.init().
    fun init(context: Context) {
        prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ── Khoá lưu trữ: gộp readingId + sid — mỗi (bài, giọng) có ĐÚNG 1
    // dòng "contentHash cuối cùng đã gửi thành công", KHÔNG cần giữ lịch sử
    // nhiều contentHash cũ (contentHash MỚI luôn thay thế hoàn toàn ý nghĩa
    // của contentHash CŨ — nội dung bài đã đổi thì job cũ hết giá trị tham
    // chiếu, xem TtsMyReadingPendingStore.add() cũng cùng logic loại bỏ
    // entry cũ khi contentHash khác). Dùng dấu phân cách "|" — KHÔNG dùng
    // "_"/":" vì readingId có thể là UUID chứa các ký tự đó, tránh nhầm lẫn
    // khi 2 readingId khác nhau vô tình ghép ra cùng 1 khoá (dù thực tế khó
    // xảy ra vì luôn có sid theo sau, nhưng an toàn vẫn hơn).
    private fun key(readingId: String, sid: Int): String = "sent|$readingId|$sid"

    // ── Điểm gọi CHÍNH cho TtsMyReadingSyncTrigger — TRƯỚC khi gọi
    // requestSynthesis(), kiểm tra xem (readingId, sid) này đã từng gửi
    // ĐÚNG contentHash này và được server xác nhận chưa. true = đã gửi rồi,
    // BỎ QUA, không gửi lại. false = CHƯA gửi (lần đầu) hoặc nội dung bài
    // đã đổi (contentHash khác lần gửi trước) — cần gửi.
    fun hasSentSameContent(readingId: String, sid: Int, contentHash: String): Boolean {
        if (!::prefs.isInitialized) return false
        return prefs.getString(key(readingId, sid), null) == contentHash
    }

    // ── Ghi nhớ "đã gửi thành công" — CHỈ gọi SAU KHI
    // TtsMyReadingRequestClient.requestSynthesis() trả về KHÁC NULL (nghĩa
    // là server đã trả HTTP 200 kèm status hợp lệ, xem ghi chú "ĐÃ XÁC
    // NHẬN" ở đầu file). KHÔNG gọi hàm này nếu requestSynthesis() trả về
    // null (lỗi mạng/server) — Trigger cần tự thử gửi lại ở lần trigger sau
    // trong trường hợp đó.
    fun markSent(readingId: String, sid: Int, contentHash: String) {
        if (!::prefs.isInitialized) return
        prefs.edit().putString(key(readingId, sid), contentHash).apply()
    }

    // ── Xoá 1 entry — dùng khi cần buộc gửi lại dù contentHash không đổi
    // (vd job bị server đánh 'failed' vĩnh viễn và người dùng muốn thử lại
    // thủ công) — CHƯA có nơi gọi ở bước này, đặt sẵn để dùng sau nếu cần,
    // cùng tinh thần "đặt sẵn hàm từ sớm" như update_job_status() bên
    // jobs.py đã làm.
    fun remove(readingId: String, sid: Int) {
        if (!::prefs.isInitialized) return
        prefs.edit().remove(key(readingId, sid)).apply()
    }
}