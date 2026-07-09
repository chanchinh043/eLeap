// TtsReadingHistory.kt
// Đặt tại: com/eleap/eleap/core/tts/pregen/TtsReadingHistory.kt
//
// Ghi nhớ "đã từng mở những bài đọc nào" — KHÔNG đếm số lần mở, chỉ cần biết
// 2 việc: (1) bài này đã từng được mở chưa, (2) lần mở gần nhất là khi nào.
// Lưu XUỐNG ĐĨA (SharedPreferences) — vì đây là "trí nhớ dài hạn", phải sống
// sót qua việc app bị kill hẳn rồi mở lại (khác với TtsForegroundReading —
// chỉ giữ trong RAM vì là trạng thái tức thời).
//
// TtsPregenWorker dùng dữ liệu ở đây để biết: khi KHÔNG có bài nào đang mở
// (TtsForegroundReading rỗng), thì nên ưu tiên generate ngầm bài nào trước —
// trả lời bằng getHistorySortedByRecent(), sắp xếp bài mở gần đây nhất lên
// đầu danh sách.
//
// Singleton thủ công, KHÔNG dùng Hilt/DI — cùng phong cách với CurrentUser,
// SyncCursor, SupabaseClientProvider.
package com.eleap.eleap.core.tts.pregen

import android.content.Context
import android.content.SharedPreferences

object TtsReadingHistory {

    private const val PREFS_NAME = "tts_pregen_history"

    // Mỗi bài đọc lưu 1 key riêng dạng "opened_at_{readingId}" → giá trị là
    // epoch millis của lần mở gần nhất. Dùng prefix cố định để liệt kê lại
    // toàn bộ danh sách bài đã mở qua prefs.all (xem getHistorySortedByRecent()).
    private const val KEY_PREFIX = "opened_at_"

    private lateinit var prefs: SharedPreferences

    // Gọi 1 lần duy nhất, ở nơi khởi tạo app (MainActivity.onCreate(), cùng
    // chỗ với CurrentUser.init()/SyncCursor.init()) — và cũng tự an toàn nếu
    // lỡ được gọi lại nhiều lần (SharedPreferences.getSharedPreferences() vốn
    // đã tự cache theo tên file, gọi lại không tốn kém gì đáng kể).
    fun init(context: Context) {
        prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun keyFor(readingId: String) = "$KEY_PREFIX$readingId"

    // ── Ghi nhận 1 bài vừa được mở ────────────────────────────────────────
    // Gọi từ nơi mở bài đọc (ReadingViewModel.loadReading() hoặc tương
    // đương) — mỗi lần gọi đều CẬP NHẬT LẠI timestamp thành "bây giờ", kể cả
    // nếu bài này đã từng mở trước đó. Đây chính là cơ chế để
    // getHistorySortedByRecent() phản ánh đúng "bài nào vừa đọc gần đây
    // nhất", không phải "bài nào mở lần đầu gần đây nhất".
    fun markOpened(readingId: String, nowMillis: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(keyFor(readingId), nowMillis).apply()
    }

    // ── Danh sách toàn bộ readingId đã từng mở, sắp xếp GẦN NHẤT → XA NHẤT ──
    // TtsPregenWorker duyệt qua danh sách này theo đúng thứ tự trả về để
    // quyết định generate ngầm bài nào trước, khi không có bài nào đang mở
    // ở foreground.
    //
    // Dùng prefs.all rồi lọc theo KEY_PREFIX thay vì lưu thêm 1 "danh sách
    // readingId" riêng — tránh phải đồng bộ 2 nguồn dữ liệu (danh sách +
    // từng timestamp) mỗi lần markOpened(), giảm khả năng lệch dữ liệu nếu
    // app bị kill giữa chừng lúc đang ghi.
    fun getHistorySortedByRecent(): List<String> {
        return prefs.all
            .filterKeys { it.startsWith(KEY_PREFIX) }
            .mapNotNull { (key, value) ->
                val readingId = key.removePrefix(KEY_PREFIX)
                val openedAt = value as? Long ?: return@mapNotNull null
                readingId to openedAt
            }
            .sortedByDescending { (_, openedAt) -> openedAt }
            .map { (readingId, _) -> readingId }
    }

    // ── Bỏ theo dõi 1 bài (vd bài đã bị xoá hẳn — MyReading user tự xoá) ────
    // Không bắt buộc dùng ngay ở bước này — chỉ để dọn dữ liệu cũ nếu cần,
    // tránh Worker mất công kiểm tra 1 readingId không còn tồn tại trong DB
    // nữa (TtsReadingContentReader ở bước sau sẽ tự trả về danh sách rỗng
    // cho bài đã xoá, nên không gọi hàm này cũng không gây lỗi, chỉ hơi phí
    // 1 vòng kiểm tra vô ích).
    fun forget(readingId: String) {
        prefs.edit().remove(keyFor(readingId)).apply()
    }
}