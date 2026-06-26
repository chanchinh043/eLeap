// MainScreen.kt
package com.eleap.eleap

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.eleap.eleap.feature.reading.ReadingListScreen
import com.eleap.eleap.feature.reading.ReadingScreen
import com.eleap.eleap.feature.vocab.VocabScreen
import com.eleap.eleap.feature.vocab.VocabStudyScreen
import com.eleap.eleap.feature.vocab.VocabReadingScreen
import kotlinx.coroutines.launch

private enum class Screen { MAIN, READING_LIST, READING, VOCAB, VOCAB_STUDY, VOCAB_READING }

// Vuốt trái -> phải qua tỉ lệ này so với chiều rộng màn hình thì "chốt" quay lại.
private const val COMMIT_FRACTION = 0.35f
private const val TRANSITION_MS = 200

private fun previousScreenOf(screen: Screen): Screen = when (screen) {
    Screen.READING_LIST -> Screen.MAIN
    Screen.READING      -> Screen.READING_LIST
    Screen.VOCAB        -> Screen.MAIN
    Screen.VOCAB_STUDY  -> Screen.VOCAB
    Screen.VOCAB_READING -> Screen.READING
    Screen.MAIN         -> Screen.MAIN
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
            .pointerInput(screen, selectedReadingId) {
                if (screen == Screen.MAIN) return@pointerInput

                // Có thể vuốt sang VocabReading khi: đã có bài đọc VÀ chưa ở VocabReading
                val canGoVocabReading = selectedReadingId != null
                        && screen != Screen.VOCAB_READING

                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        scope.launch {
                            val next = offsetX.value + dragAmount
                            val clamped = when {
                                next > 0f             -> next   // kéo phải — back (luôn cho phép)
                                next < 0f && canGoVocabReading -> next   // kéo trái — VocabReading
                                else                  -> 0f
                            }
                            offsetX.snapTo(clamped)
                        }
                    },
                    onDragEnd = {
                        scope.launch {
                            when {
                                // Kéo phải đủ xa → back
                                offsetX.value > widthPx * COMMIT_FRACTION -> {
                                    offsetX.animateTo(widthPx, tween(TRANSITION_MS))
                                    goBack()
                                    offsetX.snapTo(0f)
                                }
                                // Kéo trái đủ xa → VocabReading
                                offsetX.value < -widthPx * COMMIT_FRACTION -> {
                                    offsetX.animateTo(-widthPx, tween(TRANSITION_MS))
                                    screen = Screen.VOCAB_READING
                                    offsetX.snapTo(0f)
                                }
                                // Chưa đủ xa → bật về 0
                                else -> offsetX.animateTo(0f, tween(TRANSITION_MS))
                            }
                        }
                    },
                    onDragCancel = {
                        scope.launch { offsetX.animateTo(0f, tween(TRANSITION_MS)) }
                    }
                )
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
            // Kéo trái: VocabReadingScreen trượt vào từ bên phải
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = widthPx + offsetX.value }
            ) {
                ScreenContent(
                    screen            = Screen.VOCAB_READING,
                    selectedReadingId = selectedReadingId,
                    onNavigateTo      = {},
                    onSelectReading   = {},
                    onBack            = {}
                )
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