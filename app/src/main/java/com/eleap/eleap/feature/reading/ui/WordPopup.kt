package com.eleap.eleap.feature.reading.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.eleap.eleap.core.tts.TtsPlaybackRouter
import com.eleap.eleap.core.tts.cache.TtsCacheItemType
import com.eleap.eleap.feature.reading.data.DictEntry
import com.eleap.eleap.feature.reading.data.SentencePhrase
import com.eleap.eleap.feature.reading.data.SentenceWord

@Composable
fun WordPopup(
    readingId: String,
    word: SentenceWord,
    phrase: SentencePhrase?,
    dictEntry: DictEntry?,
    isDictExpanded: Boolean,
    anchorInfo: PopupAnchorInfo?,
    viewportRect: Rect?,
    onToggleDictExpanded: () -> Unit,
    onSaveStateChanged: () -> Unit,   // ← mới: gọi sau khi lưu hoặc bỏ lưu từ
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    // Khoảng cách giữa popup và từ được chọn — tăng từ 8dp lên 16dp để popup
    // không nằm sát chữ quá.
    val spacingPx = with(density) { 16.dp.toPx() }
    val context = LocalContext.current

    // ── Tự động đọc từ 1 lần khi popup hiện lên cho đúng word này ───────────
    // key = word.wordId → chỉ nói lại khi người dùng chuyển sang từ KHÁC
    // (bấm từ khác trong khi popup đang mở), không nói lặp lại nếu Composable
    // này chỉ recompose vì lý do khác (vd toggle "Xem thêm" ở phần từ điển).
    // ⚠️ Gọi qua TtsPlaybackRouter (KHÔNG gọi thẳng TtsManager.speak() nữa)
    // — Router tự tra cache đã tải sẵn theo (readingId, sid, WORD, wordId),
    // chỉ fallback Android TTS khi chưa có cache.
    LaunchedEffect(word.wordId) {
        word.textEn?.let { text ->
            TtsPlaybackRouter.speak(
                context   = context,
                text      = text,
                readingId = readingId,
                itemType  = TtsCacheItemType.WORD,
                itemId    = word.wordId,
            )
        }
    }

    // ── HeaderSection: từ vựng + nút Lưu từ + ipa/ipa_vi + nghĩa tiếng Việt.
    // Đây chính là phần "gọn" quyết định chiều cao ban đầu của popup (xem
    // PeekHeightScrollColumn) — khai báo là 1 hàm để dùng lại y hệt ở cả bản
    // đo ẩn (peekContent) và bản hiển thị thật (fullContent), tránh lệch
    // chiều cao đo được so với chiều cao hiển thị thật.
    @Composable
    fun HeaderSection() {
        val ipa   = dictEntry?.ipa?.takeIf { it.isNotBlank() }
        val ipaVi = dictEntry?.ipaVi?.takeIf { it.isNotBlank() }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text     = dictEntry?.wordMarkup?.let { parseMarkup(it) }
                    ?: AnnotatedString(word.textEn ?: ""),
                style    = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            // onSaveStateChanged được truyền vào SaveWordButton để nó gọi
            // sau mỗi lần lưu / bỏ lưu → ViewModel refresh savedWordIds → màu từ đổi ngay
            SaveWordButton(
                word               = word,
                phrase             = phrase,
                readingId          = readingId,
                onSaveStateChanged = onSaveStateChanged,
            )
        }

        // ── IPA: nằm dưới từ vựng; ipa_vi đặt bên phải ipa ───────────────────
        if (ipa != null || ipaVi != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ipa?.let {
                    Text(
                        text      = buildAnnotatedString {
                            append("[")
                            append(parseMarkup(it))
                            append("]")
                        },
                        style     = MaterialTheme.typography.bodyMedium,
                        color     = MaterialTheme.colorScheme.secondary,
                        fontStyle = FontStyle.Italic
                    )
                }
                ipaVi?.let {
                    Text(
                        text      = "[$it]",
                        style     = MaterialTheme.typography.bodyMedium,
                        color     = MaterialTheme.colorScheme.secondary,
                        fontStyle = FontStyle.Italic
                    )
                }
            }
        }

        // ── Nghĩa tiếng Việt — nằm dưới IPA; popup sẽ chỉ cao đến đây ────────
        word.textVi?.let {
            Text(
                text  = it,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }

    // ── ExtraSection: phần mở rộng — chỉ lộ ra khi người dùng vuốt lên
    // trong popup (lemma, giải thích, cụm từ, từ điển, loại từ) ─────────────
    @Composable
    fun ExtraSection() {
        // ── Lemma (dạng gốc) ─────────────────────────────────────────
        word.lemma?.takeIf { it != word.textEn }?.let {
            HorizontalDivider()
            Text(
                text  = "Dạng gốc: $it",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // ── Giải thích dạng từ ────────────────────────────────────────
        word.wordFormExplanation?.let {
            Text(
                text  = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // ── Giải thích từ ─────────────────────────────────────────────
        word.wordExplanation?.let {
            HorizontalDivider()
            Text(
                text  = it,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        // ── Cụm từ ───────────────────────────────────────────────────
        phrase?.let { p ->
            HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text  = "Thuộc cụm từ",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    text  = p.textEn ?: "",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.secondary
                )
                p.textVi?.let {
                    Text(
                        text  = "→ $it",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                p.phraseExplanation?.let {
                    Text(
                        text  = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ── Từ điển ──────────────────────────────────────────────────
        dictEntry?.let { entry ->
            HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text  = "Từ điển",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
                entry.shortMeaning?.let {
                    Text(
                        text  = it,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (isDictExpanded) {
                    entry.meaning?.let {
                        Text(
                            text  = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (!entry.meaning.isNullOrBlank()) {
                    TextButton(
                        onClick        = onToggleDictExpanded,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(if (isDictExpanded) "Thu gọn" else "Xem thêm")
                    }
                }
            }
        }

        // ── Loại từ (POS) ─────────────────────────────────────────────
        word.pos?.let {
            HorizontalDivider()
            Text(
                text      = "Loại từ: $it",
                style     = MaterialTheme.typography.bodySmall,
                color     = MaterialTheme.colorScheme.outline,
                fontStyle = FontStyle.Italic
            )
        }
    }

    val positionProvider = remember(anchorInfo, viewportRect) {
        if (anchorInfo != null && viewportRect != null) {
            SmartPopupPositionProvider(anchorInfo, viewportRect, spacingPx)
        } else {
            FallbackBottomCenterPositionProvider
        }
    }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest      = onDismiss,
        properties            = PopupProperties(focusable = false)
    ) {
        Card(
            modifier = Modifier
                // Chỉ rộng ~2/3 màn hình thay vì hết chiều ngang (fraction
                // tính trên constraint mà Popup cấp — thường bằng bề ngang
                // màn hình) — không dùng .padding(horizontal) nữa vì đã tự
                // co hẹp lại rồi, padding ngang chỉ cần cho phần đệm dọc.
                .fillMaxWidth(0.67f)
                .padding(vertical = 8.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            // ── Chiều cao Card = ĐÚNG chiều cao của phần "gọn" (từ vựng +
            // ipa/ipa_vi + nghĩa tiếng Việt), không phải số đoán chừng.
            // PeekHeightScrollColumn đo (subcompose) 1 bản ẩn của peekContent
            // để lấy chiều cao chính xác, rồi dùng chiều cao đó làm khung
            // nhìn cho toàn bộ nội dung (fullContent, có verticalScroll) —
            // phần còn lại (lemma/giải thích/cụm từ/từ điển/loại từ) nằm
            // ngay bên dưới, cần vuốt từ dưới lên trong popup mới thấy.
            PeekHeightScrollColumn(
                modifier = Modifier.padding(16.dp),
                peekContent = { HeaderSection() },
                fullContent = {
                    HeaderSection()
                    ExtraSection()
                }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// PeekHeightScrollColumn — Column có verticalScroll nhưng chiều cao HIỂN THỊ
// bị khoá đúng bằng chiều cao đo được của `peekContent` (đo bằng 1 lần
// subcompose ẩn, không đặt vào cây layout thật → không tốn chỗ, không hiện ra
// màn hình). `fullContent` là toàn bộ nội dung thật sẽ hiển thị (thường bắt
// đầu bằng đúng các item của peekContent rồi tới phần mở rộng) — phần vượt
// quá chiều cao đo được sẽ bị cắt và lộ ra khi người dùng vuốt/cuộn.
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun PeekHeightScrollColumn(
    modifier: Modifier = Modifier,
    peekContent: @Composable ColumnScope.() -> Unit,
    fullContent: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = rememberScrollState()
    SubcomposeLayout(modifier = modifier) { constraints ->
        val looseConstraints = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)

        // Bản đo ẩn — compose xong lấy chiều cao rồi bỏ, KHÔNG place() nên
        // không hiện ra và không có tương tác (chạm) được với nó.
        val peekHeightPx = subcompose("peek") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = peekContent)
        }.first().measure(looseConstraints).height

        val bodyConstraints = constraints.copy(
            minHeight = 0,
            maxHeight = peekHeightPx.coerceAtMost(constraints.maxHeight)
        )

        val bodyPlaceable = subcompose("body") {
            Column(
                modifier = Modifier.verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = fullContent
            )
        }.first().measure(bodyConstraints)

        layout(bodyPlaceable.width, bodyPlaceable.height) {
            bodyPlaceable.place(0, 0)
        }
    }
}