// MainScreen.kt
package com.eleap.eleap

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.abs
import com.eleap.eleap.feature.reading.ReadingListScreen
import com.eleap.eleap.feature.reading.ReadingScreen
import com.eleap.eleap.feature.vocab.VocabScreen
import com.eleap.eleap.feature.vocab.VocabStudyScreen
import com.eleap.eleap.feature.vocab.VocabReadingScreen
import com.eleap.eleap.feature.vocab.VocabViewModel
import com.eleap.eleap.feature.reading.ui.UserVocabularyEntry
import kotlinx.coroutines.launch

private enum class Screen {
    MAIN,
    READING_LIST,
    READING,
    VOCAB,
    VOCAB_STUDY,
    VOCAB_READING,
    VOCAB_READING_STUDY,
}

private const val COMMIT_FRACTION = 0.25f
private const val TRANSITION_MS   = 150
private const val FLING_VELOCITY  = 400f

private fun previousScreenOf(screen: Screen): Screen = when (screen) {
    Screen.READING_LIST        -> Screen.MAIN
    Screen.READING             -> Screen.READING_LIST
    Screen.VOCAB               -> Screen.MAIN
    Screen.VOCAB_STUDY         -> Screen.VOCAB
    Screen.VOCAB_READING       -> Screen.READING
    Screen.VOCAB_READING_STUDY -> Screen.VOCAB_READING
    Screen.MAIN                -> Screen.MAIN
}

private fun nextScreenOf(
    screen: Screen,
    hasSelectedReading: Boolean,
    hasReadingStudyWords: Boolean = false,
): Screen? = when {
    screen == Screen.READING_LIST  && hasSelectedReading   -> Screen.READING
    screen == Screen.READING       && hasSelectedReading   -> Screen.VOCAB_READING
    screen == Screen.VOCAB_READING && hasReadingStudyWords -> Screen.VOCAB_READING_STUDY
    else -> null
}

@Composable
fun MainScreen() {
    var screen by remember { mutableStateOf(Screen.MAIN) }
    var selectedReadingId by remember { mutableStateOf<Int?>(null) }

    // ── Lấy VM ở đây (root), truyền xuống qua tham số — tránh duplicate class ─
    val context  = LocalContext.current
    val activity = context as ComponentActivity
    val vm: VocabViewModel = viewModel(
        viewModelStoreOwner = activity,
        factory = VocabViewModel.Factory(context)
    )
    val vocabList        by vm.vocabList.collectAsState()
    val readingVocabList by vm.readingVocabList.collectAsState()

    fun goBack() { screen = previousScreenOf(screen) }

    BackHandler(enabled = screen != Screen.MAIN) { goBack() }

    var widthPx by remember { mutableStateOf(0f) }
    val offsetX = remember { Animatable(0f) }
    val scope   = rememberCoroutineScope()

    suspend fun animateForward(nextScreen: Screen) {
        offsetX.animateTo(-widthPx, tween(TRANSITION_MS))
        screen = nextScreen
        offsetX.snapTo(0f)
    }

    suspend fun animateBack() {
        offsetX.animateTo(widthPx, tween(TRANSITION_MS))
        goBack()
        offsetX.snapTo(0f)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(screen, selectedReadingId, readingVocabList) {
                if (screen == Screen.MAIN) return@pointerInput

                val hasReadingStudyWords = readingVocabList.any { it.selected == 1 }
                val nextScreen   = nextScreenOf(
                    screen               = screen,
                    hasSelectedReading   = selectedReadingId != null,
                    hasReadingStudyWords = hasReadingStudyWords,
                )
                val canGoForward = nextScreen != null
                val slop         = viewConfiguration.touchSlop
                val slopSq       = slop * slop

                awaitPointerEventScope {
                    while (true) {
                        val down = awaitPointerEvent(PointerEventPass.Final)
                            .changes.firstOrNull { it.pressed } ?: continue
                        if (down.isConsumed) continue

                        val startX = down.position.x
                        val startY = down.position.y
                        var directionLocked: Boolean? = null
                        var tracking = true
                        val velocityTracker = VelocityTracker()
                        velocityTracker.addPointerInputChange(down)

                        while (tracking) {
                            val event  = awaitPointerEvent(PointerEventPass.Final)
                            val change = event.changes.firstOrNull { it.id == down.id }

                            if (change == null || !change.pressed) {
                                val velocityX = velocityTracker.calculateVelocity().x
                                scope.launch {
                                    when {
                                        velocityX > FLING_VELOCITY ->
                                            animateBack()
                                        velocityX < -FLING_VELOCITY && canGoForward ->
                                            animateForward(nextScreen!!)
                                        offsetX.value > widthPx * COMMIT_FRACTION ->
                                            animateBack()
                                        offsetX.value < -widthPx * COMMIT_FRACTION && canGoForward ->
                                            animateForward(nextScreen!!)
                                        else ->
                                            offsetX.animateTo(0f, tween(TRANSITION_MS))
                                    }
                                }
                                tracking = false
                                break
                            }

                            if (change.isConsumed) {
                                scope.launch { offsetX.animateTo(0f, tween(TRANSITION_MS)) }
                                tracking = false
                                break
                            }

                            velocityTracker.addPointerInputChange(change)

                            val dx = change.position.x - startX
                            val dy = change.position.y - startY

                            if (directionLocked == null && (dx * dx + dy * dy) > slopSq) {
                                directionLocked = abs(dx) >= abs(dy)
                            }

                            when (directionLocked) {
                                true -> {
                                    val dragAmount = change.position.x - change.previousPosition.x
                                    val next = offsetX.value + dragAmount
                                    val clamped = when {
                                        next > 0f -> next
                                        next < 0f && canGoForward -> next
                                        else -> 0f
                                    }
                                    if (clamped != 0f || offsetX.value != 0f) {
                                        change.consume()
                                        scope.launch { offsetX.snapTo(clamped) }
                                    }
                                }
                                false -> {
                                    scope.launch { offsetX.animateTo(0f, tween(TRANSITION_MS)) }
                                    tracking = false
                                }
                                null -> Unit
                            }
                        }
                    }
                }
            }
    ) {
        // ── Màn TRƯỚC — chỉ render khi đang kéo phải ─────────────────────────
        if (offsetX.value > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = offsetX.value - widthPx }
            ) {
                ScreenContent(
                    screen            = previousScreenOf(screen),
                    selectedReadingId = selectedReadingId,
                    vocabStudyPool    = vocabList.filter { it.selected == 1 },
                    readingStudyPool  = readingVocabList.filter { it.selected == 1 },
                    onNavigateTo      = {},
                    onSelectReading   = {},
                    onBack            = {}
                )
            }
        }

        // ── Màn TIẾP THEO — pre-render ngầm ──────────────────────────────────
        val previewScreen = nextScreenOf(
            screen               = screen,
            hasSelectedReading   = selectedReadingId != null,
            hasReadingStudyWords = readingVocabList.any { it.selected == 1 },
        )
        if (previewScreen != null && selectedReadingId != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = widthPx + offsetX.value
                        alpha        = if (offsetX.value < 0f) 1f else 0f
                    }
            ) {
                ScreenContent(
                    screen            = previewScreen,
                    selectedReadingId = selectedReadingId,
                    vocabStudyPool    = vocabList.filter { it.selected == 1 },
                    readingStudyPool  = readingVocabList.filter { it.selected == 1 },
                    onNavigateTo      = {},
                    onSelectReading   = {},
                    onBack            = {}
                )
            }
        }

        // ── Màn HIỆN TẠI ─────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = offsetX.value }
        ) {
            ScreenContent(
                screen            = screen,
                selectedReadingId = selectedReadingId,
                vocabStudyPool    = vocabList.filter { it.selected == 1 },
                readingStudyPool  = readingVocabList.filter { it.selected == 1 },
                onNavigateTo      = { screen = it },
                onSelectReading   = { id ->
                    selectedReadingId = id
                    screen = Screen.READING
                },
                onBack = { goBack() }
            )
        }
    }
}

@Composable
private fun ScreenContent(
    screen: Screen,
    selectedReadingId: Int?,
    vocabStudyPool: List<UserVocabularyEntry>,   // pool cho VOCAB_STUDY
    readingStudyPool: List<UserVocabularyEntry>,  // pool cho VOCAB_READING_STUDY
    onNavigateTo: (Screen) -> Unit,
    onSelectReading: (Int) -> Unit,
    onBack: () -> Unit,
) {
    when (screen) {
        Screen.MAIN -> MainContent(
            onReadingClick = { onNavigateTo(Screen.READING_LIST) },
            onVocabClick   = { onNavigateTo(Screen.VOCAB) }
        )

        Screen.READING_LIST -> ReadingListScreen(
            onBack         = onBack,
            onReadingClick = { readingId -> onSelectReading(readingId) }
        )

        Screen.READING -> ReadingScreen(
            readingId = selectedReadingId ?: return,
            onBack    = onBack
        )

        Screen.VOCAB -> VocabScreen(
            onBack       = onBack,
            onStudyClick = { onNavigateTo(Screen.VOCAB_STUDY) }
        )

        Screen.VOCAB_STUDY -> VocabStudyScreen(
            pool   = vocabStudyPool,
            onBack = onBack
        )

        Screen.VOCAB_READING -> VocabReadingScreen(
            readingId    = selectedReadingId ?: return,
            onBack       = onBack,
            onStudyClick = { onNavigateTo(Screen.VOCAB_READING_STUDY) }
        )

        Screen.VOCAB_READING_STUDY -> VocabStudyScreen(
            pool   = readingStudyPool,
            onBack = onBack
        )
    }
}

@Composable
private fun MainContent(
    onReadingClick: () -> Unit,
    onVocabClick: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Button(onClick = onReadingClick) { Text("Reading") }
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = onVocabClick) { Text("Ôn từ vựng") }
    }
}