// TtsAudioCache.kt
// Đặt tại: com/eleap/eleap/core/tts/cache/TtsAudioCache.kt
//
// ⚠️ PHIÊN BẢN SAU KHI BỎ TỰ SINH AUDIO (KOKORO) — app KHÔNG còn tự generate
// audio on-device nữa, TOÀN BỘ audio đều được tải sẵn/tổng hợp từ nguồn
// ngoài (mỗi nhà cung cấp tự lo — xem core/tts/kokoro/, core/tts/sync/, và
// sau này core/tts/google_cloud/...) rồi ghi thẳng vào đúng thư mục mà
// voiceDir() bên dưới quy định. File này CHỈ còn 2 việc: (1) định nghĩa
// quy ước path/tên file dùng CHUNG giữa MỌI nhà cung cấp và
// TtsPlaybackRouter (nhánh đọc — tra cứu để phát), (2) cung cấp hàm tiện
// ích tính contentHash để phát hiện cache lỗi thời khi nội dung bài đổi.
//
// KHÔNG còn hàm ghi file nào ở đây — việc GHI file cache là trách nhiệm của
// TỪNG NHÀ CUNG CẤP, miễn là ghi đúng TÊN GỐC theo quy ước ở đây.
//
// ⚠️ ĐUÔI FILE (.ogg/.mp3/.wav...) KHÔNG do TtsAudioCache áp đặt — MỖI NHÀ
// CUNG CẤP CÓ THỂ TRẢ VỀ ĐỊNH DẠNG KHÁC NHAU tuỳ cách họ tải/tổng hợp/giải
// nén (Kokoro qua Drive hiện đóng gói .ogg; 1 nhà cung cấp on-demand khác
// sau này có thể tự ghi .mp3 hoặc .wav). Vì vậy:
//   - Khi GHI (provider tự làm): provider tự chọn đuôi phù hợp với định
//     dạng mình tạo ra, truyền qua tham số `extension` của buildFilePath().
//   - Khi ĐỌC (getCachedFile() cho TtsPlaybackRouter): KHÔNG cố định đuôi —
//     tìm bất kỳ file nào trên đĩa có ĐÚNG TÊN GỐC (baseFileName, không kể
//     đuôi), bất kể đuôi là gì. TtsPlaybackRouter dùng MediaPlayer, tự nhận
//     diện định dạng qua nội dung file, không cần biết trước đuôi.
//   - "Tên gốc" ({type}_{itemId}_{contentHash}) mới là khoá THẬT của cache —
//     đuôi chỉ là chi tiết trình bày, không mang ý nghĩa định danh.
//
// Lớp tiện ích THAO TÁC FILE THUẦN TUÝ — KHÔNG dùng database để index cache.
// Sự tồn tại của đúng file (đúng tên gốc, đúng hash nội dung) TỰ NÓ là "index".
//
// ⚠️ VENDOR TRONG PATH: 2 nhà cung cấp khác nhau có thể lỡ dùng TRÙNG số sid
// (sid chỉ có ý nghĩa nội bộ trong phạm vi 1 vendor) — nếu không tách theo
// vendor ngay trong path, 1 giọng của vendor A và 1 giọng khác hẳn của
// vendor B cùng sid=5 sẽ ghi/đọc ĐÈ LÊN NHAU, gây phát sai giọng mà không hề
// crash. Thêm vendor vào path loại bỏ hoàn toàn nhu cầu "các vendor phải tự
// né số sid của nhau".
//
// Cấu trúc thư mục (tách theo bài, theo NHÀ CUNG CẤP, rồi theo giọng — đuôi
// file do provider tự quyết định lúc ghi, có thể khác nhau GIỮA CÁC ITEM
// cùng 1 vendor nếu provider đó vì lý do gì tự trộn định dạng, dù trong
// thực tế mỗi vendor thường nhất quán 1 định dạng duy nhất):
//   filesDir/tts_cache/{readingId}/{vendor}/{sid}/word_{wordId}_{hash}.<ext>
//   filesDir/tts_cache/{readingId}/{vendor}/{sid}/sentence_{sentenceId}_{hash}.<ext>
//   filesDir/tts_cache/{readingId}/{vendor}/{sid}/phrase_{phraseId}_{hash}.<ext>
//
// contentHash: hash NGẮN của text_en tương ứng (8 ký tự đầu SHA-256) — dùng
// để tự phát hiện cache lỗi thời khi nội dung bài đổi. Không cần
// cryptographic mạnh vì chỉ để SO SÁNH THAY ĐỔI NỘI DUNG, không phải mục
// đích bảo mật.
//
// Singleton thủ công, KHÔNG dùng Hilt/DI — cùng phong cách với các singleton
// khác trong core/tts/.
package com.eleap.eleap.core.tts.cache

import android.content.Context
import com.eleap.eleap.core.tts.TtsVendor
import java.io.File
import java.security.MessageDigest

// ── Loại item được cache — dùng làm tiền tố tên file, cũng là tham số bắt
// buộc ở mọi hàm để tránh nhầm lẫn giữa 3 loại khi build đường dẫn. ──────────
enum class TtsCacheItemType(val prefix: String) {
    WORD("word"),
    SENTENCE("sentence"),
    PHRASE("phrase"),
}

object TtsAudioCache {

    private const val ROOT_DIR_NAME = "tts_cache"

    // ── Thư mục gốc: filesDir/tts_cache ──────────────────────────────────────
    private fun rootDir(context: Context): File =
        File(context.applicationContext.filesDir, ROOT_DIR_NAME)

    // ── Thư mục gốc của 1 bài: filesDir/tts_cache/{readingId} ────────────────
    private fun readingDir(context: Context, readingId: String): File =
        File(rootDir(context), readingId)

    // ── Thư mục của 1 (bài, nhà cung cấp) — CHUNG cho MỌI giọng (sid) của
    // vendor đó: filesDir/tts_cache/{readingId}/{vendor} ────────────────────
    // Không `private` — dùng làm nơi lưu các marker Ở CẤP BÀI, không gắn với
    // 1 sid cụ thể nào (vd ".reading_fully_synced" của
    // TtsKokoroPackDownloader — đánh dấu "đã tải ĐỦ mọi giọng hiện có của
    // vendor này cho bài này", xem TtsKokoroPackDownloader.isReadingFullySynced()).
    //
    // vendor.name dùng làm tên thư mục — TtsVendor chỉ chứa hằng số IN HOA
    // không dấu (xem TtsVendor.kt), an toàn làm tên thư mục.
    fun vendorDir(context: Context, readingId: String, vendor: TtsVendor): File =
        File(readingDir(context, readingId), vendor.name)

    // ── Thư mục của 1 (bài, nhà cung cấp, giọng) cụ thể:
    // filesDir/tts_cache/{readingId}/{vendor}/{sid} ─────────────────────────
    // Không `private` — mọi nhà cung cấp cần biết CHÍNH XÁC thư mục đích để
    // ghi file cache vào đúng chỗ. Đây là hàm DUY NHẤT build đúng path này.
    fun voiceDir(context: Context, readingId: String, vendor: TtsVendor, sid: Int): File =
        File(vendorDir(context, readingId, vendor), sid.toString())

    // ── contentHash: 8 ký tự đầu SHA-256 của text — dùng để phát hiện nội
    // dung đã đổi mà không cần biết "phiên bản" nào, chỉ cần so sánh hash
    // với tên file hiện có trên đĩa. ─────────────────────────────────────────
    fun contentHash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(8)
    }

    // ── TÊN GỐC (không kèm đuôi): {loại}_{itemId}_{contentHash} — đây mới là
    // khoá THẬT của cache, KHÔNG kèm đuôi vì đuôi tuỳ provider quyết định
    // (xem ghi chú đầu file). Mọi nhà cung cấp PHẢI đặt tên file audio của
    // mình bắt đầu ĐÚNG bằng chuỗi này (rồi thêm ".<đuôi bất kỳ>") thì
    // getCachedFile() bên dưới mới tra ra được.
    private fun baseFileName(type: TtsCacheItemType, itemId: String, contentHash: String): String =
        "${type.prefix}_${itemId}_$contentHash"

    // ── Đường dẫn file ĐẦY ĐỦ để GHI — dùng bởi provider khi ghi cache (vd
    // TtsPackDownloader.extractZip() cho vendor pack-based). Provider TỰ
    // TRUYỀN `extension` đúng với định dạng file mình tạo ra (không dấu
    // chấm, vd "ogg", "mp3", "wav"). Đây là hàm DUY NHẤT build path để GHI,
    // tránh nhiều nơi tự ghép chuỗi path khác nhau rồi lệch nhau. ───────────
    fun buildFilePath(
        context: Context,
        readingId: String,
        vendor: TtsVendor,
        sid: Int,
        type: TtsCacheItemType,
        itemId: String,
        contentHash: String,
        extension: String,
    ): File = File(
        voiceDir(context, readingId, vendor, sid),
        "${baseFileName(type, itemId, contentHash)}.$extension",
    )

    // ── Kiểm tra ĐÃ có cache ĐÚNG hash chưa (bất kể đuôi file) — false nếu:
    // chưa từng có, HOẶC đã có nhưng với nội dung CŨ (hash khác). ───────────
    fun hasCached(
        context: Context,
        readingId: String,
        vendor: TtsVendor,
        sid: Int,
        type: TtsCacheItemType,
        itemId: String,
        contentHash: String,
    ): Boolean = getCachedFile(context, readingId, vendor, sid, type, itemId, contentHash) != null

    // ── Lấy file cache nếu có ĐÚNG hash — dùng cho TtsPlaybackRouter khi cần
    // phát. KHÔNG cố định đuôi file — tìm bất kỳ file nào trong voiceDir có
    // TÊN GỐC khớp đúng (baseFileName), bất kể provider đã ghi đuôi gì.
    // MediaPlayer phát trực tiếp mọi định dạng phổ biến (ogg/mp3/wav...) mà
    // không cần biết trước đuôi. Trả về null nếu chưa có/đã lỗi thời —
    // caller tự quyết định fallback sang Android TTS. ───────────────────────
    fun getCachedFile(
        context: Context,
        readingId: String,
        vendor: TtsVendor,
        sid: Int,
        type: TtsCacheItemType,
        itemId: String,
        contentHash: String,
    ): File? {
        val dir = voiceDir(context, readingId, vendor, sid)
        val base = baseFileName(type, itemId, contentHash)
        return dir.listFiles { file -> file.isFile && file.name.startsWith("$base.") }
            ?.firstOrNull()
    }
}