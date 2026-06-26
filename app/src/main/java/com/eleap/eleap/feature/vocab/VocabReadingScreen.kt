// VocabReadingScreen.kt
package com.eleap.eleap.feature.vocab

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
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Màn hình ôn từ vựng gắn với bài đọc.
 * Hiển thị tất cả từ đã lưu từ bài đọc này (lọc qua source_sentence_id).
 *
 * @param readingId  ID bài đọc hiện tại.
 * @param onBack     Callback quay lại ReadingScreen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocabReadingScreen(
    readingId: Int,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val vm: VocabViewModel = viewModel(factory = VocabViewModel.Factory(context))
    val vocabList      by vm.readingVocabList.collectAsState()
    val isLoading      by vm.isLoadingReadingVocab.collectAsState()
    val selectedEntry  by vm.selectedEntry.collectAsState()
    val dictEntry      by vm.selectedDictEntry.collectAsState()
    val isDictExpanded by vm.isDictExpanded.collectAsState()

    var anchorRect by remember { mutableStateOf<Rect?>(null) }

    LaunchedEffect(readingId) { vm.loadVocabForReading(readingId) }

    selectedEntry?.let { entry ->
        VocabPopup(
            entry                = entry,
            dictEntry            = dictEntry,
            isDictExpanded       = isDictExpanded,
            anchorRect           = anchorRect,
            onToggleDictExpanded = { vm.toggleDictExpanded() },
            onDismiss            = { vm.dismissPopup() }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Từ vựng bài đọc") },
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
            when {
                isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                vocabList.isEmpty() -> Text(
                    text = "Chưa có từ nào được lưu từ bài đọc này.\nHãy bấm vào từ và lưu lại khi đọc!",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item {
                        Text(
                            text = "${vocabList.size} từ đã lưu",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    items(vocabList, key = { it.id }) { entry ->
                        var cardCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
                        VocabReadingCard(
                            entry       = entry,
                            modifier    = Modifier.onGloballyPositioned { cardCoords = it },
                            onWordClick = {
                                anchorRect = cardCoords?.boundsInWindow()
                                vm.onEntryClick(entry)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VocabReadingCard(
    entry: com.eleap.eleap.feature.reading.ui.UserVocabularyEntry,
    onWordClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.textEn ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                entry.textVi?.let {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = {}, label = { Text("Đã ôn: ${entry.count}") })
                    AssistChip(onClick = {}, label = { Text("Điểm: ${entry.score}") })
                }
            }
            TextButton(onClick = onWordClick) {
                Text("Chi tiết")
            }
        }
    }
}