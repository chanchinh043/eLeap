// MainScreen.kt
package com.eleap.eleap

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eleap.eleap.core.auth.CurrentUser
import com.eleap.eleap.core.sync.SyncEngine
import com.eleap.eleap.core.sync.SyncScheduler
import com.eleap.eleap.feature.auth.LoginScreen
import com.eleap.eleap.feature.myreading.data.MyReadingRepository
import com.eleap.eleap.feature.myreading.sync.MyReadingSyncEngine
import com.eleap.eleap.feature.myreading.sync.MyReadingSyncScheduler
import com.eleap.eleap.feature.reading.ReadingListScreen
import com.eleap.eleap.feature.reading.ReadingScreen
import com.eleap.eleap.feature.reading.ReadingViewModel
import com.eleap.eleap.feature.myreading.MyReadingListScreen
import com.eleap.eleap.feature.myreading.AddMyReadingScreen

import com.eleap.eleap.feature.vocab.VocabScreen
import com.eleap.eleap.feature.vocab.VocabStudyScreen
import com.eleap.eleap.feature.vocab.VocabReadingScreen
import com.eleap.eleap.feature.vocab.VocabViewModel
import com.eleap.eleap.feature.vocab.VocabPopup
import com.eleap.eleap.feature.vocab.data.UserVocabularyEntry
import com.eleap.eleap.core.tts.ui.TtsVoicePickerScreen
import com.eleap.eleap.ui.FloatingVocabButton
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

private enum class Screen {
    MAIN,
    LOGIN,                    // ← mới: màn đăng nhập, mở từ nút ở trang chủ
    READING_LIST,
    READING,
    MY_READING,              // "Bài đọc của tôi" — mở từ menu danh mục ở ReadingListScreen
    ADD_MY_READING,          // "Thêm bài đọc" — mở từ menu ở MyReadingListScreen
    VOCAB,
    VOCAB_STUDY,
    VOCAB_READING,
    VOCAB_READING_STUDY,
    READING_VOCAB,          // VocabReadingScreen từ luồng reading
    READING_VOCAB_STUDY,    // VocabStudyScreen từ luồng reading
    TTS_VOICE_PICKER,       // Màn chọn giọng đọc — mở từ nút "V" ở ReadingScreen
}

// ── Các màn được coi là "điểm vào" của luồng Reading từ trang chủ ───────────
// Bấm back ở các màn này sẽ về MAIN (không quay lại lẫn nhau).
private val READING_ENTRY_SCREENS = setOf(Screen.READING_LIST, Screen.MY_READING)

private fun previousScreenOf(screen: Screen): Screen = when (screen) {
    Screen.LOGIN                -> Screen.MAIN
    Screen.READING_LIST        -> Screen.MAIN
    Screen.READING             -> Screen.READING_LIST   // fallback mặc định — bị override động trong goBack() khi vào từ MY_READING
    Screen.MY_READING          -> Screen.MAIN   // ← back từ MyReading về thẳng trang chủ
    Screen.ADD_MY_READING      -> Screen.MY_READING
    Screen.VOCAB               -> Screen.MAIN
    Screen.VOCAB_STUDY         -> Screen.VOCAB
    Screen.VOCAB_READING       -> Screen.READING
    Screen.VOCAB_READING_STUDY -> Screen.VOCAB_READING
    Screen.READING_VOCAB       -> Screen.READING
    Screen.READING_VOCAB_STUDY -> Screen.READING_VOCAB
    Screen.TTS_VOICE_PICKER    -> Screen.READING   // luôn mở từ ReadingScreen, back về đúng bài đang đọc
    Screen.MAIN                -> Screen.MAIN
}

// ── Các màn hiện FloatingVocabButton (chỉ luồng từ Reading) ──────────────────
private val FLOAT_BUTTON_SCREENS = setOf(
    Screen.READING,
    Screen.READING_VOCAB,
    Screen.READING_VOCAB_STUDY,
)

// ── Persist lastReadingEntryScreen qua SharedPreferences ─────────────────────
// Để khi tắt app mở lại, bấm "Reading" từ trang chủ vẫn vào đúng màn
// (READING_LIST hoặc MY_READING) đã ghé thăm gần nhất ở lần dùng app trước.
private const val PREFS_NAME = "main_screen_prefs"
private const val KEY_LAST_READING_ENTRY_SCREEN = "last_reading_entry_screen"

private fun loadLastReadingEntryScreen(context: android.content.Context): Screen {
    val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
    val saved = prefs.getString(KEY_LAST_READING_ENTRY_SCREEN, Screen.READING_LIST.name)
    val parsed = runCatching { Screen.valueOf(saved ?: Screen.READING_LIST.name) }
        .getOrDefault(Screen.READING_LIST)
    return if (parsed in READING_ENTRY_SCREENS) parsed else Screen.READING_LIST
}

private fun saveLastReadingEntryScreen(context: android.content.Context, screen: Screen) {
    val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
    prefs.edit().putString(KEY_LAST_READING_ENTRY_SCREEN, screen.name).apply()
}

@Composable
fun MainScreen() {
    var screen by remember { mutableStateOf(Screen.MAIN) }
    var selectedReadingId by remember { mutableStateOf<String?>(null) }
    var readingStudyTabName by remember { mutableStateOf("NEW") }

    val context  = LocalContext.current
    val activity = context as ComponentActivity
    val scope    = rememberCoroutineScope()

    // ── Nhớ lần cuối vào luồng Reading là READING_LIST hay MY_READING ───────
    // Đọc từ SharedPreferences khi khởi tạo → giữ nguyên qua lần tắt/mở app.
    // Mặc định READING_LIST. Cập nhật (kèm lưu prefs) mỗi khi vào 1 trong 2
    // màn này, dùng làm đích đến khi bấm "Reading" từ trang chủ.
    var lastReadingEntryScreen by remember {
        mutableStateOf(loadLastReadingEntryScreen(context))
    }

    // ── Nhớ màn đã mở READING từ đâu (READING_LIST hay MY_READING) ──────────
    // Dùng riêng để goBack() từ READING quay đúng về nguồn, không lệ thuộc
    // lastReadingEntryScreen (cái đó chỉ dùng cho nút "Reading" ở trang chủ).
    var readingEntryPoint by remember { mutableStateOf(Screen.READING_LIST) }

    val vm: VocabViewModel = viewModel(
        viewModelStoreOwner = activity,
        factory = VocabViewModel.Factory(context)
    )
    val readingVm: ReadingViewModel = viewModel(
        viewModelStoreOwner = activity,
        factory = ReadingViewModel.Factory(context)
    )
    val vocabList          by vm.vocabList.collectAsState()
    val readingVocabList   by vm.readingVocabList.collectAsState()
    val readingSelectedByTab by vm.readingSelectedByTab.collectAsState()
    val selectedEntry      by vm.selectedEntry.collectAsState()
    val dictEntry          by vm.selectedDictEntry.collectAsState()
    val isDictExpanded     by vm.isDictExpanded.collectAsState()
    val anchorRect         by vm.anchorRect.collectAsState()

    fun navigateTo(target: Screen) {
        if (target in READING_ENTRY_SCREENS) {
            lastReadingEntryScreen = target
            saveLastReadingEntryScreen(context, target)   // ← lưu prefs, sống sót qua tắt/mở app
        }
        screen = target
    }

    fun goBack() {
        screen = if (screen == Screen.READING) {
            readingEntryPoint   // ← quay đúng về màn đã mở bài đọc (READING_LIST hoặc MY_READING)
        } else {
            previousScreenOf(screen)
        }
    }

    BackHandler(enabled = screen != Screen.MAIN) { goBack() }

    // ── Dialog hỏi migrate dữ liệu guest → user thật ─────────────────────────
    // pendingMigrationUserId khác null đúng 1 lần duy nhất mỗi lượt đăng nhập
    // MỚI (chuyển từ GUEST_ID sang uuid thật) — do CurrentUser.setUser() phát
    // hiện. Không hiện lại khi mở app với session cũ (lúc đó chiều chuyển đổi
    // không phải guest → user thật nữa).
    val pendingMigrationUserId by CurrentUser.pendingMigrationUserId.collectAsState()
    var isMigrating by remember { mutableStateOf(false) }

    // ── Trạng thái nút "Đồng bộ" — chỉ có ý nghĩa khi đã đăng nhập thật ─────
    val currentUserId by CurrentUser.userId.collectAsState()
    var isSyncing by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }
    val isLoggedIn = currentUserId != CurrentUser.GUEST_ID

    fun onSyncClick() {
        if (isSyncing) return
        isSyncing = true
        syncMessage = null
        scope.launch {
            // Chạy song song cả 2 bộ đồng bộ — vocab (user_vocabulary) và
            // MyReading (bài đọc tự tạo). Trước đây chỗ này chỉ gọi
            // SyncEngine.syncNow() nên bấm "Đồng bộ" chỉ đẩy/kéo vocab,
            // MyReading bị bỏ sót hoàn toàn.
            val vocabDeferred = async { SyncEngine.syncNow(currentUserId) }
            val myReadingDeferred = async { MyReadingSyncEngine.syncNow(currentUserId) }
            val result = vocabDeferred.await()
            val myReadingResult = myReadingDeferred.await()

            val errors = listOfNotNull(result.error, myReadingResult.error)
            syncMessage = if (errors.isNotEmpty()) {
                "Lỗi đồng bộ: ${errors.joinToString("; ")}"
            } else {
                val totalPushed = result.pushedCount + myReadingResult.pushedCount
                val totalPulled = result.pulledCount + myReadingResult.pulledCount
                val ranFull = result.ranFullPull || myReadingResult.ranFullPull
                "Đã đồng bộ: gửi $totalPushed, nhận $totalPulled" + if (ranFull) " (full pull)" else ""
            }
            isSyncing = false
        }
    }

    pendingMigrationUserId?.let { newUserId ->
        AlertDialog(
            onDismissRequest = {
                // Coi dismiss (bấm ra ngoài / back) như chọn "Không" — không
                // để dialog treo lơ lửng và cũng không hiện lại lần sau.
                if (!isMigrating) CurrentUser.clearPendingMigration()
            },
            title = { Text("Giữ lại dữ liệu cũ?") },
            text = {
                Text("Bạn có muốn giữ lại dữ liệu (bài đọc + từ vựng) đã tạo trước khi đăng nhập không?")
            },
            confirmButton = {
                TextButton(
                    enabled = !isMigrating,
                    onClick = {
                        isMigrating = true
                        scope.launch {
                            val myReadingRepo = MyReadingRepository.getInstance(context)
                            myReadingRepo.migrateGuestDataTo(newUserId)
                            vm.migrateGuestVocabDataTo(newUserId)   // suspend thật, await xong mới chạy tiếp
                            readingVm.refreshReadings()
                            // savedWordIds (dùng để tô màu highlight ở
                            // WordClickableRow) KHÔNG tự đổi theo — nó chỉ dựa
                            // vào source_word_id trong bảng user_vocabulary,
                            // và vừa đổi user_id qua migrateGuestDataTo() ở
                            // trên nên cần refresh lại để lấy đúng set mới.
                            readingVm.refreshSavedWordIds()

                            // Dữ liệu vừa migrate đang PENDING_CREATE dưới user
                            // thật — user vừa chủ động chọn giữ lại, nên đẩy lên
                            // NGAY thay vì đợi chu kỳ push 3 tiếng tiếp theo.
                            // Chỉ enqueue lịch (giống SaveWordButton), không tự
                            // gọi thẳng logic sync ở đây — MainScreen là UI,
                            // không phải nơi quyết định cách push chạy thế nào.
                            SyncScheduler.enqueueImmediatePush(context)
                            // Bài MyReading vừa migrate cũng đang PENDING_CREATE
                            // dưới user thật — đẩy lên NGAY, cùng lý do như
                            // SyncScheduler ở trên (trước đây thiếu dòng này nên
                            // MyReading migrate xong vẫn phải đợi tới chu kỳ 3h).
                            MyReadingSyncScheduler.enqueueImmediatePush(context)

                            isMigrating = false
                            CurrentUser.clearPendingMigration()
                        }
                    }
                ) {
                    if (isMigrating) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Có")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isMigrating,
                    onClick = { CurrentUser.clearPendingMigration() }
                ) {
                    Text("Không")
                }
            }
        )
    }

    selectedEntry?.let { entry ->
        VocabPopup(
            entry                = entry,
            dictEntry            = dictEntry,
            isDictExpanded       = isDictExpanded,
            anchorRect           = anchorRect,
            onToggleDictExpanded = { vm.toggleDictExpanded() },
            onDismiss            = { vm.dismissPopup() },
        )
    }

    val readingStudyPool = remember(readingVocabList, readingSelectedByTab, readingStudyTabName) {
        val selectedIds = readingSelectedByTab[readingStudyTabName] ?: emptySet()
        // Lọc từ thuộc đúng tab — dùng cùng ngưỡng với VocabReadingScreen.readingTab()
        // tránh ID cũ còn trong prefs nhưng từ đã chuyển sang tab khác do count thay đổi
        val tabWords = when (readingStudyTabName) {
            "NEW"    -> readingVocabList.filter { it.count < 30 }
            "RECENT" -> readingVocabList.filter { it.count in 30..70 }
            else     -> readingVocabList  // "ALL"
        }
        tabWords.filter { it.id in selectedIds }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ScreenContent(
            screen                  = screen,
            selectedReadingId       = selectedReadingId,
            vocabStudyPool          = vocabList.filter { it.selected == 1 },
            readingStudyPool        = readingStudyPool,
            lastReadingEntryScreen  = lastReadingEntryScreen,
            isLoggedIn              = isLoggedIn,
            isSyncing               = isSyncing,
            syncMessage             = syncMessage,
            onSyncClick             = { onSyncClick() },
            onNavigateTo            = { navigateTo(it) },
            onSelectReading         = { id ->
                readingEntryPoint = screen   // ghi nhớ đang đứng ở READING_LIST hay MY_READING
                selectedReadingId = id
                screen = Screen.READING
            },
            onReadingStudyClick = { tabName, nextScreen ->
                readingStudyTabName = tabName
                screen = nextScreen
            },
            onOpenVoicePicker = { screen = Screen.TTS_VOICE_PICKER },
            onBack = { goBack() }
        )

        // ── FloatingVocabButton: hiện ở READING, READING_VOCAB, READING_VOCAB_STUDY ──
        if (screen in FLOAT_BUTTON_SCREENS) {
            FloatingVocabButton(
                isOnVocabScreen = screen != Screen.READING,
                onToggle = {
                    if (screen == Screen.READING) {
                        vm.resetReadingActiveTab()  // mở VocabReading → luôn về tab "Mới nhất"
                        screen = Screen.READING_VOCAB
                    } else {
                        screen = Screen.READING
                    }
                }
            )
        }
    }
}

@Composable
private fun ScreenContent(
    screen: Screen,
    selectedReadingId: String?,
    vocabStudyPool: List<UserVocabularyEntry>,
    readingStudyPool: List<UserVocabularyEntry>,
    lastReadingEntryScreen: Screen,
    isLoggedIn: Boolean,
    isSyncing: Boolean,
    syncMessage: String?,
    onSyncClick: () -> Unit,
    onNavigateTo: (Screen) -> Unit,
    onSelectReading: (String) -> Unit,
    onReadingStudyClick: (tabName: String, nextScreen: Screen) -> Unit,
    onOpenVoicePicker: () -> Unit,
    onBack: () -> Unit,
) {
    when (screen) {
        Screen.MAIN -> MainContent(
            // ── Bấm "Reading" từ trang chủ → vào màn đã ghé thăm gần nhất
            //    (READING_LIST hoặc MY_READING) ──────────────────────────────
            onReadingClick = { onNavigateTo(lastReadingEntryScreen) },
            onVocabClick   = { onNavigateTo(Screen.VOCAB) },
            onLoginClick   = { onNavigateTo(Screen.LOGIN) },
            isLoggedIn     = isLoggedIn,
            isSyncing      = isSyncing,
            syncMessage    = syncMessage,
            onSyncClick    = onSyncClick,
        )
        Screen.LOGIN -> LoginScreen(
            onBack = onBack
        )

        Screen.READING_LIST -> ReadingListScreen(
            onBack           = onBack,
            onReadingClick   = { readingId -> onSelectReading(readingId) },
            onMyReadingClick = { onNavigateTo(Screen.MY_READING) }
        )

        Screen.READING -> ReadingScreen(
            readingId          = selectedReadingId ?: return,
            onBack             = onBack,
            onOpenVoicePicker  = onOpenVoicePicker
        )

        Screen.MY_READING -> MyReadingListScreen(
            onBack            = onBack,
            onAddClick        = { onNavigateTo(Screen.READING_LIST) },
            onAddReadingClick = { onNavigateTo(Screen.ADD_MY_READING) },
            onReadingClick    = { readingId -> onSelectReading(readingId) }
        )

        Screen.ADD_MY_READING -> AddMyReadingScreen(
            onBack  = onBack,
            onSaved = { onNavigateTo(Screen.MY_READING) }
        )

        Screen.VOCAB -> VocabScreen(
            onBack       = onBack,
            onStudyClick = { onNavigateTo(Screen.VOCAB_STUDY) }
        )

        Screen.VOCAB_STUDY -> VocabStudyScreen(
            pool   = vocabStudyPool,
            onBack = onBack
        )

        Screen.VOCAB_READING -> VocabReadingScreen(
            readingId    = selectedReadingId ?: return,
            onBack       = onBack,
            onStudyClick = { tabName ->
                onReadingStudyClick(tabName, Screen.VOCAB_READING_STUDY)
            }
        )

        Screen.VOCAB_READING_STUDY -> VocabStudyScreen(
            pool   = readingStudyPool,
            onBack = onBack
        )

        // ── Màn từ vựng gắn với bài đọc (truy cập qua FloatingVocabButton) ──
        Screen.READING_VOCAB -> VocabReadingScreen(
            readingId    = selectedReadingId ?: return,
            onBack       = onBack,
            onStudyClick = { tabName ->
                onReadingStudyClick(tabName, Screen.READING_VOCAB_STUDY)
            }
        )

        Screen.READING_VOCAB_STUDY -> VocabStudyScreen(
            pool   = readingStudyPool,
            onBack = onBack
        )

        // ── Màn chọn giọng đọc — luôn mở từ trong 1 bài đọc cụ thể (nút "V"
        // ở ReadingScreen), nên truyền readingId hiện tại: đổi giọng xong sẽ
        // enqueue tải NGAY gói mới cho đúng bài đang đọc (xem
        // TtsVoicePickerScreen.kt) ────────────────────────────────────────
        Screen.TTS_VOICE_PICKER -> TtsVoicePickerScreen(
            onBack    = onBack,
            readingId = selectedReadingId
        )
    }
}

@Composable
private fun MainContent(
    onReadingClick: () -> Unit,
    onVocabClick: () -> Unit,
    onLoginClick: () -> Unit,
    isLoggedIn: Boolean,
    isSyncing: Boolean,
    syncMessage: String?,
    onSyncClick: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Button(onClick = onReadingClick) { Text("Reading") }
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = onVocabClick) { Text("Ôn từ vựng") }
        Spacer(modifier = Modifier.height(12.dp))
        // ── Nút test đăng nhập — tạm thời đặt ở đây để test song song với
        //    việc cấu hình Google Cloud Console / Supabase Dashboard ──────────
        OutlinedButton(onClick = onLoginClick) { Text("Đăng nhập") }

        // ── Nút "Đồng bộ" — chỉ hiện khi đã đăng nhập thật, guest bấm vô
        //    nghĩa vì không có tài khoản trên server để đồng bộ vào ─────────
        if (isLoggedIn) {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onSyncClick,
                enabled = !isSyncing,
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Đồng bộ")
                }
            }
            syncMessage?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}