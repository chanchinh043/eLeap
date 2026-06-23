package com.eleap.eleap.feature.reading

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
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
                            onWordClick = { word -> vm.onWordClick(word, sentence) },
                            onSentenceClick = { vm.onSentenceClick(sentence) }
                        )
                    }
                }
            }
        }
    }
}

// ── WordClickableRow: tap 1 từ = dịch từ, kéo qua ≥2 từ = dịch câu ────────────
@Composable
private fun WordClickableRow(
    sentence: ReadingSentence,
    onWordClick: (com.eleap.eleap.feature.reading.data.SentenceWord) -> Unit,
    onSentenceClick: () -> Unit,
) {
    val words = sentence.words
    val bounds = remember(sentence.sentenceId) { mutableStateMapOf<Int, Rect>() }
    var startIdx by remember(sentence.sentenceId) { mutableStateOf<Int?>(null) }
    var currentIdx by remember(sentence.sentenceId) { mutableStateOf<Int?>(null) }

    val selectedRange = remember(startIdx, currentIdx) {
        val s = startIdx; val e = currentIdx
        if (s != null && e != null) minOf(s, e)..maxOf(s, e) else null
    }

    fun indexAt(pos: Offset): Int? =
        bounds.entries.firstOrNull { it.value.contains(pos) }?.key

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.pointerInput(sentence.sentenceId) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val idx = indexAt(down.position)
                startIdx = idx
                currentIdx = idx

                drag(down.id) { change ->
                    indexAt(change.position)?.let { currentIdx = it }
                    change.consume()
                }

                val s = startIdx
                val e = currentIdx
                if (s != null && e != null) {
                    if (s == e) onWordClick(words[s]) else onSentenceClick()
                }
                startIdx = null
                currentIdx = null
            }
        }
    ) {
        words.forEachIndexed { index, word ->
            val selected = selectedRange?.contains(index) == true
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