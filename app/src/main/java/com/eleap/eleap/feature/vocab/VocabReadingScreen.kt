// VocabReadingScreen.kt
package com.eleap.eleap.feature.vocab

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Màn hình ôn từ vựng gắn với bài đọc.
 * Mở khi người dùng vuốt phải → trái trên ReadingScreen.
 *
 * @param readingId  ID bài đọc hiện tại — dùng để lọc từ đã lưu của bài này.
 * @param onBack     Callback quay lại ReadingScreen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocabReadingScreen(
    readingId: Int,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Từ vựng bài đọc") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        // ── TODO: điền nội dung màn hình ────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "readingId = $readingId\n(nội dung sẽ thiết kế sau)",
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}