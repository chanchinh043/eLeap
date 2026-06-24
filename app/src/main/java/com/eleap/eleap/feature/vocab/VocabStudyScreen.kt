// VocabStudyScreen.kt
// Đặt tại: com/eleap/eleap/feature/vocab/VocabStudyScreen.kt
package com.eleap.eleap.feature.vocab

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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
import com.eleap.eleap.feature.reading.ui.UserVocabularyEntry
import com.eleap.eleap.feature.vocab.data.VocabDictEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocabStudyScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val vm: VocabViewModel = viewModel(factory = VocabViewModel.Factory(context))
    val vocabList      by vm.vocabList.collectAsState()
    val selectedEntry  by vm.selectedEntry.collectAsState()
    val dictEntry      by vm.selectedDictEntry.collectAsState()
    val isDictExpanded by vm.isDictExpanded.collectAsState()

    // ── Nguồn từ để bốc ngẫu nhiên: các từ đang được chọn (selected = 1) ─────
    val pool = remember(vocabList) {
        vocabList.filter { it.selected == 1 }
    }

    // ── "Lịch sử" các từ đã hiện ra, dùng để có thể bấm Trước quay lại ───────
    // Bấm Tiếp: nếu đang ở cuối lịch sử thì bốc 1 từ MỚI ngẫu nhiên (vô tận),
    // nếu đang lùi ở giữa lịch sử thì chỉ tiến lên trong lịch sử đã có.
    var history by remember { mutableStateOf<List<UserVocabularyEntry>>(emptyList()) }
    var currentIndex by remember { mutableIntStateOf(-1) }
    var goingForward by remember { mutableStateOf(true) }
    var anchorRect by remember { mutableStateOf<Rect?>(null) }

    // ── Bốc từ đầu tiên khi vào màn hình (hoặc khi pool có dữ liệu) ──────────
    LaunchedEffect(pool) {
        if (pool.isNotEmpty() && history.isEmpty()) {
            history = listOf(pool.random())
            currentIndex = 0
        }
    }

    fun randomExcluding(excludeId: Int?): UserVocabularyEntry {
        if (pool.size <= 1) return pool.first()
        var candidate = pool.random()
        while (candidate.id == excludeId) {
            candidate = pool.random()
        }
        return candidate
    }

    // ── Popup nghĩa từ khi bấm vào từ ────────────────────────────────────────
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
                title = {
                    if (currentIndex >= 0) {
                        Text("Đã ôn: ${currentIndex + 1} từ")
                    } else {
                        Text("Học từ")
                    }
                },
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
                pool.isEmpty() -> Text(
                    text = "Chưa có từ nào được chọn.\nHãy chọn từ trong danh sách!",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                currentIndex < 0 -> {
                    // Đang chờ bốc từ đầu tiên (LaunchedEffect chạy gần như ngay lập tức)
                }

                else -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        // ── Flashcard ─────────────────────────────────────────
                        AnimatedContent(
                            targetState = currentIndex,
                            transitionSpec = {
                                if (goingForward) {
                                    slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
                                } else {
                                    slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            label = "flashcard"
                        ) { index ->
                            val cardEntry = history.getOrNull(index)
                            if (cardEntry != null) {
                                FlashCard(
                                    entry = cardEntry,
                                    onWordClick = { rect ->
                                        anchorRect = rect
                                        vm.onEntryClick(cardEntry)
                                    }
                                )
                            }
                        }

                        // ── Điều hướng Trước / Tiếp (Tiếp = bốc ngẫu nhiên vô tận) ──
                        NavigationBar(
                            canGoPrev = currentIndex > 0,
                            onPrev = {
                                if (currentIndex > 0) {
                                    goingForward = false
                                    currentIndex--
                                }
                            },
                            onNext = {
                                goingForward = true
                                if (currentIndex < history.lastIndex) {
                                    // Đang lùi ở giữa lịch sử → chỉ tiến lên, không bốc từ mới
                                    currentIndex++
                                } else {
                                    // Đang ở từ mới nhất → bốc thêm 1 từ ngẫu nhiên, tránh lặp từ vừa xem
                                    val excludeId = history.getOrNull(currentIndex)?.id
                                    history = history + randomExcluding(excludeId)
                                    currentIndex++
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

// ── Flashcard: chỉ hiện tiếng Anh, bấm vào để xem nghĩa ─────────────────────
@Composable
private fun FlashCard(
    entry: UserVocabularyEntry,
    onWordClick: (Rect) -> Unit,
) {
    var cardCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { cardCoords = it }
                .clickable(onClick = { cardCoords?.boundsInWindow()?.let(onWordClick) }),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = entry.textEn ?: "",
                    style = MaterialTheme.typography.displaySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Bấm để xem nghĩa",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

// ── Thanh điều hướng Trước / Tiếp ────────────────────────────────────────────
@Composable
private fun NavigationBar(
    canGoPrev: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Surface(shadowElevation = 8.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onPrev,
                enabled = canGoPrev
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Trước")
            }

            Button(onClick = onNext) {
                Text("Tiếp")
                Spacer(modifier = Modifier.width(4.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
            }
        }
    }
}