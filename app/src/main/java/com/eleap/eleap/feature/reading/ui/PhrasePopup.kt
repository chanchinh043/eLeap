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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.eleap.eleap.feature.reading.data.SentencePhrase

/**
 * Popup dịch cụm từ độc lập — hiện khi ở chế độ "P" và người dùng kéo bôi đen
 * ≥2 từ nằm trong 1 phrase. Bố cục giống hệt SentencePopup, chỉ khác nguồn dữ liệu.
 */
@Composable
fun PhrasePopup(
    phrase: SentencePhrase,
    anchorInfo: PopupAnchorInfo?,
    viewportRect: Rect?,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val spacingPx = with(density) { 8.dp.toPx() }

    // ── Vị trí popup: ưu tiên TRÊN cụm từ được chọn; không đủ chỗ thì lật XUỐNG,
    //    và khi xuống thì chừa thêm 1 dòng để không che chữ sắp đọc ──────────
    val positionProvider = remember(anchorInfo, viewportRect) {
        if (anchorInfo != null && viewportRect != null) {
            SmartPopupPositionProvider(anchorInfo, viewportRect, spacingPx)
        } else {
            FallbackBottomCenterPositionProvider
        }
    }

    // Popup KHÔNG có scrim → touch vẫn xuyên xuống LazyColumn bên dưới
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .heightIn(max = 280.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                // ── Header: tiêu đề + nút Đóng ───────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Dịch cụm từ",
                        style = MaterialTheme.typography.titleMedium
                    )
                    TextButton(
                        onClick = onDismiss,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("Đóng")
                    }
                }

                // ── Cụm từ gốc tiếng Anh ─────────────────────────────────────
                Text(
                    text = phrase.textEn ?: "",
                    style = MaterialTheme.typography.bodyLarge
                )

                HorizontalDivider()

                // ── Bản dịch tiếng Việt ──────────────────────────────────────
                phrase.textVi?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // ── Giải thích cụm từ (nếu có) ────────────────────────────────
                phrase.phraseExplanation?.let {
                    HorizontalDivider()
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}