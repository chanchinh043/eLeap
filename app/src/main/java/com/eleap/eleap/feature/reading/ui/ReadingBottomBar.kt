package com.eleap.eleap.feature.reading.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eleap.eleap.feature.reading.ReadingMode

@Composable
fun ReadingBottomBar(
    mode: ReadingMode,
    onWordClick: () -> Unit,
    onSentenceClick: () -> Unit,
) {
    BottomAppBar {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Nút Dịch Từ — toggle: bấm lần 1 bật, bấm lần 2 tắt
            FilterChip(
                selected = mode == ReadingMode.WORD,
                onClick = onWordClick,
                label = { Text("Dịch Từ") }
            )

            // Nút Dịch Câu — toggle: bấm lần 1 bật, bấm lần 2 tắt
            FilterChip(
                selected = mode == ReadingMode.SENTENCE,
                onClick = onSentenceClick,
                label = { Text("Dịch Câu") }
            )
        }
    }
}