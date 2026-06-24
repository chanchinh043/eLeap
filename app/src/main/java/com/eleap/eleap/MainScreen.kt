// MainScreen.kt
package com.eleap.eleap

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eleap.eleap.feature.reading.ReadingListScreen
import com.eleap.eleap.feature.reading.ReadingScreen
import com.eleap.eleap.feature.vocab.VocabScreen

private enum class Screen { MAIN, READING_LIST, READING, VOCAB }

@Composable
fun MainScreen() {
    var screen by remember { mutableStateOf(Screen.MAIN) }
    var selectedReadingId by remember { mutableStateOf<Int?>(null) }

    when (screen) {
        Screen.MAIN -> {
            MainContent(
                onReadingClick = { screen = Screen.READING_LIST },
                onVocabClick = { screen = Screen.VOCAB }
            )
        }

        Screen.READING_LIST -> {
            ReadingListScreen(
                onBack = { screen = Screen.MAIN },
                onReadingClick = { readingId ->
                    selectedReadingId = readingId
                    screen = Screen.READING
                }
            )
        }

        Screen.READING -> {
            ReadingScreen(
                readingId = selectedReadingId ?: return,
                onBack = { screen = Screen.READING_LIST }
            )
        }

        Screen.VOCAB -> {
            VocabScreen(
                onBack = { screen = Screen.MAIN }
            )
        }
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
        Button(onClick = onReadingClick) {
            Text("Reading")
        }
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = onVocabClick) {
            Text("Ôn từ vựng")
        }
    }
}