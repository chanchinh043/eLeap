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
// ⚠️ Giới hạn đã biết: nếu sau này server build lại gói mới (sha256 đổi),
// marker này KHÔNG tự phát hiện — app sẽ tiếp tục dùng bản cache cũ. Chấp
// nhận được ở giai đoạn này (chưa có cơ chế versioning phía app); nếu cần
// version-check sau này, đổi sang lưu sha256 vào nội dung marker và so
// sánh với manifest thay vì chỉ kiểm tra tồn tại file.
private const val REMOTE_SYNCED_MARKER = ".remote_synced"

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

    // ── Điểm gọi CHÍNH — tải + xác thực + giải nén gói giọng `sid` của bài
    // `readingId` từ `source`. Trả về true nếu cuối cùng cache đã có audio
    // sẵn sàng dùng (giải nén thành công), false ở MỌI trường hợp khác
    // (không có gói, tải lỗi, checksum sai, giải nén lỗi) — caller (bước sau:
    // TtsRemotePackWorker) không cần phân biệt lý do thất bại cụ thể, chỉ
    // cần biết "có nên fallback sang chờ pregen tự sinh hay không".
    //
    // ⚠️ Hàm này KHÔNG tự kiểm tra isPackSynced() ở đầu — caller phải tự
    // gọi isPackSynced() trước nếu muốn tránh gọi mạng không cần thiết (xem
    // TtsPregenWorker.ensureRemotePackSynced()). Giữ tách biệt 2 hàm để
    // downloadAndExtract() vẫn dùng lại được cho trường hợp CỐ TÌNH muốn ép
    // tải lại (vd tương lai có nút "làm mới cache" thủ công).
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