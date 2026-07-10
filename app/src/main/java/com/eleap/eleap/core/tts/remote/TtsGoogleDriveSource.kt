// TtsGoogleDriveSource.kt
// Đặt tại: com/eleap/eleap/core/tts/remote/TtsGoogleDriveSource.kt
//
// ⚠️ ĐÃ ĐỔI SANG SERVICE ACCOUNT (OAuth) — KHÔNG còn dùng API key nữa.
// Lý do đổi: API key đơn thuần không đủ quyền gọi files.list (search theo
// tên) trên Drive API kể cả với thư mục share công khai (Google chặn từ
// ~2019 để chống scrape) — bắt buộc phải có access_token OAuth thật. Dùng
// Service Account để có access_token mà KHÔNG cần người dùng app tự đăng
// nhập Google (xem TtsServiceAccountAuth.kt để biết chi tiết luồng JWT
// Bearer + đánh đổi bảo mật đã chấp nhận).
//
// ── VÌ SAO VẪN KHÔNG CẦN TỰ QUẢN LÝ manifest.json/sha256 RIÊNG ─────────────
// Drive vẫn tự tính VÀ trả về field "sha256Checksum" cho mọi file khi liệt
// kê qua API — field này khớp Y NGUYÊN với TtsRemotePackRef.sha256 mà
// TtsRemotePackDownloader.verifyChecksum() dùng để xác thực trước khi giải
// nén. "Danh sách file trong thư mục Drive" vẫn tự nó là manifest.
//
// ── QUY ƯỚC ĐẶT TÊN FILE TRÊN DRIVE (không đổi, xem step3_package_zip.py) ──
// Tất cả file .zip nằm PHẲNG trong 1 thư mục gốc (rootFolderId), đặt tên
// đúng dạng "{readingId}_{sid}.zip".
//
// ── QUYỀN TRUY CẬP THƯ MỤC ──────────────────────────────────────────────
// Thư mục Drive PHẢI được share (Share → dán email service account, quyền
// Viewer) — KHÔNG cần "Anyone with the link" nữa, vì giờ app xác thực bằng
// access_token thật của service account, không phải bằng việc file public.
//
// Dùng HttpURLConnection + org.json (đều có sẵn trong Android SDK) — KHÔNG
// thêm dependency mới, đúng tinh thần tối giản của dự án.
package com.eleap.eleap.core.tts.remote

import android.content.Context
import android.util.Log
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
// khởi tạo (TtsRemoteConfig) để tránh leak Activity/Context ngắn hạn. ───────
class TtsGoogleDriveSource(
    private val context: Context,
    private val rootFolderId: String,
) : TtsRemoteSource {

    // ── Hỏi Drive: những file .zip nào trong thư mục gốc có tên bắt đầu
    // bằng "{readingId}_" — trả về null nếu không có file khớp, nếu thiếu
    // access_token, hoặc nếu gọi mạng thất bại — đúng hợp đồng đã định nghĩa
    // ở TtsRemoteSource.kt: KHÔNG throw. ────────────────────────────────
    //
    // ⚠️ SỬA: bọc TOÀN BỘ thân hàm trong withContext(Dispatchers.IO) — cả
    // TtsServiceAccountAuth.getAccessToken() (có thể tự gọi mạng đồng bộ để
    // làm mới token khi hết hạn) LẪN httpGet() bên dưới đều dùng
    // HttpURLConnection (blocking I/O thuần), KHÔNG tự nhảy sang background
    // thread. suspend fun KHÔNG tự động chạy ngoài Main thread — nó chạy
    // trên đúng thread của coroutine gọi nó. Trước đây khi gọi từ
    // CoroutineWorker (TtsPregenWorker/TtsRemotePackWorker) tình cờ không
    // lộ lỗi vì WorkManager tự đặt dispatcher nền sẵn — nhưng khi gọi trực
    // tiếp từ UI (vd nút debug forceCheckCurrentReadingNow() trong
    // ReadingScreen, chạy trên Dispatchers.Main.immediate của
    // rememberCoroutineScope()) sẽ ném NetworkOnMainThreadException ngay
    // lập tức (xem log thực tế đã xác nhận). Bọc ở ĐÂY (nguồn gốc gây lỗi)
    // thay vì sửa từng nơi gọi — để MỌI caller trong tương lai đều tự động
    // an toàn, không cần nhớ tự withContext(Dispatchers.IO) mỗi lần gọi.
    override suspend fun fetchManifest(readingId: String): TtsRemoteManifest? = withContext(Dispatchers.IO) {
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
                TtsRemoteManifest(packs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchManifest: lỗi parse response Drive cho readingId=$readingId", e)
            null
        }
    }

    // ── Tải nội dung 1 file (đã biết fileId qua downloadUrl) về đúng
    // destZip. Trả về false nếu bất kỳ bước nào thất bại — KHÔNG throw. ────
    // ⚠️ SỬA: bọc withContext(Dispatchers.IO) — cùng lý do như fetchManifest()
    // ở trên (getAccessToken() + HttpURLConnection đều là blocking I/O).
    override suspend fun downloadPackFile(pack: TtsRemotePackRef, destZip: File): Boolean = withContext(Dispatchers.IO) {
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

    // ── Parse response JSON của Drive files.list thành List<TtsRemotePackRef> ─
    // (không đổi logic so với bản API key — chỉ khác cách xác thực request)
    private fun parseFilesResponse(responseBody: String, readingId: String): List<TtsRemotePackRef> {
        val json = JSONObject(responseBody)
        val filesArray = json.optJSONArray("files") ?: return emptyList()

        val prefix = "${readingId}_"
        val result = mutableListOf<TtsRemotePackRef>()

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
                TtsRemotePackRef(
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

    // ── accessToken giờ gắn qua header Authorization, KHÔNG còn qua query
    // param "?key=..." như trước — đây là điểm khác biệt cốt lõi so với
    // bản API key. ──────────────────────────────────────────────────────
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