// TtsVoiceCatalog.kt
// Đặt tại: com/eleap/eleap/core/tts/TtsVoiceCatalog.kt
//
// ⚠️ ĐÃ THU GỌN: file này giờ CHỈ còn 1 việc DUY NHẤT — TỔNG HỢP danh sách
// giọng từ TẤT CẢ nhà cung cấp lại thành 1 danh sách chung cho UI (màn chọn
// giọng) dùng, và cung cấp hàm tra cứu thuần tuý theo (vendor, sid). Toàn bộ
// dữ liệu 53 giọng Kokoro (tên, ngôn ngữ, cách build displayName...) đã
// chuyển sang core/tts/kokoro/TtsKokoroVoices.kt — file NÀY không còn biết
// gì về chi tiết riêng của Kokoro nữa.
//
// data class TtsVoiceOption vẫn đặt ở ĐÂY (không chuyển vào kokoro/) vì nó
// là HÌNH DẠNG DỮ LIỆU DÙNG CHUNG cho MỌI nhà cung cấp — mỗi
// Tts<Vendor>Voices.kt (kokoro/, google_cloud/... sau này) đều phải tự build
// ra đúng shape TtsVoiceOption này để nộp vào allVoices bên dưới.
//
// ── THÊM 1 NHÀ CUNG CẤP MỚI: viết 1 file Tts<Vendor>Voices.kt trong thư mục
// riêng của vendor đó, rồi cộng thêm đúng 1 dòng vào allVoices bên dưới.
// KHÔNG cần sửa gì khác trong file này. ──────────────────────────────────
package com.eleap.eleap.core.tts

import com.eleap.eleap.core.tts.kokoro.TtsKokoroVoices

// ── 1 lựa chọn giọng cụ thể mà người dùng có thể chọn ở màn chọn giọng ───────
// vendor: nhà cung cấp giọng này thuộc về — dùng để tra cache đúng namespace
//      (xem TtsAudioCache.kt) và để route yêu cầu đồng bộ/tổng hợp tới đúng
//      logic riêng của vendor đó.
// sid: số hiệu giọng, CHỈ có ý nghĩa NỘI BỘ trong phạm vi 1 vendor — không
//      cần duy nhất toàn app (xem ghi chú ở TtsKokoroVoices.kt).
// voiceName: tên gốc theo quy ước riêng của vendor (vd "af_bella" của
//      Kokoro) — không hiển thị trực tiếp cho người dùng, chỉ để đối
//      chiếu/debug.
// languageTag: mã ngôn ngữ kiểu BCP-47 rút gọn (vd "en-US") — dùng để lọc
//      (vd chỉ lấy giọng tiếng Anh cho tính năng đọc bài).
// displayName: tên hiển thị ở UI, đã dịch sẵn sang tiếng Việt.
data class TtsVoiceOption(
    val vendor: TtsVendor,
    val sid: Int,
    val voiceName: String,
    val languageTag: String,
    val displayName: String,
)

object TtsVoiceCatalog {

    // ── Điểm gọi CHÍNH cho màn chọn giọng — TOÀN BỘ giọng từ MỌI nhà cung
    // cấp đã khai báo. Khi thêm vendor mới, chỉ cần cộng thêm danh sách mới
    // vào đây (vd TtsKokoroVoices.voices + TtsGoogleCloudVoices.voices). ────
    val allVoices: List<TtsVoiceOption> = TtsKokoroVoices.voices

    // ── Chỉ giọng tiếng Anh — eLeap hiện chỉ dạy tiếng Anh nên màn chọn
    // giọng nhiều khả năng chỉ cần dùng danh sách này thay vì allVoices. Để
    // UI tự quyết định dùng allVoices hay englishVoices, KHÔNG áp đặt cứng
    // ở đây. ─────────────────────────────────────────────────────────────
    val englishVoices: List<TtsVoiceOption> = allVoices.filter { it.languageTag.startsWith("en") }

    // ── Tra cứu 1 giọng theo (vendor, sid) — PHẢI dùng CẢ HAI, không chỉ
    // sid, vì sid không duy nhất toàn app (xem ghi chú TtsVoiceOption ở
    // trên). Dùng khi cần hiển thị TÊN của giọng đang chọn (vd
    // TtsVoiceSnapshot.currentVendor()/currentSid() trả về cặp giá trị trần
    // trụi, cần tra ngược ra displayName để hiện lên UI). Trả về null nếu
    // cặp (vendor, sid) không nằm trong danh mục đã biết (hiếm khi xảy ra,
    // trừ khi prefs bị hỏng hoặc vendor đó vừa bị xoá khỏi danh mục). ───────
    fun findByVendorAndSid(vendor: TtsVendor, sid: Int): TtsVoiceOption? =
        allVoices.firstOrNull { it.vendor == vendor && it.sid == sid }
}