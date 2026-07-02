// VocabStudyScreen.kt
// Đặt tại: com/eleap/eleap/feature/vocab/VocabStudyScreen.kt
package com.eleap.eleap.feature.vocab

import androidx.activity.ComponentActivity
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

/**
 * Màn hình quay từ (flashcard).
 *
 * @param pool   Danh sách từ để bốc ngẫu nhiên. Caller tự lọc (selected=1 hoặc
 *               toàn bộ list của bài đọc) rồi truyền vào — ViewModel không quyết định.
 * @param onBack Callback quay lại màn trước.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocabStudyScreen(
    pool: List<UserVocabularyEntry>,
    onBack: () -> Unit,
) {
    val context  = LocalContext.current
    val activity = context as ComponentActivity
    val vm: VocabViewModel = viewModel(
        viewModelStoreOwner = activity,
        factory = VocabViewModel.Factory(context)
    )

    // ── "Lịch sử" các từ đã hiện ra, dùng để bấm Trước quay lại ─────────────
    // Key là danh sách ID — chỉ reset khi nội dung pool thực sự thay đổi,
    // không bị reset do List object mới nhưng cùng nội dung (sau loadVocab()).
    val poolIds = remember(pool) { pool.map { it.id } }
    var history by remember(poolIds) {
        mutableStateOf(if (pool.isNotEmpty()) listOf(pool.random()) else emptyList<UserVocabularyEntry>())
    }
    var currentIndex by remember(poolIds) {
        mutableIntStateOf(if (pool.isNotEmpty()) 0 else -1)
    }
    var goingForward by remember { mutableStateOf(true) }

    // Tăng count cho từ đầu tiên khi pool load xong
    LaunchedEffect(poolIds) {
        if (history.isNotEmpty()) {
            vm.incrementCount(history[0])
        }
    }

    fun randomExcluding(excludeId: String?): UserVocabularyEntry {
        if (pool.size <= 1) return pool.first()
        var candidate = pool.random()
        while (candidate.id == excludeId) { candidate = pool.random() }
        return candidate
    }


    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Quay từ") },
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

                currentIndex < 0 -> { /* Đang chờ bốc từ đầu tiên */ }

                else -> Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // ── Flashcard ─────────────────────────────────────────────
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
                                    vm.setAnchorRect(rect)
                                    vm.onEntryClick(cardEntry)
                                }
                            )
                        }
                    }

                    // ── Nút Trước / Tiếp ──────────────────────────────────────
                    StudyNavigationBar(
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
                                currentIndex++
                            } else {
                                val excludeId = history.getOrNull(currentIndex)?.id
                                val next = randomExcluding(excludeId)
                                history = history + next
                                currentIndex++
                                vm.incrementCount(next)   // từ mới xuất hiện lần đầu
                            }
                        }
                    )
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
                .clickable { cardCoords?.boundsInWindow()?.let(onWordClick) },
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
private fun StudyNavigationBar(
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
            OutlinedButton(onClick = onPrev, enabled = canGoPrev) {
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