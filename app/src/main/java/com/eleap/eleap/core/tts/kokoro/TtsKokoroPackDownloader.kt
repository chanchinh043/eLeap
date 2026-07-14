// TtsKokoroPackDownloader.kt
// Đặt tại: com/eleap/eleap/core/tts/kokoro/TtsKokoroPackDownloader.kt
// (đổi tên từ TtsRemotePackDownloader.kt, chuyển từ core/tts/remote/ sang
// core/tts/kokoro/ — logic nghiệp vụ giữ nguyên, chỉ cập nhật lời gọi
// TtsAudioCache.voiceDir() để truyền thêm vendor=TtsVendor.KOKORO)
//
// Orchestrator đồng bộ RIÊNG CỦA KOKORO — không quan tâm transport tải là
// Google Drive hay gì khác (nhận vào qua interface TtsKokoroPackSource),
// chỉ lo đúng 3 việc tuần tự: (1) tải file .zip về thư mục tạm, (2) xác
// thực checksum, (3) giải nén thẳng vào ĐÚNG thư mục mà
// TtsAudioCache.voiceDir() quy định — để TtsPlaybackRouter tự nhận ra cache
// mà không cần biết gì về việc Kokoro đồng bộ kiểu gì (xem TtsAudioCache.kt:
// "sự tồn tại của đúng file tự nó là index").
//
// ⚠️ ĐÂY LÀ CƠ CHẾ ĐỒNG BỘ ĐẶC THÙ CỦA KOKORO — không phải hạ tầng dùng
// chung cho mọi nhà cung cấp. 1 nhà cung cấp khác (vd dịch vụ synth
// on-demand) có thể không cần file tương tự file này chút nào (không có
// khái niệm "gói zip cần tải", tự synth rồi ghi thẳng vào TtsAudioCache
// theo cách riêng của nó) — xem ghi chú ở TtsKokoroPackSource.kt.
//
// ⚠️ File .zip tạm nằm ở context.cacheDir (KHÔNG phải filesDir) — vì đây chỉ
// là trung gian, hệ điều hành có thể tự xoá cacheDir bất kỳ lúc nào khi
// thiếu dung lượng mà không ảnh hưởng gì (khác tts_cache/ ở filesDir là dữ
// liệu cần giữ lâu dài). Dù vậy vẫn tự dọn zip tạm ngay sau khi giải nén
// xong (thành công hay thất bại đều dọn) — không phụ thuộc hệ điều hành tự
// dọn hộ.
//
// ⚠️ Chống "zip slip": mỗi entry trong zip được kiểm tra đường dẫn giải nén
// PHẢI nằm trong đúng thư mục đích, không được có "../" thoát ra ngoài — bắt
// buộc vì nội dung zip đến từ nguồn BÊN NGOÀI, không nên tin tưởng tuyệt đối
// cấu trúc bên trong.
//
// ⚠️ TÊN FILE audio bên trong zip giữ NGUYÊN khi giải nén (không build lại
// qua TtsAudioCache.buildFilePath()) — server Kokoro tự đóng gói sẵn đúng
// quy ước "{type}_{itemId}_{contentHash}.ogg" (xem TtsAudioCache.kt), nên
// Downloader chỉ cần giải nén y nguyên vào đúng voiceDir(). Kokoro luôn tạo
// ra .ogg nên KHÔNG cần biết gì về việc TtsAudioCache giờ không còn cố định
// đuôi file nữa (xem TtsAudioCache.kt bước trước) — điều đó chỉ ảnh hưởng
// tới cách ĐỌC (getCachedFile() quét theo tên gốc, không quan tâm đuôi),
// không ảnh hưởng tới cách Downloader này GHI.
//
// Không phải class tự giữ trạng thái — mọi hàm đều nhận đủ tham số cần
// thiết (context, source, readingId, sid), gọi xong là xong, không cần
// init()/singleton lifecycle nào cả.
package com.eleap.eleap.core.tts.kokoro

import android.content.Context
import android.util.Log
import com.eleap.eleap.core.tts.TtsVendor
import com.eleap.eleap.core.tts.cache.TtsAudioCache
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

private const val TAG = "TtsKokoroPackDownloader"

// ── VENDOR CỐ ĐỊNH: file này CHỈ xử lý đồng bộ cho Kokoro — mọi lời gọi
// TtsAudioCache.voiceDir() bên dưới đều truyền cứng TtsVendor.KOKORO, không
// nhận vendor làm tham số vì file này vốn dĩ đã nằm trong package kokoro/,
// không có lý do gì xử lý vendor khác. ──────────────────────────────────
private val VENDOR = TtsVendor.KOKORO

// ── Tên file đánh dấu "đã đồng bộ gói xong" — ghi vào ĐÚNG thư mục
// voiceDir(readingId, VENDOR, sid) ngay sau khi giải nén thành công. Đây
// KHÔNG phải audio, TtsAudioCache/TtsPlaybackRouter không đọc file này —
// chỉ TtsKokoroPackDownloader tự đọc lại để trả lời "đã tải gói này chưa"
// mà KHÔNG cần hỏi lại Drive (isPackSynced() bên dưới).
//
// Marker này lưu ĐÚNG sha256 của pack tại thời điểm tải, có thêm marker
// PACK_CHECKED_AT_MARKER (bên dưới) để so sánh phát hiện bản mới trên
// Drive theo chu kỳ, xem checkForUpdate() và isPackUpToDate().
private const val PACK_SYNCED_MARKER = ".pack_synced"

// ── Tên file đánh dấu "lần cuối cùng đã hỏi Drive xem có bản mới không" —
// TÁCH RIÊNG khỏi PACK_SYNCED_MARKER (1 marker = 1 mục đích). Chỉ
// TtsKokoroPackDownloader tự đọc/ghi, không nơi nào khác cần biết.
private const val PACK_CHECKED_AT_MARKER = ".pack_checked_at"

// ── Tên file đánh dấu "bài này đã tải ĐỦ mọi giọng Kokoro hiện có trên
// Drive" — đặt Ở CẤP BÀI (trong vendorDir(readingId, KOKORO), KHÔNG phải
// trong voiceDir của 1 sid cụ thể), vì đây là trạng thái CHUNG cho toàn bộ
// danh sách giọng, không phải của riêng 1 giọng.
//
// ⚠️ TÁCH BIỆT HOÀN TOÀN khỏi PACK_CHECKED_AT_MARKER (per-sid, có hạn 24h,
// dùng để dò bản mới ĐỊNH KỲ — xem syncAllVoicesForReading()/checkForUpdate()
// bên dưới). 1 khi marker này đã ghi, isReadingFullySynced() trả về true
// MÃI MÃI (kể cả sau khi tắt app/mở lại), KHÔNG bị ảnh hưởng bởi việc 24h
// đã trôi qua hay chưa — việc dò bản mới định kỳ cho giọng ĐÃ TẢI là LOGIC
// KHÁC, không đụng vào marker này.
private const val READING_FULLY_SYNCED_MARKER = ".reading_fully_synced"

// ── Khoảng thời gian tối thiểu giữa 2 lần hỏi Drive xem có bản mới hay
// không, cho ĐÚNG 1 (readingId, sid) — đây là đòn bẩy CHÍNH để vừa bắt được
// bản voice nâng cấp (up đè trên Drive) vừa không tốn quota Drive API/tốn
// thời gian mỗi lần mở app. 24 giờ là lựa chọn hợp lý cho use-case "thỉnh
// thoảng nâng cấp 1 vài giọng" — không cần realtime.
private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

object TtsKokoroPackDownloader {

    // ── Kiểm tra NHANH (không gọi mạng): gói (readingId, sid) đã từng tải +
    // giải nén thành công chưa. Gọi hàm này TRƯỚC khi cân nhắc gọi
    // downloadAndExtract() — nếu true thì bỏ qua luôn, không cần hỏi lại
    // Drive. Đây là điểm DUY NHẤT quyết định "tránh tải 2 lần" cho cùng 1
    // (readingId, sid), dùng chung cho mọi nơi gọi. ─────────────────────
    fun isPackSynced(context: Context, readingId: String, sid: Int): Boolean {
        val markerFile = File(TtsAudioCache.voiceDir(context, readingId, VENDOR, sid), PACK_SYNCED_MARKER)
        return markerFile.exists()
    }

    // ── Kiểm tra NHANH (KHÔNG gọi mạng) — gói đã sync VÀ lần hỏi Drive gần
    // nhất còn nằm trong CHECK_INTERVAL_MS, tức "chưa tới hạn cần hỏi lại
    // Drive xem có bản mới không". Đây là cổng ĐẦU TIÊN mà syncIfNeeded()
    // nên gọi — trả về true ở TUYỆT ĐẠI ĐA SỐ lượt chạy (chỉ đọc 2 file nhỏ
    // trên đĩa). Chỉ khi trả về false (chưa từng sync HOẶC đã quá hạn check)
    // caller mới cần cân nhắc gọi tiếp checkForUpdate()/downloadAndExtract().
    // ⚠️ CỐ Ý KHÔNG còn yêu cầu isPackSynced()=true: 1 sid có thể HỢP LỆ
    // "chưa từng đồng bộ" mãi mãi vì Drive đơn giản KHÔNG CÓ gói cho sid đó
    // (giọng chưa được build/không áp dụng cho bài này) — trường hợp này
    // vẫn PHẢI được coi là "đã kiểm tra xong, chưa tới hạn hỏi lại", nếu
    // không fast-path ở syncAllVoicesForReading() sẽ không bao giờ đạt được
    // (luôn có sid không tồn tại → luôn phải quét lại Drive). Việc "có
    // checkedAt gần đây" tự nó đã đủ nghĩa "đã hỏi Drive rồi, dù kết quả là
    // có gói hay không có gói" — xem writeCheckedAtMillis() được gọi ở cả 2
    // nhánh (tìm thấy pack / không tìm thấy pack) trong downloadAndExtract()
    // và syncAllVoicesForReading().
    fun isPackUpToDate(context: Context, readingId: String, sid: Int, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val checkedAt = readCheckedAtMillis(context, readingId, sid) ?: return false
        return nowMillis - checkedAt < CHECK_INTERVAL_MS
    }

    // ── Kiểm tra NHANH (KHÔNG gọi mạng, chỉ đọc 1 file nhỏ) — bài đọc này đã
    // từng tải ĐỦ toàn bộ giọng Kokoro hiện có trên Drive hay chưa. Đây là
    // ĐIỂM GỌI DÙNG CHO "MỞ BÀI ĐỌC" — nếu true, KHÔNG cần làm gì thêm (không
    // gọi Drive, không kiểm tra từng sid, không quan tâm 24h) — xem
    // ensureReadingFullySynced() bên dưới, hàm đó tự gọi lại đúng hàm này ở
    // bước đầu tiên. Việc dò bản mới định kỳ cho giọng đã tải là TRÁCH NHIỆM
    // RIÊNG của syncAllVoicesForReading()/checkForUpdate(), KHÔNG liên quan
    // tới cờ này.
    fun isReadingFullySynced(context: Context, readingId: String): Boolean {
        val marker = File(TtsAudioCache.vendorDir(context, readingId, VENDOR), READING_FULLY_SYNCED_MARKER)
        return marker.exists()
    }

    private fun readCheckedAtMillis(context: Context, readingId: String, sid: Int): Long? {
        val file = File(TtsAudioCache.voiceDir(context, readingId, VENDOR, sid), PACK_CHECKED_AT_MARKER)
        if (!file.exists()) return null
        return try {
            file.readText().trim().toLongOrNull()
        } catch (e: Exception) {
            null
        }
    }

    private fun writeCheckedAtMillis(context: Context, readingId: String, sid: Int, nowMillis: Long) {
        try {
            val destDir = TtsAudioCache.voiceDir(context, readingId, VENDOR, sid)
            // ⚠️ PHẢI tự mkdirs() ở đây: với sid mà Drive KHÔNG có gói, thư
            // mục này chưa từng được tạo (bình thường chỉ extractZip() mới
            // mkdirs() sau khi tải thành công) — nếu không tự tạo, ghi
            // checkedAt sẽ luôn thất bại với ENOENT cho MỌI sid không tồn
            // tại trên Drive, khiến sid đó vĩnh viễn không "up to date" và
            // bug quét lại Drive mỗi lần enqueueDownloadAllVoices() (dù chưa
            // tới hạn 24h) vẫn còn nguyên dù đã sửa 2 chỗ trước đó.
            if (!destDir.exists()) destDir.mkdirs()
            File(destDir, PACK_CHECKED_AT_MARKER).writeText(nowMillis.toString())
        } catch (e: Exception) {
            Log.w(TAG, "writeCheckedAtMillis: ghi thất bại reading=$readingId sid=$sid (không ảnh hưởng audio đã có)", e)
        }
    }

    private fun readSyncedSha256(context: Context, readingId: String, sid: Int): String? {
        val file = File(TtsAudioCache.voiceDir(context, readingId, VENDOR, sid), PACK_SYNCED_MARKER)
        if (!file.exists()) return null
        return try {
            file.readText().trim().ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    // ── Điểm gọi khi ĐÃ có cache (isPackSynced()=true) nhưng ĐÃ QUÁ HẠN
    // check (isPackUpToDate()=false) — CHỈ gọi tới đây, KHÔNG gọi
    // downloadAndExtract() thẳng, để tránh tải lại file .zip khi nội dung
    // trên Drive KHÔNG hề đổi (trường hợp phổ biến nhất: chưa kịp up bản
    // mới, chỉ là tới chu kỳ 24h phải hỏi lại).
    //
    // Chi phí: ĐÚNG 1 lệnh gọi Drive files.list (source.fetchManifest()) —
    // CHỈ trả về metadata (tên file, sha256Checksum, modifiedTime), KHÔNG
    // tải nội dung .zip — rất nhẹ so với tải cả gói. Chỉ khi sha256 server
    // trả về KHÁC với sha256 đã lưu local (vừa up đè bản mới lên Drive) mới
    // thực sự gọi downloadAndExtract() để tải + giải nén bản mới.
    //
    // Trả về true nếu sau lượt gọi này, cache local coi như "đã cập nhật"
    // (dù là do không có gì mới, hay do vừa tải bản mới thành công) — false
    // nếu gọi Drive thất bại (mất mạng...) hoặc tải bản mới thất bại; ở cả
    // 2 trường hợp false, cache CŨ vẫn còn nguyên và vẫn dùng được bình
    // thường.
    suspend fun checkForUpdate(
        context: Context,
        source: TtsKokoroPackSource,
        readingId: String,
        sid: Int,
    ): Boolean {
        val now = System.currentTimeMillis()
        val manifest = source.fetchManifest(readingId)
        if (manifest == null) {
            Log.d(TAG, "checkForUpdate: không gọi được Drive (mất mạng?), giữ nguyên cache cũ reading=$readingId sid=$sid")
            // KHÔNG touch checkedAt ở đây — để lần gọi KẾ TIẾP thử hỏi lại
            // ngay (không phải chờ thêm 24h nữa), vì lần này thất bại là do
            // lỗi tạm thời (mất mạng), không phải "đã hỏi rồi không có gì mới".
            return false
        }

        val pack = manifest.findPack(readingId, sid)
        if (pack == null) {
            // Drive không còn gói cho (readingId, sid) này (hiếm — vd lỡ
            // xoá file trên Drive) — giữ nguyên cache local, chỉ cập nhật
            // checkedAt để không hỏi lại liên tục trong 24h tới.
            Log.d(TAG, "checkForUpdate: Drive không còn gói cho reading=$readingId sid=$sid, giữ cache cũ")
            writeCheckedAtMillis(context, readingId, sid, now)
            return true
        }

        val localSha256 = readSyncedSha256(context, readingId, sid)
        if (pack.sha256.equals(localSha256, ignoreCase = true)) {
            // Không có gì mới — chỉ cần "chạm" lại mốc thời gian check,
            // KHÔNG tải lại zip. Đây là nhánh chạy ở tuyệt đại đa số lượt
            // check (không nâng cấp voice mỗi ngày).
            Log.d(TAG, "checkForUpdate: reading=$readingId sid=$sid vẫn là bản mới nhất (sha256 không đổi)")
            writeCheckedAtMillis(context, readingId, sid, now)
            return true
        }

        // sha256 khác local → đã up đè bản mới lên Drive (cùng tên file,
        // Drive tự tính lại sha256Checksum mới) — tải lại đúng gói này.
        // ⚠️ Gọi thẳng downloadAndExtractPack(pack) — KHÔNG gọi lại
        // downloadAndExtract(context, source, readingId, sid) như bản cũ,
        // vì hàm đó sẽ tự fetchManifest() THÊM 1 LẦN NỮA dù `pack` ở đây
        // đã có sẵn đầy đủ thông tin cần thiết rồi — tránh hỏi Drive dư
        // thừa. downloadAndExtractPack() tự giải nén ĐÈ lên file cũ (cùng
        // tên) và tự ghi lại PACK_SYNCED_MARKER với sha256 mới.
        Log.d(
            TAG,
            "checkForUpdate: PHÁT HIỆN bản MỚI trên Drive cho reading=$readingId sid=$sid " +
                    "(sha256 cũ=$localSha256, mới=${pack.sha256}), tải lại"
        )
        val ok = downloadAndExtractPack(context, source, pack)
        if (ok) {
            writeCheckedAtMillis(context, readingId, sid, now)
        }
        // Nếu tải thất bại, KHÔNG touch checkedAt — để lần gọi kế tiếp thử
        // tải lại sớm, không phải chờ đủ 24h.
        return ok
    }

    // ── Điểm gọi GATED DUY NHẤT — dùng chung cho MỌI caller muốn đảm bảo
    // cache local đã đồng bộ với Drive mà KHÔNG tốn băng thông/quota nếu
    // chưa tới hạn. Mọi caller hiện tại (TtsKokoroPackWorker) và tương lai
    // (vd nút "làm mới" trong Settings) tự động được bảo vệ, không cần tự
    // nhớ implement lại gate ở từng nơi.
    //
    // ⚠️ NHẬN `source` LÀM THAM SỐ (không tự lấy từ registry toàn cục) — đây
    // là điểm SỬA so với bản gốc TtsRemotePackDownloader.syncIfNeeded() cũ
    // (từng tự gọi TtsRemoteSourceRegistry.current() bên trong). Giữ
    // TtsKokoroPackDownloader KHÔNG phụ thuộc trực tiếp vào registry —
    // caller (TtsKokoroPackWorker) tự tra registry của Kokoro rồi truyền
    // vào, giúp Downloader dễ test hơn (truyền source giả vào thẳng) và
    // tường minh hơn (đọc chữ ký hàm là biết ngay cần gì, không cần lần
    // theo registry ẩn bên trong).
    //
    // Trả về true nếu sau lượt gọi này cache coi như đã đồng bộ (không cần
    // làm gì vì chưa tới hạn, hoặc check thấy không có gì mới, hoặc vừa tải
    // bản mới thành công) — false nếu check/tải thất bại (cache cũ — nếu
    // có — vẫn dùng bình thường ở mọi nhánh false, không có gì bị xoá).
    suspend fun syncIfNeeded(
        context: Context,
        source: TtsKokoroPackSource,
        readingId: String,
        sid: Int,
    ): Boolean {
        if (isPackUpToDate(context, readingId, sid)) {
            Log.d(TAG, "syncIfNeeded: reading=$readingId sid=$sid đã đồng bộ và chưa tới hạn check lại, bỏ qua")
            return true
        }

        return if (isPackSynced(context, readingId, sid)) {
            Log.d(TAG, "syncIfNeeded: đã quá hạn check, hỏi Drive xem có bản mới cho reading=$readingId sid=$sid")
            checkForUpdate(context, source, readingId, sid)
        } else {
            Log.d(TAG, "syncIfNeeded: chưa từng đồng bộ, tải mới cho reading=$readingId sid=$sid")
            val ok = downloadAndExtract(context, source, readingId, sid)
            Log.d(TAG, "syncIfNeeded: kết quả tải=$ok cho reading=$readingId sid=$sid")
            ok
        }
    }

    // ── Wrapper MỎNG cho caller CHỈ biết readingId/sid (chưa có sẵn pack)
    // — tự fetchManifest() rồi tìm đúng pack, sau đó giao hết việc tải/xác
    // thực/giải nén cho downloadAndExtractPack() (private, bên dưới). Trả
    // về true nếu cuối cùng cache đã có audio sẵn sàng dùng (giải nén thành
    // công), false ở MỌI trường hợp khác (không có gói, tải lỗi, checksum
    // sai, giải nén lỗi) — caller không cần phân biệt lý do thất bại cụ thể.
    //
    // ⚠️ Hàm này KHÔNG tự kiểm tra isPackSynced()/isPackUpToDate() ở đầu —
    // caller phải tự gọi trước nếu muốn tránh gọi mạng không cần thiết (xem
    // syncIfNeeded() ở trên). Giữ tách biệt để vẫn dùng lại được cho trường
    // hợp CỐ TÌNH muốn ép tải lại (vd nút "làm mới cache" thủ công).
    //
    // ⚠️ Nếu ĐÃ có sẵn `pack` trong tay (vd đang lặp qua nhiều sid của cùng
    // 1 bài, đã fetchManifest() từ trước) — gọi thẳng downloadAndExtractPack()
    // thay vì hàm này, để KHÔNG fetchManifest() lại thêm 1 lần vô ích cho
    // đúng 1 thông tin đã có (xem checkForUpdate() và
    // syncAllVoicesForReading() bên dưới).
    suspend fun downloadAndExtract(
        context: Context,
        source: TtsKokoroPackSource,
        readingId: String,
        sid: Int,
    ): Boolean {
        val manifest = source.fetchManifest(readingId)
        if (manifest == null) {
            Log.d(TAG, "downloadAndExtract: không lấy được manifest cho reading=$readingId, bỏ qua")
            return false
        }

        val pack = manifest.findPack(readingId, sid)
        if (pack == null) {
            Log.d(TAG, "downloadAndExtract: nguồn không có gói cho reading=$readingId sid=$sid")
            // Vẫn "chạm" checkedAt dù không có gói — đã hỏi Drive xong, tránh
            // syncIfNeeded() hỏi lại Drive mỗi lần gọi cho đúng 1 sid không
            // tồn tại (xem ghi chú ở isPackUpToDate()).
            writeCheckedAtMillis(context, readingId, sid, System.currentTimeMillis())
            return false
        }

        return downloadAndExtractPack(context, source, pack)
    }

    // ── LÕI thật sự của việc tải — nhận thẳng `pack` (đã biết trước
    // downloadUrl/sha256, KHÔNG tự fetchManifest() bên trong) — dùng cho MỌI
    // caller đã có sẵn `pack` trong tay (checkForUpdate(),
    // syncAllVoicesForReading() bên dưới), tránh hỏi Drive dư thừa lần nữa
    // cho cùng 1 readingId chỉ để lấy lại đúng thông tin đã có.
    // downloadAndExtract(context, source, readingId, sid) ở trên là wrapper
    // MỎNG gọi xuống đây, dành cho caller CHƯA có sẵn pack (chỉ biết
    // readingId/sid).
    private suspend fun downloadAndExtractPack(
        context: Context,
        source: TtsKokoroPackSource,
        pack: TtsKokoroPackRef,
    ): Boolean {
        val readingId = pack.readingId
        val sid = pack.sid
        val tempZip = File(context.cacheDir, "tts_kokoro_pack_${readingId}_${sid}_${System.currentTimeMillis()}.zip")
        try {
            val downloaded = source.downloadPackFile(pack, tempZip)
            if (!downloaded) {
                Log.w(TAG, "downloadAndExtractPack: tải thất bại reading=$readingId sid=$sid url=${pack.downloadUrl}")
                return false
            }

            if (!verifyChecksum(tempZip, pack.sha256)) {
                Log.w(TAG, "downloadAndExtractPack: checksum KHÔNG khớp reading=$readingId sid=$sid, huỷ giải nén")
                return false
            }

            val destDir = TtsAudioCache.voiceDir(context, readingId, VENDOR, sid)
            val extracted = extractZip(tempZip, destDir)
            if (!extracted) {
                Log.w(TAG, "downloadAndExtractPack: giải nén lỗi reading=$readingId sid=$sid")
                return false
            }

            // ── Ghi marker NGAY SAU KHI giải nén xong — mọi lượt gọi
            // isPackSynced() sau đây (kể cả sau khi app bị kill/mở lại, vì
            // đây là file trên đĩa, không phải RAM) sẽ trả về true, không
            // cần hỏi lại Drive nữa. Lỗi ghi marker (hiếm, hết dung lượng...)
            // chỉ log cảnh báo, KHÔNG coi cả lượt tải là thất bại — audio đã
            // giải nén xong vẫn dùng được bình thường.
            try {
                File(destDir, PACK_SYNCED_MARKER).writeText(pack.sha256)
            } catch (e: Exception) {
                Log.w(TAG, "downloadAndExtractPack: ghi marker thất bại reading=$readingId sid=$sid (không ảnh hưởng audio đã tải)", e)
            }
            // Ghi luôn mốc "vừa check Drive xong" — để isPackUpToDate() trả
            // về true NGAY sau lần tải này.
            writeCheckedAtMillis(context, readingId, sid, System.currentTimeMillis())

            Log.d(TAG, "downloadAndExtractPack: xong reading=$readingId sid=$sid version=${pack.version}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "downloadAndExtractPack: lỗi khi xử lý reading=$readingId sid=$sid", e)
            return false
        } finally {
            // Luôn dọn zip tạm, kể cả khi có exception ở bất kỳ bước nào —
            // không để rác tồn đọng trong cacheDir.
            if (tempZip.exists()) tempZip.delete()
        }
    }

    // ── ĐIỂM GỌI MỚI — quét Drive ĐÚNG 1 LẦN cho toàn bộ `orderedSids` của
    // 1 bài, rồi tải tuần tự những giọng nào THỰC SỰ có trên Drive (giọng
    // nào không có thì bỏ qua, không coi là lỗi). Thay cho việc gọi
    // syncIfNeeded()/checkForUpdate()/downloadAndExtract() RIÊNG cho từng
    // sid — mỗi lần gọi riêng như vậy đều tự fetchManifest(readingId) lại
    // từ đầu, dù kết quả trả về GIỐNG HỆT nhau cho mọi sid của cùng 1 bài
    // (files.list không lọc theo sid, xem TtsGoogleDriveSource.kt) — với 1
    // bài có N giọng, cách cũ tốn N lần gọi Drive API cho đúng 1 thông tin,
    // hàm này chỉ tốn ĐÚNG 1 lần.
    //
    // orderedSids: thứ tự ưu tiên tải — phần tử ĐẦU tải trước (thường là
    // giọng người dùng đang chọn), phần tử sau tải theo thứ tự tuần tự
    // (không song song, xem enqueueDownloadAllVoices() ở TtsKokoroPackSync.kt).
    //
    // ⚠️ FAST PATH: nếu MỌI sid trong orderedSids đều isPackUpToDate() (đã
    // đồng bộ VÀ chưa tới hạn check lại 24h) — trả về true NGAY, KHÔNG gọi
    // Drive dù chỉ 1 lần. Đây là nhánh chạy ở tuyệt đại đa số lượt mở lại 1
    // bài đã tải xong từ trước.
    //
    // Trả về true nếu đã fetchManifest() thành công (bất kể từng giọng lẻ
    // tải được hay không — giọng nào lỗi/không có trên Drive chỉ log, không
    // làm hỏng kết quả chung) — false CHỈ khi không gọi được Drive (mất
    // mạng...), lúc đó cache cũ (nếu có) vẫn giữ nguyên, dùng bình thường.
    suspend fun syncAllVoicesForReading(
        context: Context,
        source: TtsKokoroPackSource,
        readingId: String,
        orderedSids: List<Int>,
    ): Boolean {
        if (orderedSids.isEmpty()) return true

        val now = System.currentTimeMillis()

        if (orderedSids.all { isPackUpToDate(context, readingId, it, now) }) {
            Log.d(
                TAG,
                "syncAllVoicesForReading: mọi giọng (${orderedSids.size}) đã đồng bộ, chưa tới hạn check lại, " +
                        "bỏ qua reading=$readingId — KHÔNG gọi Drive"
            )
            return true
        }

        Log.d(
            TAG,
            "syncAllVoicesForReading: quét Drive ĐÚNG 1 LẦN cho reading=$readingId " +
                    "(${orderedSids.size} giọng cần kiểm tra)"
        )
        val manifest = source.fetchManifest(readingId)
        if (manifest == null) {
            Log.d(TAG, "syncAllVoicesForReading: không gọi được Drive (mất mạng?), giữ nguyên cache cũ reading=$readingId")
            return false
        }

        for (sid in orderedSids) {
            // Có thể đã lệch so với kiểm tra fast-path ở đầu hàm (vd sid vừa
            // tới hạn 24h ngay lúc đang chạy vòng lặp) — kiểm tra lại cho
            // chắc, tránh tải thừa nếu không cần.
            if (isPackUpToDate(context, readingId, sid, now)) continue

            val pack = manifest.findPack(readingId, sid)
            if (pack == null) {
                Log.d(TAG, "syncAllVoicesForReading: Drive KHÔNG có giọng sid=$sid cho reading=$readingId, bỏ qua")
                // ⚠️ QUAN TRỌNG: vẫn phải ghi checkedAt dù không có gói — nếu
                // không, sid này VĨNH VIỄN không "up to date" (không bao giờ
                // có PACK_SYNCED_MARKER vì chưa từng tải được gì), khiến fast
                // path ở đầu hàm (orderedSids.all { isPackUpToDate(...) })
                // không bao giờ đạt được khi bài có giọng không tồn tại trên
                // Drive → mỗi lần enqueueDownloadAllVoices() (vd mỗi lần đổi
                // giọng) đều quét lại Drive từ đầu dù chưa tới hạn 24h.
                writeCheckedAtMillis(context, readingId, sid, now)
                continue
            }

            val localSha256 = readSyncedSha256(context, readingId, sid)
            if (pack.sha256.equals(localSha256, ignoreCase = true)) {
                // Không có gì mới, chỉ "chạm" lại mốc thời gian check.
                writeCheckedAtMillis(context, readingId, sid, now)
                continue
            }

            Log.d(
                TAG,
                "syncAllVoicesForReading: tải sid=$sid reading=$readingId " +
                        "(sha256 cũ=$localSha256, mới=${pack.sha256})"
            )
            val ok = downloadAndExtractPack(context, source, pack)
            if (ok) {
                writeCheckedAtMillis(context, readingId, sid, now)
            }
            // Nếu tải thất bại, KHÔNG touch checkedAt cho sid này — để lượt
            // sau thử tải lại sớm, không phải chờ đủ 24h; các sid khác
            // trong orderedSids vẫn tiếp tục xử lý bình thường.
        }

        return true
    }

    // ── ĐIỂM GỌI DÙNG CHO "MỞ BÀI ĐỌC" — mục tiêu DUY NHẤT là tải cho BẰNG
    // ĐƯỢC toàn bộ giọng Kokoro HIỆN CÓ trên Drive cho bài này (giọng nào
    // Drive không có thì bỏ qua, không tính là thiếu), rồi mới ghi
    // READING_FULLY_SYNCED_MARKER. KHÁC HẲN syncAllVoicesForReading() ở
    // trên (dùng cho dò bản mới ĐỊNH KỲ, có gate 24h, luôn coi "chưa tới hạn"
    // là xong việc dù chưa chắc đã tải đủ) — 2 hàm phục vụ 2 mục đích khác
    // nhau, KHÔNG gọi lẫn nhau, KHÔNG chia sẻ điều kiện dừng.
    //
    // ⚠️ KHÔNG dùng isPackUpToDate()/gate 24h ở đây — 1 sid tải lỗi ở lượt
    // trước (mất mạng giữa chừng) PHẢI được thử lại ở lượt gọi KẾ TIẾP, dù
    // mới cách đây vài giây, không phải chờ đủ 24h. Việc dò "server vừa up
    // bản MỚI đè lên bản đã tải" (khác với "tải lần đầu chưa xong") vẫn là
    // việc của syncAllVoicesForReading()/checkForUpdate(), không phải hàm này.
    //
    // Trả về true CHỈ KHI đã xử lý xong (tải được hoặc xác nhận Drive không
    // có) cho TOÀN BỘ orderedSids trong ĐÚNG 1 lượt gọi — lúc đó marker cấp
    // bài được ghi. Trả về false nếu: không gọi được Drive, HOẶC còn ít nhất
    // 1 sid tải lỗi — ở cả 2 trường hợp, KHÔNG ghi marker, caller (Worker)
    // tự quyết định có retry hay không (xem TtsKokoroReadingEnsureSyncWorker
    // ở TtsKokoroPackSync.kt — dùng Result.retry() thay vì luôn success()).
    suspend fun ensureReadingFullySynced(
        context: Context,
        source: TtsKokoroPackSource,
        readingId: String,
        orderedSids: List<Int>,
    ): Boolean {
        if (orderedSids.isEmpty()) return true

        // ── Cổng ĐẦU TIÊN, không gọi mạng — nếu bài này đã từng tải đủ, coi
        // như xong, KHÔNG cần hỏi Drive dù chỉ 1 lần. Đây là nhánh chạy ở
        // TUYỆT ĐẠI ĐA SỐ lượt mở lại 1 bài đã tải xong từ trước. ──────────
        if (isReadingFullySynced(context, readingId)) {
            Log.d(TAG, "ensureReadingFullySynced: reading=$readingId đã đánh dấu tải ĐỦ từ trước, bỏ qua")
            return true
        }

        val manifest = source.fetchManifest(readingId)
        if (manifest == null) {
            Log.d(TAG, "ensureReadingFullySynced: không gọi được Drive (mất mạng?), reading=$readingId, sẽ thử lại")
            return false
        }

        val now = System.currentTimeMillis()
        var allOk = true

        for (sid in orderedSids) {
            val pack = manifest.findPack(readingId, sid)
            if (pack == null) {
                // Drive không có giọng này cho bài này — coi là ĐÃ XỬ LÝ XONG
                // cho sid này (không phải lỗi), tiếp tục các sid còn lại.
                writeCheckedAtMillis(context, readingId, sid, now)
                continue
            }

            // Đã có sẵn ĐÚNG bản (sha256 khớp) từ 1 lượt tải trước đó (vd
            // syncAllVoicesForReading() đã tải sid này trước rồi) — không
            // cần tải lại, chỉ cần công nhận đã xử lý xong.
            val localSha256 = readSyncedSha256(context, readingId, sid)
            if (pack.sha256.equals(localSha256, ignoreCase = true)) {
                writeCheckedAtMillis(context, readingId, sid, now)
                continue
            }

            val ok = downloadAndExtractPack(context, source, pack)
            if (ok) {
                writeCheckedAtMillis(context, readingId, sid, now)
            } else {
                allOk = false
                Log.w(TAG, "ensureReadingFullySynced: tải thất bại sid=$sid reading=$readingId, sẽ thử lại ở lượt sau")
                // Vẫn TIẾP TỤC tải các sid còn lại — không dừng giữa chừng
                // chỉ vì 1 sid lỗi, để tận dụng tối đa mạng đang có sẵn.
            }
        }

        if (!allOk) {
            Log.d(TAG, "ensureReadingFullySynced: reading=$readingId CHƯA đủ giọng, chưa ghi marker hoàn tất")
            return false
        }

        try {
            val vendorDir = TtsAudioCache.vendorDir(context, readingId, VENDOR)
            if (!vendorDir.exists()) vendorDir.mkdirs()
            File(vendorDir, READING_FULLY_SYNCED_MARKER).writeText(now.toString())
        } catch (e: Exception) {
            // Audio đã tải xong hết, chỉ ghi marker thất bại (hiếm, hết dung
            // lượng...) — trả về false để lượt sau thử ghi lại marker, KHÔNG
            // coi là mất dữ liệu (mọi file audio vẫn dùng bình thường).
            Log.w(TAG, "ensureReadingFullySynced: ghi marker hoàn tất thất bại reading=$readingId", e)
            return false
        }

        Log.d(TAG, "ensureReadingFullySynced: reading=$readingId đã tải ĐỦ ${orderedSids.size} giọng, đánh dấu hoàn tất")
        return true
    }

    // ── So khớp SHA-256 của file zip vừa tải với giá trị server công bố ─────
    private fun verifyChecksum(file: File, expectedSha256: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return actual.equals(expectedSha256, ignoreCase = true)
    }

    // ── Giải nén toàn bộ zip vào `destDir` — tự tạo thư mục đích nếu chưa
    // có, tự chặn entry nào cố thoát ra ngoài destDir (zip slip). Trả về
    // false ngay khi gặp entry bất thường hoặc lỗi I/O bất kỳ, KHÔNG cố giải
    // nén tiếp phần còn lại (an toàn hơn giải nén dở dang, để lần tải sau
    // làm lại từ đầu). Trước khi giải nén, XOÁ SẠCH các file audio cũ trong
    // thư mục đích — đảm bảo không tồn đọng file lỗi thời của phiên bản
    // trước (vd item đã bị xoá khỏi bài, hoặc đổi hash do nội dung sửa lại)
    // — ⚠️ file marker (.pack_synced/.pack_checked_at) KHÔNG bị xoá vì sẽ
    // được ghi lại đúng NGAY SAU khi hàm này chạy xong ở downloadAndExtractPack().
    //
    // ⚠️ TÊN FILE giữ NGUYÊN từ entry.name trong zip — server Kokoro tự đóng
    // gói đúng quy ước "{type}_{itemId}_{hash}.ogg" của TtsAudioCache, hàm
    // này KHÔNG gọi TtsAudioCache.buildFilePath() để build lại tên, chỉ tin
    // tưởng tên đã đúng sẵn trong zip (an toàn vì đã qua verifyChecksum() ở
    // bước trước).
    private fun extractZip(zipFile: File, destDir: File): Boolean {
        if (!destDir.exists() && !destDir.mkdirs()) {
            Log.e(TAG, "extractZip: mkdirs() thất bại cho $destDir")
            return false
        }

        clearOldAudioFiles(destDir)

        val destCanonicalPath = destDir.canonicalPath
        return try {
            ZipInputStream(zipFile.inputStream()).use { zipInput ->
                var entry = zipInput.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val outFile = File(destDir, entry.name)
                        if (!outFile.canonicalPath.startsWith(destCanonicalPath + File.separator)) {
                            Log.e(TAG, "extractZip: entry bất thường '${entry.name}' thoát ra ngoài thư mục đích, huỷ")
                            return false
                        }
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { output ->
                            zipInput.copyTo(output)
                        }
                    }
                    zipInput.closeEntry()
                    entry = zipInput.nextEntry
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "extractZip: lỗi giải nén '${zipFile.name}'", e)
            false
        }
    }

    // ── Xoá mọi file audio đã có TRƯỚC KHI giải nén gói mới đè lên — tránh
    // tồn đọng audio của item đã bị xoá khỏi bài (server không còn đóng gói
    // lại nữa) hoặc audio ứng với hash cũ (nội dung đã sửa, tên file đổi
    // theo hash mới, file cũ nếu không xoá sẽ nằm lại vĩnh viễn không ai
    // dùng tới). CHỈ xoá file audio (không phải thư mục con, không phải 2
    // marker .pack_synced/.pack_checked_at — chúng sẽ được ghi lại ngay sau
    // khi giải nén xong ở downloadAndExtract()).
    //
    // ⚠️ Trước đây lọc theo `extension == "ogg"` cứng — giờ Kokoro luôn tạo
    // .ogg nên vẫn đúng trong thực tế, nhưng để KHÔNG phụ thuộc giả định
    // "TtsAudioCache chỉ có .ogg" (đã bỏ ở tầng cache chung), lọc bằng cách
    // loại trừ đúng 2 tên marker đã biết thay vì lọc theo đuôi — an toàn
    // hơn nếu sau này Kokoro đổi định dạng audio mà không cần sửa dòng này.
    private fun clearOldAudioFiles(destDir: File) {
        destDir.listFiles()?.forEach { file ->
            val isMarker = file.name == PACK_SYNCED_MARKER || file.name == PACK_CHECKED_AT_MARKER
            if (file.isFile && !isMarker) {
                file.delete()
            }
        }
    }
}