// SyncCursor.kt
// Đặt tại: com/eleap/eleap/core/sync/SyncCursor.kt
//
// Quản lý 2 mốc thời gian dùng cho sync — KHÔNG phải cột trong bảng
// user_vocabulary, mà là trạng thái cục bộ của quá trình đồng bộ trên CHÍNH
// thiết bị này:
//
//   last_sync_cursor  — mốc updated_at cao nhất đã pull được từ server
//                        (dùng để delta pull chỉ lấy dòng mới hơn mốc này).
//   last_full_pull_at  — thời điểm lần full pull thành công gần nhất (dùng
//                        để tính khi nào đã đủ 1 tuần, nên chạy full thay vì
//                        delta).
//
// Lưu theo userId — vì "guest" và mỗi tài khoản thật đều có cursor riêng,
// tránh trường hợp đổi tài khoản trên cùng máy làm lẫn lộn mốc pull.
//
// Singleton thủ công, KHÔNG dùng Hilt/DI — cùng phong cách với CurrentUser,
// SupabaseClientProvider.
package com.eleap.eleap.core.sync

import android.content.Context
import android.content.SharedPreferences

object SyncCursor {

    private const val PREFS_NAME = "sync_cursor"

    private lateinit var prefs: SharedPreferences

    // Gọi 1 lần duy nhất, ở MainActivity.onCreate() trước setContent —
    // giống cách CurrentUser.init(context) đang làm.
    fun init(context: Context) {
        prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun cursorKey(userId: String) = "last_sync_cursor_$userId"
    private fun fullPullKey(userId: String) = "last_full_pull_at_$userId"

    // ── last_sync_cursor ──────────────────────────────────────────────────
    // null nghĩa là chưa từng pull lần nào (delta pull đầu tiên nên lấy hết,
    // hoặc SyncEngine tự quyết định chạy full pull khi chưa có cursor).
    fun getLastSyncCursor(userId: String): String? =
        prefs.getString(cursorKey(userId), null)

    fun setLastSyncCursor(userId: String, updatedAt: String) {
        prefs.edit().putString(cursorKey(userId), updatedAt).apply()
    }

    // ── last_full_pull_at ─────────────────────────────────────────────────
    fun getLastFullPullAt(userId: String): String? =
        prefs.getString(fullPullKey(userId), null)

    fun setLastFullPullAt(userId: String, timestampIso: String) {
        prefs.edit().putString(fullPullKey(userId), timestampIso).apply()
    }

    // ── Có cần full pull không? ───────────────────────────────────────────
    // true nếu: chưa từng full pull, HOẶC đã ≥ 7 ngày kể từ lần full pull
    // gần nhất. So sánh bằng epoch millis để không phụ thuộc định dạng string.
    fun shouldRunFullPull(userId: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val lastFullPullAt = getLastFullPullAt(userId) ?: return true
        val lastMillis = parseIsoToMillis(lastFullPullAt) ?: return true
        val sevenDaysMs = 7L * 24 * 60 * 60 * 1000
        return (nowMillis - lastMillis) >= sevenDaysMs
    }

    private fun parseIsoToMillis(iso: String): Long? = try {
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.getDefault())
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .parse(iso)?.time
    } catch (e: Exception) {
        null
    }

    // ── Xoá cursor khi đăng xuất/đổi tài khoản (tuỳ chọn, gọi nếu cần) ─────
    // Không bắt buộc dùng ngay — vì cursor lưu theo userId nên tự nhiên đã
    // tách biệt giữa các tài khoản. Hàm này chỉ để dọn dữ liệu cũ nếu muốn.
    fun clear(userId: String) {
        prefs.edit()
            .remove(cursorKey(userId))
            .remove(fullPullKey(userId))
            .apply()
    }
}