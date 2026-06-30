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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eleap.eleap.feature.reading.ReadingListScreen
import com.eleap.eleap.feature.reading.ReadingScreen
import com.eleap.eleap.feature.reading.ReadingViewModel
import com.eleap.eleap.feature.userreading.UserReadingScreen
import kotlinx.coroutines.delay

import com.eleap.eleap.feature.vocab.VocabScreen
import com.eleap.eleap.feature.vocab.VocabStudyScreen
import com.eleap.eleap.feature.vocab.VocabReadingScreen
import com.eleap.eleap.feature.vocab.VocabViewModel
import com.eleap.eleap.feature.vocab.VocabPopup
import com.eleap.eleap.feature.reading.ui.UserVocabularyEntry
import com.eleap.eleap.ui.FloatingVocabButton

// Watchdog quét bài tồn đọng (is_ai_processed = 0) định kỳ — chạy nền cho
// TOÀN BỘ app, không phụ thuộc đang đứng ở màn hình nào. Trước đây việc này
// chỉ được kích hoạt trong LaunchedEffect(Unit) của ReadingListScreen, nên
// nếu user thêm bài rồi rời khỏi màn hình danh sách trước khi AI xử lý xong
// (hoặc lần gọi đầu tiên thất bại vì lỗi mạng/API), bài đó "im" cho tới khi
// user quay lại đúng màn hình ReadingListScreen mới được thử lại.
private const val AI_WATCHDOG_INTERVAL_MS = 15_000L

private enum class Screen {
    MAIN,
    READING_LIST,
    READING,
    ADD_READING,            // ← mới: UserReadingScreen
    VOCAB,
    VOCAB_STUDY,
    VOCAB_READING,
    VOCAB_READING_STUDY,
    READING_VOCAB,
    READING_VOCAB_STUDY,
}

private fun previousScreenOf(screen: Screen): Screen = when (screen) {
    Screen.READING_LIST        -> Screen.MAIN
    Screen.READING             -> Screen.READING_LIST
    Screen.ADD_READING         -> Screen.READING_LIST  // ← quay về danh sách
    Screen.VOCAB               -> Screen.MAIN
    Screen.VOCAB_STUDY         -> Screen.VOCAB
    Screen.VOCAB_READING       -> Screen.READING
    Screen.VOCAB_READING_STUDY -> Screen.VOCAB_READING
    Screen.READING_VOCAB       -> Screen.READING
    Screen.READING_VOCAB_STUDY -> Screen.READING_VOCAB
    Screen.MAIN                -> Screen.MAIN
}

private val FLOAT_BUTTON_SCREENS = setOf(
    Screen.READING,
    Screen.READING_VOCAB,
    Screen.READING_VOCAB_STUDY,
)

@Composable
fun MainScreen() {
    var screen by remember { mutableStateOf(Screen.MAIN) }
    var selectedReadingId by remember { mutableStateOf<Int?>(null) }
    var readingStudyTabName by remember { mutableStateOf("NEW") }

    val context  = LocalContext.current
    val activity = context as ComponentActivity
    val vm: VocabViewModel = viewModel(
        viewModelStoreOwner = activity,
        factory = VocabViewModel.Factory(context)
    )
    val vocabList          by vm.vocabList.collectAsState()
    val readingVocabList   by vm.readingVocabList.collectAsState()
    val readingSelectedByTab by vm.readingSelectedByTab.collectAsState()
    val selectedEntry      by vm.selectedEntry.collectAsState()
    val dictEntry          by vm.selectedDictEntry.collectAsState()
    val isDictExpanded     by vm.isDictExpanded.collectAsState()
    val anchorRect         by vm.anchorRect.collectAsState()

    // ReadingViewModel là singleton (Factory tự cache instance, xem
    // ReadingViewModel.Factory) nên lấy ở đây cũng CHÍNH LÀ instance được
    // ReadingListScreen / ReadingScreen / UserReadingScreen dùng — không tạo
    // thêm state riêng, chỉ mượn để gọi triggerAiProcessing() ở tầng app.
    val readingVm: ReadingViewModel = viewModel(
        viewModelStoreOwner = activity,
        factory = ReadingViewModel.Factory(context)
    )

    // ── Watchdog AI xử lý bài đọc — chạy ở cấp MainScreen (root composable),
    //    tồn tại suốt vòng đời app, KHÔNG phụ thuộc đang ở screen nào. ───────
    //    - Lần đầu chạy ngay khi mở app → bắt các bài tồn đọng từ phiên trước.
    //    - Sau đó lặp lại mỗi AI_WATCHDOG_INTERVAL_MS → nếu lần gọi
    //      processSingleReading ngay sau khi lưu bài bị lỗi (mất mạng, AI trả
    //      JSON sai định dạng, v.v.) thì watchdog này sẽ tự động thử lại sau
    //      tối đa ~15s, dù user đang ở màn hình Reading, Vocab, hay bất cứ
    //      đâu — không cần quay lại ReadingListScreen mới chịu chạy lại.
    //    - triggerAiProcessing() dùng tryLock nội bộ nên gọi dồn dập vẫn an
    //      toàn, không bao giờ chạy chồng 2 lần cùng lúc.
    //    - Bọc trong repeatOnLifecycle(STARTED): khi app bị đưa xuống nền
    //      (home, khoá máy, app khác che lên...) vòng lặp TỰ ĐỘNG bị huỷ —
    //      không gọi OpenAI hay query DB vô ích lúc user không nhìn vào app.
    //      Khi quay lại foreground, repeatOnLifecycle tự khởi động lại vòng
    //      lặp từ đầu (kích hoạt ngay 1 lần, không cần đợi đủ 15s).
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, readingVm) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                readingVm.triggerAiProcessing(context)
                delay(AI_WATCHDOG_INTERVAL_MS)
            }
        }
    }

    // ── Snackbar trạng thái AI ("Đang dịch...", "Đã dịch xong...") — hiển thị
    //    Ở CẤP MAINSCREEN (root), nên dù đang đứng ở màn hình nào (Reading,
    //    Vocab, ADD_READING...) snackbar vẫn nổi lên trên cùng, không còn bị
    //    "câm" chỉ vì không đứng ở ReadingListScreen. ───────────────────────
    //    LƯU Ý: aiStatusEvents là Channel (one-shot, chỉ 1 consumer nhận được
    //    mỗi message) → CHỈ collect ở DUY NHẤT một nơi trong toàn app (ở đây).
    //    Nếu còn nơi khác cũng collect (vd ReadingListScreen) thì 2 collector
    //    sẽ giành nhau message, snackbar hiện thất thường tuỳ nơi nào "nhanh
    //    tay" nhận được — nên đã bỏ phần collect riêng ở ReadingListScreen.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        readingVm.aiStatusEvents.collect { msg ->
            snackbarHostState.showSnackbar(message = msg, duration = SnackbarDuration.Short)
        }
    }

    fun goBack() { screen = previousScreenOf(screen) }

    BackHandler(enabled = screen != Screen.MAIN) { goBack() }

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
        val tabWords = when (readingStudyTabName) {
            "NEW"    -> readingVocabList.filter { it.count < 30 }
            "RECENT" -> readingVocabList.filter { it.count in 30..70 }
            else     -> readingVocabList
        }
        tabWords.filter { it.id in selectedIds }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ScreenContent(
            screen            = screen,
            selectedReadingId = selectedReadingId,
            vocabStudyPool    = vocabList.filter { it.selected == 1 },
            readingStudyPool  = readingStudyPool,
            onNavigateTo      = { screen = it },
            onSelectReading   = { id ->
                selectedReadingId = id
                screen = Screen.READING
            },
            onReadingStudyClick = { tabName, nextScreen ->
                readingStudyTabName = tabName
                screen = nextScreen
            },
            onBack = { goBack() }
        )

        if (screen in FLOAT_BUTTON_SCREENS) {
            FloatingVocabButton(
                isOnVocabScreen = screen != Screen.READING,
                onToggle = {
                    if (screen == Screen.READING) {
                        vm.resetReadingActiveTab()
                        screen = Screen.READING_VOCAB
                    } else {
                        screen = Screen.READING
                    }
                }
            )
        }

        // Snackbar AI status nổi trên TẤT CẢ màn hình con, vì nó là phần tử
        // cuối cùng trong Box gốc của MainScreen (luôn vẽ đè lên trên cùng).
        SnackbarHost(
            hostState = snackbarHostState,
            modifier  = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun ScreenContent(
    screen: Screen,
    selectedReadingId: Int?,
    vocabStudyPool: List<UserVocabularyEntry>,
    readingStudyPool: List<UserVocabularyEntry>,
    onNavigateTo: (Screen) -> Unit,
    onSelectReading: (Int) -> Unit,
    onReadingStudyClick: (tabName: String, nextScreen: Screen) -> Unit,
    onBack: () -> Unit,
) {
    when (screen) {
        Screen.MAIN -> MainContent(
            onReadingClick = { onNavigateTo(Screen.READING_LIST) },
            onVocabClick   = { onNavigateTo(Screen.VOCAB) }
        )

        Screen.READING_LIST -> ReadingListScreen(
            onBack            = onBack,
            onReadingClick    = { readingId -> onSelectReading(readingId) },
            onAddReadingClick = { onNavigateTo(Screen.ADD_READING) }   // ← mới
        )

        Screen.READING -> ReadingScreen(
            readingId = selectedReadingId ?: return,
            onBack    = onBack
        )

        Screen.ADD_READING -> UserReadingScreen(         // ← mới
            onBack  = onBack,
            onSaved = onBack                             // sau khi lưu → quay về danh sách
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
    }
}

@Composable
private fun MainContent(
    onReadingClick: () -> Unit,
    onVocabClick: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Button(onClick = onReadingClick) { Text("Reading") }
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = onVocabClick) { Text("Ôn từ vựng") }
    }
}