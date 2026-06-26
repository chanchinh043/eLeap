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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import com.eleap.eleap.feature.reading.ReadingListScreen
import com.eleap.eleap.feature.reading.ReadingScreen
import com.eleap.eleap.feature.vocab.VocabScreen
import com.eleap.eleap.feature.vocab.VocabStudyScreen
import com.eleap.eleap.feature.vocab.VocabReadingScreen
import kotlinx.coroutines.launch

private enum class Screen { MAIN, READING_LIST, READING, VOCAB, VOCAB_STUDY, VOCAB_READING }

// Vuốt trái -> phải qua tỉ lệ này so với chiều rộng màn hình thì "chốt" quay lại.
private const val COMMIT_FRACTION = 0.25f       // giảm từ 0.35 → nhạy hơn
private const val TRANSITION_MS   = 150         // giảm từ 200 → nhanh hơn
private const val FLING_VELOCITY  = 400f        // px/s — kéo nhanh vượt ngưỡng là chuyển ngay

private fun previousScreenOf(screen: Screen): Screen = when (screen) {
    Screen.READING_LIST  -> Screen.MAIN
    Screen.READING       -> Screen.READING_LIST
    Screen.VOCAB         -> Screen.MAIN
    Screen.VOCAB_STUDY   -> Screen.VOCAB
    Screen.VOCAB_READING -> Screen.READING
    Screen.MAIN          -> Screen.MAIN
}

/**
 * Màn hình tiếp theo khi vuốt trái (forward).
 * Trả về null nếu không có màn nào phía trước.
 * READING_LIST → READING  (chỉ khi đã có selectedReadingId)
 * READING      → VOCAB_READING (chỉ khi đã có selectedReadingId)
 */
private fun nextScreenOf(screen: Screen, hasSelectedReading: Boolean): Screen? = when {
    screen == Screen.READING_LIST && hasSelectedReading -> Screen.READING
    screen == Screen.READING      && hasSelectedReading -> Screen.VOCAB_READING
    else -> null
}

@Composable
fun MainScreen() {
    var screen by remember { mutableStateOf(Screen.MAIN) }
    var selectedReadingId by remember { mutableStateOf<Int?>(null) }

    fun goBack() { screen = previousScreenOf(screen) }

    BackHandler(enabled = screen != Screen.MAIN) { goBack() }

    var widthPx by remember { mutableStateOf(0f) }
    // offsetX > 0 : đang kéo phải (back)
    // offsetX < 0 : đang kéo trái (forward → VocabReading)
    val offsetX = remember { Animatable(0f) }
    val scope   = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { widthPx = it.width.toFloat() }
            // Dùng PointerEventPass.Final — chạy SAU khi con (ReadingScreen/FlowRow)
            // đã xử lý xong. Nếu con đã consume (drag-select kéo phải), MainScreen
            // bỏ qua. Nếu con không consume (kéo trái hoặc kéo phải ngoài vùng chữ),
            // MainScreen nhận và thực hiện page transition.
            .pointerInput(screen, selectedReadingId) {
                if (screen == Screen.MAIN) return@pointerInput

                val nextScreen = nextScreenOf(screen, selectedReadingId != null)
                val canGoForward = nextScreen != null

                val slop = viewConfiguration.touchSlop
                val slopSq = slop * slop

                awaitPointerEventScope {
                    while (true) {
                        // ── Chờ ngón tay đặt xuống (Final pass = sau con) ──────
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
                            val event = awaitPointerEvent(PointerEventPass.Final)
                            val change = event.changes.firstOrNull { it.id == down.id }

                            if (change == null || !change.pressed) {
                                // Tính vận tốc khi nhả ngón tay
                                val velocityX = velocityTracker.calculateVelocity().x
                                scope.launch {
                                    when {
                                        // Fling nhanh sang phải → back
                                        velocityX > FLING_VELOCITY -> {
                                            offsetX.animateTo(widthPx, tween(TRANSITION_MS))
                                            goBack()
                                            offsetX.snapTo(0f)
                                        }
                                        // Fling nhanh sang trái → forward
                                        velocityX < -FLING_VELOCITY && canGoForward -> {
                                            offsetX.animateTo(-widthPx, tween(TRANSITION_MS))
                                            screen = nextScreen!!
                                            offsetX.snapTo(0f)
                                        }
                                        // Kéo qua ngưỡng → back
                                        offsetX.value > widthPx * COMMIT_FRACTION -> {
                                            offsetX.animateTo(widthPx, tween(TRANSITION_MS))
                                            goBack()
                                            offsetX.snapTo(0f)
                                        }
                                        // Kéo qua ngưỡng → forward
                                        offsetX.value < -widthPx * COMMIT_FRACTION && canGoForward -> {
                                            offsetX.animateTo(-widthPx, tween(TRANSITION_MS))
                                            screen = nextScreen!!
                                            offsetX.snapTo(0f)
                                        }
                                        // Chưa qua ngưỡng → snap về
                                        else -> offsetX.animateTo(0f, tween(TRANSITION_MS))
                                    }
                                }
                                tracking = false
                                break
                            }

                            // Nếu con đã consume event này (drag-select kéo phải) → dừng
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
                                    // Swipe ngang, con không consume → MainScreen xử lý
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
        // ── Màn nền (sliding-in) — chỉ render khi đang kéo ──────────────────
        if (offsetX.value > 0f) {
            // Kéo phải: màn TRƯỚC trượt vào từ bên trái
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = offsetX.value - widthPx }
            ) {
                ScreenContent(
                    screen            = previousScreenOf(screen),
                    selectedReadingId = selectedReadingId,
                    onNavigateTo      = {},
                    onSelectReading   = {},
                    onBack            = {}
                )
            }
        } else if (offsetX.value < 0f && selectedReadingId != null) {
            // Kéo trái: màn TIẾP THEO trượt vào từ bên phải
            val previewScreen = nextScreenOf(screen, selectedReadingId != null)
            if (previewScreen != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationX = widthPx + offsetX.value }
                ) {
                    ScreenContent(
                        screen            = previewScreen,
                        selectedReadingId = selectedReadingId,
                        onNavigateTo      = {},
                        onSelectReading   = {},
                        onBack            = {}
                    )
                }
            }
        }

        // ── Màn hiện tại — bám ngón tay 1:1 ─────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = offsetX.value }
        ) {
            ScreenContent(
                screen            = screen,
                selectedReadingId = selectedReadingId,
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
            onBack = onBack
        )

        Screen.VOCAB_READING -> VocabReadingScreen(
            readingId = selectedReadingId ?: return,
            onBack    = onBack
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