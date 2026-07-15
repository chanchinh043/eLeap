// TtsMyReadingRequestClient.kt
// Đặt tại: com/eleap/eleap/core/tts/kokoro/myreading/TtsMyReadingRequestClient.kt
//
// ⚠️ PHẠM VI: file này CHỈ lo việc "xin server tổng hợp giọng Kokoro cho 1
// bài MyReading" — hoàn toàn KHÔNG đụng gì tới việc TẢI kết quả về (đó vẫn
// là việc của TtsKokoroPackDownloader/TtsGoogleDriveSource như bình thường,
// vì server đóng gói xong vẫn upload vào ĐÚNG folder Drive hiện tại đang
// dùng cho Kokoro — không tách folder riêng, xem quyết định đã chốt).
//
// Vì sao cần 1 client riêng thay vì tái dùng TtsKokoroPackSource: đây là 2
// PHA khác nhau trong pipeline —
//   (a) "xin server xử lý"  → client này
//   (b) "tải kết quả có sẵn" → TtsKokoroPackSource (đã có, không đổi)
// Nhét chung sẽ làm lẫn lộn trách nhiệm giữa "yêu cầu tạo mới" và "tải cái
// đã tồn tại", đúng thứ toàn bộ package core/tts/ đang cố tách bạch.
//
// ⚠️ CHỈ ÁP DỤNG CHO MYREADING — bài đọc hệ thống build TTS theo pipeline
// thủ công/batch riêng, KHÔNG đi qua client này. Đây cũng là lý do đặt
// trong thư mục con myreading/ thay vì ngang hàng với drive/ trong kokoro/.
//
// ── HỢP ĐỒNG VỚI SERVER (thoả thuận riêng, không phải chuẩn Kokoro chung) ──
// POST {baseUrl}/tts/myreading/request
//   body: {"readingId": "...", "sid": <int>, "contentHash": "..."}
//   → server dedup theo (readingId, sid, contentHash) bằng UNIQUE constraint
//     ở tầng DB của server — 2 thiết bị gửi cùng bộ 3 giá trị này chỉ tạo ra
//     ĐÚNG 1 job xử lý, an toàn khi gọi trùng lặp (an toàn để gọi nhiều lần,
//     kể cả từ nhiều thiết bị cùng lúc).
//   response JSON: {"status": "pending" | "processing" | "ready" | "failed"}
//
// GET {baseUrl}/tts/myreading/status?readingId=...&sid=...&contentHash=...
//   response JSON: {"status": "pending" | "processing" | "ready" | "failed"}
//   → dùng để hỏi trước khi thử tải Drive, tránh gọi Drive API vô ích khi
//     biết chắc server chưa xử lý xong.
//
// contentHash: PHẢI dùng đúng hàm TtsMyReadingContentHash.compute() (file
// anh em cùng thư mục) — hash tổng hợp toàn bộ nội dung bài tại thời điểm
// gọi, để server tự nhận ra bài đã bị sửa nội dung (hash đổi = job mới,
// không đụng job cũ).
//
// Mọi hàm KHÔNG throw ra ngoài — lỗi mạng/server chỉ log rồi trả về null,
// đúng tinh thần "core/tts/ luôn là lưới an toàn tuỳ chọn, không bao giờ
// làm app crash" đã thống nhất từ TtsServiceAccountAuth.kt/TtsGoogleDriveSource.kt.
//
// Dùng HttpURLConnection + org.json (có sẵn Android SDK) — KHÔNG thêm
// dependency mới, cùng phong cách với TtsGoogleDriveSource.kt.
package com.eleap.eleap.core.tts.kokoro.myreading

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val TAG = "TtsMyReadingRequestClient"
private const val CONNECT_TIMEOUT_MS = 15_000
private const val READ_TIMEOUT_MS = 30_000

// ── Trạng thái xử lý phía server — ánh xạ 1:1 với cột tts_status trên DB
// của server (không phải Supabase, xem ghi chú đầu file). UNKNOWN dùng khi
// parse thất bại/giá trị lạ, để caller tự quyết định coi như "chưa sẵn
// sàng" thay vì crash. ───────────────────────────────────────────────────
enum class TtsMyReadingJobStatus {
    PENDING,
    PROCESSING,
    READY,
    FAILED,
    UNKNOWN;

    companion object {
        fun fromApiValue(value: String?): TtsMyReadingJobStatus = when (value) {
            "pending" -> PENDING
            "processing" -> PROCESSING
            "ready" -> READY
            "failed" -> FAILED
            else -> UNKNOWN
        }
    }
}

// ── Loại item — KHỚP 1:1 với pattern "^(sentence|word|phrase)$" ở
// MyReadingItem (main.py, tầng Pydantic) và với tên field `type` trong JSON
// body gửi lên. apiValue LUÔN là chữ thường, KHÔNG dùng .name/.toLowerCase()
// tự động ở nơi gọi để tránh vỡ hợp đồng nếu sau này đổi thứ tự enum. ──────
enum class TtsMyReadingItemType(val apiValue: String) {
    SENTENCE("sentence"),
    WORD("word"),
    PHRASE("phrase"),
}

// ── 1 đơn vị text cần server tổng hợp giọng — KHỚP 1:1 field JSON mà
// MyReadingItem (main.py) mong đợi: type/itemId/textEn (camelCase, đúng như
// Android gửi mọi field khác trong body request này). Xây dựng bởi
// TtsMyReadingSyncTrigger.buildItems() từ CHÍNH danh sách sentences dùng để
// tính contentHash — đảm bảo không lệch pha giữa hash và nội dung gửi lên. ──
data class TtsMyReadingItem(
    val type: TtsMyReadingItemType,
    val itemId: String,
    val textEn: String,
)

// ── Client gọi server — KHÔNG phải singleton object như các file khác
// trong kokoro/, vì baseUrl là tham số biến thiên theo cấu hình (đọc từ
// BuildConfig ở nơi khởi tạo, xem TtsMyReadingConfig.kt sẽ thêm ở bước sau)
// chứ không cố định như rootFolderId của Drive. Instance hoá đơn giản, có
// thể coi là "nhẹ" (không giữ state gì ngoài baseUrl), tạo mới thoải mái. ──
class TtsMyReadingRequestClient(
    private val baseUrl: String,
) {

    // ── Xin server xử lý — AN TOÀN gọi lặp lại nhiều lần/nhiều thiết bị,
    // server tự dedup theo (readingId, sid, contentHash). Trả về status
    // hiện tại ngay sau khi gọi (thường là "pending" nếu vừa tạo job mới,
    // hoặc status thật của job đã tồn tại nếu trùng request trước đó).
    // Trả về null nếu gọi mạng thất bại — caller (nơi trigger) không cần
    // coi đây là lỗi nghiêm trọng, chỉ đơn giản là "chưa xin được, thử lại
    // ở lần trigger sau" (vd lần mở bài kế tiếp).
    // items: TOÀN BỘ nội dung cần tổng hợp giọng cho bài này (sentence +
    // phrase + word) — server (main.py, bước 4a) KHÔNG có nguồn nào khác để
    // lấy text, thiếu items = job sẽ mãi mãi "failed" ở bước xử lý (bước 8).
    // Cho phép truyền rỗng ở tầng chữ ký hàm (không chặn cứng ở đây) — nơi
    // gọi (TtsMyReadingSyncTrigger) đã tự lọc rỗng trước khi gọi tới đây.
    suspend fun requestSynthesis(
        readingId: String,
        sid: Int,
        contentHash: String,
        items: List<TtsMyReadingItem>,
    ): TtsMyReadingJobStatus? = withContext(Dispatchers.IO) {
        val itemsArray = org.json.JSONArray()
        items.forEach { item ->
            itemsArray.put(
                JSONObject()
                    .put("type", item.type.apiValue)
                    .put("itemId", item.itemId)
                    .put("textEn", item.textEn)
            )
        }

        val body = JSONObject()
            .put("readingId", readingId)
            .put("sid", sid)
            .put("contentHash", contentHash)
            .put("items", itemsArray)
            .toString()

        try {
            val connection = openConnection("$baseUrl/tts/myreading/request", method = "POST")
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val responseBody = readResponseOrLog(connection, label = "requestSynthesis($readingId, sid=$sid)")
                ?: return@withContext null

            TtsMyReadingJobStatus.fromApiValue(JSONObject(responseBody).optString("status", null))
        } catch (e: Exception) {
            Log.e(TAG, "requestSynthesis: lỗi gọi server readingId=$readingId sid=$sid", e)
            null
        }
    }

    // ── Hỏi trạng thái hiện tại — dùng TRƯỚC khi thử tải Drive, tránh gọi
    // Drive API vô ích khi biết chắc server chưa xử lý xong. Trả về null
    // nếu gọi mạng thất bại — caller tự hiểu là "không biết trạng thái",
    // an toàn nhất là coi như chưa ready và fallback Android TTS.
    suspend fun checkStatus(
        readingId: String,
        sid: Int,
        contentHash: String,
    ): TtsMyReadingJobStatus? = withContext(Dispatchers.IO) {
        val query = "readingId=${urlEncode(readingId)}&sid=$sid&contentHash=${urlEncode(contentHash)}"
        val url = "$baseUrl/tts/myreading/status?$query"

        try {
            val connection = openConnection(url, method = "GET")
            val responseBody = readResponseOrLog(connection, label = "checkStatus($readingId, sid=$sid)")
                ?: return@withContext null

            TtsMyReadingJobStatus.fromApiValue(JSONObject(responseBody).optString("status", null))
        } catch (e: Exception) {
            Log.e(TAG, "checkStatus: lỗi gọi server readingId=$readingId sid=$sid", e)
            null
        }
    }

    private fun openConnection(url: String, method: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.requestMethod = method
        return connection
    }

    // ── Đọc response body nếu HTTP OK, log + trả null nếu không — dùng
    // chung cho cả 2 hàm ở trên để tránh lặp code xử lý lỗi HTTP. ──────────
    private fun readResponseOrLog(connection: HttpURLConnection, label: String): String? {
        connection.connect()
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK &&
                connection.responseCode != HttpURLConnection.HTTP_ACCEPTED
            ) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }
                Log.w(TAG, "$label: HTTP ${connection.responseCode}, body=$errorBody")
                return null
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")
}