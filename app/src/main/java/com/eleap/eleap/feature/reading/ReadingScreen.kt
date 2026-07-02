package com.eleap.eleap.feature.reading

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eleap.eleap.feature.reading.ui.PhrasePopup
import com.eleap.eleap.feature.reading.ui.PopupAnchorInfo
import com.eleap.eleap.feature.reading.ui.SentencePopup
import com.eleap.eleap.feature.reading.ui.WordClickableRow
import com.eleap.eleap.feature.reading.ui.WordPopup

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingScreen(
    readingId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val vm: ReadingViewModel = viewModel(factory = ReadingViewModel.Factory(context))
    val sentences         by vm.sentences.collectAsState()
    val isLoading         by vm.isLoadingReading.collectAsState()
    val selectedWord      by vm.selectedWord.collectAsState()
    val selectedPhrase    by vm.selectedPhrase.collectAsState()   // dùng chung cho WordPopup context & PhrasePopup độc lập
    val selectedSentence  by vm.selectedSentence.collectAsState()
    val selectedDictEntry by vm.selectedDictEntry.collectAsState()
    val isDictExpanded    by vm.isDictExpanded.collectAsState()
    val savedWordIds      by vm.savedWordIds.collectAsState()

    val prefs = remember { context.getSharedPreferences("reading_settings", android.content.Context.MODE_PRIVATE) }
    var fontSize by remember { mutableStateOf(prefs.getInt("font_size", 16)) }

    // ── Chế độ dịch khi kéo bôi đen ≥2 từ: "S" = dịch câu, "P" = dịch cụm từ ──
    var translateMode by remember { mutableStateOf(prefs.getString("translate_mode", "S") ?: "S") }

    // ── Chế độ hiển thị cụm từ, chỉ áp dụng khi translateMode == "P":
    //    "underline" = gạch chân nhẹ dưới các từ cùng phrase, vẫn chảy chữ bình thường
    //    "line"      = mỗi phrase xuống 1 dòng riêng
    var phraseFormat by remember { mutableStateOf(prefs.getString("phrase_format", "underline") ?: "underline") }

    var anchorInfo   by remember { mutableStateOf<PopupAnchorInfo?>(null) }
    var viewportRect by remember { mutableStateOf<Rect?>(null) }

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
            onSaveStateChanged   = { vm.refreshSavedWordIds() },
            onDismiss            = {
                vm.dismissWordPopup()
                anchorInfo = null
            }
        )
    }

    // ── PhrasePopup (độc lập, chế độ "P") ───────────────────────────────────────
    // Chỉ hiện khi selectedWord == null — tránh trùng với phrase context bên
    // trong WordPopup (trường hợp đó selectedWord != null, đã xử lý ở trên).
    if (selectedWord == null) {
        selectedPhrase?.let { phrase ->
            PhrasePopup(
                phrase       = phrase,
                anchorInfo   = anchorInfo,
                viewportRect = viewportRect,
                onDismiss    = {
                    vm.dismissPhrasePopup()
                    anchorInfo = null
                }
            )
        }
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
                    // ── Toggle chế độ dịch khi kéo bôi đen ≥2 từ: S (câu) ↔ P (cụm từ) ──
                    TextButton(
                        onClick = {
                            translateMode = if (translateMode == "S") "P" else "S"
                            prefs.edit().putString("translate_mode", translateMode).apply()
                        }
                    ) {
                        Text(
                            text = translateMode,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    // ── Toggle định dạng hiển thị cụm từ (chỉ có ý nghĩa ở mode "P"):
                    //    "underline" ↔ "line". Mờ đi khi đang ở mode "S" vì không áp dụng.
                    TextButton(
                        onClick = {
                            phraseFormat = if (phraseFormat == "underline") "line" else "underline"
                            prefs.edit().putString("phrase_format", phraseFormat).apply()
                        },
                        enabled = translateMode == "P"
                    ) {
                        Text(
                            text = "F",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
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
                            sentence            = sentence,
                            selectedWord        = selectedWord,
                            selectedPhrase      = selectedPhrase,
                            selectedSentence    = selectedSentence,
                            fontSize            = fontSize,
                            savedWordIds        = savedWordIds,
                            translateMode       = translateMode,
                            phraseFormat        = phraseFormat,
                            onWordClick         = { word -> vm.onWordClick(word, sentence) },
                            onSentenceClick     = { vm.onSentenceClick(sentence) },
                            onPhraseRangeSelect = { anchorWord -> vm.onPhraseRangeSelect(anchorWord, sentence) },
                            onAnchorInfoChanged = { info -> anchorInfo = info }
                        )
                    }
                }
            }
        }
    }
}