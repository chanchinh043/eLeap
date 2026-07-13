// TtsKokoroPackManifest.kt
// Đặt tại: com/eleap/eleap/core/tts/kokoro/TtsKokoroPackManifest.kt
// (đổi tên từ TtsRemoteManifest.kt, thu hẹp phạm vi CHỈ còn cho Kokoro)
//
// Data class THUẦN TUÝ — mô tả "có những gói giọng Kokoro nào đang sẵn để
// tải" cho 1 bài đọc, KHÔNG chứa logic tải/giải nén (xem
// TtsKokoroPackDownloader.kt). Tách riêng ra 1 file chỉ-data để mọi
// transport khác nhau CỦA KOKORO (Google Drive, CDN riêng...) đều trả về
// ĐÚNG 1 hình dạng dữ liệu này qua interface TtsKokoroPackSource.
//
// ⚠️ Vì sao có sha256 ở cấp PACK (cả gói .zip) chứ không phải ở từng file
// audio bên trong: mục đích của hash này là XÁC THỰC file zip tải về có
// nguyên vẹn không (tránh giải nén nhầm file lỗi/nửa chừng do mạng chập
// chờn) — khác hẳn với contentHash trong TtsAudioCache (dùng để phát hiện
// NỘI DUNG BÀI đã đổi, so khớp qua tên file). Từng file audio bên trong zip
// vẫn phải tự đặt tên đúng quy ước "{type}_{itemId}_{contentHash}.<ext>" của
// TtsAudioCache để sau khi giải nén, cache tự nhận ra — đây là việc của bên
// ĐÓNG GÓI zip (server/pipeline build gói của Kokoro), không phải việc của
// app.
package com.eleap.eleap.core.tts.kokoro

// ── Mô tả 1 gói giọng Kokoro cụ thể có thể tải: đúng 1 (readingId, sid) ────
// version: số nguyên tăng dần mỗi lần server build lại gói (vd sau khi sửa
// nội dung bài) — dùng để so sánh với gói đã tải trước đó (nếu cần), KHÔNG
// bắt buộc phải dùng ngay (đã có sha256 làm cơ chế phát hiện bản mới chính,
// xem TtsKokoroPackDownloader.checkForUpdate()).
data class TtsKokoroPackRef(
    val readingId: String,
    val sid: Int,
    val downloadUrl: String,
    val sha256: String,
    val version: Int,
)

// ── Toàn bộ danh sách gói mà 1 transport (TtsKokoroPackSource) đang biết ───
// Không nhất thiết chứa TẤT CẢ bài trong app — 1 transport có thể chỉ trả
// về manifest cho ĐÚNG 1 bài được hỏi tới, packs ở đây có thể chứa nhiều sid
// khác nhau của CÙNG 1 readingId đó (mỗi giọng Kokoro người dùng có thể
// chọn là 1 pack riêng).
data class TtsKokoroPackManifest(
    val packs: List<TtsKokoroPackRef>,
) {
    // ── Tìm đúng gói cho 1 (readingId, sid) cụ thể — tiện ích nhỏ, tránh mỗi
    // nơi gọi phải tự viết lại đúng logic filter này.
    fun findPack(readingId: String, sid: Int): TtsKokoroPackRef? =
        packs.firstOrNull { it.readingId == readingId && it.sid == sid }
}