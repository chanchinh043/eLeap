// VocabPopup.kt
// Đặt tại: com/eleap/eleap/feature/vocab/VocabPopup.kt
package com.eleap.eleap.feature.vocab

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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.eleap.eleap.feature.reading.ui.UserVocabularyEntry
import com.eleap.eleap.feature.vocab.data.VocabDictEntry

// ── Vị trí popup linh động: ưu tiên hiện PHÍA TRÊN anchor (từ vừa bấm), ─────
// nếu không đủ chỗ ở trên thì hiện phía dưới. Dùng Popup (không phải Dialog)
// để không chặn touch của list bên dưới — vẫn bấm được sang từ khác.
private class SmartVocabPopupPositionProvider(
    private val anchorRect: Rect,
    private val spacingPx: Float,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val spaceAbove = anchorRect.top
        val spaceBelow = windowSize.height - anchorRect.bottom

        val y = when {
            // Ưu tiên ở trên nếu đủ chỗ
            spaceAbove >= popupContentSize.height + spacingPx ->
                anchorRect.top - spacingPx - popupContentSize.height
            // Không đủ trên thì thử dưới
            spaceBelow >= popupContentSize.height + spacingPx ->
                anchorRect.bottom + spacingPx
            // Không đủ cả hai bên thì chọn bên rộng hơn, ghim vào mép màn hình
            spaceAbove >= spaceBelow -> 0f
            else -> (windowSize.height - popupContentSize.height).toFloat()
        }

        val x = (windowSize.width - popupContentSize.width) / 2

        val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(
            x = x,
            y = y.toInt().coerceIn(0, maxY)
        )
    }
}

// ── Fallback khi chưa có vị trí anchor: ghim ở đáy màn hình ──────────────────
private object BottomFullWidthPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val x = (windowSize.width - popupContentSize.width) / 2
        val y = windowSize.height - popupContentSize.height
        return IntOffset(x, y)
    }
}

@Composable
fun VocabPopup(
    entry: UserVocabularyEntry,
    dictEntry: VocabDictEntry?,
    isDictExpanded: Boolean,
    anchorRect: Rect? = null,
    onToggleDictExpanded: () -> Unit,
    onDismiss: () -> Unit,
) {
    val density: Density = LocalDensity.current
    val spacingPx = with(density) { 8.dp.toPx() }

    val positionProvider = remember(anchorRect, spacingPx) {
        if (anchorRect != null) {
            SmartVocabPopupPositionProvider(anchorRect, spacingPx)
        } else {
            BottomFullWidthPositionProvider
        }
    }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = false,
            dismissOnClickOutside = true,
            dismissOnBackPress = true,
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .heightIn(max = 280.dp),
            shape = MaterialTheme.shapes.extraLarge,
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {

                // ── Header: từ + IPA + nút Đóng ──────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = entry.textEn ?: "",
                            style = MaterialTheme.typography.titleLarge
                        )
                        val ipa   = dictEntry?.ipa?.takeIf { it.isNotBlank() }
                        val ipaVi = dictEntry?.ipaVi?.takeIf { it.isNotBlank() }
                        if (ipa != null || ipaVi != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                ipa?.let {
                                    Text(
                                        text = "/$it/",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontStyle = FontStyle.Italic
                                    )
                                }
                                ipaVi?.let {
                                    Text(
                                        text = "/$it/",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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

                // ── Nghĩa tiếng Việt ─────────────────────────────────────
                entry.textVi?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // ── Từ điển ──────────────────────────────────────────────
                if (dictEntry != null) {
                    HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
            }
        }
    }
}