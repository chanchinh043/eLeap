// VocabScreen.kt
// Đặt tại: com/eleap/eleap/feature/vocab/VocabScreen.kt
package com.eleap.eleap.feature.vocab

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eleap.eleap.feature.reading.ui.UserVocabularyEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocabScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val vm: VocabViewModel = viewModel(factory = VocabViewModel.Factory(context))
    val vocabList     by vm.vocabList.collectAsState()
    val isLoading     by vm.isLoading.collectAsState()
    val selectedEntry by vm.selectedEntry.collectAsState()
    val dictEntry     by vm.selectedDictEntry.collectAsState()
    val isDictExpanded by vm.isDictExpanded.collectAsState()

    // ── Reload mỗi khi VocabScreen được mở ───────────────────────────────────
    LaunchedEffect(Unit) {
        vm.loadVocab()
    }

    // ── Popup ─────────────────────────────────────────────────────────────────
    selectedEntry?.let { entry ->
        VocabPopup(
            entry              = entry,
            dictEntry          = dictEntry,
            isDictExpanded     = isDictExpanded,
            onToggleDictExpanded = { vm.toggleDictExpanded() },
            onDelete           = { vm.deleteWord(entry.id) },
            onDismiss          = { vm.dismissPopup() }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ôn từ vựng") },
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
                    text = "Chưa có từ nào được lưu.\nHãy lưu từ trong lúc đọc bài!",
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
                    items(vocabList, key = { it.id }) { entry ->
                        VocabCard(
                            entry    = entry,
                            onWordClick = { vm.onEntryClick(entry) },
                            onDelete = { vm.deleteWord(entry.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VocabCard(
    entry: UserVocabularyEntry,
    onWordClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {

                // ── Tap vào textEn để mở popup ───────────────────────────────
                Text(
                    text = entry.textEn ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onWordClick)
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
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Xoá từ",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}