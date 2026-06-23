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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eleap.eleap.feature.reading.data.ReadingSentence
import com.eleap.eleap.feature.reading.data.SentenceWord
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
            onToggleDictExpanded = { vm.toggleDictExpanded() },
            onDismiss = { vm.dismissWordPopup() }
        )
    }

    // ── SentencePopup ─────────────────────────────────────────────────────────
    selectedSentence?.let { sentence ->
        SentencePopup(
            sentence = sentence,
            onDismiss = { vm.dismissSentencePopup() }
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
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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
                            onWordClick = { word -> vm.onWordClick(word, sentence) },
                            onSentenceClick = { vm.onSentenceClick(sentence) }
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
    onWordClick: (SentenceWord) -> Unit,
    onSentenceClick: () -> Unit,
) {
    val words = sentence.words
    val bounds = remember(sentence.sentenceId) { mutableStateMapOf<Int, Rect>() }
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
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .onGloballyPositioned { c ->
                        bounds[index] = Rect(c.positionInParent(), c.size.toSize())
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