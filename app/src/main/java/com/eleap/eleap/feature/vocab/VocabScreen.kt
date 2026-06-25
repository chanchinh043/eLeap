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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.eleap.eleap.feature.reading.ui.UserVocabularyEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocabScreen(
    onBack: () -> Unit,
    onStudyClick: () -> Unit,
) {
    val context = LocalContext.current
    val vm: VocabViewModel = viewModel(factory = VocabViewModel.Factory(context))
    val vocabList      by vm.vocabList.collectAsState()
    val isLoading      by vm.isLoading.collectAsState()
    val selectedEntry  by vm.selectedEntry.collectAsState()
    val dictEntry      by vm.selectedDictEntry.collectAsState()
    val isDictExpanded by vm.isDictExpanded.collectAsState()
    val selectedCount  by vm.selectedCount.collectAsState()

    var anchorRect by remember { mutableStateOf<Rect?>(null) }

    LaunchedEffect(Unit) { vm.loadVocab() }

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
                title = { Text("Ôn từ vựng") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            // Nút "Học từ" chỉ hiện khi có ít nhất 1 từ được chọn
            if (selectedCount > 0) {
                Surface(shadowElevation = 8.dp) {
                    Button(
                        onClick = onStudyClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            // Đẩy nút lên trên nút ảo Home/Back (navigation bar)
                            .windowInsetsPadding(WindowInsets.navigationBars)
                    ) {
                        Text("Học từ ($selectedCount từ được chọn)")
                    }
                }
            }
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
                        var cardCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
                        VocabCard(
                            entry            = entry,
                            modifier         = Modifier.onGloballyPositioned { cardCoords = it },
                            onWordClick      = {
                                anchorRect = cardCoords?.boundsInWindow()
                                vm.onEntryClick(entry)
                            },
                            onToggleSelected = { vm.toggleSelected(entry) },
                            onDelete         = { vm.deleteWord(entry.id) }
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
    onToggleSelected: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isSelected = entry.selected == 1
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ── Checkbox chọn từ để học ───────────────────────────────────────
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelected() }
            )

            // ── Nội dung từ ───────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp)
            ) {
                Text(
                    text = entry.textEn ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isSelected)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.primary,
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

            // ── Nút xoá ──────────────────────────────────────────────────────
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