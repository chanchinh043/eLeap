// AndroidTtsEngine.kt
// Đặt tại: com/eleap/eleap/core/tts/AndroidTtsEngine.kt
//
// Bọc lại android.speech.tts.TextToSpeech (on-device, có sẵn từ hệ thống) —
// implement TtsEngine để TtsManager dùng chung interface với KokoroTtsEngine.
// Logic giữ NGUYÊN như TtsManager.kt cũ (trước khi tách engine): khởi tạo
// async qua callback, set Locale.US, hàng đợi 1 phần tử (pendingText) cho
// trường hợp speak() gọi trước khi engine init xong.
//
// KHÔNG phải singleton — TtsManager sẽ tự giữ 1 instance của class này (thay
// vì object như bản cũ), vì giờ có thể có 2 engine cùng tồn tại (Android +
// Kokoro), TtsManager mới là nơi quyết định dùng engine nào.
package com.eleap.eleap.core.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class AndroidTtsEngine : TtsEngine {

    private val TAG = "AndroidTtsEngine"

    private var tts: TextToSpeech? = null
    private var isReadyFlag: Boolean = false
    private var currentRate: Float = 1.0f

    // Hàng đợi 1 phần tử: nếu speak() được gọi TRƯỚC khi engine init xong
    // (hiếm, chỉ xảy ra ở vài trăm ms đầu tiên sau init()), giữ lại text
    // cuối cùng để nói ngay khi sẵn sàng.
    private var pendingText: String? = null

    override fun init(context: Context, onReady: (success: Boolean) -> Unit) {
        if (tts != null) {
            Log.d(TAG, "init: đã init từ trước, bỏ qua")
            onReady(isReadyFlag)
            return
        }

        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.US)
                isReadyFlag = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED
                Log.d(TAG, "init: engine sẵn sàng, isReady=$isReadyFlag")

                if (isReadyFlag) {
                    tts?.setSpeechRate(currentRate)
                    pendingText?.let { text ->
                        pendingText = null
                        speak(text)
                    }
                }
                onReady(isReadyFlag)
            } else {
                isReadyFlag = false
                Log.e(TAG, "init: khởi tạo TextToSpeech thất bại, status=$status")
                onReady(false)
            }
        }
    }

    override fun speak(text: String) {
        if (text.isBlank()) return

        val engine = tts
        if (engine == null || !isReadyFlag) {
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

    override fun stop() {
        tts?.stop()
    }

    override fun setSpeechRate(rate: Float) {
        currentRate = rate
        tts?.setSpeechRate(rate)
    }

    override fun isReady(): Boolean = isReadyFlag

    override fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReadyFlag = false
        pendingText = null
        Log.d(TAG, "shutdown: đã giải phóng engine")
    }
}