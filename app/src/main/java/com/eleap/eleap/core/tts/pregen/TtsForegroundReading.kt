// TtsForegroundReading.kt
// Đặt tại: com/eleap/eleap/core/tts/pregen/TtsForegroundReading.kt
//
// Giữ trạng thái "đang mở bài đọc nào NGAY LÚC NÀY" — CHỈ trong RAM, KHÔNG
// lưu đĩa. Khác hẳn TtsReadingHistory (lưu SharedPreferences, sống sót qua
// việc kill app): đây là trạng thái TỨC THỜI — khi app bị kill, "đang mở"
// đương nhiên không còn đúng nữa; khi mở lại app, giá trị này tự nhiên bắt
// đầu lại là null (chưa mở bài nào), cho tới khi người dùng vào 1
// ReadingScreen mới thì set lại. Không có gì để "khôi phục" ở đây cả.
//
// TtsPregenWorker đọc StateFlow này liên tục (trước mỗi item xử lý — xem
// TtsPregenWorker.kt) để biết có nên ưu tiên tuyệt đối 1 bài nào đó không,
// và để phát hiện người dùng vừa CHUYỂN sang bài khác giữa lúc Worker đang
// generate ngầm, nhằm ngắt và đổi hướng ưu tiên ngay lập tức.
//
// Singleton thủ công, KHÔNG dùng Hilt/DI — cùng phong cách với CurrentUser,
// TtsReadingHistory.
package com.eleap.eleap.core.tts.pregen

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object TtsForegroundReading {

    // null nghĩa là hiện KHÔNG có màn đọc nào đang mở (vd người dùng đang ở
    // màn Vocab, màn Login, hoặc vừa rời khỏi ReadingScreen).
    private val _currentReadingId = MutableStateFlow<String?>(null)
    val currentReadingId: StateFlow<String?> = _currentReadingId

    // ── Gọi khi vào 1 bài đọc ────────────────────────────────────────────
    // Gọi từ ReadingViewModel.loadReading() (hoặc DisposableEffect ở
    // ReadingScreen) ngay khi biết readingId đang được mở. An toàn khi gọi
    // lại nhiều lần với cùng readingId (vd recompose) — chỉ đơn giản ghi đè
    // lại đúng giá trị đó, không có tác dụng phụ gì thêm.
    fun set(readingId: String) {
        _currentReadingId.value = readingId
    }

    // ── Gọi khi rời khỏi màn đọc ──────────────────────────────────────────
    // Gọi từ onBack hoặc DisposableEffect.onDispose ở ReadingScreen — để
    // TtsPregenWorker biết "không còn ưu tiên tuyệt đối bài nào nữa", tự
    // rơi xuống xử lý danh sách lịch sử (TtsReadingHistory) như bình
    // thường.
    fun clear() {
        _currentReadingId.value = null
    }
}