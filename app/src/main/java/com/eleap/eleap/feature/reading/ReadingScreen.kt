package com.eleap.eleap.feature.reading

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eleap.eleap.feature.reading.data.ReadingSentence
import com.eleap.eleap.feature.reading.data.SentenceWord
import com.eleap.eleap.feature.reading.ui.PopupAnchorInfo
import com.eleap.eleap.feature.reading.ui.SentencePopup
import com.eleap.eleap.feature.reading.ui.WordPopup

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingScreen(
    readingId: Int,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val vm: ReadingViewModel = viewModel(factory = ReadingViewModel.Factory(context))
    val sentences         by vm.sentences.collectAsState()
    val isLoading         by vm.isLoadingReading.collectAsState()
    val selectedWord      by vm.selectedWord.collectAsState()
    val selectedPhrase    by vm.selectedPhrase.collectAsState()
    val selectedSentence  by vm.selectedSentence.collectAsState()
    val selectedDictEntry by vm.selectedDictEntry.collectAsState()
    val isDictExpanded    by vm.isDictExpanded.collectAsState()

    // ── Cỡ chữ — đọc từ SharedPreferences khi mở, ghi lại mỗi khi đổi ─────────
    val prefs = remember { context.getSharedPreferences("reading_settings", android.content.Context.MODE_PRIVATE) }
    var fontSize by remember { mutableStateOf(prefs.getInt("font_size", 16)) }

    // ── Vị trí "mỏ neo" (từ/câu đang chọn) + khung hiển thị — dùng để đặt popup ──
    var anchorInfo by remember { mutableStateOf<PopupAnchorInfo?>(null) }
    var viewportRect by remember { mutableStateOf<Rect?>(null) }

    LaunchedEffect(readingId) {
        vm.loadReading(readingId)
    }

    // ── WordPopup (kèm phrase nếu từ thuộc cụm) ──────────────────────────────
    selectedWord?.let { word ->
        WordPopup(
            word = word,
            phrase = selectedPhrase,
            dictEntry = selectedDictEntry,
            isDictExpanded = isDictExpanded,
            anchorInfo = anchorInfo,
            viewportRect = viewportRect,
            onToggleDictExpanded = { vm.toggleDictExpanded() },
            onDismiss = {
                vm.dismissWordPopup()
                anchorInfo = null
            }
        )
    }

    // ── SentencePopup ─────────────────────────────────────────────────────────
    selectedSentence?.let { sentence ->
        SentencePopup(
            sentence = sentence,
            anchorInfo = anchorInfo,
            viewportRect = viewportRect,
            onDismiss = {
                vm.dismissSentencePopup()
                anchorInfo = null
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reading") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // ── Nút trừ ──────────────────────────────────────────────
                    IconButton(
                        onClick = {
                            if (fontSize > 10) {
                                fontSize--
                                prefs.edit().putInt("font_size", fontSize).apply()
                            }
                        },
                        enabled = fontSize > 10
                    ) {
                        Text(text = "−", style = MaterialTheme.typography.titleLarge)
                    }
                    // ── Hiện cỡ chữ hiện tại ─────────────────────────────────
                    Text(
                        text = "$fontSize",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.widthIn(min = 28.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    // ── Nút cộng ─────────────────────────────────────────────
                    IconButton(
                        onClick = {
                            if (fontSize < 30) {
                                fontSize++
                                prefs.edit().putInt("font_size", fontSize).apply()
                            }
                        },
                        enabled = fontSize < 30
                    ) {
                        Text(text = "+", style = MaterialTheme.typography.titleLarge)
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .onGloballyPositioned { viewportRect = it.boundsInWindow() }
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(sentences, key = { it.sentenceId }) { sentence ->
                        WordClickableRow(
                            sentence = sentence,
                            selectedWord = selectedWord,
                            selectedSentence = selectedSentence,
                            fontSize = fontSize,
                            onWordClick = { word -> vm.onWordClick(word, sentence) },
                            onSentenceClick = { vm.onSentenceClick(sentence) },
                            onAnchorInfoChanged = { info -> anchorInfo = info }
                        )
                    }
                }
            }
        }
    }
}

// ── WordClickableRow ──────────────────────────────────────────────────────────
// Tap 1 từ          → dịch từ (WordPopup)
// Kéo ngang qua ≥2 từ → dịch câu (SentencePopup)
// Kéo dọc            → nhường cho LazyColumn cuộn trang
// Highlight giữ nguyên trong lúc popup tương ứng đang mở
@Composable
private fun WordClickableRow(
    sentence: ReadingSentence,
    selectedWord: SentenceWord?,
    selectedSentence: ReadingSentence?,
    fontSize: Int,
    onWordClick: (SentenceWord) -> Unit,
    onSentenceClick: () -> Unit,
    onAnchorInfoChanged: (PopupAnchorInfo) -> Unit,
) {
    val words = sentence.words
    val bounds = remember(sentence.sentenceId) { mutableStateMapOf<Int, Rect>() }
    // Toạ độ WINDOW (màn hình thật) của từng từ — dùng riêng để định vị popup,
    // tách biệt với `bounds` (toạ độ cục bộ trong FlowRow) vốn dùng để hit-test khi kéo.
    val windowBounds = remember(sentence.sentenceId) { mutableStateMapOf<Int, Rect>() }
    var startIdx by remember(sentence.sentenceId) { mutableStateOf<Int?>(null) }
    var currentIdx by remember(sentence.sentenceId) { mutableStateOf<Int?>(null) }

    // Phạm vi đang kéo tay (live, chỉ tồn tại trong lúc gesture diễn ra)
    val liveRange = remember(startIdx, currentIdx) {
        val s = startIdx; val e = currentIdx
        if (s != null && e != null) minOf(s, e)..maxOf(s, e) else null
    }

    // Phạm vi "đã chốt" — giữ highlight khi popup (từ hoặc câu) đang mở
    val committedRange = remember(selectedWord?.wordId, selectedSentence?.sentenceId, sentence.sentenceId) {
        when {
            selectedSentence?.sentenceId == sentence.sentenceId ->
                if (words.isNotEmpty()) 0..words.lastIndex else null
            selectedWord != null -> {
                val idx = words.indexOfFirst { it.wordId == selectedWord.wordId }
                if (idx >= 0) idx..idx else null
            }
            else -> null
        }
    }

    // Đang kéo tay thì ưu tiên hiện theo tay; nhả tay xong thì theo trạng thái popup
    val highlightRange = liveRange ?: committedRange

    // ── Báo vị trí "mỏ neo" lên ReadingScreen để đặt popup ───────────────────
    // Chỉ row đang chứa lựa chọn hiện tại (committedRange != null) mới báo cáo.
    // anchor  = hợp các Rect (toạ độ window) của các từ trong committedRange
    //           (xử lý cả trường hợp câu wrap nhiều dòng).
    // lineHeight = chiều cao 1 dòng, lấy luôn từ Rect của từ đầu tiên — không cần đo thêm.
    val computedAnchor = committedRange?.let { range ->
        val rects = range.mapNotNull { windowBounds[it] }
        val unionRect = rects.reduceOrNull { acc, r ->
            Rect(
                left = minOf(acc.left, r.left),
                top = minOf(acc.top, r.top),
                right = maxOf(acc.right, r.right),
                bottom = maxOf(acc.bottom, r.bottom),
            )
        }
        val lineHeight = windowBounds[range.first]?.height
        if (unionRect != null && lineHeight != null) {
            PopupAnchorInfo(rect = unionRect, lineHeightPx = lineHeight)
        } else null
    }
    LaunchedEffect(computedAnchor) {
        computedAnchor?.let(onAnchorInfoChanged)
    }

    fun indexAt(pos: Offset): Int? =
        bounds.entries.firstOrNull { it.value.contains(pos) }?.key

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.pointerInput(sentence.sentenceId) {
            val slop = viewConfiguration.touchSlop
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                startIdx = indexAt(down.position)
                currentIdx = startIdx

                var horizontal: Boolean? = null // null = chưa rõ hướng

                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }

                    if (change == null || !change.pressed) {
                        if (horizontal == true) change?.consume()
                        break
                    }

                    if (horizontal == null) {
                        val dx = kotlin.math.abs(change.position.x - down.position.x)
                        val dy = kotlin.math.abs(change.position.y - down.position.y)
                        horizontal = when {
                            dx > slop && dx > dy -> true   // kéo ngang → chọn câu
                            dy > slop -> false              // kéo dọc → để LazyColumn cuộn
                            else -> null                    // chưa đủ để xác định
                        }
                    }

                    when (horizontal) {
                        true -> {
                            change.consume()
                            indexAt(change.position)?.let { currentIdx = it }
                        }
                        false -> {
                            // Nhường gesture cho LazyColumn cuộn — không consume, dừng luôn
                            startIdx = null
                            currentIdx = null
                            return@awaitEachGesture
                        }
                        null -> Unit
                    }
                }

                val s = startIdx
                val e = currentIdx
                if (s != null && e != null) {
                    if (horizontal == true && s != e) onSentenceClick() else onWordClick(words[s])
                }
                startIdx = null
                currentIdx = null
            }
        }
    ) {
        words.forEachIndexed { index, word ->
            val selected = highlightRange?.contains(index) == true
            Text(
                text = word.textEn ?: "",
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = fontSize.sp),
                color = if (selected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.primary,

                modifier = Modifier
                    .onGloballyPositioned { c ->
                        bounds[index] = Rect(c.positionInParent(), c.size.toSize())
                        windowBounds[index] = c.boundsInWindow()
                    }
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 2.dp, vertical = 2.dp)
            )
        }
    }
}