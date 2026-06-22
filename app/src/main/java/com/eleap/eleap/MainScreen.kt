// MainScreen.kt
package com.eleap.eleap

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.eleap.eleap.feature.reading.ReadingListScreen
import com.eleap.eleap.feature.reading.ReadingScreen

private enum class Screen { MAIN, READING_LIST, READING }

@Composable
fun MainScreen() {
    var screen by remember { mutableStateOf(Screen.MAIN) }
    var selectedReadingId by remember { mutableStateOf<Int?>(null) }

    when (screen) {
        Screen.MAIN -> {
            MainContent(
                onReadingClick = { screen = Screen.READING_LIST }
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
    }
}

@Composable
private fun MainContent(onReadingClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Button(onClick = onReadingClick) {
            Text("Reading")
        }
    }
}