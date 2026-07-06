package com.eleap.eleap
import com.eleap.eleap.core.sync.SyncCursor
import com.eleap.eleap.core.sync.SyncEngine

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.eleap.eleap.core.auth.CurrentUser
import com.eleap.eleap.core.auth.SupabaseClientProvider
import com.eleap.eleap.ui.theme.ELeapTheme
import dagger.hilt.android.AndroidEntryPoint
import io.github.jan.supabase.auth.handleDeeplinks
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    // Scope riêng cho việc xử lý deep link + lắng nghe session — sống theo
    // Activity, không theo Compose lifecycle.
    private val activityScope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Khởi tạo CurrentUser TRƯỚC setContent — để lúc UI vẽ ra,
        // userId đã sẵn sàng đọc được ngay.
        CurrentUser.init(this)
        SyncCursor.init(this)        // ← thêm dòng này
        SyncEngine.init(this)        // ← thêm dòng này

        // Xử lý deep link nếu app được mở LẦN ĐẦU từ link đăng nhập Google
        // (trường hợp app chưa chạy, trình duyệt mở thẳng activity mới).
        handleAuthDeeplink(intent)

        // ── Lắng nghe trạng thái đăng nhập Supabase — khi Authenticated,
        // đồng bộ userId vào CurrentUser để toàn app dùng ngay (chưa xử lý
        // migrate dữ liệu guest → user thật, sẽ làm ở bước sau). ──────────────
        observeSupabaseSession()

        enableEdgeToEdge()
        setContent {
            ELeapTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen()
                }
            }
        }
    }

    // Trường hợp app ĐÃ chạy sẵn (singleTop) — trình duyệt redirect về sẽ
    // gọi onNewIntent() thay vì tạo Activity mới.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAuthDeeplink(intent)
    }

    private fun handleAuthDeeplink(intent: Intent) {
        val data = intent.data ?: return
        if (data.scheme == "eleap" && data.host == "login-callback") {
            activityScope.launch {
                SupabaseClientProvider.client.handleDeeplinks(intent)
            }
        }
    }

    private fun observeSupabaseSession() {
        activityScope.launch {
            SupabaseClientProvider.auth.sessionStatus.collect { status ->
                Log.d("MainActivity", "sessionStatus = $status")
                when (status) {
                    is SessionStatus.Authenticated -> {
                        // Bỏ qua nếu đang trong quá trình đăng xuất (LoginScreen
                        // vừa gọi SupabaseClientProvider.signOut()) — tránh
                        // trường hợp SDK còn phát lại 1 sự kiện Authenticated
                        // "trễ" của session cũ TRƯỚC khi signOut() hoàn tất
                        // hẳn, ghi đè ngược CurrentUser.userId về tài khoản
                        // vừa thoát.
                        if (CurrentUser.isLoggingOut.value) {
                            Log.d("MainActivity", "Bỏ qua Authenticated vì đang logout")
                            return@collect
                        }

                        val supabaseUserId = status.session.user?.id
                        // Chỉ set khi có id hợp lệ và đúng chiều: khác với
                        // userId hiện tại. setUser() bên trong CurrentUser tự
                        // phân biệt "guest → user thật" (kích hoạt migrate
                        // dialog) hay "user thật → user thật khác id" (session
                        // refresh bình thường) — ở đây không cần phân biệt
                        // thêm, chỉ cần đảm bảo không set ngược lại GUEST_ID
                        // qua nhánh này (nhánh Authenticated không bao giờ có
                        // supabaseUserId == GUEST_ID nên không cần chặn riêng).
                        if (supabaseUserId != null && supabaseUserId != CurrentUser.userId.value) {
                            CurrentUser.setUser(supabaseUserId)
                        }
                    }
                    else -> { /* NotAuthenticated / RefreshFailure / Initializing — chưa xử lý */ }
                }
            }
        }
    }
}