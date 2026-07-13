// TtsKokoroPackSource.kt
// Đặt tại: com/eleap/eleap/core/tts/kokoro/TtsKokoroPackSource.kt
// (đổi tên từ TtsRemoteSource.kt, thu hẹp phạm vi CHỈ còn cho Kokoro)
//
// Interface trừu tượng hoá "1 nguồn TẢI GÓI GIỌNG KOKORO từ xa" —
// TtsKokoroPackDownloader (file sau) chỉ gọi qua đây, không biết bên dưới
// đang tải từ Google Drive, 1 CDN riêng, hay bất kỳ nguồn nào khác. Thêm 1
// transport mới cho Kokoro (vd đổi Drive sang S3) chỉ cần viết 1 class
// implement interface này, không đụng gì tới Downloader/Scheduler/Worker.
//
// ⚠️ PHẠM VI: interface này CHỈ mô tả đúng mô hình "pack-based" mà Kokoro
// đang dùng (server build sẵn .zip cho từng (readingId, sid), app tải về
// giải nén). KHÔNG coi đây là hợp đồng chung cho MỌI nhà cung cấp — 1 nhà
// cung cấp khác (vd 1 dịch vụ synth theo yêu cầu) có thể không cần đồng bộ
// gì cả, hoặc cần 1 hợp đồng hoàn toàn khác (vd "synthesize(text) ->
// ByteArray") — tự định nghĩa trong thư mục riêng của nó, không ép theo
// interface này. Xem TtsAudioCache.kt là điểm dùng-chung THẬT SỰ giữa mọi
// nhà cung cấp (ở tầng file system), còn interface này chỉ là chi tiết
// triển khai nội bộ của riêng Kokoro.
//
// Thiết kế tối giản — chỉ đủ 2 việc: (1) hỏi "bài này có gói nào để tải?",
// (2) "tải đúng gói đó về 1 file cụ thể".
package com.eleap.eleap.core.tts.kokoro

import java.io.File

interface TtsKokoroPackSource {

    // ── Hỏi nguồn: bài `readingId` này có những gói giọng Kokoro nào sẵn để
    // tải? suspend vì luôn cần gọi mạng. Trả về null nếu nguồn không có
    // thông tin gì cho bài này (KHÔNG phải lỗi — đơn giản là bài đó chưa
    // được ai build gói), hoặc nếu gọi mạng thất bại (mất mạng, timeout...)
    // — TtsKokoroPackDownloader sẽ tự hiểu "không có gì để tải", KHÔNG throw
    // exception lên trên vì đây là tình huống bình thường, thường xuyên xảy
    // ra (offline-first).
    suspend fun fetchManifest(readingId: String): TtsKokoroPackManifest?

    // ── Tải 1 gói cụ thể (đã biết downloadUrl từ manifest) về đúng
    // `destZip`. Trả về true nếu tải xong TOÀN BỘ file thành công, false nếu
    // thất bại ở bất kỳ bước nào. Downloader sẽ tự dọn `destZip` nếu trả về
    // false — implement KHÔNG cần tự xoá file dở dang trước khi return
    // false, chỉ cần đảm bảo trả đúng false để báo thất bại.
    //
    // Không trả về ByteArray/InputStream trực tiếp (thay vì ghi thẳng ra
    // File) vì gói .zip có thể khá nặng — ghi thẳng ra file tránh giữ toàn
    // bộ nội dung trong RAM cùng lúc.
    suspend fun downloadPackFile(pack: TtsKokoroPackRef, destZip: File): Boolean
}