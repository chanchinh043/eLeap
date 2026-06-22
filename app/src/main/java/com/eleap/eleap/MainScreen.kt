package com.eleap.eleap

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eleap.eleap.feature.reading.ReadingListScreen

private enum class Screen { MAIN, READING_LIST }

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
                    // TODO: screen = Screen.READING (flow 3)
                }
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