// ReadingVocabScreen.kt
// Đặt tại: com/eleap/eleap/feature/reading/ReadingVocabScreen.kt
//
// KHÔNG cần sửa file nào khác ngoài MainScreen.kt (thêm Screen.READING_VOCAB + gesture vuốt trái).
// Tự quản lý dữ liệu riêng — không chia sẻ ViewModel với ReadingScreen.
//
// Phân loại từ theo count:
//   count == 0  → tab "Mới nhất"  (chưa từng ôn)
//   1..49       → tab "Gần đây"   (đang học)
//   50+         → tab "Tất cả từ" (đã thuộc nhiều)
// Trong mỗi tab, sắp xếp theo created_at DESC (mới lưu lên trước).
//
// Tích hợp vào MainScreen.kt:
//   1. Thêm READING_VOCAB vào enum Screen
//   2. previousScreenOf(READING_VOCAB) = READING
//   3. Trong ScreenContent, thêm case READING_VOCAB → ReadingVocabScreen(readingId, onBack)
//   4. Trong ReadingScreen, thêm gesture vuốt trái → onNavigateTo(Screen.READING_VOCAB)

package com.eleap.eleap.feature.reading

import android.content.ContentValues
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.eleap.eleap.feature.reading.ui.UserDatabase
import com.eleap.eleap.feature.reading.ui.UserVocabularyEntry
import com.eleap.eleap.feature.vocab.data.VocabRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── Phân loại từ ──────────────────────────────────────────────────────────────
private enum class VocabTab(val label: String) {
    NEW("Mới nhất"),
    RECENT("Gần đây"),
    ALL("Tất cả từ"),
}

private fun UserVocabularyEntry.tab(): VocabTab = when {
    count == 0  -> VocabTab.NEW
    count < 50  -> VocabTab.RECENT
    else        -> VocabTab.ALL
}

// ─────────────────────────────────────────────────────────────────────────────
// Màn hình chính
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingVocabScreen(
    readingId: Int,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    // ── Dữ liệu ──────────────────────────────────────────────────────────────
    // Lấy từ đã lưu thuộc bài đọc này: lọc theo source_sentence_id
    // trong danh sách sentence của bài (đọc từ readings.db đã cache trong RAM).
    val userDb     = remember { UserDatabase.getInstance(context) }
    val vocabRepo  = remember { VocabRepository.getInstance(context) }

    var allWords   by remember { mutableStateOf<List<UserVocabularyEntry>>(emptyList()) }
    var isLoading  by remember { mutableStateOf(true) }

    // ── Mode: danh sách (false) hoặc quay flashcard (true) ───────────────────
    var flashcardMode by remember { mutableStateOf(false) }

    // ── Tab đang chọn ─────────────────────────────────────────────────────────
    var selectedTab by remember { mutableStateOf(VocabTab.NEW) }

    // ── Popup từ ─────────────────────────────────────────────────────────────
    var popupEntry      by remember { mutableStateOf<UserVocabularyEntry?>(null) }
    var popupDictEntry  by remember { mutableStateOf<com.eleap.eleap.feature.vocab.data.VocabDictEntry?>(null) }
    var isDictExpanded  by remember { mutableStateOf(false) }
    var popupAnchor     by remember { mutableStateOf<Rect?>(null) }

    // ── Load từ của bài đọc từ users.db ──────────────────────────────────────
    LaunchedEffect(readingId) {
        isLoading = true
        withContext(Dispatchers.IO) {
            // 1. Lấy toàn bộ từ đã lưu của user
            val all = userDb.getAllVocabulary()

            // 2. Lấy sentenceId của bài đọc hiện tại
            //    (đọc DB readings.db qua ReadingDatabase — đã init sẵn)
            val db = com.eleap.eleap.feature.reading.data.ReadingDatabase
                .getInstance(context).db
            val sentenceCursor = db.rawQuery(
                "SELECT sentence_id FROM reading_sentences WHERE reading_id = ?",
                arrayOf(readingId.toString())
            )
            val sentenceIds = mutableSetOf<Int>()
            sentenceCursor.use {
                while (it.moveToNext()) sentenceIds.add(it.getInt(0))
            }

            // 3. Lọc ra các từ thuộc bài đọc này
            val filtered = all.filter { it.sourceSentenceId in sentenceIds }
            allWords = filtered

            // 4. Preload dict cho các từ này (chạy nền)
            launch {
                vocabRepo.preloadDict(filtered.mapNotNull { it.textEn })
            }
        }
        isLoading = false
    }

    // ── Popup helper ──────────────────────────────────────────────────────────
    fun openPopup(entry: UserVocabularyEntry, anchor: Rect?) {
        popupEntry     = entry
        popupAnchor    = anchor
        isDictExpanded = false
        scope.launch {
            popupDictEntry = vocabRepo.getDictEntry(entry.textEn)
        }
    }

    fun dismissPopup() {
        popupEntry    = null
        popupDictEntry = null
        isDictExpanded = false
    }

    // ── Popup ─────────────────────────────────────────────────────────────────
    popupEntry?.let { entry ->
        ReadingVocabPopup(
            entry            = entry,
            dictEntry        = popupDictEntry,
            isDictExpanded   = isDictExpanded,
            anchorRect       = popupAnchor,
            onToggleExpanded = { isDictExpanded = !isDictExpanded },
            onDismiss        = { dismissPopup() }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (flashcardMode) "Quay từ" else "Ôn từ bài đọc") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (flashcardMode) flashcardMode = false else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Nút toggle giữa danh sách và quay flashcard
                    TextButton(onClick = { flashcardMode = !flashcardMode }) {
                        Text(if (flashcardMode) "Danh sách" else "Quay từ")
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

                allWords.isEmpty() -> EmptyState(
                    modifier = Modifier.align(Alignment.Center)
                )

                flashcardMode -> FlashcardPane(
                    allWords  = allWords,
                    vocabRepo = vocabRepo,
                    onWordClick = { entry, anchor -> openPopup(entry, anchor) }
                )

                else -> ListPane(
                    allWords     = allWords,
                    selectedTab  = selectedTab,
                    onTabChange  = { selectedTab = it },
                    onWordClick  = { entry, anchor -> openPopup(entry, anchor) },
                    onCountIncrement = { entry ->
                        // Tăng count sau mỗi lần xem — ghi DB nền, cập nhật state local
                        scope.launch(Dispatchers.IO) {
                            val cv = ContentValues().apply { put("count", entry.count + 1) }
                            userDb.db.update(
                                "user_vocabulary", cv, "id = ?", arrayOf(entry.id.toString())
                            )
                        }
                        allWords = allWords.map {
                            if (it.id == entry.id) it.copy(count = it.count + 1) else it
                        }
                    }
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Pane 1: Danh sách — 3 tab
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ListPane(
    allWords: List<UserVocabularyEntry>,
    selectedTab: VocabTab,
    onTabChange: (VocabTab) -> Unit,
    onWordClick: (UserVocabularyEntry, Rect?) -> Unit,
    onCountIncrement: (UserVocabularyEntry) -> Unit,
) {
    // Phân loại theo tab, trong mỗi tab sắp xếp mới lưu lên trước
    val byTab = remember(allWords) {
        allWords
            .sortedByDescending { it.createdAt }
            .groupBy { it.tab() }
    }
    val currentList = byTab[selectedTab] ?: emptyList()

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Tab row ───────────────────────────────────────────────────────────
        TabRow(selectedTabIndex = selectedTab.ordinal) {
            VocabTab.entries.forEach { tab ->
                val count = byTab[tab]?.size ?: 0
                Tab(
                    selected  = selectedTab == tab,
                    onClick   = { onTabChange(tab) },
                    text      = { Text("${tab.label} ($count)") }
                )
            }
        }

        // ── Danh sách từ ──────────────────────────────────────────────────────
        if (currentList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
                    ReadingVocabCard(
                        entry   = entry,
                        modifier = Modifier.onGloballyPositioned { coords = it },
                        onClick = {
                            onCountIncrement(entry)
                            onWordClick(entry, coords?.boundsInWindow())
                        }
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Pane 2: Quay flashcard ngẫu nhiên từ tất cả từ của bài
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun FlashcardPane(
    allWords: List<UserVocabularyEntry>,
    vocabRepo: VocabRepository,
    onWordClick: (UserVocabularyEntry, Rect?) -> Unit,
) {
    if (allWords.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Chưa có từ nào trong bài để quay")
        }
        return
    }

    var history      by remember { mutableStateOf<List<UserVocabularyEntry>>(emptyList()) }
    var currentIndex by remember { mutableIntStateOf(-1) }
    var goForward    by remember { mutableStateOf(true) }

    // Bốc từ đầu tiên
    LaunchedEffect(allWords) {
        if (allWords.isNotEmpty() && history.isEmpty()) {
            history = listOf(allWords.random())
            currentIndex = 0
        }
    }

    fun nextRandom(excludeId: Int?): UserVocabularyEntry {
        if (allWords.size <= 1) return allWords.first()
        var candidate = allWords.random()
        while (candidate.id == excludeId) candidate = allWords.random()
        return candidate
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        if (currentIndex >= 0) {
            AnimatedContent(
                targetState  = currentIndex,
                transitionSpec = {
                    if (goForward)
                        slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
                    else
                        slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                label = "reading_flashcard"
            ) { idx ->
                history.getOrNull(idx)?.let { entry ->
                    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
                    ReadingFlashCard(
                        entry    = entry,
                        modifier = Modifier.onGloballyPositioned { coords = it },
                        onClick  = { onWordClick(entry, coords?.boundsInWindow()) }
                    )
                }
            }
        }

        // ── Nút điều hướng ────────────────────────────────────────────────────
        Surface(shadowElevation = 8.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick  = { if (currentIndex > 0) { goForward = false; currentIndex-- } },
                    enabled  = currentIndex > 0
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Trước")
                }
                Text(
                    text  = if (currentIndex >= 0) "${currentIndex + 1} / ${history.size}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = {
                    goForward = true
                    if (currentIndex < history.lastIndex) {
                        currentIndex++
                    } else {
                        val exclude = history.getOrNull(currentIndex)?.id
                        history = history + nextRandom(exclude)
                        currentIndex++
                    }
                }) {
                    Text("Tiếp")
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// UI Components
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ReadingVocabCard(
    entry: UserVocabularyEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick    = onClick,
        modifier   = modifier.fillMaxWidth(),
        elevation  = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text  = entry.textEn ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                entry.textVi?.let {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text  = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Chip count nhỏ gọn bên phải
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.padding(start = 12.dp)
            ) {
                Text(
                    text     = "×${entry.count}",
                    style    = MaterialTheme.typography.labelMedium,
                    color    = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun ReadingFlashCard(
    entry: UserVocabularyEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            onClick   = onClick,
            modifier  = modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            colors    = CardDefaults.cardColors(
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
                    text      = entry.textEn ?: "",
                    style     = MaterialTheme.typography.displaySmall,
                    textAlign = TextAlign.Center,
                    color     = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text  = "Bấm để xem nghĩa",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Text(
        text      = "Chưa có từ nào được lưu từ bài đọc này.\nHãy bấm vào từ bất kỳ rồi nhấn \"Lưu từ\"!",
        modifier  = modifier.padding(32.dp),
        textAlign = TextAlign.Center,
        style     = MaterialTheme.typography.bodyLarge,
        color     = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Popup nghĩa từ — tự định vị thông minh (ưu tiên phía trên anchor)
// ─────────────────────────────────────────────────────────────────────────────

private class ReadingVocabPopupPositionProvider(
    private val anchorRect: Rect,
    private val spacingPx: Float,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val spaceAbove = anchorRect.top
        val spaceBelow = windowSize.height - anchorRect.bottom
        val y = when {
            spaceAbove >= popupContentSize.height + spacingPx ->
                anchorRect.top - spacingPx - popupContentSize.height
            spaceBelow >= popupContentSize.height + spacingPx ->
                anchorRect.bottom + spacingPx
            spaceAbove >= spaceBelow -> 0f
            else -> (windowSize.height - popupContentSize.height).toFloat()
        }
        val x = (windowSize.width - popupContentSize.width) / 2
        return IntOffset(x, y.toInt().coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
    }
}

private object FallbackBottomCenterProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect, windowSize: IntSize,
        layoutDirection: LayoutDirection, popupContentSize: IntSize,
    ) = IntOffset(
        (windowSize.width - popupContentSize.width) / 2,
        windowSize.height - popupContentSize.height
    )
}

@Composable
private fun ReadingVocabPopup(
    entry: UserVocabularyEntry,
    dictEntry: com.eleap.eleap.feature.vocab.data.VocabDictEntry?,
    isDictExpanded: Boolean,
    anchorRect: Rect?,
    onToggleExpanded: () -> Unit,
    onDismiss: () -> Unit,
) {
    val spacingPx = with(LocalDensity.current) { 8.dp.toPx() }
    val positionProvider = remember(anchorRect) {
        if (anchorRect != null) ReadingVocabPopupPositionProvider(anchorRect, spacingPx)
        else FallbackBottomCenterProvider
    }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest      = onDismiss,
        properties            = PopupProperties(
            focusable            = false,
            dismissOnClickOutside = true,
            dismissOnBackPress   = true,
        )
    ) {
        Card(
            modifier  = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .heightIn(max = 300.dp),
            shape     = MaterialTheme.shapes.extraLarge,
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            colors    = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // ── Header: từ + IPA + nút Đóng ──────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text  = entry.textEn ?: "",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        val ipa   = dictEntry?.ipa?.takeIf { it.isNotBlank() }
                        val ipaVi = dictEntry?.ipaVi?.takeIf { it.isNotBlank() }
                        if (ipa != null || ipaVi != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                ipa?.let {
                                    Text(
                                        text      = "/$it/",
                                        style     = MaterialTheme.typography.bodySmall,
                                        color     = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontStyle = FontStyle.Italic
                                    )
                                }
                                ipaVi?.let {
                                    Text(
                                        text      = "/$it/",
                                        style     = MaterialTheme.typography.bodySmall,
                                        color     = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontStyle = FontStyle.Italic
                                    )
                                }
                            }
                        }
                    }
                    TextButton(
                        onClick        = onDismiss,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("Đóng")
                    }
                }

                // ── Nghĩa tiếng Việt ─────────────────────────────────────────
                entry.textVi?.let {
                    Text(
                        text  = it,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                // ── Số lần đã ôn ─────────────────────────────────────────────
                HorizontalDivider()
                Text(
                    text  = "Đã ôn: ${entry.count} lần  ·  Điểm: ${entry.score}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // ── Từ điển ───────────────────────────────────────────────────
                if (dictEntry != null) {
                    HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text  = "Từ điển",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        dictEntry.shortMeaning?.let {
                            Text(text = it, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (isDictExpanded) {
                            dictEntry.meaning?.let {
                                Text(
                                    text  = it,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (!dictEntry.meaning.isNullOrBlank()) {
                            TextButton(
                                onClick        = onToggleExpanded,
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(if (isDictExpanded) "Thu gọn" else "Xem thêm")
                            }
                        }
                    }
                }
            }
        }
    }
}