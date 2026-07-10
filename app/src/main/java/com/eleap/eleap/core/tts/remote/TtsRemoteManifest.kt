// TtsRemoteManifest.kt
// Đặt tại: com/eleap/eleap/core/tts/remote/TtsRemoteManifest.kt
//
// Data class THUẦN TUÝ — mô tả "có những gói giọng đọc nào đang sẵn để tải"
// cho 1 bài đọc, KHÔNG chứa logic tải/giải nén (xem TtsRemotePackDownloader.kt
// ở bước sau). Tách riêng ra 1 file chỉ-data để mọi nguồn tải khác nhau
// (Google Drive, CDN riêng...) đều trả về ĐÚNG 1 hình dạng dữ liệu này qua
// interface TtsRemoteSource — nơi gọi (TtsRemotePackDownloader) không cần
// biết manifest gốc ở nguồn nào là JSON, XML hay bất kỳ định dạng gì, mỗi
// impl của TtsRemoteSource tự lo việc parse rồi trả về đúng shape này.
//
// ⚠️ Vì sao có sha256 ở cấp PACK (cả gói .zip) chứ không phải ở từng file
// .wav bên trong: mục đích của hash này là XÁC THỰC file zip tải về có
// nguyên vẹn không (tránh giải nén nhầm file lỗi/nửa chừng do mạng chập
// chờn) — khác hẳn với contentHash trong TtsAudioCache (dùng để phát hiện
// NỘI DUNG BÀI đã đổi). Từng file .wav bên trong zip vẫn phải tự đặt tên
// đúng quy ước "{type}_{itemId}_{contentHash}.wav" của TtsAudioCache để sau
// khi giải nén, cache tự nhận ra — đây là việc của bên ĐÓNG GÓI zip (server/
// pipeline build gói), không phải việc của app.
package com.eleap.eleap.core.tts.remote

// ── Mô tả 1 gói giọng đọc cụ thể có thể tải: đúng 1 (readingId, sid) ────────
// version: số nguyên tăng dần mỗi lần server build lại gói (vd sau khi sửa
// nội dung bài) — dùng để so sánh với gói đã tải trước đó (nếu cần, xem
// bước sau), KHÔNG bắt buộc app phải dùng ngay ở bước này.
data class TtsRemotePackRef(
    val readingId: String,
    val sid: Int,
    val downloadUrl: String,
    val sha256: String,
    val version: Int,
)

// ── Toàn bộ danh sách gói mà 1 nguồn (TtsRemoteSource) đang biết ────────────
// Không nhất thiết chứa TẤT CẢ bài trong app — 1 nguồn có thể chỉ trả về
// manifest cho ĐÚNG 1 bài được hỏi tới (tuỳ cách TtsRemoteSource.fetchManifest()
// được thiết kế ở bước sau), packs ở đây có thể chứa nhiều sid khác nhau
// của CÙNG 1 readingId đó.
data class TtsRemoteManifest(
    val packs: List<TtsRemotePackRef>,
) {
    // ── Tìm đúng gói cho 1 (readingId, sid) cụ thể — tiện ích nhỏ, tránh mỗi
    // nơi gọi phải tự viết lại đúng logic filter này.
    fun findPack(readingId: String, sid: Int): TtsRemotePackRef? =
        packs.firstOrNull { it.readingId == readingId && it.sid == sid }
}