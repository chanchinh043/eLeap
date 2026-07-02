// CurrentUser.kt
// Đặt tại: com/eleap/eleap/core/auth/CurrentUser.kt
package com.eleap.eleap.core.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// ── Singleton thủ công, KHÔNG dùng Hilt/DI ───────────────────────────────────
// Giữ user_id hiện tại (guest hoặc uuid Supabase sau này) trong RAM (StateFlow)
// và lưu bền trong SharedPreferences để sống sót qua tắt/mở app.
object CurrentUser {

    private const val PREFS_NAME  = "current_user"
    private const val KEY_USER_ID = "user_id"
    const val GUEST_ID = "guest"

    private lateinit var prefs: SharedPreferences

    private val _userId = MutableStateFlow(GUEST_ID)
    val userId: StateFlow<String> = _userId

    // Gọi 1 lần duy nhất, ở MainActivity.onCreate() trước setContent
    fun init(context: Context) {
        prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val saved = prefs.getString(KEY_USER_ID, null)
        if (saved == null) {
            // Lần đầu mở app — chưa có gì trong prefs → ghi "guest" xuống ngay
            prefs.edit().putString(KEY_USER_ID, GUEST_ID).apply()
        }
        _userId.value = saved ?: GUEST_ID

        // ── Log để kiểm tra trong Logcat ─────────────────────────────────────
        Log.d("CurrentUser", "init() done → userId = ${_userId.value}")
    }

    // Gọi khi đăng nhập/đăng ký Supabase thành công (sau này, ở feature/auth)
    fun setUser(id: String) {
        _userId.value = id
        prefs.edit().putString(KEY_USER_ID, id).apply()
        Log.d("CurrentUser", "setUser() → userId = $id")
    }

    // Gọi khi đăng xuất (sau này)
    fun logout() = setUser(GUEST_ID)
}