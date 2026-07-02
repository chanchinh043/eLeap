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
import com.eleap.eleap.feature.reading.ReadingListScreen
import com.eleap.eleap.feature.reading.ReadingScreen
import com.eleap.eleap.feature.myreading.MyReadingListScreen
import com.eleap.eleap.feature.myreading.AddMyReadingScreen

import com.eleap.eleap.feature.vocab.VocabScreen
import com.eleap.eleap.feature.vocab.VocabStudyScreen
import com.eleap.eleap.feature.vocab.VocabReadingScreen
import com.eleap.eleap.feature.vocab.VocabViewModel
import com.eleap.eleap.feature.vocab.VocabPopup
import com.eleap.eleap.feature.reading.ui.UserVocabularyEntry
import com.eleap.eleap.ui.FloatingVocabButton

private enum class Screen {
    MAIN,
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
}

// ── Các màn được coi là "điểm vào" của luồng Reading từ trang chủ ───────────
// Bấm back ở các màn này sẽ về MAIN (không quay lại lẫn nhau).
private val READING_ENTRY_SCREENS = setOf(Screen.READING_LIST, Screen.MY_READING)

private fun previousScreenOf(screen: Screen): Screen = when (screen) {
    Screen.READING_LIST        -> Screen.MAIN
    Screen.READING             -> Screen.READING_LIST
    Screen.MY_READING          -> Screen.MAIN   // ← back từ MyReading về thẳng trang chủ
    Screen.ADD_MY_READING      -> Screen.MY_READING
    Screen.VOCAB               -> Screen.MAIN
    Screen.VOCAB_STUDY         -> Screen.VOCAB
    Screen.VOCAB_READING       -> Screen.READING
    Screen.VOCAB_READING_STUDY -> Screen.VOCAB_READING
    Screen.READING_VOCAB       -> Screen.READING
    Screen.READING_VOCAB_STUDY -> Screen.READING_VOCAB
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

    // ── Nhớ lần cuối vào luồng Reading là READING_LIST hay MY_READING ───────
    // Đọc từ SharedPreferences khi khởi tạo → giữ nguyên qua lần tắt/mở app.
    // Mặc định READING_LIST. Cập nhật (kèm lưu prefs) mỗi khi vào 1 trong 2
    // màn này, dùng làm đích đến khi bấm "Reading" từ trang chủ.
    var lastReadingEntryScreen by remember {
        mutableStateOf(loadLastReadingEntryScreen(context))
    }
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

    fun navigateTo(target: Screen) {
        if (target in READING_ENTRY_SCREENS) {
            lastReadingEntryScreen = target
            saveLastReadingEntryScreen(context, target)   // ← lưu prefs, sống sót qua tắt/mở app
        }
        screen = target
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
            onNavigateTo            = { navigateTo(it) },
            onSelectReading         = { id ->
                selectedReadingId = id
                screen = Screen.READING
            },
            onReadingStudyClick = { tabName, nextScreen ->
                readingStudyTabName = tabName
                screen = nextScreen
            },
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
    onNavigateTo: (Screen) -> Unit,
    onSelectReading: (String) -> Unit,
    onReadingStudyClick: (tabName: String, nextScreen: Screen) -> Unit,
    onBack: () -> Unit,
) {
    when (screen) {
        Screen.MAIN -> MainContent(
            // ── Bấm "Reading" từ trang chủ → vào màn đã ghé thăm gần nhất
            //    (READING_LIST hoặc MY_READING) ──────────────────────────────
            onReadingClick = { onNavigateTo(lastReadingEntryScreen) },
            onVocabClick   = { onNavigateTo(Screen.VOCAB) }
        )

        Screen.READING_LIST -> ReadingListScreen(
            onBack           = onBack,
            onReadingClick   = { readingId -> onSelectReading(readingId) },
            onMyReadingClick = { onNavigateTo(Screen.MY_READING) }
        )

        Screen.READING -> ReadingScreen(
            readingId = selectedReadingId ?: return,
            onBack    = onBack
        )

        Screen.MY_READING -> MyReadingListScreen(
            onBack            = onBack,
            onAddClick        = { onNavigateTo(Screen.READING_LIST) },
            onAddReadingClick = { onNavigateTo(Screen.ADD_MY_READING) }
        )

        Screen.ADD_MY_READING -> AddMyReadingScreen(
            onBack = onBack
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