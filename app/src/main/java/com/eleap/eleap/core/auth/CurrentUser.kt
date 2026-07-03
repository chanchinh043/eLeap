// CurrentUser.kt
// Đặt tại: com/eleap/eleap/core/auth/CurrentUser.kt
package com.eleap.eleap.core.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// ── Singleton thủ công, KHÔNG dùng Hilt/DI ───────────────────────────────────
// userId luôn là String (TEXT) — "guest" khi chưa đăng nhập, hoặc uuid thật
// do Supabase cấp sau khi đăng nhập/đăng ký. Khớp thẳng với cột user_id
// (TEXT) trong users.db — không cần convert Int/String ở bất kỳ đâu nữa.
object CurrentUser {

    private const val PREFS_NAME  = "current_user"
    private const val KEY_USER_ID = "user_id"
    const val GUEST_ID = "guest"

    private lateinit var prefs: SharedPreferences

    private val _userId = MutableStateFlow(GUEST_ID)
    val userId: StateFlow<String> = _userId

    // ── Migrate dữ liệu guest → user thật ────────────────────────────────
    // Khác null nghĩa là vừa có 1 lượt chuyển guest → user thật CHƯA được xử
    // lý (hiện dialog hỏi migrate). MainScreen collect giá trị này, hiện
    // AlertDialog, rồi gọi clearPendingMigration() sau khi người dùng chọn
    // Có/Không — đảm bảo dialog chỉ hiện đúng 1 lần mỗi lượt đăng nhập mới,
    // không lặp lại khi mở lại app với session cũ (lúc đó previousId lúc
    // setUser() đã là id thật từ trước, không phải GUEST_ID).
    private val _pendingMigrationUserId = MutableStateFlow<String?>(null)
    val pendingMigrationUserId: StateFlow<String?> = _pendingMigrationUserId

    fun clearPendingMigration() {
        _pendingMigrationUserId.value = null
    }

    // ── Cờ đang trong quá trình đăng xuất ────────────────────────────────
    // Bật ngay trước khi gọi SupabaseClientProvider.auth.signOut(), tắt lại
    // trong logout() (hoặc bị huỷ qua cancelLogout() nếu signOut lỗi). Dùng
    // để MainActivity.observeSupabaseSession() bỏ qua sự kiện
    // SessionStatus.Authenticated "trễ" lọt về TRONG lúc đang đăng xuất,
    // tránh bị ghi đè ngược userId về lại tài khoản vừa thoát.
    private val _isLoggingOut = MutableStateFlow(false)
    val isLoggingOut: StateFlow<Boolean> = _isLoggingOut

    fun beginLogout() {
        _isLoggingOut.value = true
    }

    fun cancelLogout() {
        _isLoggingOut.value = false
    }

    // Gọi 1 lần duy nhất, ở MainActivity.onCreate() trước setContent
    fun init(context: Context) {
        prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val saved = prefs.getString(KEY_USER_ID, null)
        if (saved == null) {
            prefs.edit().putString(KEY_USER_ID, GUEST_ID).apply()
        }
        _userId.value = saved ?: GUEST_ID

        Log.d("CurrentUser", "init() done → userId = ${_userId.value}")
    }

    // Gọi khi đăng nhập/đăng ký Supabase thành công (MainActivity.observeSupabaseSession()).
    fun setUser(id: String) {
        val previousId = _userId.value

        _userId.value = id
        prefs.edit().putString(KEY_USER_ID, id).apply()
        Log.d("CurrentUser", "setUser() → userId = $id")

        // Chỉ coi là "lần đăng nhập đầu tiên" khi chuyển ĐÚNG chiều
        // guest → user thật. Không hiện dialog khi mở app lại với session
        // cũ (lúc đó previousId đã là id thật từ trước).
        if (previousId == GUEST_ID && id != GUEST_ID) {
            _pendingMigrationUserId.value = id
        }
    }

    // Gọi khi đăng xuất — luôn gọi SAU khi SupabaseClientProvider.auth.signOut()
    // đã thành công (xem LoginScreen.kt).
    fun logout() {
        setUser(GUEST_ID)
        _isLoggingOut.value = false
    }
}