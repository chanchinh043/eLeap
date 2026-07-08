// TtsManager.kt
// Đặt tại: com/eleap/eleap/core/tts/TtsManager.kt
//
// Singleton thủ công, KHÔNG dùng Hilt/DI — cùng phong cách với CurrentUser,
// SyncCursor, SupabaseClientProvider.
//
// Bước 1 của lộ trình TTS toàn app: chỉ bọc Android TextToSpeech có sẵn
// (on-device, offline, zero-latency setup). Sau này khi có KokoroTtsEngine,
// sẽ trừu tượng hoá thành interface TtsEngine + TtsManager chọn/fallback
// giữa 2 engine — CHƯA làm ở bước này, giữ đơn giản trước.
package com.eleap.eleap.core.tts

import android.content.Context
import android.content.SharedPreferences
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

object TtsManager {

    private const val TAG = "TtsManager"

    private const val PREFS_NAME    = "tts_settings"
    private const val KEY_RATE      = "speech_rate"
    private const val DEFAULT_RATE  = 1.0f
    const val MIN_RATE = 0.1f
    const val MAX_RATE = 2.0f

    private lateinit var prefs: SharedPreferences

    private var tts: TextToSpeech? = null
    private var isReady: Boolean = false

    // Tốc độ đọc hiện tại — đọc từ prefs khi init(), áp lại mỗi lần app mở
    // lại (không cần người dùng chỉnh lại từ đầu mỗi phiên).
    private var currentRate: Float = DEFAULT_RATE

    // Hàng đợi 1 phần tử: nếu speak() được gọi TRƯỚC khi engine init xong
    // (hiếm, chỉ xảy ra ở vài trăm ms đầu tiên sau init()), giữ lại text
    // cuối cùng để nói ngay khi sẵn sàng — không cần người dùng bấm lại.
    private var pendingText: String? = null

    // Gọi 1 lần duy nhất, ở MainActivity.onCreate() — giống cách
    // CurrentUser.init(context)/SyncCursor.init(context) đang làm.
    fun init(context: Context) {
        if (tts != null) {
            Log.d(TAG, "init: đã init từ trước, bỏ qua")
            return
        }

        prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        currentRate = prefs.getFloat(KEY_RATE, DEFAULT_RATE)

        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.US)
                isReady = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED
                Log.d(TAG, "init: engine sẵn sàng, isReady=$isReady")

                if (isReady) {
                    // Áp lại tốc độ đã lưu từ lần trước — PHẢI làm sau khi
                    // engine sẵn sàng, setSpeechRate() gọi trước đó sẽ vô nghĩa.
                    tts?.setSpeechRate(currentRate)

                    pendingText?.let { text ->
                        pendingText = null
                        speak(text)
                    }
                }
            } else {
                isReady = false
                Log.e(TAG, "init: khởi tạo TextToSpeech thất bại, status=$status")
            }
        }
    }

    // Nói 1 câu — luôn NGẮT câu đang đọc dở (QUEUE_FLUSH) để tránh chồng
    // âm thanh khi người dùng bấm/chuyển từ liên tiếp nhanh.
    fun speak(text: String) {
        if (text.isBlank()) return

        val engine = tts
        if (engine == null || !isReady) {
            // Engine chưa sẵn sàng — lưu lại, init() sẽ tự nói khi xong.
            pendingText = text
            Log.d(TAG, "speak: engine chưa sẵn sàng, lưu tạm: \"$text\"")
            return
        }

        val utteranceId = "eleap_tts_${System.currentTimeMillis()}"
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result == TextToSpeech.ERROR) {
            Log.e(TAG, "speak: lỗi khi phát \"$text\"")
        }
    }

    fun stop() {
        tts?.stop()
    }

    // ── Đổi tốc độ đọc — 1.0 = bình thường, <1.0 chậm hơn, >1.0 nhanh hơn.
    // Clamp về [MIN_RATE, MAX_RATE] để tránh giá trị quá nhỏ/lớn làm giọng
    // đọc vô nghĩa (Android không tự chặn giá trị bất hợp lý). Lưu ngay vào
    // SharedPreferences để giữ nguyên lựa chọn qua lần mở app sau.
    fun setSpeechRate(rate: Float) {
        val clamped = rate.coerceIn(MIN_RATE, MAX_RATE)
        currentRate = clamped
        tts?.setSpeechRate(clamped)
        if (::prefs.isInitialized) {
            prefs.edit().putFloat(KEY_RATE, clamped).apply()
        }
        Log.d(TAG, "setSpeechRate: $clamped")
    }

    fun getSpeechRate(): Float = currentRate

    // Gọi ở MainActivity.onDestroy() — giải phóng engine, tránh rò rỉ.
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
        pendingText = null
        Log.d(TAG, "shutdown: đã giải phóng engine")
    }
}