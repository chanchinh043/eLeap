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
//
// ⚠️ MỚI: setSpeaker(sid) — chỉ có ý nghĩa với KokoroTtsEngine (nhiều giọng
// trong 1 model multi-speaker). Có default body RỖNG để AndroidTtsEngine
// không cần override gì (TextToSpeech không có khái niệm speaker id kiểu
// này) — tránh phải sửa AndroidTtsEngine.kt chỉ vì thêm tính năng debug tạm
// thời cho riêng Kokoro.
//
// ⚠️ MỚI (core/tts/pregen/): generateAudio(text, sid) — sinh audio THÔ
// (samples + sampleRate), KHÔNG phát ra loa, dùng cho TtsPregenWorker để lưu
// sẵn file cache (xem TtsAudioCache.kt). Khác hẳn speak(): speak() luôn
// generate RỒI phát ngay, không có cách nào lấy lại samples đã sinh ra —
// nên cần 1 đường riêng. Có default trả về null (không hỗ trợ) — chỉ
// KokoroTtsEngine override thật, AndroidTtsEngine KHÔNG cần override vì
// theo thiết kế đã chốt, giọng hệ thống (Android TTS) KHÔNG được pre-cache
// (generate gần như tức thời, pre-cache chỉ tốn dung lượng vô ích — xem
// TtsVoiceSnapshot.kt).
package com.eleap.eleap.core.tts

import android.content.Context

// ── Kết quả sinh audio thô — dùng cho generateAudio() bên dưới. Tách riêng
// thành data class (thay vì trả về Pair<FloatArray, Int> mù mờ) để rõ ràng
// tên trường ở mọi nơi gọi (KokoroTtsEngine, TtsManager, TtsPregenWorker). ──
data class TtsAudioResult(
    val samples: FloatArray,
    val sampleRate: Int,
)

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

    // ── MỚI (tạm thời, để debug/thử nghiệm chọn giọng Kokoro) ───────────────
    // Đổi giọng đọc theo speaker id — chỉ KokoroTtsEngine implement thật,
    // AndroidTtsEngine dùng default rỗng (không áp dụng được với
    // TextToSpeech).
    fun setSpeaker(sid: Int) {}

    // ── MỚI (core/tts/pregen/): sinh audio thô cho 1 (text, sid) cụ thể,
    // KHÔNG phát ra loa — dùng để lưu cache. suspend vì cần đợi generate()
    // (native, có thể mất vài trăm ms tới vài giây) chạy xong rồi mới có
    // kết quả, khác hẳn speak() (fire-and-forget, tự launch coroutine riêng
    // bên trong rồi trả về ngay). Default null: engine không hỗ trợ
    // pre-generate (đúng cho AndroidTtsEngine — không cần override).
    //
    // ⚠️ MỚI: thêm readingId (CHỈ để log rõ đang generate cho bài nào — KHÔNG
    // dùng cho logic/cache key gì cả, cache key vẫn tính từ contentHash(text)
    // như cũ ở TtsAudioCache). Trước đây log chỉ có text + sid, không biết
    // đang xử lý bài nào khi đọc logcat lúc TtsPregenWorker chạy ngầm qua
    // nhiều bài liên tiếp. Default rỗng "" để không phá vỡ nơi gọi cũ (dù
    // hiện tại chỉ có đúng 1 nơi gọi là TtsManager.generateKokoroAudioForCache()).
    suspend fun generateAudio(text: String, sid: Int, readingId: String = ""): TtsAudioResult? = null

    // Engine đã init xong và sẵn sàng nhận speak() chưa.
    fun isReady(): Boolean

    // Giải phóng tài nguyên — gọi ở MainActivity.onDestroy().
    fun shutdown()
}