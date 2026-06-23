package com.eleap.eleap.feature.reading.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eleap.eleap.feature.reading.data.ReadingSentence

@Composable
fun SentencePopup(
    sentence: ReadingSentence,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Dịch câu",
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Câu gốc tiếng Anh
                Text(
                    text = sentence.textEn ?: "",
                    style = MaterialTheme.typography.bodyLarge
                )

                HorizontalDivider()

                // Bản dịch tiếng Việt
                Text(
                    text = sentence.textVi ?: "",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary
                )

                // Giải thích câu (nếu có)
                sentence.sentenceExplanation?.let {
                    HorizontalDivider()
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Đóng")
            }
        }
    )
}