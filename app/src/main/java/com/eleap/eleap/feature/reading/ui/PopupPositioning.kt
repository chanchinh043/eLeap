// PopupPositioning.kt
package com.eleap.eleap.feature.reading.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import kotlin.math.roundToInt

/**
 * Thông tin "mỏ neo" để định vị popup:
 * - rect: khung bao (toạ độ window) của từ/câu đang được chọn — có thể là hợp
 *   nhiều dòng nếu câu wrap qua >1 dòng.
 * - lineHeightPx: chiều cao thực tế của 1 dòng chữ (đo từ chính chữ đang hiển thị),
 *   dùng làm khoảng đệm khi popup phải hiện ở dưới, để không che dòng kế tiếp.
 */
data class PopupAnchorInfo(
    val rect: Rect,
    val lineHeightPx: Float,
)

/**
 * Định vị popup theo nguyên tắc:
 * 1) Ưu tiên hiện TRÊN vùng được chọn (không che nội dung sắp đọc ở dưới,
 *    không bị ngón tay che khi vừa chạm).
 * 2) Nếu không đủ chỗ phía trên → lật XUỐNG dưới, chừa thêm 1 dòng (lineHeightPx)
 *    để vẫn không che mất dòng chữ ngay sau vùng chọn.
 * 3) Nếu cả trên và dưới đều không đủ chỗ → ghim vào mép còn nhiều khoảng trống
 *    hơn; Card đã có heightIn(max=...) + verticalScroll nên nội dung dư tự cuộn.
 */
class SmartPopupPositionProvider(
    private val anchor: PopupAnchorInfo,
    private val viewportRect: Rect,
    private val spacingPx: Float,
) : PopupPositionProvider {

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val rect = anchor.rect
        val gapBelow = anchor.lineHeightPx + spacingPx

        val spaceAbove = rect.top - viewportRect.top
        val spaceBelow = viewportRect.bottom - rect.bottom - gapBelow

        val y = when {
            // Ưu tiên 1: đủ chỗ phía TRÊN → đặt trên, chỉ cần khoảng đệm nhỏ
            spaceAbove >= popupContentSize.height ->
                rect.top - spacingPx - popupContentSize.height
            // Ưu tiên 2: không đủ trên → lật xuống dưới, chừa thêm 1 dòng
            spaceBelow >= popupContentSize.height ->
                rect.bottom + gapBelow
            // Ưu tiên 3: cả hai đều thiếu → ghim vào mép rộng hơn, để Card tự cuộn
            spaceAbove >= spaceBelow ->
                viewportRect.top
            else ->
                viewportRect.bottom - popupContentSize.height
        }

        val centerX = (rect.left + rect.right) / 2f - popupContentSize.width / 2f
        val minX = viewportRect.left
        val maxX = maxOf(minX, viewportRect.right - popupContentSize.width)
        val x = centerX.coerceIn(minX, maxX)

        return IntOffset(x.roundToInt(), y.roundToInt())
    }
}

/** Fallback khi chưa đo được vị trí (gần như chỉ xảy ra ở frame đầu) — giữ hành vi cũ: giữa, đáy màn hình. */
object FallbackBottomCenterPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset(
        x = (windowSize.width - popupContentSize.width) / 2,
        y = windowSize.height - popupContentSize.height,
    )
}