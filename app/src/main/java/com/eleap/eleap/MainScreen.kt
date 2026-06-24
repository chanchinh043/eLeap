// MainScreen.kt
package com.eleap.eleap

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eleap.eleap.feature.reading.ReadingListScreen
import com.eleap.eleap.feature.reading.ReadingScreen
import com.eleap.eleap.feature.vocab.VocabScreen
import com.eleap.eleap.feature.vocab.VocabStudyScreen

private enum class Screen { MAIN, READING_LIST, READING, VOCAB, VOCAB_STUDY }

@Composable
fun MainScreen() {
    var screen by remember { mutableStateOf(Screen.MAIN) }
    var selectedReadingId by remember { mutableStateOf<Int?>(null) }

    // Quy tắc "quay lại 1 màn" dùng chung cho cả nút Back trong UI và nút Back hệ thống.
    fun goBack() {
        screen = when (screen) {
            Screen.READING_LIST -> Screen.MAIN
            Screen.READING -> Screen.READING_LIST
            Screen.VOCAB -> Screen.MAIN
            Screen.VOCAB_STUDY -> Screen.VOCAB
            Screen.MAIN -> Screen.MAIN
        }
    }

    // Bắt nút/gesture Back của hệ thống. Chỉ bật khi không ở màn MAIN,
    // để khi ở MAIN thì Back hệ thống vẫn thoát app như bình thường.
    BackHandler(enabled = screen != Screen.MAIN) { goBack() }

    when (screen) {
        Screen.MAIN -> MainContent(
            onReadingClick = { screen = Screen.READING_LIST },
            onVocabClick   = { screen = Screen.VOCAB }
        )

        Screen.READING_LIST -> ReadingListScreen(
            onBack         = { goBack() },
            onReadingClick = { readingId ->
                selectedReadingId = readingId
                screen = Screen.READING
            }
        )

        Screen.READING -> ReadingScreen(
            readingId = selectedReadingId ?: return,
            onBack    = { goBack() }
        )

        Screen.VOCAB -> VocabScreen(
            onBack       = { goBack() },
            onStudyClick = { screen = Screen.VOCAB_STUDY }
        )

        Screen.VOCAB_STUDY -> VocabStudyScreen(
            onBack = { goBack() }
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