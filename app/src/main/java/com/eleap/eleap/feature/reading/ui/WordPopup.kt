package com.eleap.eleap.feature.reading.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.eleap.eleap.feature.reading.data.SentencePhrase
import com.eleap.eleap.feature.reading.data.SentenceWord

@Composable
fun WordPopup(
    word: SentenceWord,
    phrase: SentencePhrase?,   // null nếu từ không thuộc cụm nào
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = word.textEn ?: "",
                    style = MaterialTheme.typography.titleLarge
                )
                word.pos?.let {
                    Text(
                        text = "[$it]",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        fontStyle = FontStyle.Italic
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

                // ── Nghĩa tiếng Việt của từ ──────────────────────────────────
                word.textVi?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // ── Lemma (dạng gốc) ─────────────────────────────────────────
                word.lemma?.takeIf { it != word.textEn }?.let {
                    HorizontalDivider()
                    Text(
                        text = "Dạng gốc: $it",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // ── Giải thích dạng từ ────────────────────────────────────────
                word.wordFormExplanation?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // ── Giải thích từ ─────────────────────────────────────────────
                word.wordExplanation?.let {
                    HorizontalDivider()
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                // ── Section cụm từ (chỉ hiện khi từ thuộc 1 phrase) ──────────
                phrase?.let { p ->
                    HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Thuộc cụm từ",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                        // Tên cụm từ tiếng Anh
                        Text(
                            text = p.textEn ?: "",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        // Nghĩa cụm từ tiếng Việt
                        p.textVi?.let {
                            Text(
                                text = "→ $it",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        // Giải thích cụm từ (nếu có)
                        p.phraseExplanation?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
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