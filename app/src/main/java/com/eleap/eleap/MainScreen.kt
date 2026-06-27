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
    VOCAB,
    VOCAB_STUDY,
    VOCAB_READING,
    VOCAB_READING_STUDY,
    READING_VOCAB,          // VocabReadingScreen từ luồng reading
    READING_VOCAB_STUDY,    // VocabStudyScreen từ luồng reading
}

private fun previousScreenOf(screen: Screen): Screen = when (screen) {
    Screen.READING_LIST        -> Screen.MAIN
    Screen.READING             -> Screen.READING_LIST
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

@Composable
fun MainScreen() {
    var screen by remember { mutableStateOf(Screen.MAIN) }
    var selectedReadingId by remember { mutableStateOf<Int?>(null) }

    val context  = LocalContext.current
    val activity = context as ComponentActivity
    val vm: VocabViewModel = viewModel(
        viewModelStoreOwner = activity,
        factory = VocabViewModel.Factory(context)
    )
    val vocabList        by vm.vocabList.collectAsState()
    val readingVocabList by vm.readingVocabList.collectAsState()
    val selectedEntry    by vm.selectedEntry.collectAsState()
    val dictEntry        by vm.selectedDictEntry.collectAsState()
    val isDictExpanded   by vm.isDictExpanded.collectAsState()
    val anchorRect       by vm.anchorRect.collectAsState()

    fun goBack() { screen = previousScreenOf(screen) }

    BackHandler(enabled = screen != Screen.MAIN) { goBack() }

    // ── Popup từ vựng — 1 instance duy nhất cho toàn app ──────────────────────
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

    Box(modifier = Modifier.fillMaxSize()) {
        ScreenContent(
            screen            = screen,
            selectedReadingId = selectedReadingId,
            vocabStudyPool    = vocabList.filter { it.selected == 1 },
            readingStudyPool  = readingVocabList.filter { it.selected == 1 },
            onNavigateTo      = { screen = it },
            onSelectReading   = { id ->
                selectedReadingId = id
                screen = Screen.READING
            },
            onBack = { goBack() }
        )

        // ── FloatingVocabButton: hiện ở READING, READING_VOCAB, READING_VOCAB_STUDY ──
        if (screen in FLOAT_BUTTON_SCREENS) {
            FloatingVocabButton(
                isOnVocabScreen = screen != Screen.READING,
                onToggle = {
                    screen = if (screen == Screen.READING) Screen.READING_VOCAB
                    else Screen.READING
                }
            )
        }
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
    onBack: () -> Unit,
) {
    when (screen) {
        Screen.MAIN -> MainContent(
            onReadingClick = { onNavigateTo(Screen.READING_LIST) },
            onVocabClick   = { onNavigateTo(Screen.VOCAB) }
        )

        Screen.READING_LIST -> ReadingListScreen(
            onBack         = onBack,
            onReadingClick = { readingId -> onSelectReading(readingId) }
        )

        Screen.READING -> ReadingScreen(
            readingId = selectedReadingId ?: return,
            onBack    = onBack
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
            onStudyClick = { onNavigateTo(Screen.VOCAB_READING_STUDY) }
        )

        Screen.VOCAB_READING_STUDY -> VocabStudyScreen(
            pool   = readingStudyPool,
            onBack = onBack
        )

        // ── Màn từ vựng gắn với bài đọc (truy cập qua FloatingVocabButton) ──
        Screen.READING_VOCAB -> VocabReadingScreen(
            readingId    = selectedReadingId ?: return,
            onBack       = onBack,
            onStudyClick = { onNavigateTo(Screen.READING_VOCAB_STUDY) }
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