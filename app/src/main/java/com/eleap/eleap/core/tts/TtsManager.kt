// TtsManager.kt
// Đặt tại: com/eleap/eleap/core/tts/TtsManager.kt
//
// Singleton thủ công, KHÔNG dùng Hilt/DI — cùng phong cách với CurrentUser,
// SyncCursor, SupabaseClientProvider.
//
// ⚠️ ĐÃ ĐỔI THIẾT KẾ: trước đây TtsManager tự cầm luôn 1 TextToSpeech bên
// trong. Giờ trừu tượng hoá qua interface TtsEngine (xem TtsEngine.kt) — có
// 2 cài đặt: AndroidTtsEngine (bọc TextToSpeech cũ) và KokoroTtsEngine (bọc
// sherpa-onnx, chất lượng cao hơn, on-device). TtsManager là nơi DUY NHẤT
// quyết định dùng engine nào, và API công khai (init/speak/stop/
// setSpeechRate/getSpeechRate/shutdown) giữ NGUYÊN như cũ — mọi nơi khác
// đang gọi TtsManager (ReadingScreen, WordPopup, SentencePopup, PhrasePopup,
// VocabPopup) KHÔNG cần sửa gì.
//
// ── Chiến lược chọn engine ───────────────────────────────────────────────
// Thử khởi tạo Kokoro TRƯỚC (chất lượng đọc tốt hơn nhiều so với
// TextToSpeech mặc định của Android, đặc biệt với giọng US/UK tự nhiên).
// Nếu Kokoro init lỗi (model thiếu file, sai định dạng, lỗi native
// library,...) → tự động rơi về AndroidTtsEngine, đảm bảo app luôn có TTS
// hoạt động, không phụ thuộc hoàn toàn vào Kokoro load thành công.
//
// activeEngine chỉ được set SAU KHI biết chắc engine đó init thành công —
// tránh trường hợp gọi speak() vào 1 engine chưa sẵn sàng.
package com.eleap.eleap.core.tts

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

object TtsManager {

    private const val TAG = "TtsManager"

    private const val PREFS_NAME    = "tts_settings"
    private const val KEY_RATE      = "speech_rate"
    private const val DEFAULT_RATE  = 1.0f
    const val MIN_RATE = 0.1f
    const val MAX_RATE = 2.0f

    private lateinit var prefs: SharedPreferences

    private var activeEngine: TtsEngine? = null
    private var isInitializing = false

    // Tốc độ đọc hiện tại — đọc từ prefs khi init(), áp lại mỗi lần app mở
    // lại, và áp lại cho engine MỚI nếu sau này engine active bị đổi (hiện
    // tại chưa có UI đổi engine giữa chừng, nhưng giữ logic này để an toàn).
    private var currentRate: Float = DEFAULT_RATE

    // Hàng đợi 1 phần tử: nếu speak() được gọi TRƯỚC khi engine nào đó init
    // xong (cả Kokoro lẫn fallback Android đều có thể mất vài trăm ms tới
    // vài giây với Kokoro do phải copy asset + load model lần đầu).
    private var pendingText: String? = null

    // Gọi 1 lần duy nhất, ở MainActivity.onCreate().
    fun init(context: Context) {
        if (activeEngine != null || isInitializing) {
            Log.d(TAG, "init: đã init hoặc đang init, bỏ qua")
            return
        }
        isInitializing = true

        prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        currentRate = prefs.getFloat(KEY_RATE, DEFAULT_RATE)

        val kokoro = KokoroTtsEngine()
        kokoro.init(context) { kokoroReady ->
            if (kokoroReady) {
                Log.d(TAG, "init: dùng KokoroTtsEngine")
                activateEngine(kokoro)
            } else {
                Log.w(TAG, "init: Kokoro init thất bại, fallback sang AndroidTtsEngine")
                val android = AndroidTtsEngine()
                android.init(context) { androidReady ->
                    if (androidReady) {
                        Log.d(TAG, "init: dùng AndroidTtsEngine (fallback)")
                        activateEngine(android)
                    } else {
                        // Cả 2 engine đều lỗi — hiếm khi xảy ra (AndroidTtsEngine
                        // hầu như luôn init được vì là engine hệ thống), nhưng
                        // vẫn xử lý để không crash: giữ isInitializing = false,
                        // speak() sau này sẽ tự no-op vì activeEngine vẫn null.
                        Log.e(TAG, "init: cả Kokoro lẫn Android TTS đều lỗi")
                        isInitializing = false
                    }
                }
            }
        }
    }

    private fun activateEngine(engine: TtsEngine) {
        engine.setSpeechRate(currentRate)
        activeEngine = engine
        isInitializing = false

        pendingText?.let { text ->
            pendingText = null
            speak(text)
        }
    }

    // Nói 1 câu — luôn NGẮT câu đang đọc dở, uỷ quyền thẳng xuống engine
    // đang active.
    fun speak(text: String) {
        if (text.isBlank()) return

        val engine = activeEngine
        if (engine == null || !engine.isReady()) {
            pendingText = text
            Log.d(TAG, "speak: chưa có engine sẵn sàng, lưu tạm: \"$text\"")
            return
        }
        engine.speak(text)
    }

    fun stop() {
        activeEngine?.stop()
    }

    // ── Đổi tốc độ đọc — 1.0 = bình thường, <1.0 chậm hơn, >1.0 nhanh hơn.
    // Clamp về [MIN_RATE, MAX_RATE] để tránh giá trị quá nhỏ/lớn làm giọng
    // đọc vô nghĩa. Lưu ngay vào SharedPreferences để giữ nguyên lựa chọn
    // qua lần mở app sau.
    fun setSpeechRate(rate: Float) {
        val clamped = rate.coerceIn(MIN_RATE, MAX_RATE)
        currentRate = clamped
        activeEngine?.setSpeechRate(clamped)
        if (::prefs.isInitialized) {
            prefs.edit().putFloat(KEY_RATE, clamped).apply()
        }
        Log.d(TAG, "setSpeechRate: $clamped")
    }

    fun getSpeechRate(): Float = currentRate

    // Gọi ở MainActivity.onDestroy() — giải phóng engine, tránh rò rỉ.
    fun shutdown() {
        activeEngine?.shutdown()
        activeEngine = null
        isInitializing = false
        pendingText = null
        Log.d(TAG, "shutdown: đã giải phóng engine")
    }
}