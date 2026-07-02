// WordClickableRow.kt
package com.eleap.eleap.feature.reading.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import com.eleap.eleap.feature.reading.data.ReadingSentence
import com.eleap.eleap.feature.reading.data.SentencePhrase
import com.eleap.eleap.feature.reading.data.SentenceWord
import kotlin.math.abs

// ── Ngưỡng kéo ngang (dp) để kích hoạt bôi đen khi chưa sang từ kế tiếp ────
// Tăng nếu muốn phải kéo xa hơn mới bôi đen.
private const val HIGHLIGHT_THRESHOLD_DP = 12f

// ── WordClickableRow ──────────────────────────────────────────────────────────
// Vẽ 1 câu (danh sách từ) có thể chạm/kéo để: dịch từ, dịch câu, hoặc dịch
// cụm từ (mode "P"). Tự quản lý toàn bộ hit-test + gesture bôi đen bên trong,
// chỉ báo ra ngoài qua các callback (onWordClick, onSentenceClick,
// onPhraseRangeSelect, onAnchorInfoChanged).
@Composable
fun WordClickableRow(
    sentence: ReadingSentence,
    selectedWord: SentenceWord?,
    selectedPhrase: SentencePhrase?,   // ← PhrasePopup độc lập (mode "P", selectedWord == null) — dùng để bôi đậm cụm từ
    selectedSentence: ReadingSentence?,
    fontSize: Int,
    savedWordIds: Set<String>,
    translateMode: String,   // "S" = kéo ≥2 từ → dịch câu | "P" = kéo ≥2 từ → dịch cụm từ
    phraseFormat: String,    // "underline" | "line" — chỉ có ý nghĩa khi translateMode == "P"
    onWordClick: (SentenceWord) -> Unit,
    onSentenceClick: () -> Unit,
    onPhraseRangeSelect: (SentenceWord) -> Unit,   // truyền anchorWord — VM tự tìm phrase chứa nó
    onAnchorInfoChanged: (PopupAnchorInfo) -> Unit,
) {
    val words = sentence.words

    // windowBounds  : toạ độ (window) của từng từ — dùng để tính PopupAnchorInfo
    //                 VÀ để hit-test (trừ đi containerOrigin → ra toạ độ cục bộ).
    // containerOrigin: gốc toạ độ (window) của Box ngoài cùng chứa các dòng chữ.
    //                 Dùng containerOrigin thay vì positionInParent() của từng Text
    //                 vì ở chế độ "line" mỗi phrase nằm trong 1 FlowRow riêng —
    //                 positionInParent() lúc đó sẽ trả về toạ độ tương đối của
    //                 từng dòng khác nhau, không dùng chung được cho hit-test.
    val windowBounds    = remember(sentence.sentenceId) { mutableStateMapOf<Int, Rect>() }
    var containerOrigin by remember(sentence.sentenceId) { mutableStateOf(Offset.Zero) }

    // liveRange    : phạm vi đang bôi đen trong khi kéo (realtime, chưa thả tay)
    var liveRange by remember(sentence.sentenceId) { mutableStateOf<IntRange?>(null) }

    // committedRange: phạm vi được xác nhận sau khi thả tay (phản ánh state ViewModel)
    val committedRange = remember(
        selectedWord?.wordId, selectedPhrase?.phraseId, selectedSentence?.sentenceId, sentence.sentenceId
    ) {
        when {
            selectedSentence?.sentenceId == sentence.sentenceId ->
                if (words.isNotEmpty()) 0..words.lastIndex else null
            selectedWord != null -> {
                val idx = words.indexOfFirst { it.wordId == selectedWord.wordId }
                if (idx >= 0) idx..idx else null
            }
            // PhrasePopup độc lập (mode "P", selectedWord == null) — bôi đậm
            // toàn bộ cụm từ giống hệt word/sentence. Không cần kiểm tra
            // sentence.sentenceId của phrase vì mỗi WordClickableRow chỉ có
            // 1 câu riêng, chỉ cần khớp phraseId trong danh sách words của câu này.
            selectedPhrase != null -> {
                val idxs = words.indices.filter { words[it].phraseId == selectedPhrase.phraseId }
                if (idxs.isNotEmpty()) idxs.min()..idxs.max() else null
            }
            else -> null
        }
    }

    // Trong khi kéo ưu tiên liveRange; sau khi thả ưu tiên committedRange
    val highlightRange = liveRange ?: committedRange

    // Tính anchor cho popup mỗi khi committedRange thay đổi
    val computedAnchor = committedRange?.let { range ->
        val rects = range.mapNotNull { windowBounds[it] }
        val unionRect = rects.reduceOrNull { acc, r ->
            Rect(
                left   = minOf(acc.left, r.left),
                top    = minOf(acc.top, r.top),
                right  = maxOf(acc.right, r.right),
                bottom = maxOf(acc.bottom, r.bottom),
            )
        }
        val lineHeight = windowBounds[range.first]?.height
        if (unionRect != null && lineHeight != null)
            PopupAnchorInfo(rect = unionRect, lineHeightPx = lineHeight)
        else null
    }
    LaunchedEffect(computedAnchor) {
        computedAnchor?.let(onAnchorInfoChanged)
    }

    // ── Nhóm các từ liên tiếp cùng phraseId (dùng cho chế độ "line") ─────────
    // Từ không thuộc phrase nào (phraseId == null) vẫn được gom liên tiếp
    // thành 1 nhóm "văn bản thường" để không bị vỡ dòng quá vụn.
    val phraseGroups = remember(sentence.sentenceId) {
        buildList {
            var i = 0
            while (i < words.size) {
                var j = i
                val pid = words[i].phraseId
                while (j + 1 < words.size && words[j + 1].phraseId == pid) j++
                add(i..j)
                i = j + 1
            }
        }
    }

    val useLineFormat = translateMode == "P" && phraseFormat == "line"
    val phraseUnderlineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)

    // ── HIT-TEST ──────────────────────────────────────────────────────────────
    // Toạ độ cục bộ = windowBounds - containerOrigin, dùng chung cho mọi dòng
    // (dù các dòng nằm trong FlowRow khác nhau ở chế độ "line").
    fun localRectOf(index: Int): Rect? = windowBounds[index]?.translate(-containerOrigin)

    // Chỉ dùng để kiểm tra sự kiện DOWN: trả null nếu không chạm trúng từ nào.
    // Đây là điểm mấu chốt — tap vào khoảng trống sẽ bị bỏ qua hoàn toàn.
    fun indexAtExact(pos: Offset): Int? =
        windowBounds.keys.firstOrNull { idx -> localRectOf(idx)?.contains(pos) == true }

    // Chỉ dùng khi đang kéo ngang: cho phép trượt qua khoảng cách giữa các từ.
    // Ưu tiên hit trực tiếp; nếu không thì tìm từ cùng hàng gần nhất theo X.
    fun indexNearestX(pos: Offset): Int? {
        val indices = windowBounds.keys.toList()
        if (indices.isEmpty()) return null
        val direct = indices.firstOrNull { idx -> localRectOf(idx)?.contains(pos) == true }
        if (direct != null) return direct
        val sameRow = indices.filter { idx ->
            val r = localRectOf(idx) ?: return@filter false
            pos.y >= r.top && pos.y <= r.bottom
        }
        if (sameRow.isEmpty()) return null
        return sameRow.minByOrNull { idx ->
            val r = localRectOf(idx)!!
            abs(pos.x - (r.left + r.right) / 2f)
        }
    }

    Box(
        modifier = Modifier
            .onGloballyPositioned { containerOrigin = it.boundsInWindow().topLeft }
            .pointerInput(sentence.sentenceId, translateMode) {
                // density có sẵn từ PointerInputScope (kế thừa Density)
                val highlightThresholdPx = HIGHLIGHT_THRESHOLD_DP * density

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)

                    // ── Tap vào khoảng trống → bỏ qua hoàn toàn ─────────────────
                    val anchorIdx = indexAtExact(down.position) ?: return@awaitEachGesture

                    val startX = down.position.x

                    // direction: null = chưa rõ | true = ngang | false = dọc
                    // Vẫn giữ nguyên yêu cầu: PHẢI kéo ngang trước mới kích hoạt bôi
                    // đen (như hành vi cũ) — kéo dọc ngay từ đầu vẫn trả về cho
                    // LazyColumn cuộn như bình thường.
                    var direction: Boolean? = null
                    // Đã kích hoạt chế độ bôi đen chưa?
                    var highlightActivated = false
                    // Mode "P": đã bôi vượt ra ngoài phrase trong lượt chạm này chưa?
                    // Một khi true thì giữ nguyên tới khi thả tay — tránh nhấp nháy
                    // bật/tắt highlight khi ngón tay dao động quanh biên phrase.
                    var phraseCancelled = false

                    // Mode "P": dải chỉ số (liên tục) của cả cụm từ chứa anchorIdx —
                    // tính 1 lần khi bắt đầu kéo, không phụ thuộc vị trí ngón tay,
                    // nên không bị lệch khi cụm từ vắt qua 2 dòng (từ đầu ở cuối
                    // dòng trên, từ cuối ở đầu dòng dưới). Chỉ dùng để xác định
                    // PHẠM VI bôi sau khi đã kích hoạt (kéo ngang trước) — không
                    // thay đổi điều kiện kích hoạt ban đầu.
                    val anchorPhraseRange: IntRange? =
                        if (translateMode == "P" && words[anchorIdx].phraseId != null) {
                            phraseGroups.firstOrNull { anchorIdx in it }
                        } else null

                    while (true) {
                        val event  = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }

                        // ── Thả tay ───────────────────────────────────────────────
                        if (change == null || !change.pressed) {
                            val range = liveRange
                            val cancelled = phraseCancelled
                            liveRange = null   // xoá live highlight

                            when {
                                // Mode P, đã bôi vượt ranh giới phrase → không mở popup nào
                                translateMode == "P" && cancelled -> Unit
                                // Tap (không kéo) hoặc bôi đúng 1 từ → dịch từ
                                range == null || range.first == range.last ->
                                    onWordClick(words[anchorIdx])
                                // Bôi 2+ từ, mode P → range lúc này luôn CHÍNH LÀ
                                // anchorPhraseRange (đã được gán khi kéo), nên nhận thẳng.
                                translateMode == "P" ->
                                    onPhraseRangeSelect(words[anchorIdx])
                                // Bôi 2+ từ, mode S → dịch câu
                                else ->
                                    onSentenceClick()
                            }
                            return@awaitEachGesture
                        }

                        val dx  = change.position.x - startX
                        val dy  = change.position.y - down.position.y
                        val adx = abs(dx)
                        val ady = abs(dy)

                        // ── Xác định hướng kéo (chỉ lock 1 lần, ở lần di chuyển đầu
                        //    tiên) — giữ nguyên như cũ: phải kéo ngang trước mới vào
                        //    chế độ bôi đen. Sau khi đã lock "true" (ngang) thì dù
                        //    sau đó ngón tay đổi hướng xuống dưới, direction vẫn giữ
                        //    nguyên "true" (không re-lock), nên vẫn ở nhánh bôi đen. ──
                        if (direction == null) {
                            val moved = adx > viewConfiguration.touchSlop ||
                                    ady > viewConfiguration.touchSlop
                            if (moved) {
                                direction = adx >= ady   // true = ngang, false = dọc
                            }
                        }

                        when (direction) {

                            // ── Dọc (ngay từ đầu) → trả gesture về cho LazyColumn cuộn ──
                            false -> {
                                liveRange = null
                                return@awaitEachGesture
                            }

                            // ── Ngang (đã kích hoạt từ đầu) → chế độ bôi đen ─────
                            true -> {
                                if (!highlightActivated) {
                                    // Kích hoạt khi kéo vượt ngưỡng DP,
                                    // HOẶC ngón tay đã sang từ khác (dù chưa đủ ngưỡng).
                                    val currentIdxForActivation = indexNearestX(change.position)
                                    val overThreshold  = adx >= highlightThresholdPx
                                    val movedToNewWord = currentIdxForActivation != null &&
                                            currentIdxForActivation != anchorIdx
                                    if (overThreshold || movedToNewWord) {
                                        highlightActivated = true
                                    }
                                }

                                if (highlightActivated) {
                                    if (!phraseCancelled) {
                                        if (translateMode == "P") {
                                            // Mode P: không dò từng từ theo hàng nữa (vì có thể
                                            // đang ở dòng khác với dòng chứa anchor) — chỉ kiểm
                                            // tra ngón tay còn nằm trong vùng bao (union rect,
                                            // đã cộng thêm margin) của TOÀN BỘ cụm từ hay không.
                                            // Nhờ vậy khi kéo ngang xong rồi kéo xuống dòng dưới
                                            // để lấy nốt phần còn lại của cụm vẫn nhận đúng.
                                            if (anchorPhraseRange != null) {
                                                val phraseRect = anchorPhraseRange
                                                    .mapNotNull { localRectOf(it) }
                                                    .reduceOrNull { acc, r ->
                                                        Rect(
                                                            left   = minOf(acc.left, r.left),
                                                            top    = minOf(acc.top, r.top),
                                                            right  = maxOf(acc.right, r.right),
                                                            bottom = maxOf(acc.bottom, r.bottom),
                                                        )
                                                    }
                                                val margin = highlightThresholdPx
                                                val expanded = phraseRect?.let {
                                                    Rect(
                                                        left   = it.left - margin,
                                                        top    = it.top - margin,
                                                        right  = it.right + margin,
                                                        bottom = it.bottom + margin,
                                                    )
                                                }
                                                if (expanded != null && expanded.contains(change.position)) {
                                                    liveRange = anchorPhraseRange
                                                } else {
                                                    liveRange = null
                                                    phraseCancelled = true
                                                }
                                            } else {
                                                // Từ chạm đầu không thuộc cụm từ nào → mode P
                                                // không có gì để bôi, huỷ luôn.
                                                liveRange = null
                                                phraseCancelled = true
                                            }
                                        } else {
                                            // Mode S: giữ nguyên hành vi cũ — dò từ gần nhất
                                            // theo hàng ngang hiện tại của ngón tay.
                                            val currentIdx = indexNearestX(change.position)
                                            val endIdx = currentIdx ?: anchorIdx
                                            val lo = minOf(anchorIdx, endIdx)
                                            val hi = maxOf(anchorIdx, endIdx)
                                            liveRange = lo..hi
                                        }
                                    }
                                    change.consume()   // ngăn LazyColumn scroll theo X
                                }
                            }

                            // ── Chưa rõ hướng → chờ thêm ─────────────────────────
                            null -> Unit
                        }
                    }
                }
            }
    ) {
        // ── Từ riêng lẻ — dùng chung cho cả 2 chế độ hiển thị ────────────────
        @Composable
        fun WordItem(index: Int) {
            val word = words[index]
            val selected = highlightRange?.contains(index) == true
            val isSaved  = word.wordId in savedWordIds
            // Gạch chân nhẹ dưới từ thuộc phrase — chỉ ở mode P + định dạng "underline"
            val showPhraseUnderline = translateMode == "P" &&
                    phraseFormat == "underline" &&
                    word.phraseId != null
            // Từ kế tiếp có cùng phraseId → nối liền gạch chân qua khoảng cách
            // giữa 2 từ (padding của Text + spacing của FlowRow), để không bị đứt đoạn.
            val extendUnderlineRight = showPhraseUnderline &&
                    index + 1 < words.size &&
                    words[index + 1].phraseId == word.phraseId

            Text(
                text  = word.textEn ?: "",
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = fontSize.sp),
                color = when {
                    selected -> MaterialTheme.colorScheme.onPrimary
                    isSaved  -> MaterialTheme.colorScheme.tertiary
                    else     -> MaterialTheme.colorScheme.primary
                },
                modifier = Modifier
                    .onGloballyPositioned { c ->
                        windowBounds[index] = c.boundsInWindow()
                    }
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 2.dp, vertical = 2.dp)
                    .then(
                        if (showPhraseUnderline) {
                            Modifier.drawBehind {
                                val strokeWidthPx = 1.dp.toPx()
                                val y = size.height - strokeWidthPx
                                // Nối liền sang từ kế tiếp (cùng phrase): vẽ tràn qua
                                // phần padding bên phải của từ này + spacing của FlowRow
                                // + padding bên trái của từ kế tiếp, để gạch chân không
                                // bị đứt đoạn giữa 2 từ liền nhau trong cùng 1 cụm từ.
                                val endX = if (extendUnderlineRight) {
                                    size.width + (2.dp + 2.dp + 2.dp).toPx()
                                } else {
                                    size.width
                                }
                                drawLine(
                                    color = phraseUnderlineColor,
                                    start = Offset(0f, y),
                                    end = Offset(endX, y),
                                    strokeWidth = strokeWidthPx
                                )
                            }
                        } else Modifier
                    )
            )
        }

        if (useLineFormat) {
            // ── Chế độ "line": mỗi phrase (hoặc cụm văn bản thường) xuống 1 dòng riêng ──
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                phraseGroups.forEach { group ->
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement   = Arrangement.spacedBy(2.dp),
                    ) {
                        for (index in group) {
                            WordItem(index)
                        }
                    }
                }
            }
        } else {
            // ── Chế độ mặc định: toàn bộ câu chảy chung 1 khối, tự wrap theo bề rộng ──
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement   = Arrangement.spacedBy(2.dp),
            ) {
                words.forEachIndexed { index, _ ->
                    WordItem(index)
                }
            }
        }
    }
}