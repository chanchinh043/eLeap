// TtsMyReadingDownloadGate.kt
// Đặt tại: com/eleap/eleap/core/tts/kokoro/myreading/TtsMyReadingDownloadGate.kt
//
// ⚠️ VÌ SAO FILE NÀY BẮT BUỘC PHẢI CÓ (không thể bỏ qua bước này) ───────────
// TtsKokoroPackDownloader.ensureReadingFullySynced() (được gọi qua
// TtsKokoroPackScheduler.enqueueEnsureReadingSynced() mỗi khi mở 1 bài đọc,
// xem ReadingScreen.kt) có hành vi: nếu Drive KHÔNG có gói cho 1 sid, nó
// COI NHƯ "đã xử lý xong" cho sid đó (không phải lỗi) — và nếu MỌI sid cần
// tải đều rơi vào tình huống này, nó ghi READING_FULLY_SYNCED_MARKER, tức
// "bài này đã tải ĐỦ, KHÔNG BAO GIỜ hỏi lại Drive nữa" (xem comment
// ensureReadingFullySynced() ở TtsKokoroPackDownloader.kt).
//
// Với bài đọc HỆ THỐNG, hành vi này ĐÚNG — nếu Drive chưa có, nó sẽ KHÔNG
// BAO GIỜ tự có (build thủ công/batch, không có gì "đang chạy ngầm" để chờ).
//
// Với bài MYREADING, hành vi này SAI và NGUY HIỂM — server tổng hợp
// audio BẤT ĐỒNG BỘ (xem TtsMyReadingSyncTrigger.kt), nên rất có khả năng
// người dùng mở bài NGAY sau khi AI dịch xong, TRƯỚC KHI server kịp tổng
// hợp + upload Drive. Nếu để enqueueEnsureReadingSynced() chạy thẳng lúc
// đó, nó sẽ ghi marker "đã tải đủ" (vì Drive đang trống, y hệt trường hợp
// "server sẽ mãi mãi không có gì") — và khi server upload xong VÀI PHÚT
// SAU đó, app sẽ KHÔNG BAO GIỜ tự kiểm tra lại Drive cho bài này nữa, mọi
// lượt phát mãi mãi fallback Android TTS dù audio thật đã sẵn sàng.
//
// Gate này chặn đúng lỗ hổng đó: TRƯỚC khi cho phép gọi
// enqueueEnsureReadingSynced() cho 1 bài MyReading, hỏi server (qua
// TtsMyReadingRequestClient.checkStatus(), KHÔNG phải hỏi Drive) xem job có
// thật sự READY hay chưa. Chỉ khi READY mới cho phép chạy tiếp — lúc đó
// Drive chắc chắn đã có file, ensureReadingFullySynced() ghi marker đúng ý
// nghĩa của nó. Nếu CHƯA ready (pending/processing/failed/unknown), gate
// trả về false — KHÔNG gọi enqueueEnsureReadingSynced() lần này, để lần mở
// bài KẾ TIẾP tự thử lại (không có marker sai nào bị ghi, không mất gì).
//
// ⚠️ KHÔNG ÁP DỤNG CHO BÀI HỆ THỐNG — tham số `isMyReading` do caller tự
// xác định (xem ReadingViewModel.isMyReadingId()) và truyền vào; nếu false,
// gate luôn trả về true NGAY (giữ nguyên hành vi cũ 100%, không có gì thay
// đổi cho bài hệ thống).
//
// ⚠️ NẾU CHƯA CẤU HÌNH TtsMyReadingConfig (baseUrl null) — coi như tính
// năng "xin server tổng hợp" CHƯA BẬT, gate trả về true để giữ nguyên hành
// vi cũ (thử Drive trực tiếp) — không có rủi ro marker sai vì không có
// pipeline bất đồng bộ nào đang chạy trong trường hợp này.
//
// ⚠️ MỚI — CHECK CỤC BỘ TRƯỚC KHI GỌI SERVER: nếu
// TtsKokoroPackDownloader.isPackSynced(readingId, sid) đã trả về true (gói
// đã tải + giải nén xong TỪ TRƯỚC — chỉ đọc 1 file marker nhỏ trên đĩa,
// KHÔNG gọi mạng), gate trả về true NGAY, KHÔNG gọi checkStatus() tới
// server nữa. Trước đây (thiếu bước này) mỗi lần ReadingScreen tạo mới/
// LaunchedEffect chạy lại (vd AI watchdog reload sentences mỗi 15s) đều
// tốn 1 lượt gọi mạng tới server dù đã biết chắc gói này đã tải xong từ
// lâu — vô ích, chỉ tổ tốn băng thông/thời gian. Đây là điểm gọi DUY NHẤT
// quyết định "đã tải rồi thì đừng hỏi lại nữa".
package com.eleap.eleap.core.tts.kokoro.myreading

import android.content.Context
import android.util.Log
import com.eleap.eleap.core.tts.kokoro.TtsKokoroPackDownloader
import com.eleap.eleap.feature.reading.data.ReadingSentence

private const val TAG = "TtsMyReadingDownloadGate"

object TtsMyReadingDownloadGate {

    // ── Điểm gọi CHÍNH — gọi TRƯỚC MỖI LẦN định gọi
    // TtsKokoroPackScheduler.enqueueEnsureReadingSynced() ở ReadingScreen.kt.
    // Trả về true = an toàn để tiến hành gọi enqueueEnsureReadingSynced()
    // như bình thường; false = BỎ QUA lượt này, không gọi gì cả.
    suspend fun shouldProceedToDriveSync(
        context: Context,
        readingId: String,
        sid: Int,
        sentences: List<ReadingSentence>,
        isMyReading: Boolean,
    ): Boolean {
        if (!isMyReading) return true

        // ⚠️ MỚI — check CỤC BỘ, KHÔNG gọi mạng: gói này đã tải xong từ
        // trước rồi thì khỏi cần hỏi server nữa, cho tiến hành ngay (bước
        // enqueueEnsureReadingSynced() phía sau cũng tự fast-path qua
        // isPackUpToDate() nội bộ, không tốn gì thêm).
        if (TtsKokoroPackDownloader.isPackSynced(context, readingId, sid)) {
            Log.d(TAG, "shouldProceedToDriveSync: reading_id=$readingId sid=$sid đã tải xong từ trước (cục bộ), bỏ qua hỏi server")
            return true
        }

        val baseUrl = TtsMyReadingConfig.baseUrl() ?: return true

        if (sentences.isEmpty()) {
            // Chưa có nội dung để tính hash (vd sentences chưa load xong) —
            // KHÔNG cho tiến hành, tránh gọi enqueueEnsureReadingSynced()
            // với dữ liệu chưa sẵn sàng. Caller (ReadingScreen) nên tự
            // guard bằng sentences.isNotEmpty() trước khi gọi tới đây, đây
            // chỉ là lớp an toàn thứ 2.
            return false
        }

        val contentHash = TtsMyReadingContentHash.compute(sentences)
        val status = TtsMyReadingRequestClient(baseUrl).checkStatus(
            readingId   = readingId,
            sid         = sid,
            contentHash = contentHash,
        )

        val ready = status == TtsMyReadingJobStatus.READY
        Log.d(
            TAG,
            "shouldProceedToDriveSync: reading_id=$readingId sid=$sid status=$status → " +
                    if (ready) "cho phép enqueueEnsureReadingSynced()" else "BỎ QUA lượt này, thử lại lần mở bài sau"
        )
        return ready
    }
}