package com.eleap.eleap.feature.reading

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eleap.eleap.feature.reading.data.ReadingSentence
import com.eleap.eleap.feature.reading.data.SentenceWord
import com.eleap.eleap.feature.reading.ui.PopupAnchorInfo
import com.eleap.eleap.feature.reading.ui.SentencePopup
import com.eleap.eleap.feature.reading.ui.WordPopup
import kotlin.math.abs

// ── Ngưỡng kéo ngang (dp) để kích hoạt bôi đen khi chưa sang từ kế tiếp ────
// Tăng nếu muốn phải kéo xa hơn mới bôi đen.
private const val HIGHLIGHT_THRESHOLD_DP = 12f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingScreen(
    readingId: Int,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val vm: ReadingViewModel = viewModel(factory = ReadingViewModel.Factory(context))
    val sentences         by vm.sentences.collectAsState()
    val isLoading         by vm.isLoadingReading.collectAsState()
    val selectedWord      by vm.selectedWord.collectAsState()
    val selectedPhrase    by vm.selectedPhrase.collectAsState()
    val selectedSentence  by vm.selectedSentence.collectAsState()
    val selectedDictEntry by vm.selectedDictEntry.collectAsState()
    val isDictExpanded    by vm.isDictExpanded.collectAsState()
    val savedWordIds      by vm.savedWordIds.collectAsState()
    val aiCompletedId     by vm.aiCompletedReadingId.collectAsState()

    val prefs = remember { context.getSharedPreferences("reading_settings", android.content.Context.MODE_PRIVATE) }
    var fontSize by remember { mutableStateOf(prefs.getInt("font_size", 16)) }

    var anchorInfo   by remember { mutableStateOf<PopupAnchorInfo?>(null) }
    var viewportRect by remember { mutableStateOf<Rect?>(null) }

    // Load bài lần đầu
    LaunchedEffect(readingId) {
        vm.loadReading(readingId)
    }

    // Khi AI xử lý xong bài đang mở → âm thầm reload để hiển thị dữ liệu đầy đủ
    // (silent = true: không hiện loading spinner, không nháy UI, user không
    // nhận ra là đã reload — chỉ thấy phần tiếng Việt/giải thích tự "hiện ra")
    LaunchedEffect(aiCompletedId) {
        if (aiCompletedId == readingId) {
            Log.d("ReadingScreen", "AI xong readingId=$readingId → reload ngầm")
            vm.loadReading(readingId, silent = true)
            vm.consumeAiCompleted()
        }
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
                            selectedSentence    = selectedSentence,
                            fontSize            = fontSize,
                            savedWordIds        = savedWordIds,
                            onWordClick         = { word -> vm.onWordClick(word, sentence) },
                            onSentenceClick     = { vm.onSentenceClick(sentence) },
                            onAnchorInfoChanged = { info -> anchorInfo = info }
                        )
                    }
                }
            }
        }
    }
}

// ── WordClickableRow ──────────────────────────────────────────────────────────
@Composable
private fun WordClickableRow(
    sentence: ReadingSentence,
    selectedWord: SentenceWord?,
    selectedSentence: ReadingSentence?,
    fontSize: Int,
    savedWordIds: Set<Int>,
    onWordClick: (SentenceWord) -> Unit,
    onSentenceClick: () -> Unit,
    onAnchorInfoChanged: (PopupAnchorInfo) -> Unit,
) {
    val words = sentence.words

    // localBounds  : toạ độ trong FlowRow  → dùng để hit-test ngón tay
    // windowBounds : toạ độ trong window   → dùng để tính PopupAnchorInfo
    val localBounds  = remember(sentence.sentenceId) { mutableStateMapOf<Int, Rect>() }
    val windowBounds = remember(sentence.sentenceId) { mutableStateMapOf<Int, Rect>() }

    // liveRange    : phạm vi đang bôi đen trong khi kéo (realtime, chưa thả tay)
    var liveRange by remember(sentence.sentenceId) { mutableStateOf<IntRange?>(null) }

    // committedRange: phạm vi được xác nhận sau khi thả tay (phản ánh state ViewModel)
    val committedRange = remember(
        selectedWord?.wordId, selectedSentence?.sentenceId, sentence.sentenceId
    ) {
        when {
            selectedSentence?.sentenceId == sentence.sentenceId ->
                if (words.isNotEmpty()) 0..words.lastIndex else null
            selectedWord != null -> {
                val idx = words.indexOfFirst { it.wordId == selectedWord.wordId }
                if (idx >= 0) idx..idx else null
            }
            else -> null
        }
    }

    // Trong khi kéo ưu tiên liveRange; sau khi thả ưu tiên committedRange
    val highlightRange = liveRange ?: committedRange

    // Tính anchor cho popup mỗi khi committedRange thay đổi
    val computedAnchor = committedRange?.let { range ->
        val rects = range.mapNotNull { windowBounds[it] }
        val unionRect = rects.reduceOrNull { acc, r ->
            Rect(
                left   = minOf(acc.left, r.left),
                top    = minOf(acc.top, r.top),
                right  = maxOf(acc.right, r.right),
                bottom = maxOf(acc.bottom, r.bottom),
            )
        }
        val lineHeight = windowBounds[range.first]?.height
        if (unionRect != null && lineHeight != null)
            PopupAnchorInfo(rect = unionRect, lineHeightPx = lineHeight)
        else null
    }
    LaunchedEffect(computedAnchor) {
        computedAnchor?.let(onAnchorInfoChanged)
    }

    // ── HIT-TEST ──────────────────────────────────────────────────────────────
    // Chỉ dùng để kiểm tra sự kiện DOWN: trả null nếu không chạm trúng từ nào.
    // Đây là điểm mấu chốt — tap vào khoảng trống sẽ bị bỏ qua hoàn toàn.
    fun indexAtExact(pos: Offset): Int? =
        localBounds.entries.firstOrNull { it.value.contains(pos) }?.key

    // Chỉ dùng khi đang kéo ngang: cho phép trượt qua khoảng cách giữa các từ.
    // Ưu tiên hit trực tiếp; nếu không thì tìm từ cùng hàng gần nhất theo X.
    fun indexNearestX(pos: Offset): Int? {
        val entries = localBounds.entries.toList()
        if (entries.isEmpty()) return null
        val direct = entries.firstOrNull { it.value.contains(pos) }
        if (direct != null) return direct.key
        val sameRow = entries.filter { (_, r) -> pos.y >= r.top && pos.y <= r.bottom }
        if (sameRow.isEmpty()) return null
        return sameRow.minByOrNull { (_, r) -> abs(pos.x - (r.left + r.right) / 2f) }?.key
    }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement   = Arrangement.spacedBy(2.dp),
        modifier = Modifier.pointerInput(sentence.sentenceId) {
            val highlightThresholdPx = HIGHLIGHT_THRESHOLD_DP * density

            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)

                // ── Tap vào khoảng trống → bỏ qua hoàn toàn ─────────────────
                // indexAtExact chỉ trả về giá trị khi chạm trúng bounds của từ,
                // khác với indexNearestX luôn tìm từ gần nhất ngay cả khi chạm trống.
                val anchorIdx = indexAtExact(down.position) ?: return@awaitEachGesture

                val startX = down.position.x

                // direction: null = chưa rõ | true = ngang | false = dọc
                var direction: Boolean? = null
                // Đã kích hoạt chế độ bôi đen chưa?
                var highlightActivated = false

                while (true) {
                    val event  = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }

                    // ── Thả tay ───────────────────────────────────────────────
                    if (change == null || !change.pressed) {
                        val range = liveRange
                        liveRange = null   // xoá live highlight

                        when {
                            // Tap (không kéo) hoặc bôi đúng 1 từ → dịch từ
                            range == null || range.first == range.last ->
                                onWordClick(words[anchorIdx])
                            // Bôi 2+ từ → dịch câu
                            else ->
                                onSentenceClick()
                        }
                        return@awaitEachGesture
                    }

                    val dx  = change.position.x - startX
                    val dy  = change.position.y - down.position.y
                    val adx = abs(dx)
                    val ady = abs(dy)

                    // ── Xác định hướng kéo (chỉ lock 1 lần) ─────────────────
                    if (direction == null) {
                        val moved = adx > viewConfiguration.touchSlop ||
                                ady > viewConfiguration.touchSlop
                        if (moved) {
                            direction = adx >= ady   // true = ngang, false = dọc
                        }
                    }

                    when (direction) {

                        // ── Dọc → trả gesture về cho LazyColumn cuộn ─────────
                        false -> {
                            liveRange = null
                            return@awaitEachGesture
                        }

                        // ── Ngang → chế độ bôi đen ───────────────────────────
                        true -> {
                            val currentIdx = indexNearestX(change.position)

                            if (!highlightActivated) {
                                // Kích hoạt khi kéo vượt ngưỡng DP,
                                // HOẶC ngón tay đã sang từ khác (dù chưa đủ ngưỡng).
                                val overThreshold  = adx >= highlightThresholdPx
                                val movedToNewWord = currentIdx != null && currentIdx != anchorIdx
                                if (overThreshold || movedToNewWord) {
                                    highlightActivated = true
                                }
                            }

                            if (highlightActivated) {
                                val endIdx = currentIdx ?: anchorIdx
                                val lo = minOf(anchorIdx, endIdx)
                                val hi = maxOf(anchorIdx, endIdx)
                                liveRange = lo..hi
                                change.consume()   // ngăn LazyColumn scroll theo X
                            }
                        }

                        // ── Chưa rõ hướng → chờ thêm ─────────────────────────
                        null -> Unit
                    }
                }
            }
        }
    ) {
        words.forEachIndexed { index, word ->
            val token = word.textEn ?: ""

            // Tách dấu kết câu (. ? !) dính ở cuối token ra khỏi phần "từ" thật.
            // Nếu token toàn là dấu câu (hiếm gặp) thì không tách, giữ nguyên.
            val punctSuffix = Regex("[.?!]+$").find(token)?.value ?: ""
            val core  = token.removeSuffix(punctSuffix).ifEmpty { token }
            val punct = if (core == token) "" else punctSuffix

            val selected = highlightRange?.contains(index) == true
            // Đang bôi đen CẢ câu (2+ từ, tức là tra câu) hay chỉ 1 từ (tra từ)?
            val isSentenceHighlight = highlightRange?.let { it.first != it.last } == true
            val isSaved = word.wordId in savedWordIds

            val coreColor = when {
                selected -> MaterialTheme.colorScheme.onPrimary
                isSaved  -> MaterialTheme.colorScheme.tertiary
                else     -> MaterialTheme.colorScheme.primary
            }
            val coreBg = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent

            // Dấu . ? ! chỉ được tô nền khi đang bôi cả câu (tra câu).
            // Khi chỉ chọn riêng từ này (tra từ) thì dấu câu giữ màu chữ thường.
            val punctSelected = selected && isSentenceHighlight
            val punctColor = if (punctSelected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.primary
            val punctBg = if (punctSelected) MaterialTheme.colorScheme.primary else Color.Transparent

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .onGloballyPositioned { c ->
                        // Bounds gộp cả core + punct → hit-test/anchor không đổi so với trước
                        localBounds[index]  = Rect(c.positionInParent(), c.size.toSize())
                        windowBounds[index] = c.boundsInWindow()
                    }
            ) {
                Text(
                    text  = core,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = fontSize.sp),
                    color = coreColor,
                    modifier = Modifier
                        .background(coreBg, RoundedCornerShape(4.dp))
                        .padding(
                            start  = 2.dp,
                            top    = 2.dp,
                            bottom = 2.dp,
                            end    = if (punct.isEmpty()) 2.dp else 0.dp
                        )
                )
                if (punct.isNotEmpty()) {
                    Text(
                        text  = punct,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = fontSize.sp),
                        color = punctColor,
                        modifier = Modifier
                            .background(punctBg, RoundedCornerShape(4.dp))
                            .padding(start = 0.dp, top = 2.dp, bottom = 2.dp, end = 2.dp)
                    )
                }
            }
        }
    }
}