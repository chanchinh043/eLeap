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
import kotlinx.coroutines.launch

private enum class Screen { MAIN, READING_LIST, READING, VOCAB, VOCAB_STUDY }

// Vuốt trái -> phải qua tỉ lệ này so với chiều rộng màn hình thì "chốt" quay lại.
private const val COMMIT_FRACTION = 0.35f
private const val TRANSITION_MS = 200

private fun previousScreenOf(screen: Screen): Screen = when (screen) {
    Screen.READING_LIST -> Screen.MAIN
    Screen.READING -> Screen.READING_LIST
    Screen.VOCAB -> Screen.MAIN
    Screen.VOCAB_STUDY -> Screen.VOCAB
    Screen.MAIN -> Screen.MAIN
}

@Composable
fun MainScreen() {
    var screen by remember { mutableStateOf(Screen.MAIN) }
    var selectedReadingId by remember { mutableStateOf<Int?>(null) }

    fun goBack() {
        screen = previousScreenOf(screen)
    }

    // Bắt nút/gesture Back của hệ thống (vật lý hoặc cử chỉ Android).
    BackHandler(enabled = screen != Screen.MAIN) { goBack() }

    var widthPx by remember { mutableStateOf(0f) }
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(screen) {
                // Đang ở màn MAIN thì không có gì để quay lại -> không cần lắng nghe vuốt.
                if (screen == Screen.MAIN) return@pointerInput

                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        scope.launch {
                            // Chỉ cho kéo theo hướng trái -> phải;
                            // coerceAtLeast(0f) chặn hẳn hướng phải -> trái, không cho offset xuống dưới 0.
                            offsetX.snapTo((offsetX.value + dragAmount).coerceAtLeast(0f))
                        }
                    },
                    onDragEnd = {
                        scope.launch {
                            if (offsetX.value > widthPx * COMMIT_FRACTION) {
                                // Kéo đủ xa -> hoàn tất animation rồi mới đổi màn, để không bị giật.
                                offsetX.animateTo(widthPx, tween(TRANSITION_MS))
                                screen = previousScreenOf(screen)
                                offsetX.snapTo(0f)
                            } else {
                                // Chưa đủ xa -> bật ngược lại vị trí ban đầu.
                                offsetX.animateTo(0f, tween(TRANSITION_MS))
                            }
                        }
                    },
                    onDragCancel = {
                        scope.launch { offsetX.animateTo(0f, tween(TRANSITION_MS)) }
                    }
                )
            }
    ) {
        val isDragging = screen != Screen.MAIN && offsetX.value != 0f

        // Màn "chuẩn bị đến" - chỉ render trong lúc kéo, trượt vào dần từ bên trái đúng theo khoảng kéo.
        if (isDragging) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = offsetX.value - widthPx }
            ) {
                ScreenContent(
                    screen = previousScreenOf(screen),
                    selectedReadingId = selectedReadingId,
                    onNavigateTo = {},
                    onSelectReading = {},
                    onBack = {}
                )
            }
        }

        // Màn hiện tại - luôn bám theo ngón tay 1:1.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = offsetX.value }
        ) {
            ScreenContent(
                screen = screen,
                selectedReadingId = selectedReadingId,
                onNavigateTo = { screen = it },
                onSelectReading = { id ->
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