// VocabReadingScreen.kt
// Đặt tại: com/eleap/eleap/feature/vocab/VocabReadingScreen.kt
package com.eleap.eleap.feature.vocab

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eleap.eleap.feature.reading.ui.UserVocabularyEntry

/**
 * Màn hình ôn từ vựng gắn với bài đọc.
 * Giống VocabScreen (checkbox chọn, xóa, nút Học từ) nhưng chỉ hiện
 * các từ được lưu từ bài đọc có [readingId] này.
 *
 * @param readingId    ID bài đọc hiện tại.
 * @param onBack       Callback quay lại.
 * @param onStudyClick Callback navigate sang VocabStudyScreen với pool là
 *                     các từ đang được chọn trong bài đọc này.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocabReadingScreen(
    readingId: Int,
    onBack: () -> Unit,
    onStudyClick: () -> Unit,
) {
    val context  = LocalContext.current
    val activity = context as ComponentActivity
    val vm: VocabViewModel = viewModel(
        viewModelStoreOwner = activity,
        factory = VocabViewModel.Factory(context)
    )

    val vocabList      by vm.readingVocabList.collectAsState()
    val isLoading      by vm.isLoadingReadingVocab.collectAsState()

    val selectedCount = remember(vocabList) { vocabList.count { it.selected == 1 } }


    LaunchedEffect(readingId) { vm.loadVocabForReading(readingId) }


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
        },
        bottomBar = {
            if (selectedCount > 0) {
                Surface(shadowElevation = 8.dp) {
                    Button(
                        onClick = onStudyClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
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
                isLoading && vocabList.isEmpty() ->
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

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
                            entry            = entry,
                            modifier         = Modifier.onGloballyPositioned { cardCoords = it },
                            onWordClick      = {
                                vm.setAnchorRect(cardCoords?.boundsInWindow())
                                vm.onEntryClick(entry)
                            },
                            onToggleSelected = { vm.toggleSelectedInReading(entry) },
                            onDelete         = { vm.deleteWordFromReading(entry.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VocabReadingCard(
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
                checked         = isSelected,
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