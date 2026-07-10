// TtsRemotePackDownloader.kt
// Đặt tại: com/eleap/eleap/core/tts/remote/TtsRemotePackDownloader.kt
//
// Orchestrator DUY NHẤT của package remote/ — không quan tâm nguồn tải là
// Google Drive hay gì khác (nhận vào qua interface TtsRemoteSource), chỉ lo
// đúng 3 việc tuần tự: (1) tải file .zip về thư mục tạm, (2) xác thực
// checksum, (3) giải nén thẳng vào ĐÚNG thư mục mà TtsAudioCache.voiceDir()
// quy định — để TtsPlaybackRouter/TtsCacheAuditor tự nhận ra cache mà không
// cần biết gì về package remote/ này (xem TtsAudioCache.kt: "sự tồn tại của
// đúng file tự nó là index").
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
// buộc vì nội dung zip đến từ nguồn BÊN NGOÀI (không phải do chính app tạo
// ra như trường hợp pregen/), không nên tin tưởng tuyệt đối cấu trúc bên
// trong.
//
// Không phải class tự giữ trạng thái — mọi hàm đều nhận đủ tham số cần
// thiết (context, source, readingId, sid), gọi xong là xong, không cần
// init()/singleton lifecycle nào cả.
package com.eleap.eleap.core.tts.remote

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import com.eleap.eleap.core.tts.cache.TtsAudioCache

private const val TAG = "TtsRemotePackDownloader"

// ── Tên file đánh dấu "đã đồng bộ gói remote xong" — ghi vào ĐÚNG thư mục
// voiceDir(readingId, sid) ngay sau khi giải nén thành công. Đây KHÔNG phải
// audio, TtsAudioCache/TtsPlaybackRouter/TtsCacheAuditor không đọc file
// này — chỉ TtsRemotePackDownloader tự đọc lại để trả lời "đã tải gói này
// chưa" mà KHÔNG cần hỏi lại Drive (isPackSynced() bên dưới) — tránh
// TtsPregenWorker gọi lại downloadAndExtract() mỗi lần processReading() lặp
// lại cho cùng (readingId, sid) đã tải xong từ trước.
//
// ⚠️ MỚI: đã VÁ giới hạn ghi chú ở trên — marker này giờ lưu ĐÚNG sha256 của
// pack tại thời điểm tải (không đổi so với trước), và có thêm marker
// REMOTE_CHECKED_AT_MARKER (bên dưới) để so sánh phát hiện bản mới trên
// Drive theo chu kỳ, xem checkForUpdate() và isPackUpToDate().
private const val REMOTE_SYNCED_MARKER = ".remote_synced"

// ── MỚI: Tên file đánh dấu "lần cuối cùng đã hỏi Drive xem có bản mới
// không" — TÁCH RIÊNG khỏi REMOTE_SYNCED_MARKER (giữ đúng tinh thần "1
// marker = 1 mục đích" của các file khác trong dự án, vd AssetCopier.kt).
// Chỉ TtsRemotePackDownloader tự đọc/ghi, không nơi nào khác cần biết.
private const val REMOTE_CHECKED_AT_MARKER = ".remote_checked_at"

// ── MỚI: khoảng thời gian tối thiểu giữa 2 lần hỏi Drive xem có bản mới
// hay không, cho ĐÚNG 1 (readingId, sid) — đây là đòn bẩy CHÍNH để vừa bắt
// được bản voice nâng cấp (up đè trên Drive) vừa không tốn quota Drive
// API/tốn thời gian mỗi lần mở app. 24 giờ là lựa chọn hợp lý cho use-case
// "thỉnh thoảng nâng cấp 1 vài giọng" — không cần realtime, và vẫn đủ
// nhanh để người dùng thấy bản mới trong vòng 1 ngày mà không cần chờ
// build lại app hay xoá cache thủ công. Tăng lên (vd 7 ngày) nếu muốn tiết
// kiệm quota Drive hơn nữa, hoặc giảm xuống nếu cần cập nhật gấp hơn.
private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

object TtsRemotePackDownloader {

    // ── Kiểm tra NHANH (không gọi mạng): gói (readingId, sid) đã từng tải +
    // giải nén thành công chưa. Gọi hàm này TRƯỚC khi cân nhắc gọi
    // downloadAndExtract() — nếu true thì bỏ qua luôn, không cần hỏi lại
    // Drive. Đây là điểm DUY NHẤT quyết định "tránh tải 2 lần" cho cùng 1
    // (readingId, sid), dùng chung cho mọi nơi gọi (TtsPregenWorker,
    // TtsRemotePackWorker). ─────────────────────────────────────────────
    fun isPackSynced(context: Context, readingId: String, sid: Int): Boolean {
        val markerFile = File(TtsAudioCache.voiceDir(context, readingId, sid), REMOTE_SYNCED_MARKER)
        return markerFile.exists()
    }

    // ── MỚI: Kiểm tra NHANH (KHÔNG gọi mạng) — gói đã sync VÀ lần hỏi Drive
    // gần nhất còn nằm trong CHECK_INTERVAL_MS, tức "chưa tới hạn cần hỏi
    // lại Drive xem có bản mới không". Đây là cổng ĐẦU TIÊN mà
    // TtsPregenWorker.ensureRemotePackSynced() nên gọi — trả về true ở
    // TUYỆT ĐẠI ĐA SỐ lượt chạy (chỉ đọc 2 file nhỏ trên đĩa), giữ nguyên
    // tốc độ/chi phí như isPackSynced() cũ. Chỉ khi trả về false (chưa
    // từng sync HOẶC đã quá hạn check) caller mới cần cân nhắc gọi tiếp
    // checkForUpdate()/downloadAndExtract(). ───────────────────────────────
    fun isPackUpToDate(context: Context, readingId: String, sid: Int, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (!isPackSynced(context, readingId, sid)) return false
        val checkedAt = readCheckedAtMillis(context, readingId, sid) ?: return false
        return nowMillis - checkedAt < CHECK_INTERVAL_MS
    }

    private fun readCheckedAtMillis(context: Context, readingId: String, sid: Int): Long? {
        val file = File(TtsAudioCache.voiceDir(context, readingId, sid), REMOTE_CHECKED_AT_MARKER)
        if (!file.exists()) return null
        return try {
            file.readText().trim().toLongOrNull()
        } catch (e: Exception) {
            null
        }
    }

    private fun writeCheckedAtMillis(context: Context, readingId: String, sid: Int, nowMillis: Long) {
        try {
            val destDir = TtsAudioCache.voiceDir(context, readingId, sid)
            File(destDir, REMOTE_CHECKED_AT_MARKER).writeText(nowMillis.toString())
        } catch (e: Exception) {
            Log.w(TAG, "writeCheckedAtMillis: ghi thất bại reading=$readingId sid=$sid (không ảnh hưởng audio đã có)", e)
        }
    }

    private fun readSyncedSha256(context: Context, readingId: String, sid: Int): String? {
        val file = File(TtsAudioCache.voiceDir(context, readingId, sid), REMOTE_SYNCED_MARKER)
        if (!file.exists()) return null
        return try {
            file.readText().trim().ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    // ── MỚI: Điểm gọi khi ĐÃ có cache (isPackSynced()=true) nhưng ĐÃ QUÁ
    // HẠN check (isPackUpToDate()=false) — CHỈ gọi tới đây, KHÔNG gọi
    // downloadAndExtract() thẳng, để tránh tải lại file .zip khi nội dung
    // trên Drive KHÔNG hề đổi (trường hợp phổ biến nhất: bạn chưa kịp up
    // bản mới, chỉ là tới chu kỳ 24h phải hỏi lại).
    //
    // Chi phí: ĐÚNG 1 lệnh gọi Drive files.list (source.fetchManifest()) —
    // CHỈ trả về metadata (tên file, sha256Checksum, modifiedTime), KHÔNG
    // tải nội dung .zip — rất nhẹ so với tải cả gói. Chỉ khi sha256 server
    // trả về KHÁC với sha256 đã lưu local (bạn vừa up đè bản mới lên Drive)
    // mới thực sự gọi downloadAndExtract() để tải + giải nén bản mới.
    //
    // Trả về true nếu sau lượt gọi này, cache local coi như "đã cập nhật"
    // (dù là do không có gì mới, hay do vừa tải bản mới thành công) — false
    // nếu gọi Drive thất bại (mất mạng...) hoặc tải bản mới thất bại; ở cả
    // 2 trường hợp false, cache CŨ vẫn còn nguyên và vẫn dùng được bình
    // thường (an toàn, không xoá gì trước khi có bản thay thế chắc chắn
    // tải xong — xem downloadAndExtract()/extractZip() tự ghi đè đúng tên
    // file, không xoá trước).
    suspend fun checkForUpdate(
        context: Context,
        source: TtsRemoteSource,
        readingId: String,
        sid: Int,
    ): Boolean {
        val now = System.currentTimeMillis()
        val manifest = source.fetchManifest(readingId)
        if (manifest == null) {
            Log.d(TAG, "checkForUpdate: không gọi được Drive (mất mạng?), giữ nguyên cache cũ reading=$readingId sid=$sid")
            // KHÔNG touch checkedAt ở đây — để lần doWork() KẾ TIẾP thử
            // hỏi lại ngay (không phải chờ thêm 24h nữa), vì lần này thất
            // bại là do lỗi tạm thời (mất mạng), không phải "đã hỏi rồi
            // không có gì mới".
            return false
        }

        val pack = manifest.findPack(readingId, sid)
        if (pack == null) {
            // Drive không còn gói cho (readingId, sid) này (hiếm — vd bạn
            // lỡ xoá file trên Drive) — giữ nguyên cache local, chỉ cập
            // nhật checkedAt để không hỏi lại liên tục trong 24h tới.
            Log.d(TAG, "checkForUpdate: Drive không còn gói cho reading=$readingId sid=$sid, giữ cache cũ")
            writeCheckedAtMillis(context, readingId, sid, now)
            return true
        }

        val localSha256 = readSyncedSha256(context, readingId, sid)
        if (pack.sha256.equals(localSha256, ignoreCase = true)) {
            // Không có gì mới — chỉ cần "chạm" lại mốc thời gian check,
            // KHÔNG tải lại zip. Đây là nhánh chạy ở tuyệt đại đa số lượt
            // check (bạn không nâng cấp voice mỗi ngày).
            Log.d(TAG, "checkForUpdate: reading=$readingId sid=$sid vẫn là bản mới nhất (sha256 không đổi)")
            writeCheckedAtMillis(context, readingId, sid, now)
            return true
        }

        // sha256 khác local → bạn đã up đè bản mới lên Drive (cùng tên
        // file, Drive tự tính lại sha256Checksum mới) — tải lại đúng gói
        // này. downloadAndExtract() tự giải nén ĐÈ lên file cũ (cùng tên)
        // và tự ghi lại REMOTE_SYNCED_MARKER với sha256 mới.
        Log.d(
            TAG,
            "checkForUpdate: PHÁT HIỆN bản MỚI trên Drive cho reading=$readingId sid=$sid " +
                    "(sha256 cũ=$localSha256, mới=${pack.sha256}), tải lại"
        )
        val ok = downloadAndExtract(context, source, readingId, sid)
        if (ok) {
            writeCheckedAtMillis(context, readingId, sid, now)
        }
        // Nếu tải thất bại, KHÔNG touch checkedAt — để lần doWork() kế
        // tiếp thử tải lại sớm, không phải chờ đủ 24h.
        return ok
    }

    // ── MỚI: Điểm gọi GATED DUY NHẤT — dùng chung cho MỌI caller muốn đảm
    // bảo cache local đã đồng bộ với Drive mà KHÔNG tốn băng thông/quota
    // nếu chưa tới hạn. Trước đây logic này (isPackUpToDate → isPackSynced
    // → checkForUpdate/downloadAndExtract) nằm RIÊNG bên trong
    // TtsPregenWorker.ensureRemotePackSynced() — TtsRemotePackWorker (chạy
    // khi người dùng mở 1 bài cụ thể) lại gọi thẳng downloadAndExtract(),
    // bỏ qua hoàn toàn gate 24h → tải lại nguyên gói .zip mỗi lần mở bài dù
    // chưa hề có gì đổi trên Drive (đã xác nhận qua log thực tế
    // 2026-07-10: TtsRemotePackWorker tải lại đúng lúc TtsPregenWorker nói
    // "đã đồng bộ, bỏ qua" cho CÙNG 1 (readingId, sid)).
    //
    // Chuyển logic gate vào ĐÂY để mọi caller hiện tại (TtsPregenWorker,
    // TtsRemotePackWorker) và tương lai (vd nút "làm mới" trong Settings)
    // tự động được bảo vệ, không cần tự nhớ implement lại gate ở từng nơi.
    //
    // Trả về true nếu sau lượt gọi này cache coi như đã đồng bộ (không cần
    // làm gì vì chưa tới hạn, hoặc check thấy không có gì mới, hoặc vừa tải
    // bản mới thành công) — false nếu chưa cấu hình nguồn remote, hoặc
    // check/tải thất bại (cache cũ — nếu có — vẫn dùng bình thường ở mọi
    // nhánh false, không có gì bị xoá).
    suspend fun syncIfNeeded(context: Context, readingId: String, sid: Int): Boolean {
        if (isPackUpToDate(context, readingId, sid)) {
            Log.d(TAG, "syncIfNeeded: reading=$readingId sid=$sid đã đồng bộ và chưa tới hạn check lại, bỏ qua")
            return true
        }

        val source = TtsRemoteSourceRegistry.current()
        if (source == null) {
            Log.d(TAG, "syncIfNeeded: chưa cấu hình nguồn remote, coi như không có gì để đồng bộ")
            return false
        }

        return if (isPackSynced(context, readingId, sid)) {
            Log.d(TAG, "syncIfNeeded: đã quá hạn check, hỏi Drive xem có bản mới cho reading=$readingId sid=$sid")
            checkForUpdate(context, source, readingId, sid)
        } else {
            Log.d(TAG, "syncIfNeeded: kiểm tra Drive TRƯỚC khi generate on-device cho reading=$readingId sid=$sid")
            val ok = downloadAndExtract(context, source, readingId, sid)
            Log.d(TAG, "syncIfNeeded: kết quả tải=$ok cho reading=$readingId sid=$sid")
            ok
        }
    }

    // ── Điểm gọi CHÍNH — tải + xác thực + giải nén gói giọng `sid` của bài
    // `readingId` từ `source`. Trả về true nếu cuối cùng cache đã có audio
    // sẵn sàng dùng (giải nén thành công), false ở MỌI trường hợp khác
    // (không có gói, tải lỗi, checksum sai, giải nén lỗi) — caller (bước sau:
    // TtsRemotePackWorker) không cần phân biệt lý do thất bại cụ thể, chỉ
    // cần biết "có nên fallback sang chờ pregen tự sinh hay không".
    //
    // ⚠️ Hàm này KHÔNG tự kiểm tra isPackSynced()/isPackUpToDate() ở đầu —
    // caller phải tự gọi trước nếu muốn tránh gọi mạng không cần thiết (xem
    // TtsPregenWorker.ensureRemotePackSynced()). Giữ tách biệt để
    // downloadAndExtract() vẫn dùng lại được cho trường hợp CỐ TÌNH muốn ép
    // tải lại (vd nút "làm mới cache" thủ công, hoặc gọi từ checkForUpdate()
    // ở trên khi đã xác nhận sha256 đổi).
    suspend fun downloadAndExtract(
        context: Context,
        source: TtsRemoteSource,
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

        val tempZip = File(context.cacheDir, "tts_remote_${readingId}_${sid}_${System.currentTimeMillis()}.zip")
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

            val destDir = TtsAudioCache.voiceDir(context, readingId, sid)
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
            // giải nén xong vẫn dùng được bình thường, chỉ là lần sau có thể
            // phải hỏi lại Drive 1 lần nữa (không gây sai dữ liệu).
            try {
                File(destDir, REMOTE_SYNCED_MARKER).writeText(pack.sha256)
            } catch (e: Exception) {
                Log.w(TAG, "downloadAndExtract: ghi marker thất bại reading=$readingId sid=$sid (không ảnh hưởng audio đã tải)", e)
            }
            // ── MỚI: ghi luôn mốc "vừa check Drive xong" — để isPackUpToDate()
            // trả về true NGAY sau lần tải này, không rơi vào vòng lặp gọi
            // checkForUpdate() lại ở lượt processReading() kế tiếp trong
            // CÙNG phiên chạy Worker (vd bài dài, nhiều sentence, mỗi
            // sentence gọi lại ensureRemotePackSynced() 1 lần).
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
    // làm lại từ đầu).
    private fun extractZip(zipFile: File, destDir: File): Boolean {
        if (!destDir.exists() && !destDir.mkdirs()) {
            Log.e(TAG, "extractZip: mkdirs() thất bại cho $destDir")
            return false
        }

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

                        // ── MỚI: dọn .wav CŨ trùng (type, itemId, hash) nếu có —
                        // trường hợp item này đã được TtsPregenWorker generate
                        // on-device (.wav) TRƯỚC KHI gói .ogg cùng hash được
                        // tải về sau đó (vd Drive vừa có gói mới, hoặc lượt
                        // ensureRemotePackSynced() trước đó tải thất bại rồi
                        // pregen/ đã tự generate .wav như lưới an toàn). Ưu
                        // tiên dùng .ogg (đã kiểm câm sẵn ở pipeline build gói,
                        // xem TtsCacheAuditor.kt) — xoá luôn .wav để không tồn
                        // đọng 2 file cùng nội dung, tránh lẫn lộn định dạng
                        // trong thư mục cache. CHỈ áp dụng khi outFile vừa ghi
                        // là .ogg (không đụng tới trường hợp ngược lại — nhánh
                        // GHI .wav luôn do saveGenerated() đảm nhiệm, không đi
                        // qua extractZip() này).
                        if (outFile.extension.equals("ogg", ignoreCase = true)) {
                            val staleWav = File(outFile.parentFile, outFile.nameWithoutExtension + ".wav")
                            if (staleWav.exists()) {
                                val deleted = staleWav.delete()
                                Log.d(
                                    TAG,
                                    "extractZip: đã có sẵn .wav on-device trùng hash '${staleWav.name}', " +
                                            "ưu tiên .ogg vừa tải về, xoá .wav cũ → deleted=$deleted"
                                )
                            }
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
}