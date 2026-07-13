// TtsVoiceSnapshot.kt
// Đặt tại: com/eleap/eleap/core/tts/TtsVoiceSnapshot.kt
//
// Ghi nhớ "giọng người dùng đang chọn để nghe" — lưu XUỐNG ĐĨA
// (SharedPreferences) để sống sót qua việc app bị kill hẳn rồi mở lại.
// TtsPlaybackRouter đọc giá trị này mỗi lần cần phát để biết tra cache theo
// đúng (vendor, sid) nào (xem TtsPlaybackRouter.kt).
//
// ⚠️ THÊM `vendor` CẠNH `sid` (khác bản trước chỉ có sid): từ khi hỗ trợ
// nhiều nhà cung cấp, riêng số sid KHÔNG đủ để xác định 1 giọng — sid chỉ
// có ý nghĩa NỘI BỘ trong phạm vi 1 vendor (Kokoro có thể có sid=5, và 1
// vendor khác sau này cũng có thể tự đánh sid=5 cho 1 giọng hoàn toàn khác
// — xem TtsAudioCache.kt và TtsVendor.kt). Vì vậy "giọng đang chọn" giờ là
// 1 CẶP (vendor, sid), không phải 1 con số đơn lẻ — lưu và đọc phải LUÔN đi
// cùng nhau, không tách rời.
//
// ⚠️ ĐƠN GIẢN HOÁ so với thiết kế cũ (khi còn Kokoro là engine tự sinh duy
// nhất): trước đây file này còn phải phân biệt "đang active Kokoro hay
// Android TTS" và tự enqueue TtsPregenWorker mỗi khi đổi giọng. Giờ KHÔNG
// còn engine tự sinh nào cả — "giọng đang chọn" chỉ đơn thuần là 1 cặp chỉ
// số (vendor, sid) dùng để tra cache đã có sẵn, tồn tại ĐỘC LẬP với việc
// engine fallback là gì. Vì vậy:
//   - Không còn khái niệm EngineType/savedEngineType() — chỉ còn (vendor, sid).
//   - Không tự enqueue worker nào ở đây nữa — việc tải/tổng hợp cho đúng
//     (readingId, vendor, sid) hiện tại là trách nhiệm của nơi gọi
//     (ReadingViewModel/ReadingScreen/TtsVoicePickerScreen), gọi thẳng
//     API phù hợp của vendor đó (vd TtsPackScheduler.enqueueDownload() cho
//     vendor pack-based) khi mở bài HOẶC khi đổi giọng — KHÔNG giấu việc
//     này bên trong setSelectedVoice() — tách bạch rõ ràng: TtsVoiceSnapshot
//     CHỈ lo việc "nhớ", không tự ý kích hoạt tác vụ nền nào.
//
// Singleton thủ công, KHÔNG dùng Hilt/DI — cùng phong cách với các singleton
// khác trong core/tts/.
package com.eleap.eleap.core.tts

import android.content.Context
import android.content.SharedPreferences

object TtsVoiceSnapshot {

    private const val PREFS_NAME  = "tts_voice"
    private const val KEY_VENDOR  = "selected_vendor"
    private const val KEY_SID     = "selected_sid"

    // Giọng mặc định khi chưa từng chọn (lần đầu cài app) — Kokoro là nhà
    // cung cấp duy nhất hiện có, sid=0 là giọng đầu tiên trong danh sách mà
    // TtsKokoroVoices cung cấp.
    private val DEFAULT_VENDOR = TtsVendor.KOKORO
    private const val DEFAULT_SID = 0

    private lateinit var prefs: SharedPreferences

    // Gọi 1 lần duy nhất, ở nơi khởi tạo app (MainActivity.onCreate()).
    fun init(context: Context) {
        prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ── Nhà cung cấp đang được chọn — đọc trước KEY_SID vì
    // TtsPlaybackRouter/TtsAudioCache luôn cần cả 2 giá trị đi cùng nhau
    // (không có ý nghĩa gì nếu chỉ đọc 1 trong 2). ──────────────────────────
    fun currentVendor(): TtsVendor {
        if (!::prefs.isInitialized) return DEFAULT_VENDOR
        val name = prefs.getString(KEY_VENDOR, null) ?: return DEFAULT_VENDOR
        // enum.valueOf() ném exception nếu tên không khớp (vd prefs cũ còn
        // sót giá trị của 1 vendor đã bị xoá khỏi TtsVendor sau này) — bắt
        // lại, trả về mặc định thay vì crash app chỉ vì 1 giá trị prefs cũ.
        return try {
            TtsVendor.valueOf(name)
        } catch (e: IllegalArgumentException) {
            DEFAULT_VENDOR
        }
    }

    // ── sid đang được chọn TRONG PHẠM VI vendor ở trên — TtsPlaybackRouter
    // dùng cặp (currentVendor(), currentSid()) để build path tra cache (xem
    // TtsAudioCache.voiceDir()). ────────────────────────────────────────────
    fun currentSid(): Int {
        if (!::prefs.isInitialized) return DEFAULT_SID
        return prefs.getInt(KEY_SID, DEFAULT_SID)
    }

    // ── Gọi mỗi khi người dùng đổi giọng (xem TtsVoicePickerScreen) — LUÔN
    // ghi CẢ HAI giá trị cùng lúc trong 1 lệnh gọi, không tách thành 2 hàm
    // setVendor()/setSid() riêng — tránh trạng thái nửa vời nếu caller lỡ
    // chỉ gọi 1 trong 2 (vd đổi sid nhưng quên đổi vendor, dẫn tới tra
    // nhầm cache của vendor cũ với sid mới của vendor khác).
    //
    // CHỈ ghi nhớ, KHÔNG tự enqueue gì cả (xem ghi chú ở đầu file). Nơi gọi
    // tự chịu trách nhiệm kích hoạt tải/tổng hợp cho giọng mới ngay sau khi
    // gọi hàm này.
    fun setSelectedVoice(vendor: TtsVendor, sid: Int) {
        if (!::prefs.isInitialized) return
        prefs.edit()
            .putString(KEY_VENDOR, vendor.name)
            .putInt(KEY_SID, sid)
            .apply()
    }
}