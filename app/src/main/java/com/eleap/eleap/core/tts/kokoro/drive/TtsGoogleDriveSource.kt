// TtsGoogleDriveSource.kt
// Đặt tại: com/eleap/eleap/core/tts/kokoro/drive/TtsGoogleDriveSource.kt
// (chuyển từ core/tts/kokoro/ sang core/tts/kokoro/drive/ — logic không
// đổi, chỉ đổi package. Đây là 1 TRANSPORT CỤ THỂ trong số có thể nhiều
// transport mà Kokoro hỗ trợ — nếu sau này thêm S3/CDN riêng, sẽ có thêm
// 1 thư mục anh em kokoro/s3/ với TtsS3Source.kt tương tự, không đụng gì
// tới file này)
//
// Implement TtsKokoroPackSource bằng Google Drive — transport CỤ THỂ mà
// Kokoro đang dùng để phân phối gói giọng. Đây KHÔNG phải "vendor giọng
// đọc" — Google Drive chỉ là NƠI CHỨA file zip mà Kokoro chọn dùng; nếu mai
// đổi sang S3, chỉ cần viết 1 kokoro/s3/TtsS3Source.kt khác, không đụng gì
// tới phần còn lại của Kokoro (Downloader/Sync/Voices).
//
// Dùng Service Account (OAuth) để có access_token thật, vì API key đơn
// thuần không đủ quyền gọi files.list (xem TtsServiceAccountAuth.kt để biết
// chi tiết luồng JWT Bearer + đánh đổi bảo mật đã chấp nhận).
//
// ── VÌ SAO KHÔNG CẦN TỰ QUẢN LÝ manifest.json/sha256 RIÊNG ─────────────────
// Drive tự tính VÀ trả về field "sha256Checksum" cho mọi file khi liệt kê
// qua API — field này khớp Y NGUYÊN với TtsKokoroPackRef.sha256 mà
// TtsKokoroPackDownloader.verifyChecksum() dùng để xác thực trước khi giải
// nén. "Danh sách file trong thư mục Drive" vẫn tự nó là manifest.
//
// ── QUY ƯỚC ĐẶT TÊN FILE TRÊN DRIVE ─────────────────────────────────────
// Tất cả file .zip nằm PHẲNG trong 1 thư mục gốc (rootFolderId), đặt tên
// đúng dạng "{readingId}_{sid}.zip". Bên trong mỗi .zip là các file audio
// đặt tên đúng quy ước "{type}_{itemId}_{contentHash}.<ext>" của
// TtsAudioCache.
//
// ── QUYỀN TRUY CẬP THƯ MỤC ──────────────────────────────────────────────
// Thư mục Drive PHẢI được share (Share → dán email service account, quyền
// Viewer) — KHÔNG cần "Anyone with the link", vì app xác thực bằng
// access_token thật của service account, không phải bằng việc file public.
//
// Dùng HttpURLConnection + org.json (đều có sẵn trong Android SDK) — KHÔNG
// thêm dependency mới.
package com.eleap.eleap.core.tts.kokoro.drive

import android.content.Context
import android.util.Log
import com.eleap.eleap.core.tts.kokoro.TtsKokoroPackManifest
import com.eleap.eleap.core.tts.kokoro.TtsKokoroPackRef
import com.eleap.eleap.core.tts.kokoro.TtsKokoroPackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val TAG = "TtsGoogleDriveSource"
private const val DRIVE_API_BASE = "https://www.googleapis.com/drive/v3"
private const val CONNECT_TIMEOUT_MS = 15_000
private const val READ_TIMEOUT_MS = 30_000

// ── context: dùng để TtsServiceAccountAuth đọc assets/tts_service_account.json
// và tự làm mới access_token khi cần — LUÔN truyền applicationContext ở nơi
// khởi tạo (TtsKokoroConfig) để tránh leak Activity/Context ngắn hạn. ───────
class TtsGoogleDriveSource(
    private val context: Context,
    private val rootFolderId: String,
) : TtsKokoroPackSource {

    // ── Hỏi Drive: những file .zip nào trong thư mục gốc có tên bắt đầu
    // bằng "{readingId}_" — trả về null nếu không có file khớp, nếu thiếu
    // access_token, hoặc nếu gọi mạng thất bại — đúng hợp đồng đã định nghĩa
    // ở TtsKokoroPackSource.kt: KHÔNG throw. ────────────────────────────────
    //
    // Bọc TOÀN BỘ thân hàm trong withContext(Dispatchers.IO) — cả
    // TtsServiceAccountAuth.getAccessToken() (có thể tự gọi mạng đồng bộ để
    // làm mới token khi hết hạn) LẪN httpGet() bên dưới đều dùng
    // HttpURLConnection (blocking I/O thuần), KHÔNG tự nhảy sang background
    // thread. suspend fun KHÔNG tự động chạy ngoài Main thread — nó chạy
    // trên đúng thread của coroutine gọi nó, nên PHẢI tự bọc ở đây để mọi
    // caller trong tương lai (kể cả gọi trực tiếp từ UI) đều an toàn.
    override suspend fun fetchManifest(readingId: String): TtsKokoroPackManifest? = withContext(Dispatchers.IO) {
        val accessToken = TtsServiceAccountAuth.getAccessToken(context)
        if (accessToken == null) {
            Log.d(TAG, "fetchManifest: không lấy được access_token, coi như chưa cấu hình")
            return@withContext null
        }

        val query = buildString {
            append("'").append(rootFolderId).append("' in parents")
            append(" and trashed = false")
            append(" and name contains '").append(escapeForDriveQuery(readingId)).append("_'")
        }
        val fields = "files(id,name,sha256Checksum,modifiedTime)"
        val url = "$DRIVE_API_BASE/files" +
                "?q=${urlEncode(query)}" +
                "&fields=${urlEncode(fields)}"

        val responseBody = try {
            httpGet(url, accessToken)
        } catch (e: Exception) {
            Log.w(TAG, "fetchManifest: lỗi gọi Drive API cho readingId=$readingId, coi như không có gì", e)
            return@withContext null
        }

        if (responseBody == null) {
            return@withContext null
        }

        try {
            val packs = parseFilesResponse(responseBody, readingId)
            if (packs.isEmpty()) {
                Log.d(TAG, "fetchManifest: Drive không có file nào cho readingId=$readingId")
                null
            } else {
                TtsKokoroPackManifest(packs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchManifest: lỗi parse response Drive cho readingId=$readingId", e)
            null
        }
    }

    // ── Tải nội dung 1 file (đã biết fileId qua downloadUrl) về đúng
    // destZip. Trả về false nếu bất kỳ bước nào thất bại — KHÔNG throw. ────
    override suspend fun downloadPackFile(pack: TtsKokoroPackRef, destZip: File): Boolean = withContext(Dispatchers.IO) {
        val accessToken = TtsServiceAccountAuth.getAccessToken(context)
        if (accessToken == null) {
            Log.w(TAG, "downloadPackFile: không lấy được access_token, huỷ tải reading=${pack.readingId} sid=${pack.sid}")
            return@withContext false
        }

        try {
            val connection = openConnection(pack.downloadUrl, accessToken)
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(
                    TAG,
                    "downloadPackFile: HTTP ${connection.responseCode} khi tải readingId=${pack.readingId} " +
                            "sid=${pack.sid}, url=${pack.downloadUrl}"
                )
                connection.disconnect()
                return@withContext false
            }

            connection.inputStream.use { input ->
                FileOutputStream(destZip).use { output ->
                    input.copyTo(output)
                }
            }
            connection.disconnect()
            true
        } catch (e: Exception) {
            Log.e(TAG, "downloadPackFile: lỗi tải readingId=${pack.readingId} sid=${pack.sid}", e)
            false
        }
    }

    // ── Parse response JSON của Drive files.list thành List<TtsKokoroPackRef> ─
    private fun parseFilesResponse(responseBody: String, readingId: String): List<TtsKokoroPackRef> {
        val json = JSONObject(responseBody)
        val filesArray = json.optJSONArray("files") ?: return emptyList()

        val prefix = "${readingId}_"
        val result = mutableListOf<TtsKokoroPackRef>()

        for (i in 0 until filesArray.length()) {
            val fileObj = filesArray.getJSONObject(i)
            val fileId = fileObj.getString("id")
            val name = fileObj.getString("name")
            val sha256 = fileObj.optString("sha256Checksum", "")

            if (sha256.isBlank()) {
                Log.w(TAG, "parseFilesResponse: file '$name' thiếu sha256Checksum (Drive chưa tính xong?), bỏ qua")
                continue
            }
            if (!name.startsWith(prefix) || !name.endsWith(".zip")) {
                continue
            }

            val sidPart = name.removePrefix(prefix).removeSuffix(".zip")
            val sid = sidPart.toIntOrNull()
            if (sid == null) {
                Log.w(TAG, "parseFilesResponse: không parse được sid từ tên file '$name', bỏ qua")
                continue
            }

            val version = System.currentTimeMillis().toInt()

            result.add(
                TtsKokoroPackRef(
                    readingId = readingId,
                    sid = sid,
                    downloadUrl = buildDownloadUrl(fileId),
                    sha256 = sha256,
                    version = version,
                )
            )
        }

        return result
    }

    private fun buildDownloadUrl(fileId: String): String =
        "$DRIVE_API_BASE/files/$fileId?alt=media"

    private fun escapeForDriveQuery(value: String): String = value.replace("'", "\\'")

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    // ── accessToken gắn qua header Authorization, KHÔNG qua query param
    // "?key=..." — đây là điểm khác biệt cốt lõi so với dùng API key. ──────
    private fun openConnection(url: String, accessToken: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.requestMethod = "GET"
        connection.setRequestProperty("Authorization", "Bearer $accessToken")
        return connection
    }

    private fun httpGet(url: String, accessToken: String): String? {
        val connection = openConnection(url, accessToken)
        connection.connect()
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }
                Log.w(TAG, "httpGet: HTTP ${connection.responseCode} cho url=$url, body=$errorBody")
                return null
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}