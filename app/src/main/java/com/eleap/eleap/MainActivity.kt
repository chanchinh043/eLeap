package com.eleap.eleap
import com.eleap.eleap.core.sync.SyncCursor
import com.eleap.eleap.core.sync.SyncEngine
import com.eleap.eleap.core.sync.SyncScheduler

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
import com.eleap.eleap.feature.reading.ReadingViewModel
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
        SyncCursor.init(this)
        SyncEngine.init(this)

        // Đăng ký 2 lịch chạy nền (push mỗi 3h, pull mỗi 5h) — dùng
        // enqueueUniquePeriodicWork với policy KEEP bên trong nên gọi lại
        // nhiều lần (mỗi lần app khởi động) không tạo lịch chồng chéo.
        // THIẾU dòng này thì SyncPushWorker/SyncPullWorker không bao giờ
        // được WorkManager biết tới — chỉ có enqueueImmediatePush() (gọi từ
        // SaveWordButton) là chạy thật.
        SyncScheduler.schedulePeriodicWork(this)

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

                            // ── Bật lại lịch nền (push 3h, pull 5h) ──────────
                            // Nếu trước đó user đã từng đăng xuất trong CÙNG
                            // session app (không restart app), LoginScreen đã
                            // gọi SyncScheduler.cancelAll() lúc logout — 2
                            // worker định kỳ bị huỷ hẳn khỏi WorkManager, chứ
                            // không phải tạm dừng. Nếu không gọi lại
                            // schedulePeriodicWork() ở đây, user đăng nhập lại
                            // sẽ không có bất kỳ lịch sync nền nào cho tới khi
                            // họ tự tắt/mở lại app (lúc đó onCreate() mới gọi
                            // lại). Gọi lại ở đây an toàn để lặp nhiều lần vì
                            // bên trong dùng ExistingPeriodicWorkPolicy.KEEP.
                            SyncScheduler.schedulePeriodicWork(this@MainActivity)

                            // Đăng nhập xong → sync ngay lập tức (đặc biệt quan
                            // trọng cho lần đầu đăng nhập / đổi máy: nếu không
                            // gọi ở đây, user phải đợi tới chu kỳ pull 5 tiếng
                            // mới thấy dữ liệu cũ từ server). syncNow() tự biết
                            // chạy full pull nếu đây là lần đầu (SyncCursor
                            // chưa có last_full_pull_at cho userId này).
                            val outcome = SyncEngine.syncNow(supabaseUserId)
                            if (outcome.error != null) {
                                Log.e("MainActivity", "Sync sau đăng nhập lỗi: ${outcome.error}")
                            } else {
                                Log.d(
                                    "MainActivity",
                                    "Sync sau đăng nhập: gửi ${outcome.pushedCount}, " +
                                            "nhận ${outcome.pulledCount}, full=${outcome.ranFullPull}"
                                )
                            }

                            // savedWordIds (dùng để tô màu highlight ở
                            // WordClickableRow) sống trong ReadingViewModel,
                            // KHÔNG tự refresh theo dữ liệu vừa pull về —
                            // ReadingViewModel chỉ refresh lúc CurrentUser.userId
                            // ĐỔI GIÁ TRỊ (đã xảy ra ở dòng setUser() phía trên,
                            // tức là TRƯỚC khi syncNow() pull xong). Gọi lại ở
                            // đây để set highlight khớp đúng dữ liệu vừa pull.
                            ReadingViewModel.Factory(this@MainActivity)
                                .create(ReadingViewModel::class.java)
                                .refreshSavedWordIds()
                        }
                    }
                    else -> { /* NotAuthenticated / RefreshFailure / Initializing — chưa xử lý */ }
                }
            }
        }
    }
}