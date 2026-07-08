// TtsEngine.kt
// Đặt tại: com/eleap/eleap/core/tts/TtsEngine.kt
//
// Interface chung cho mọi engine đọc (TTS) — TtsManager chỉ gọi qua đây,
// không biết bên dưới đang chạy AndroidTtsEngine (TextToSpeech có sẵn của
// Android) hay KokoroTtsEngine (sherpa-onnx, on-device, chất lượng cao hơn).
//
// Thiết kế tối giản — chỉ đủ những gì TtsManager đang cần dùng thật sự (xem
// TtsManager.kt hiện tại: init/speak/stop/setSpeechRate/shutdown). Không thêm
// tính năng thừa (vd chọn giọng đọc, ngôn ngữ khác) ở bước này — có thể mở
// rộng interface sau nếu cần.
package com.eleap.eleap.core.tts

import android.content.Context

interface TtsEngine {

    // Khởi tạo engine — có thể là async (vd AndroidTtsEngine cần callback từ
    // TextToSpeech), nên dùng callback onReady thay vì suspend fun, để khớp
    // với cách TtsManager.init() hiện tại đang gọi (không phải coroutine).
    // onReady(true) khi engine sẵn sàng nhận speak(), onReady(false) nếu khởi
    // tạo thất bại (TtsManager sẽ tự quyết định fallback hay báo lỗi).
    fun init(context: Context, onReady: (success: Boolean) -> Unit)

    // Nói 1 câu — luôn ngắt câu đang đọc dở (tương đương QUEUE_FLUSH của
    // Android TextToSpeech) để tránh chồng âm thanh khi người dùng bấm/chuyển
    // từ liên tiếp nhanh.
    fun speak(text: String)

    // Dừng đọc ngay lập tức.
    fun stop()

    // Đổi tốc độ đọc — rate đã được TtsManager clamp về [MIN_RATE, MAX_RATE]
    // từ trước khi gọi xuống đây, engine không cần tự clamp lại.
    fun setSpeechRate(rate: Float)

    // Engine đã init xong và sẵn sàng nhận speak() chưa.
    fun isReady(): Boolean

    // Giải phóng tài nguyên — gọi ở MainActivity.onDestroy().
    fun shutdown()
}