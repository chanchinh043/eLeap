// VocabReadingScreen.kt
// Đặt tại: com/eleap/eleap/feature/vocab/VocabReadingScreen.kt
package com.eleap.eleap.feature.vocab

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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

// ── Phân loại từ theo số lần ôn ──────────────────────────────────────────────
private enum class VocabReadingTab(val label: String) {
    NEW("Mới nhất"),
    RECENT("Gần đây"),
    ALL("Tất cả từ"),
}

private fun UserVocabularyEntry.readingTab(): VocabReadingTab = when {
    count < 30  -> VocabReadingTab.NEW
    count <= 70 -> VocabReadingTab.RECENT
    else        -> VocabReadingTab.ALL
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocabReadingScreen(
    readingId: Int,
    onBack: () -> Unit,
    onStudyClick: (tabName: String) -> Unit,
) {
    val context  = LocalContext.current
    val activity = context as ComponentActivity
    val vm: VocabViewModel = viewModel(
        viewModelStoreOwner = activity,
        factory = VocabViewModel.Factory(context)
    )

    val vocabList          by vm.readingVocabList.collectAsState()
    val isLoading          by vm.isLoadingReadingVocab.collectAsState()
    val selectedByTab      by vm.readingSelectedByTab.collectAsState()

    // ── Tab được nhớ trong ViewModel để giữ nguyên khi back từ Study ─────────
    val activeTabName by vm.readingActiveTab.collectAsState()
    var selectedTab by remember(activeTabName) {
        mutableStateOf(
            VocabReadingTab.entries.firstOrNull { it.name == activeTabName }
                ?: VocabReadingTab.NEW
        )
    }

    val byTab = remember(vocabList) {
        vocabList.sortedByDescending { it.createdAt }.groupBy { it.readingTab() }
    }

    val currentList = when (selectedTab) {
        VocabReadingTab.ALL -> vocabList.sortedByDescending { it.createdAt }
        else                -> byTab[selectedTab] ?: emptyList()
    }

    val rawSelectedIds     = selectedByTab[selectedTab.name] ?: emptySet()
    // Chỉ tính những ID thực sự có trong danh sách hiện tại của tab
    // (tránh ID cũ còn trong prefs sau khi từ bị xóa hoặc chuyển sang tab khác)
    val currentSelectedIds = rawSelectedIds.intersect(currentList.map { it.id }.toSet())
    val selectedCount      = currentSelectedIds.size

    // ── Trạng thái "Tự động chọn tất cả" — đọc từ VM, lưu vào SharedPreferences ─
    val autoSelectEnabled by vm.readingAutoSelect.collectAsState()

    // Khi autoSelect bật và currentList thay đổi → tự select tất cả
    LaunchedEffect(autoSelectEnabled, currentList) {
        if (selectedTab == VocabReadingTab.NEW && autoSelectEnabled && currentList.isNotEmpty()) {
            vm.setAllSelectedInReading(currentList.map { it.id }.toSet(), selectedTab.name)
        }
    }

    // Tất cả đã được chọn chưa? (dùng cho nút Chọn tất cả / Bỏ chọn tất cả)
    val allSelected = currentList.isNotEmpty() && currentList.all { it.id in currentSelectedIds }

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
                        onClick = { onStudyClick(selectedTab.name) },
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

                else -> Column(modifier = Modifier.fillMaxSize()) {
                    // ── Tab row ───────────────────────────────────────────────
                    TabRow(selectedTabIndex = selectedTab.ordinal) {
                        VocabReadingTab.entries.forEach { tab ->
                            val count = when (tab) {
                                VocabReadingTab.ALL -> vocabList.size
                                else -> byTab[tab]?.size ?: 0
                            }
                            Tab(
                                selected = selectedTab == tab,
                                onClick  = {
                                    selectedTab = tab
                                    vm.setReadingActiveTab(tab.name)
                                },
                                text     = { Text("${tab.label} ($count)") }
                            )
                        }
                    }

                    // ── Thanh công cụ chỉ hiện ở tab NEW ─────────────────────
                    if (selectedTab == VocabReadingTab.NEW && currentList.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Nút Tự động chọn tất cả (toggle)
                            FilterChip(
                                selected = autoSelectEnabled,
                                onClick  = { vm.setAutoSelect(!autoSelectEnabled) },
                                label    = { Text("Tự động chọn tất cả") },
                            )

                            // Nút Chọn tất cả / Bỏ chọn tất cả
                            OutlinedButton(
                                onClick = {
                                    if (allSelected) {
                                        vm.setAllSelectedInReading(emptySet(), selectedTab.name)
                                        vm.setAutoSelect(false)
                                    } else {
                                        // Chọn tất cả
                                        vm.setAllSelectedInReading(
                                            currentList.map { it.id }.toSet(),
                                            selectedTab.name
                                        )
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text(if (allSelected) "Bỏ chọn tất cả" else "Chọn tất cả")
                            }
                        }
                        HorizontalDivider()
                    }

                    // ── Danh sách từ theo tab ─────────────────────────────────
                    if (currentList.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text  = "Không có từ nào trong mục này",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(currentList, key = { it.id }) { entry ->
                                var cardCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
                                VocabReadingCard(
                                    entry            = entry,
                                    isSelected       = entry.id in currentSelectedIds,
                                    modifier         = Modifier.onGloballyPositioned { cardCoords = it },
                                    onWordClick      = {
                                        vm.setAnchorRect(cardCoords?.boundsInWindow())
                                        vm.onEntryClick(entry)
                                    },
                                    onToggleSelected = {
                                        if (selectedTab == VocabReadingTab.NEW) {
                                            vm.setAutoSelect(false)
                                        }
                                        vm.toggleSelectedInReading(entry, selectedTab.name)
                                    },
                                    onDelete         = { vm.deleteWordFromReading(entry.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VocabReadingCard(
    entry: UserVocabularyEntry,
    isSelected: Boolean,
    onWordClick: () -> Unit,
    onToggleSelected: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier  = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked         = isSelected,
                onCheckedChange = { onToggleSelected() }
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp)
            ) {
                Text(
                    text     = entry.textEn ?: "",
                    style    = MaterialTheme.typography.titleMedium,
                    color    = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onWordClick)
                )
                entry.textVi?.let {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text  = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Surface(
                shape    = RoundedCornerShape(12.dp),
                color    = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Text(
                    text     = "×${entry.count}",
                    style    = MaterialTheme.typography.labelMedium,
                    color    = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
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