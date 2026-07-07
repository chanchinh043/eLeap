// SupabaseClientProvider.kt
// Đặt tại: com/eleap/eleap/core/auth/SupabaseClientProvider.kt
package com.eleap.eleap.core.auth

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime

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
            // Cần cho SyncApi (core/sync) — mọi truy vấn bảng user_vocabulary
            // (select/insert/update) đều đi qua plugin này.
            install(Postgrest)
            // Cần cho SyncRealtime (core/sync) — lắng nghe INSERT/UPDATE/DELETE
            // trên bảng user_vocabulary qua WebSocket, để các thiết bị khác
            // đang đăng nhập cùng tài khoản nhận được thay đổi gần như ngay lập
            // tức, không cần đợi chu kỳ pull định kỳ (SyncPullWorker, 5h).
            install(Realtime)
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
        ) {
            // Luôn hiện màn hình chọn tài khoản Google, kể cả khi trình duyệt
            // vẫn còn phiên đăng nhập từ lần trước — tránh tự đăng nhập lại
            // đúng tài khoản cũ sau khi user đã logout.
            queryParams["prompt"] = "select_account"
        }
    }

    // Đăng xuất khỏi Supabase — huỷ session hiện tại (xoá access/refresh token
    // khỏi local storage của SDK). Sau khi hàm này trả về THÀNH CÔNG, nơi gọi
    // (LoginScreen) mới nên gọi CurrentUser.logout() để đưa userId về GUEST_ID
    // — đảm bảo thứ tự: Supabase signOut xong rồi mới đổi CurrentUser, tránh
    // trường hợp UI/DB đọc theo userId=guest trong khi Supabase vẫn còn coi
    // là đã đăng nhập.
    suspend fun signOut() {
        auth.signOut()
    }
}