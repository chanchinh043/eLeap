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

    // ── savedWordIds: đọc từ ViewModel — cập nhật reactive khi lưu/bỏ lưu từ ──
    // Không còn cần LaunchedEffect poll DB nữa. ViewModel tự refresh khi
    // SaveWordButton gọi vm.refreshSavedWordIds().
    val savedWordIds by vm.savedWordIds.collectAsState()

    // ── Cỡ chữ ───────────────────────────────────────────────────────────────
    val prefs = remember { context.getSharedPreferences("reading_settings", android.content.Context.MODE_PRIVATE) }
    var fontSize by remember { mutableStateOf(prefs.getInt("font_size", 16)) }

    // ── Vị trí "mỏ neo" + khung hiển thị — dùng để đặt popup ────────────────
    var anchorInfo   by remember { mutableStateOf<PopupAnchorInfo?>(null) }
    var viewportRect by remember { mutableStateOf<Rect?>(null) }

    // ── Load bài đọc (bỏ qua nếu đã cache trong ViewModel) ───────────────────
    LaunchedEffect(readingId) {
        vm.loadReading(readingId)
    }

    // ── WordPopup ─────────────────────────────────────────────────────────────
    selectedWord?.let { word ->
        WordPopup(
            word                 = word,
            phrase               = selectedPhrase,
            dictEntry            = selectedDictEntry,
            isDictExpanded       = isDictExpanded,
            anchorInfo           = anchorInfo,
            viewportRect         = viewportRect,
            onToggleDictExpanded = { vm.toggleDictExpanded() },
            onSaveStateChanged   = { vm.refreshSavedWordIds() },  // ← mới: callback sau lưu/bỏ lưu
            onDismiss            = {
                vm.dismissWordPopup()
                anchorInfo = null
            }
        )
    }

    // ── SentencePopup ─────────────────────────────────────────────────────────
    selectedSentence?.let { sentence ->
        SentencePopup(
            sentence     = sentence,
            anchorInfo   = anchorInfo,
            viewportRect = viewportRect,
            onDismiss    = {
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
                    Text(
                        text = "$fontSize",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.widthIn(min = 28.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
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
                            sentence         = sentence,
                            selectedWord     = selectedWord,
                            selectedSentence = selectedSentence,
                            fontSize         = fontSize,
                            savedWordIds     = savedWordIds,
                            onWordClick      = { word -> vm.onWordClick(word, sentence) },
                            onSentenceClick  = { vm.onSentenceClick(sentence) },
                            onAnchorInfoChanged = { info -> anchorInfo = info }
                        )
                    }
                }
            }
        }
    }
}

// ── WordClickableRow ──────────────────────────────────────────────────────────
@Composable
private fun WordClickableRow(
    sentence: ReadingSentence,
    selectedWord: SentenceWord?,
    selectedSentence: ReadingSentence?,
    fontSize: Int,
    savedWordIds: Set<Int>,
    onWordClick: (SentenceWord) -> Unit,
    onSentenceClick: () -> Unit,
    onAnchorInfoChanged: (PopupAnchorInfo) -> Unit,
) {
    val words = sentence.words
    val bounds       = remember(sentence.sentenceId) { mutableStateMapOf<Int, Rect>() }
    val windowBounds = remember(sentence.sentenceId) { mutableStateMapOf<Int, Rect>() }
    var startIdx   by remember(sentence.sentenceId) { mutableStateOf<Int?>(null) }
    var currentIdx by remember(sentence.sentenceId) { mutableStateOf<Int?>(null) }

    val liveRange = remember(startIdx, currentIdx) {
        val s = startIdx; val e = currentIdx
        if (s != null && e != null && e > s) s..e else null
    }

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

    val highlightRange = liveRange ?: committedRange

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
        verticalArrangement   = Arrangement.spacedBy(2.dp),
        modifier = Modifier.pointerInput(sentence.sentenceId) {
            val slop = viewConfiguration.touchSlop
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                startIdx   = indexAt(down.position)
                currentIdx = startIdx

                // Chạm vào khoảng không (không trúng từ nào) → không xử lý gì,
                // trả gesture về cho MainScreen để chuyển màn hình.
                if (startIdx == null) return@awaitEachGesture

                var horizontal: Boolean? = null
                val dragStartX = down.position.x
                var lastX = dragStartX

                while (true) {
                    val event  = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }

                    if (change == null || !change.pressed) {
                        if (horizontal == true) change?.consume()
                        break
                    }

                    lastX = change.position.x

                    if (horizontal == null) {
                        val dx = kotlin.math.abs(change.position.x - down.position.x)
                        val dy = kotlin.math.abs(change.position.y - down.position.y)
                        horizontal = when {
                            dx > slop && dx > dy -> true
                            dy > slop            -> false
                            else                 -> null
                        }
                    }

                    when (horizontal) {
                        true -> {
                            val draggingRight = change.position.x >= dragStartX
                            if (draggingRight) {
                                // Kéo phải trong vùng chữ → đang chọn câu (drag-select)
                                change.consume()
                                indexAt(change.position)?.let { currentIdx = it }
                            }
                            // Kéo trái → MainScreen đã bắt bằng PointerEventPass.Initial,
                            // đây chỉ cần không consume() và không reset để tránh giật.
                        }
                        false -> {
                            startIdx   = null
                            currentIdx = null
                            return@awaitEachGesture
                        }
                        null -> Unit
                    }
                }

                val s = startIdx
                val e = currentIdx
                val draggedRightward = lastX > dragStartX
                if (s != null && e != null) {
                    when {
                        horizontal == true && e > s && draggedRightward -> onSentenceClick()
                        horizontal != true -> onWordClick(words[s])
                    }
                }
                startIdx   = null
                currentIdx = null
            }
        }
    ) {
        words.forEachIndexed { index, word ->
            val selected = highlightRange?.contains(index) == true
            val isSaved  = word.wordId in savedWordIds
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
                        bounds[index]       = Rect(c.positionInParent(), c.size.toSize())
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