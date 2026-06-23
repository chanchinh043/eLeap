package com.eleap.eleap.feature.reading

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eleap.eleap.feature.reading.data.ReadingSentence
import com.eleap.eleap.feature.reading.data.SentenceWord
import com.eleap.eleap.feature.reading.ui.ReadingBottomBar
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
    val sentences        by vm.sentences.collectAsState()
    val isLoading        by vm.isLoadingReading.collectAsState()
    val readingMode      by vm.readingMode.collectAsState()
    val selectedWord     by vm.selectedWord.collectAsState()
    val selectedPhrase   by vm.selectedPhrase.collectAsState()
    val selectedSentence by vm.selectedSentence.collectAsState()

    LaunchedEffect(readingId) {
        vm.loadReading(readingId)
    }

    // ── Flow 6: WordPopup (kèm phrase nếu từ thuộc cụm) ─────────────────────
    selectedWord?.let { word ->
        WordPopup(
            word = word,
            phrase = selectedPhrase,                    // null nếu từ không thuộc cụm
            onDismiss = { vm.dismissWordPopup() }       // Flow 8
        )
    }

    // ── Flow 7: SentencePopup ────────────────────────────────────────────────
    selectedSentence?.let { sentence ->
        SentencePopup(
            sentence = sentence,
            onDismiss = { vm.dismissSentencePopup() }   // Flow 9
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
        },
        bottomBar = {
            ReadingBottomBar(
                mode = readingMode,
                onWordClick = { vm.toggleWordMode() },          // Flow 4
                onSentenceClick = { vm.toggleSentenceMode() }  // Flow 5
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
                        SentenceRow(
                            sentence = sentence,
                            mode = readingMode,
                            // Flow 6: truyền cả sentence để VM lookup phrase từ RAM
                            onWordClick = { word -> vm.onWordClick(word, sentence) },
                            onSentenceClick = { vm.onSentenceClick(sentence) }  // Flow 7
                        )
                    }
                }
            }
        }
    }
}

// ── SentenceRow ───────────────────────────────────────────────────────────────
@Composable
private fun SentenceRow(
    sentence: ReadingSentence,
    mode: ReadingMode,
    onWordClick: (SentenceWord) -> Unit,
    onSentenceClick: () -> Unit,
) {
    when (mode) {

        // ── WORD mode: mỗi từ là 1 element có thể click ───────────────────────
        ReadingMode.WORD -> {
            WordClickableRow(
                sentence = sentence,
                onWordClick = onWordClick
            )
        }

        // ── SENTENCE mode: cả câu có thể click ───────────────────────────────
        ReadingMode.SENTENCE -> {
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
                        append(sentence.textEn ?: "")
                    }
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSentenceClick() }
            )
        }

        // ── NONE: plain text ──────────────────────────────────────────────────
        ReadingMode.NONE -> {
            Text(
                text = sentence.textEn ?: "",
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

// ── WordClickableRow ──────────────────────────────────────────────────────────
@Composable
private fun WordClickableRow(
    sentence: ReadingSentence,
    onWordClick: (SentenceWord) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        sentence.words.forEach { word ->
            Text(
                text = word.textEn ?: "",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .clickable { onWordClick(word) }
                    .padding(horizontal = 2.dp, vertical = 2.dp)
            )
        }
    }
}