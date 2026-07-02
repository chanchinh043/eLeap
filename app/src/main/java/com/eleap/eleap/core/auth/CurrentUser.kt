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

    // Gọi khi đăng nhập/đăng ký Supabase thành công (sau này, ở feature/auth)
    fun setUser(id: String) {
        _userId.value = id
        prefs.edit().putString(KEY_USER_ID, id).apply()
        Log.d("CurrentUser", "setUser() → userId = $id")
    }

    // Gọi khi đăng xuất (sau này)
    fun logout() = setUser(GUEST_ID)
}