package com.eleap.eleap
import com.eleap.eleap.core.sync.SyncCursor
import com.eleap.eleap.core.sync.SyncEngine
import com.eleap.eleap.core.sync.SyncRealtime
import com.eleap.eleap.core.sync.SyncScheduler
import com.eleap.eleap.core.tts.TtsManager
import com.eleap.eleap.core.tts.TtsVoiceSnapshot
import com.eleap.eleap.core.tts.kokoro.TtsKokoroConfig
import com.eleap.eleap.core.tts.kokoro.myreading.TtsMyReadingPendingStore
import com.eleap.eleap.core.tts.kokoro.myreading.TtsMyReadingPrecacheScheduler
import com.eleap.eleap.feature.myreading.data.MyReadingRepository
import com.eleap.eleap.feature.myreading.sync.MyReadingSyncCursor
import com.eleap.eleap.feature.myreading.sync.MyReadingSyncEngine
import com.eleap.eleap.feature.myreading.sync.MyReadingSyncRealtime
import com.eleap.eleap.feature.myreading.sync.MyReadingSyncScheduler

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
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

    // ── Observer theo dõi lifecycle của TOÀN BỘ APP (không phải riêng
    // Activity này) qua ProcessLifecycleOwner — để bật/tắt Realtime đúng lúc
    // app thật sự hiện/ẩn khỏi người dùng, không bị ảnh hưởng bởi việc xoay
    // màn hình hay chuyển giữa các Activity trong CÙNG app (nếu sau này có
    // thêm Activity khác). ON_START (app vừa hiện lên, kể cả lần mở đầu tiên
    // lẫn quay lại từ background) → subscribe lại Realtime nếu đã đăng nhập.
    // ON_STOP (app vào background, kể cả bị che khuất hoàn toàn) → huỷ kết
    // nối WebSocket, tránh giữ kết nối sống vô ích khi không hiển thị và
    // tránh rò rỉ coroutine/connection.
    private val realtimeLifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> {
                val userId = CurrentUser.userId.value
                if (userId != CurrentUser.GUEST_ID) {
                    SyncRealtime.startListening(userId)
                    MyReadingSyncRealtime.startListening(userId)
                }
            }
            Lifecycle.Event.ON_STOP -> {
                SyncRealtime.stopListening()
                MyReadingSyncRealtime.stopListening()
            }
            else -> { /* ON_CREATE/ON_RESUME/ON_PAUSE/ON_DESTROY — không cần xử lý */ }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Khởi tạo CurrentUser TRƯỚC setContent — để lúc UI vẽ ra,
        // userId đã sẵn sàng đọc được ngay.
        CurrentUser.init(this)
        SyncCursor.init(this)
        SyncEngine.init(this)
        SyncRealtime.init(this)
        MyReadingSyncCursor.init(this)
        MyReadingSyncEngine.init(this)
        MyReadingSyncRealtime.init(this)
        TtsManager.init(this)
        // ⚠️ 2 dòng dưới đây PHẢI có — hậu quả im lặng (không crash) nhưng
        // nghiêm trọng nếu thiếu:
        //   - Thiếu TtsVoiceSnapshot.init() → currentVendor()/currentSid()
        //     luôn rơi về giá trị mặc định vì prefs chưa init, lựa chọn giọng
        //     đã lưu từ phiên trước không bao giờ đọc lại được.
        //   - Thiếu TtsKokoroConfig.registerIfConfigured() →
        //     TtsKokoroPackSourceRegistry.current() luôn null, không đăng ký
        //     transport Google Drive nào cả → TtsKokoroPackDownloader không
        //     bao giờ tải được gì, mọi lượt phát dùng giọng Kokoro fallback
        //     thẳng Android TTS.
        TtsVoiceSnapshot.init(this)
        TtsKokoroConfig.registerIfConfigured(this)
        // ⚠️ Cùng lý do như 2 dòng trên — thiếu dòng này thì
        // TtsMyReadingPrecacheWorker (chạy nền định kỳ, xem
        // TtsMyReadingPrecacheScheduler bên dưới) không đọc được danh sách
        // job đang chờ, luôn coi như rỗng, không bao giờ tự pre-cache audio
        // MyReading trước khi user mở bài (vẫn không sao — chỉ là tối ưu
        // tuỳ chọn, TtsMyReadingDownloadGate lúc mở bài vẫn hoạt động đúng
        // dù thiếu dòng này, xem TtsMyReadingPendingStore.kt).
        TtsMyReadingPendingStore.init(this)

        // Đăng ký 2 lịch chạy nền (push mỗi 3h, pull mỗi 5h) — dùng
        // enqueueUniquePeriodicWork với policy KEEP bên trong nên gọi lại
        // nhiều lần (mỗi lần app khởi động) không tạo lịch chồng chéo.
        // THIẾU dòng này thì SyncPushWorker/SyncPullWorker không bao giờ
        // được WorkManager biết tới — chỉ có enqueueImmediatePush() (gọi từ
        // SaveWordButton) là chạy thật.
        SyncScheduler.schedulePeriodicWork(this)
        MyReadingSyncScheduler.schedulePeriodicWork(this)
        // Tuỳ chọn — KHÔNG bắt buộc, xem TtsMyReadingPrecacheWorker.kt. Nếu
        // bỏ dòng này, tính năng vẫn hoạt động đúng qua
        // TtsMyReadingDownloadGate lúc mở bài, chỉ là audio pre-cache xuất
        // hiện muộn hơn (đúng lúc mở bài lần kế thay vì chủ động tải trước).
        TtsMyReadingPrecacheScheduler.schedulePeriodicWork(this)

        // Đăng ký lifecycle observer TRƯỚC khi xử lý deep link/session — để
        // không bỏ lỡ ON_START đầu tiên của app (ProcessLifecycleOwner phát
        // ON_CREATE/ON_START/ON_RESUME ngay khi app khởi động lần đầu, gần
        // như đồng thời với Activity.onCreate() này).
        ProcessLifecycleOwner.get().lifecycle.addObserver(realtimeLifecycleObserver)

        // Xử lý deep link nếu app được mở LẦN ĐẦU từ link đăng nhập Google
        // (trường hợp app chưa chạy, trình duyệt mở thẳng activity mới).
        handleAuthDeeplink(intent)

        // ── Lắng nghe trạng thái đăng nhập Supabase — khi Authenticated,
        // đồng bộ userId vào CurrentUser để toàn app dùng ngay; khi
        // NotAuthenticated (vd sau khi đăng xuất), dừng Realtime. ─────────────
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

    override fun onDestroy() {
        super.onDestroy()
        // Gỡ observer khi Activity bị huỷ hẳn — tránh giữ tham chiếu tới
        // Activity đã chết trong ProcessLifecycleOwner (vốn sống cùng cả
        // vòng đời process, lâu hơn nhiều so với 1 Activity).
        ProcessLifecycleOwner.get().lifecycle.removeObserver(realtimeLifecycleObserver)
        TtsManager.shutdown()
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

                            // ── Bắt đầu lắng nghe Realtime cho tài khoản vừa
                            // đăng nhập/refresh — gọi SAU syncNow() để đảm bảo
                            // dữ liệu nền tảng (full/delta pull) đã có trước,
                            // Realtime chỉ tiếp nhận thay đổi phát sinh SAU mốc
                            // này. An toàn gọi lại nhiều lần (vd session refresh
                            // token định kỳ) vì startListening() tự bỏ qua nếu
                            // đã đang lắng nghe đúng userId này rồi.
                            SyncRealtime.startListening(supabaseUserId)

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

                            // ── Đồng bộ MyReading (bài đọc tự tạo) ───────────
                            // a. Migrate dữ liệu MyReading của guest (nếu có,
                            // vd user vừa tạo vài bài đọc lúc chưa đăng nhập)
                            // sang đúng userId thật vừa đăng nhập — hàm này đã
                            // có sẵn từ trước nhưng chưa từng được gọi ở đâu.
                            // Gọi TRƯỚC khi sync để các bài mới migrate cũng
                            // được đẩy lên server trong cùng lượt syncNow() bên
                            // dưới, không phải đợi chu kỳ push 3h kế tiếp.
                            val migratedCount =
                                MyReadingRepository.getInstance(this@MainActivity)
                                    .migrateGuestDataTo(supabaseUserId)
                            Log.d(
                                "MainActivity",
                                "Đã migrate $migratedCount bài MyReading sang userId=$supabaseUserId"
                            )

                            // b. Bật lại lịch nền cho bộ MyReading — cùng lý do
                            // như SyncScheduler.schedulePeriodicWork() ở trên
                            // (đề phòng đã bị cancelAll() lúc đăng xuất trước
                            // đó trong cùng session app).
                            MyReadingSyncScheduler.schedulePeriodicWork(this@MainActivity)

                            // c. Sync ngay lập tức, tương tự SyncEngine.syncNow()
                            // của vocab ở trên — không đợi tới chu kỳ pull 5h.
                            val myReadingOutcome = MyReadingSyncEngine.syncNow(supabaseUserId)
                            if (myReadingOutcome.error != null) {
                                Log.e(
                                    "MainActivity",
                                    "Sync MyReading sau đăng nhập lỗi: ${myReadingOutcome.error}"
                                )
                            } else {
                                Log.d(
                                    "MainActivity",
                                    "Sync MyReading sau đăng nhập: gửi ${myReadingOutcome.pushedCount}, " +
                                            "nhận ${myReadingOutcome.pulledCount}, full=${myReadingOutcome.ranFullPull}"
                                )
                            }

                            // d. Bắt đầu lắng nghe Realtime cho bộ MyReading —
                            // gọi SAU syncNow() cùng lý do như SyncRealtime ở
                            // trên (đảm bảo full/delta pull xong trước).
                            MyReadingSyncRealtime.startListening(supabaseUserId)
                        }
                    }
                    is SessionStatus.NotAuthenticated -> {
                        // Xảy ra sau khi LoginScreen gọi signOut() thành công
                        // (hoặc chưa từng đăng nhập). Dừng Realtime nếu đang
                        // lắng nghe — stopListening() tự an toàn khi gọi lúc
                        // không có gì đang chạy (channel null → không làm gì).
                        SyncRealtime.stopListening()
                        MyReadingSyncRealtime.stopListening()
                    }
                    else -> { /* RefreshFailure / Initializing — chưa xử lý */ }
                }
            }
        }
    }
}