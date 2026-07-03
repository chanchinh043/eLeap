// SupabaseClientProvider.kt
// Đặt tại: com/eleap/eleap/core/auth/SupabaseClientProvider.kt
package com.eleap.eleap.core.auth

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google

// ── Singleton thủ công, KHÔNG dùng Hilt/DI — cùng phong cách với CurrentUser ──
object SupabaseClientProvider {

    // Deep link app dùng để nhận redirect sau khi đăng nhập — phải khớp với
    // intent-filter trong AndroidManifest.xml và Redirect URLs trên Supabase Dashboard.
    private const val AUTH_REDIRECT_URL = "eleap://login-callback"

    val client by lazy {
        createSupabaseClient(
            supabaseUrl = "https://mrmpwpbxjyznvbsnqubo.supabase.co",
            supabaseKey = "sb_publishable__20WmcUv1hjv5WNfC4SH3A_wAiHYHPI"
        ) {
            install(Auth) {
                // Dùng để SDK tự nhận diện + parse đúng deep link khi
                // handleDeeplinks(intent) được gọi ở MainActivity.
                scheme = "eleap"
                host   = "login-callback"
            }
        }
    }

    val auth get() = client.auth

    // Mở trình duyệt (Custom Tab) để đăng nhập Google, sau khi xong sẽ
    // redirect ngược về app qua deep link AUTH_REDIRECT_URL. redirectUrl là
    // THAM SỐ RIÊNG của signInWith(), không phải field trong lambda config
    // — nếu thiếu, Supabase sẽ fallback về Site URL (localhost).
    suspend fun signInWithGoogle() {
        auth.signInWith(
            provider = Google,
            redirectUrl = AUTH_REDIRECT_URL
        )
    }
}