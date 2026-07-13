// TtsVoiceSnapshot.kt
// Đặt tại: com/eleap/eleap/core/tts/TtsVoiceSnapshot.kt
//
// Ghi nhớ "giọng (sid) người dùng đang chọn để nghe" — lưu XUỐNG ĐĨA
// (SharedPreferences) để sống sót qua việc app bị kill hẳn rồi mở lại.
// TtsPlaybackRouter đọc giá trị này mỗi lần cần phát để biết tra cache theo
// đúng sid nào (xem TtsPlaybackRouter.kt).
//
// ⚠️ ĐƠN GIẢN HOÁ so với thiết kế cũ (khi còn Kokoro): trước đây file này
// còn phải phân biệt "đang active Kokoro hay Android TTS" (vì chỉ Kokoro
// mới có khái niệm sid/pre-cache) và tự enqueue TtsPregenWorker mỗi khi đổi
// giọng. Giờ KHÔNG còn engine tự sinh nào cả — "giọng đang chọn" chỉ đơn
// thuần là 1 chỉ số (sid) dùng để tra cache đã tải sẵn, tồn tại ĐỘC LẬP với
// việc engine fallback là gì. Vì vậy:
//   - Không còn khái niệm EngineType/savedEngineType() — chỉ còn 1 con số sid.
//   - Không tự enqueue worker nào ở đây nữa — việc tải gói cho đúng
//     (readingId, sid) hiện tại là trách nhiệm của nơi gọi
//     (ReadingViewModel/ReadingScreen — xem bước sau), gọi thẳng
//     TtsRemotePackScheduler.enqueueDownload() khi mở bài HOẶC khi đổi
//     giọng, KHÔNG giấu việc này bên trong recordSelectedSid() nữa — tách
//     bạch rõ ràng: TtsVoiceSnapshot CHỈ lo việc "nhớ", không tự ý kích hoạt
//     tác vụ nền nào.
//
// Singleton thủ công, KHÔNG dùng Hilt/DI — cùng phong cách với các singleton
// khác trong core/tts/.
package com.eleap.eleap.core.tts

import android.content.Context
import android.content.SharedPreferences

object TtsVoiceSnapshot {

    private const val PREFS_NAME = "tts_voice"
    private const val KEY_SID    = "selected_sid"

    // sid mặc định khi chưa từng chọn (lần đầu cài app) — 0 là giọng đầu
    // tiên trong danh sách giọng mà server cung cấp (tuỳ pipeline build gói
    // phía server quy định sid nào ứng với giọng nào).
    private const val DEFAULT_SID = 0

    private lateinit var prefs: SharedPreferences

    // Gọi 1 lần duy nhất, ở nơi khởi tạo app (MainActivity.onCreate()).
    fun init(context: Context) {
        prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ── Giọng (sid) đang được chọn để nghe — TtsPlaybackRouter dùng giá trị
    // này để build path tra cache (xem TtsAudioCache.voiceDir()). ──────────
    fun currentSid(): Int {
        if (!::prefs.isInitialized) return DEFAULT_SID
        return prefs.getInt(KEY_SID, DEFAULT_SID)
    }

    // ── Gọi mỗi khi người dùng đổi giọng — CHỈ ghi nhớ, KHÔNG tự enqueue gì
    // cả (xem ghi chú ở đầu file). Nơi gọi (vd màn chọn giọng, hoặc
    // ReadingViewModel) tự chịu trách nhiệm gọi thêm
    // TtsRemotePackScheduler.enqueueDownload(context, readingId, sid) cho
    // bài đang mở (nếu có) ngay sau khi gọi hàm này, để tải kịp gói của
    // giọng mới.
    fun setSelectedSid(sid: Int) {
        if (!::prefs.isInitialized) return
        prefs.edit().putInt(KEY_SID, sid).apply()
    }
}