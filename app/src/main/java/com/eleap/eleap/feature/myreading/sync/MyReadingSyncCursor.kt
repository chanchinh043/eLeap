// MyReadingSyncCursor.kt
// Đặt tại: feature/myreading/sync/MyReadingSyncCursor.kt
//
// Quản lý 2 mốc thời gian dùng cho sync bài đọc MyReading — bản tương đương
// core/sync/SyncCursor.kt (dùng cho user_vocabulary) nhưng cho bảng
// my_readings. KHÔNG phải cột trong bảng readings, mà là trạng thái cục bộ
// của quá trình đồng bộ trên CHÍNH thiết bị này:
//
//   last_sync_cursor  — mốc updated_at cao nhất đã pull được từ my_readings
//                        trên server (dùng để delta pull chỉ lấy dòng mới
//                        hơn mốc này).
//   last_full_pull_at  — thời điểm lần full pull thành công gần nhất (dùng
//                        để tính khi nào đã đủ 1 tuần, nên chạy full thay vì
//                        delta).
//
// Lưu theo userId — vì "guest" và mỗi tài khoản thật đều có cursor riêng,
// tránh trường hợp đổi tài khoản trên cùng máy làm lẫn lộn mốc pull.
//
// ⚠️ PREFS_NAME cố tình đặt KHÁC với core/sync/SyncCursor.kt
// ("myreading_sync_cursor" thay vì "sync_cursor") — 2 bộ sync (vocab và
// myreading) hoàn toàn độc lập, không được dùng chung 1 file
// SharedPreferences, nếu không cursor của bên này sẽ đè lên bên kia.
//
// Singleton thủ công, KHÔNG dùng Hilt/DI — cùng phong cách với CurrentUser,
// SupabaseClientProvider, core/sync/SyncCursor.
package com.eleap.eleap.feature.myreading.sync

import android.content.Context
import android.content.SharedPreferences

object MyReadingSyncCursor {

    private const val PREFS_NAME = "myreading_sync_cursor"

    private lateinit var prefs: SharedPreferences

    // Gọi 1 lần duy nhất, ở MainActivity.onCreate() trước setContent —
    // giống cách core/sync/SyncCursor.init(context) đang làm.
    fun init(context: Context) {
        prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun cursorKey(userId: String) = "last_sync_cursor_$userId"
    private fun fullPullKey(userId: String) = "last_full_pull_at_$userId"

    // ── last_sync_cursor ──────────────────────────────────────────────────
    // null nghĩa là chưa từng pull lần nào (delta pull đầu tiên nên lấy hết,
    // hoặc MyReadingSyncEngine tự quyết định chạy full pull khi chưa có
    // cursor).
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

    // ── Xoá cursor khi đăng xuất/đổi tài khoản (gọi từ LoginScreen, giống
    // cách core/sync/SyncCursor.clear() đang được gọi cho bộ vocab) ────────
    fun clear(userId: String) {
        prefs.edit()
            .remove(cursorKey(userId))
            .remove(fullPullKey(userId))
            .apply()
    }
}