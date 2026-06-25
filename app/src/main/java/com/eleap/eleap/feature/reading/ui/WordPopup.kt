package com.eleap.eleap.feature.reading.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.eleap.eleap.feature.reading.data.DictEntry
import com.eleap.eleap.feature.reading.data.SentencePhrase
import com.eleap.eleap.feature.reading.data.SentenceWord
import com.eleap.eleap.feature.reading.ui.SaveWordButton

@Composable
fun WordPopup(
    word: SentenceWord,
    phrase: SentencePhrase?,
    dictEntry: DictEntry?,
    isDictExpanded: Boolean,
    anchorInfo: PopupAnchorInfo?,
    viewportRect: Rect?,
    onToggleDictExpanded: () -> Unit,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val spacingPx = with(density) { 8.dp.toPx() }

    val positionProvider = remember(anchorInfo, viewportRect) {
        if (anchorInfo != null && viewportRect != null) {
            SmartPopupPositionProvider(anchorInfo, viewportRect, spacingPx)
        } else {
            FallbackBottomCenterPositionProvider
        }
    }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .heightIn(max = 320.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {

                // ── Header: từ + IPA cùng hàng + nút Đóng ───────────────────
                val ipa   = dictEntry?.ipa?.takeIf { it.isNotBlank() }
                val ipaVi = dictEntry?.ipaVi?.takeIf { it.isNotBlank() }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Dòng từ + IPA nằm ngang nhau, co lại để không đè nút Đóng
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = word.textEn ?: "",
                            style = MaterialTheme.typography.titleLarge
                        )
                        // IPA: [ipa][ipa_vi] — cỡ bodyMedium cho dễ đọc hơn
                        if (ipa != null || ipaVi != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                ipa?.let {
                                    Text(
                                        text = "[$it]",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.secondary,
                                        fontStyle = FontStyle.Italic
                                    )
                                }
                                ipaVi?.let {
                                    Text(
                                        text = "[$it]",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.secondary,
                                        fontStyle = FontStyle.Italic
                                    )
                                }
                            }
                        }
                    }
                    TextButton(
                        onClick = onDismiss,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("Đóng")
                    }
                }

                // ── Nghĩa tiếng Việt ─────────────────────────────────────────
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

                // ── Cụm từ ───────────────────────────────────────────────────
                phrase?.let { p ->
                    HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Thuộc cụm từ",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            text = p.textEn ?: "",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        p.textVi?.let {
                            Text(
                                text = "→ $it",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        p.phraseExplanation?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // ── Lưu từ ───────────────────────────────────────────────────
                HorizontalDivider()
                SaveWordButton(word = word, phrase = phrase)

                // ── Từ điển ──────────────────────────────────────────────────
                dictEntry?.let { entry ->
                    HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Từ điển",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                        entry.shortMeaning?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        if (isDictExpanded) {
                            entry.meaning?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (!entry.meaning.isNullOrBlank()) {
                            TextButton(
                                onClick = onToggleDictExpanded,
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(if (isDictExpanded) "Thu gọn" else "Xem thêm")
                            }
                        }
                    }
                }

                // ── Loại từ (POS) — ít quan trọng, để cuối cùng ──────────────
                word.pos?.let {
                    HorizontalDivider()
                    Text(
                        text = "Loại từ: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        fontStyle = FontStyle.Italic
                    )
                }
            }
        }
    }
}