// VocabPopup.kt
// Đặt tại: com/eleap/eleap/feature/vocab/VocabPopup.kt
package com.eleap.eleap.feature.vocab

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.eleap.eleap.feature.reading.ui.UserVocabularyEntry
import com.eleap.eleap.feature.vocab.data.VocabDictEntry

/**
 * Popup hiển thị nghĩa từ trong VocabScreen.
 * Hoàn toàn độc lập với feature reading.
 *
 * Hiển thị:
 *  - textEn + textVi (từ danh sách đã lưu)
 *  - Nghĩa từ điển dict.db (shortMeaning + meaning đầy đủ khi mở rộng)
 *  - Điểm / số lần ôn (count, score)
 *  - Nút xoá từ
 */
@Composable
fun VocabPopup(
    entry: UserVocabularyEntry,
    dictEntry: VocabDictEntry?,
    isDictExpanded: Boolean,
    onToggleDictExpanded: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {

                // ── Header: từ tiếng Anh + nút Đóng ─────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = entry.textEn ?: "",
                        style = MaterialTheme.typography.titleLarge
                    )
                    TextButton(
                        onClick = onDismiss,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("Đóng")
                    }
                }

                // ── Nghĩa tiếng Việt (từ danh sách đã lưu) ──────────────────
                entry.textVi?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // ── Thống kê ôn tập ──────────────────────────────────────────
                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = {}, label = { Text("Đã ôn: ${entry.count}") })
                    AssistChip(onClick = {}, label = { Text("Điểm: ${entry.score}") })
                }

                // ── Từ điển dict.db ───────────────────────────────────────────
                if (dictEntry != null) {
                    HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Từ điển",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                        dictEntry.shortMeaning?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        if (isDictExpanded) {
                            dictEntry.meaning?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (!dictEntry.meaning.isNullOrBlank()) {
                            TextButton(
                                onClick = onToggleDictExpanded,
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(if (isDictExpanded) "Thu gọn" else "Xem thêm")
                            }
                        }
                    }
                }

                // ── Nút xoá từ ───────────────────────────────────────────────
                HorizontalDivider()
                TextButton(
                    onClick = {
                        onDelete()
                        onDismiss()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Xoá từ này")
                }
            }
        }
    }
}