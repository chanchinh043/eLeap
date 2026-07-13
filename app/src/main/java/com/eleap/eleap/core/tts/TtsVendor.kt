// TtsVendor.kt
// Đặt tại: com/eleap/eleap/core/tts/TtsVendor.kt
//
// Định danh nhà cung cấp giọng đọc — dùng XUYÊN SUỐT toàn bộ package
// core/tts/ (path cache trong TtsAudioCache, trạng thái đã chọn trong
// TtsVoiceSnapshot, phân loại giọng trong TtsVoiceCatalog...). Đây là điểm
// dùng-chung DUY NHẤT giữa mọi nhà cung cấp — bản thân enum này KHÔNG chứa
// logic gì, chỉ là 1 tập giá trị hữu hạn.
//
// Thêm nhà cung cấp mới (vd Google Cloud TTS) chỉ cần thêm 1 giá trị mới
// vào đây — KHÔNG cần sửa gì ở TtsAudioCache/TtsVoiceSnapshot (chúng chỉ
// dùng TtsVendor như 1 khoá path/khoá lưu trữ trung lập, không hardcode
// theo từng giá trị cụ thể).
package com.eleap.eleap.core.tts

enum class TtsVendor {
    KOKORO,
}