// TtsRemoteSource.kt
// Đặt tại: com/eleap/eleap/core/tts/remote/TtsRemoteSource.kt
//
// Interface trừu tượng hoá "1 nguồn tải gói giọng đọc từ xa" — TtsRemotePackDownloader
// (bước sau) chỉ gọi qua đây, không biết bên dưới đang tải từ Google Drive,
// 1 CDN riêng, hay bất kỳ nguồn nào khác. Thêm 1 nguồn mới sau này chỉ cần
// viết 1 class implement interface này, không đụng gì tới Downloader hay bất
// kỳ nơi nào khác.
//
// Thiết kế tối giản — chỉ đủ 2 việc: (1) hỏi "bài này có gói nào để tải?",
// (2) "tải đúng gói đó về 1 file cụ thể". Không có hàm nào khác (vd upload,
// list toàn bộ bài trong app...) vì Downloader không cần tới.
package com.eleap.eleap.core.tts.remote

import java.io.File

interface TtsRemoteSource {

    // ── Hỏi nguồn: bài `readingId` này có những gói giọng nào sẵn để tải? ───
    // suspend vì luôn cần gọi mạng (HTTP GET tới file manifest, hoặc gọi API
    // của Google Drive...). Trả về null nếu nguồn không có thông tin gì cho
    // bài này (KHÔNG phải lỗi — đơn giản là bài đó chưa được ai build gói),
    // hoặc nếu gọi mạng thất bại (mất mạng, timeout...) — TtsRemotePackDownloader
    // sẽ tự hiểu "không có gì để tải", KHÔNG throw exception lên trên vì đây
    // là tình huống bình thường, thường xuyên xảy ra (offline-first).
    suspend fun fetchManifest(readingId: String): TtsRemoteManifest?

    // ── Tải 1 gói cụ thể (đã biết downloadUrl từ manifest) về đúng `destZip` ─
    // Trả về true nếu tải xong TOÀN BỘ file thành công, false nếu thất bại ở
    // bất kỳ bước nào (mất mạng giữa chừng, server trả lỗi...). Downloader sẽ
    // tự dọn `destZip` nếu trả về false — implement KHÔNG cần tự xoá file dở
    // dang trước khi return false, chỉ cần đảm bảo trả đúng false để báo
    // thất bại.
    //
    // Không trả về ByteArray/InputStream trực tiếp (thay vì ghi thẳng ra
    // File) vì gói .zip có thể khá nặng (nhiều giọng × nhiều audio 1 bài) —
    // ghi thẳng ra file tránh giữ toàn bộ nội dung trong RAM cùng lúc.
    suspend fun downloadPackFile(pack: TtsRemotePackRef, destZip: File): Boolean
}