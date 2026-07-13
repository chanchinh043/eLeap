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
    fun isPackUpToDate(context: Context, readingId: String, sid: Int, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (!isPackSynced(context, readingId, sid)) return false
        val checkedAt = readCheckedAtMillis(context, readingId, sid) ?: return false
        return nowMillis - checkedAt < CHECK_INTERVAL_MS
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
        // downloadAndExtract() tự giải nén ĐÈ lên file cũ (cùng tên) và tự
        // ghi lại PACK_SYNCED_MARKER với sha256 mới.
        Log.d(
            TAG,
            "checkForUpdate: PHÁT HIỆN bản MỚI trên Drive cho reading=$readingId sid=$sid " +
                    "(sha256 cũ=$localSha256, mới=${pack.sha256}), tải lại"
        )
        val ok = downloadAndExtract(context, source, readingId, sid)
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

    // ── Điểm gọi CHÍNH — tải + xác thực + giải nén gói giọng `sid` của bài
    // `readingId` từ `source`. Trả về true nếu cuối cùng cache đã có audio
    // sẵn sàng dùng (giải nén thành công), false ở MỌI trường hợp khác
    // (không có gói, tải lỗi, checksum sai, giải nén lỗi) — caller không cần
    // phân biệt lý do thất bại cụ thể.
    //
    // ⚠️ Hàm này KHÔNG tự kiểm tra isPackSynced()/isPackUpToDate() ở đầu —
    // caller phải tự gọi trước nếu muốn tránh gọi mạng không cần thiết (xem
    // syncIfNeeded() ở trên). Giữ tách biệt để downloadAndExtract() vẫn
    // dùng lại được cho trường hợp CỐ TÌNH muốn ép tải lại (vd nút "làm mới
    // cache" thủ công, hoặc gọi từ checkForUpdate() khi đã xác nhận sha256
    // đổi).
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
            return false
        }

        val tempZip = File(context.cacheDir, "tts_kokoro_pack_${readingId}_${sid}_${System.currentTimeMillis()}.zip")
        try {
            val downloaded = source.downloadPackFile(pack, tempZip)
            if (!downloaded) {
                Log.w(TAG, "downloadAndExtract: tải thất bại reading=$readingId sid=$sid url=${pack.downloadUrl}")
                return false
            }

            if (!verifyChecksum(tempZip, pack.sha256)) {
                Log.w(TAG, "downloadAndExtract: checksum KHÔNG khớp reading=$readingId sid=$sid, huỷ giải nén")
                return false
            }

            val destDir = TtsAudioCache.voiceDir(context, readingId, VENDOR, sid)
            val extracted = extractZip(tempZip, destDir)
            if (!extracted) {
                Log.w(TAG, "downloadAndExtract: giải nén lỗi reading=$readingId sid=$sid")
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
                Log.w(TAG, "downloadAndExtract: ghi marker thất bại reading=$readingId sid=$sid (không ảnh hưởng audio đã tải)", e)
            }
            // Ghi luôn mốc "vừa check Drive xong" — để isPackUpToDate() trả
            // về true NGAY sau lần tải này.
            writeCheckedAtMillis(context, readingId, sid, System.currentTimeMillis())

            Log.d(TAG, "downloadAndExtract: xong reading=$readingId sid=$sid version=${pack.version}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "downloadAndExtract: lỗi khi xử lý reading=$readingId sid=$sid", e)
            return false
        } finally {
            // Luôn dọn zip tạm, kể cả khi có exception ở bất kỳ bước nào —
            // không để rác tồn đọng trong cacheDir.
            if (tempZip.exists()) tempZip.delete()
        }
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
    // được ghi lại đúng NGAY SAU khi hàm này chạy xong ở downloadAndExtract().
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