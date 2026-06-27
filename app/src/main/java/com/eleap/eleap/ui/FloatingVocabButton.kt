// FloatingVocabButton.kt
// Đặt tại: com/eleap/eleap/ui/FloatingVocabButton.kt
//
// Nút nổi có thể kéo thả tự do trên màn hình.
// - Bấm   → toggle giữa ReadingScreen ↔ VocabReadingScreen
// - Giữ   → kích hoạt chế độ kéo (hiện vùng X ở đáy màn)
// - Kéo vào vùng X → ẩn nút (dismiss)
// - Vị trí được ghi nhớ trong session (reset khi app khởi động lại)

package com.eleap.eleap.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// ── Kích thước vùng X ở đáy màn ─────────────────────────────────────────────
private val DROP_ZONE_HEIGHT = 80.dp
private val DROP_ZONE_WIDTH  = 120.dp
private val BUTTON_SIZE      = 52.dp

@Composable
fun FloatingVocabButton(
    isOnVocabScreen: Boolean,     // true nếu đang ở VocabReadingScreen
    onToggle: () -> Unit,          // callback toggle Reading ↔ VocabReading
) {
    // ── Trạng thái vị trí nút ─────────────────────────────────────────────────
    // Khởi tạo ở góc phải, cách đáy 200dp — sẽ tính px sau khi có density
    var offsetX by remember { mutableFloatStateOf(-1f) }  // -1 = chưa init
    var offsetY by remember { mutableFloatStateOf(-1f) }

    // ── Chế độ kéo (long press kích hoạt) ────────────────────────────────────
    var isDragging by remember { mutableStateOf(false) }

    // ── Ẩn/hiện nút (kéo vào vùng X thì dismiss) ─────────────────────────────
    var isVisible by remember { mutableStateOf(true) }

    // ── Kích thước màn hình (lấy từ BoxWithConstraints) ──────────────────────
    var screenWidthPx  by remember { mutableFloatStateOf(0f) }
    var screenHeightPx by remember { mutableFloatStateOf(0f) }

    // ── Vị trí nút trong root — dùng để kiểm tra có vào vùng X không ─────────
    var buttonPositionInRoot by remember { mutableStateOf(Offset.Zero) }

    val density = LocalDensity.current

    // ── Hiệu ứng scale khi kéo ───────────────────────────────────────────────
    val buttonScale by animateFloatAsState(
        targetValue = if (isDragging) 1.15f else 1f,
        animationSpec = tween(150),
        label = "buttonScale"
    )

    // ── Drop zone bounds (tính theo px trong root) ───────────────────────────
    // Vùng X nằm giữa-đáy màn hình
    val dropZoneHeightPx = with(density) { DROP_ZONE_HEIGHT.toPx() }
    val dropZoneWidthPx  = with(density) { DROP_ZONE_WIDTH.toPx() }
    val buttonSizePx     = with(density) { BUTTON_SIZE.toPx() }

    // Trả true nếu tâm nút đang nằm trong vùng X
    fun isOverDropZone(): Boolean {
        if (screenWidthPx == 0f || screenHeightPx == 0f) return false
        val btnCenterX = buttonPositionInRoot.x + buttonSizePx / 2
        val btnCenterY = buttonPositionInRoot.y + buttonSizePx / 2
        val zoneLeft   = (screenWidthPx - dropZoneWidthPx) / 2
        val zoneRight  = zoneLeft + dropZoneWidthPx
        val zoneTop    = screenHeightPx - dropZoneHeightPx
        return btnCenterX in zoneLeft..zoneRight && btnCenterY >= zoneTop
    }

    // ── Màu nút đổi khi đang kéo vào vùng X ─────────────────────────────────
    val overDrop = isDragging && isOverDropZone()

    Box(modifier = Modifier.fillMaxSize()) {

        // ── Lấy kích thước màn hình ───────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coords ->
                    screenWidthPx  = coords.size.width.toFloat()
                    screenHeightPx = coords.size.height.toFloat()
                    // Init vị trí nút lần đầu: góc phải, cách đáy ~200dp
                    if (offsetX < 0f) {
                        val margin = with(density) { 16.dp.toPx() }
                        val bottom200 = with(density) { 200.dp.toPx() }
                        offsetX = coords.size.width - buttonSizePx - margin
                        offsetY = coords.size.height - bottom200
                    }
                }
        )

        // ── Vùng X (chỉ hiện khi đang kéo) ──────────────────────────────────
        AnimatedVisibility(
            visible = isDragging,
            enter   = fadeIn(tween(200)) + scaleIn(tween(200)),
            exit    = fadeOut(tween(150)) + scaleOut(tween(150)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Box(
                modifier = Modifier
                    .padding(bottom = 24.dp)
                    .size(width = DROP_ZONE_WIDTH, height = DROP_ZONE_HEIGHT)
                    .background(
                        color = if (overDrop)
                            MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                        else
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                        shape = RoundedCornerShape(40.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Xoá nút",
                        tint = if (overDrop)
                            MaterialTheme.colorScheme.onError
                        else
                            MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(28.dp)
                    )
                    Text(
                        text  = "Thả để ẩn",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (overDrop)
                            MaterialTheme.colorScheme.onError
                        else
                            MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        // ── Nút nổi ──────────────────────────────────────────────────────────
        if (isVisible && offsetX >= 0f) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                    .size(BUTTON_SIZE)
                    .scale(buttonScale)
                    .shadow(elevation = if (isDragging) 12.dp else 6.dp, shape = CircleShape)
                    .background(
                        color = if (isOnVocabScreen)
                            MaterialTheme.colorScheme.secondary
                        else
                            MaterialTheme.colorScheme.primary,
                        shape = CircleShape
                    )
                    .onGloballyPositioned { coords ->
                        buttonPositionInRoot = coords.positionInRoot()
                    }
                    .pointerInput(Unit) {
                        // ── Dùng detectTapGestures cho tap + longPress ────────
                        detectTapGestures(
                            onTap = {
                                if (!isDragging) onToggle()
                            },
                            onLongPress = {
                                isDragging = true
                            },
                            onPress = { _ ->
                                // Không làm gì — chờ long press hoặc tap
                            }
                        )
                    }
                    .pointerInput(isDragging) {
                        // ── Gesture kéo — chỉ hoạt động sau khi isDragging=true ─
                        if (!isDragging) return@pointerInput
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (!change.pressed) {
                                    // Thả tay
                                    if (isOverDropZone()) {
                                        isVisible  = false
                                    }
                                    isDragging = false
                                    break
                                }
                                // Di chuyển nút theo ngón tay
                                val delta = change.position - change.previousPosition
                                val newX = (offsetX + delta.x).coerceIn(
                                    0f, screenWidthPx - buttonSizePx
                                )
                                val newY = (offsetY + delta.y).coerceIn(
                                    0f, screenHeightPx - buttonSizePx
                                )
                                offsetX = newX
                                offsetY = newY
                                change.consume()
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector        = Icons.Filled.List,
                    contentDescription = if (isOnVocabScreen) "Về bài đọc" else "Xem từ vựng",
                    tint               = if (isOnVocabScreen)
                        MaterialTheme.colorScheme.onSecondary
                    else
                        MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}