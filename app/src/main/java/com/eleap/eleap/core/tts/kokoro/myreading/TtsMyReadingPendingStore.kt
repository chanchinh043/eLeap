// TtsMyReadingPendingStore.kt
// Đặt tại: com/eleap/eleap/core/tts/kokoro/myreading/TtsMyReadingPendingStore.kt
//
// Lưu XUỐNG ĐĨA (SharedPreferences) danh sách các job "đã xin server tổng
// hợp nhưng CHƯA XÁC NHẬN ready" — để TtsMyReadingPrecacheWorker (chạy nền
// định kỳ) biết cần hỏi lại status cho đúng những bộ (readingId, sid,
// contentHash) nào, KHÔNG cần quét lại toàn bộ bài MyReading trong máy mỗi
// chu kỳ.
//
// ⚠️ VÌ SAO CẦN LƯU CỤC BỘ (không hỏi server "job nào đang treo"): server
// (thoả thuận riêng ở TtsMyReadingRequestClient.kt) không có endpoint kiểu
// "liệt kê mọi job pending của tôi" — chỉ có "hỏi status của 1 bộ
// (readingId, sid, contentHash) cụ thể". App phải tự nhớ mình đã xin những
// gì để biết cần hỏi lại cái gì.
//
// ⚠️ MẤT DỮ LIỆU Ở ĐÂY KHÔNG NGHIÊM TRỌNG: nếu SharedPreferences bị xoá
// (gỡ cài đặt, xoá dữ liệu app), CHỈ MẤT khả năng tự động pre-cache TRƯỚC
// khi mở bài — người dùng vẫn nghe được audio Kokoro bình thường ở lần mở
// bài kế tiếp, vì TtsMyReadingDownloadGate (gọi từ ReadingScreen) tự hỏi
// lại server độc lập với store này. Store này CHỈ phục vụ mục đích "chủ
// động tải trước", không phải nguồn sự thật duy nhất.
//
// Singleton thủ công, KHÔNG dùng Hilt/DI — cùng phong cách với
// TtsVoiceSnapshot.kt.
package com.eleap.eleap.core.tts.kokoro.myreading

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

private const val PREFS_NAME = "tts_myreading_pending"
private const val KEY_ENTRIES = "entries"

// 1 job đang chờ server xử lý — sid gắn kèm vì 1 bài có thể có nhiều job
// (nếu người dùng đổi giọng trước khi job cũ kịp ready), contentHash gắn
// kèm để tự loại job CŨ khi nội dung bài đã đổi (job cũ không còn ý nghĩa
// gì để tiếp tục hỏi status).
data class TtsMyReadingPendingEntry(
    val readingId: String,
    val sid: Int,
    val contentHash: String,
)

object TtsMyReadingPendingStore {

    private lateinit var prefs: SharedPreferences

    // Gọi 1 lần ở MainActivity.onCreate(), cùng chỗ với TtsVoiceSnapshot.init().
    fun init(context: Context) {
        prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ── Thêm 1 job vào danh sách chờ — gọi từ TtsMyReadingSyncTrigger ngay
    // sau khi requestSynthesis() thành công (bất kể status trả về là gì,
    // trừ khi đã READY ngay lập tức — xem ghi chú ở nơi gọi). Tự loại bỏ
    // entry CŨ có cùng (readingId, sid) nhưng contentHash KHÁC trước khi
    // thêm — job cũ chắc chắn đã lỗi thời (nội dung bài đổi), không cần giữ
    // lại hỏi status vô ích nữa.
    fun add(entry: TtsMyReadingPendingEntry) {
        if (!::prefs.isInitialized) return
        val current = getAll().filterNot { it.readingId == entry.readingId && it.sid == entry.sid }
        save(current + entry)
    }

    // ── Xoá 1 job — gọi khi đã xác nhận READY (đã enqueue tải xong) hoặc
    // FAILED (server báo lỗi vĩnh viễn, không còn gì để chờ) hoặc khi
    // contentHash không còn khớp (bài vừa bị sửa nội dung, xem
    // TtsMyReadingPrecacheWorker).
    fun remove(readingId: String, sid: Int) {
        if (!::prefs.isInitialized) return
        save(getAll().filterNot { it.readingId == readingId && it.sid == sid })
    }

    // ── Toàn bộ job đang chờ — dùng bởi TtsMyReadingPrecacheWorker mỗi chu
    // kỳ chạy nền.
    fun getAll(): List<TtsMyReadingPendingEntry> {
        if (!::prefs.isInitialized) return emptyList()
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                TtsMyReadingPendingEntry(
                    readingId   = obj.getString("readingId"),
                    sid         = obj.getInt("sid"),
                    contentHash = obj.getString("contentHash"),
                )
            }
        } catch (e: Exception) {
            // Dữ liệu prefs hỏng (hiếm) — coi như rỗng, không throw. Lần
            // add() kế tiếp sẽ tự ghi lại đúng định dạng.
            emptyList()
        }
    }

    private fun save(entries: List<TtsMyReadingPendingEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("readingId", entry.readingId)
                    .put("sid", entry.sid)
                    .put("contentHash", entry.contentHash)
            )
        }
        prefs.edit().putString(KEY_ENTRIES, array.toString()).apply()
    }
}