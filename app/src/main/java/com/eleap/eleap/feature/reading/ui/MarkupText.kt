// MarkupText.kt
// Parse chuỗi có ký hiệu markup <...>/[...] (xem quy tắc đánh dấu trọng âm &
// độ dài nguyên âm trong dict.db) thành AnnotatedString để tô màu khi hiển
// thị — dùng chung cho cả cột `ipa` và `word_markup`, vì 2 cột này dùng
// CHUNG 1 cú pháp markup, chỉ khác nội dung gốc bên trong.
//
// Ký hiệu:
//   <...>  → trọng âm chính        → đỏ
//   [...]  → nguyên âm dài          → xanh
//   [<...>] (lồng trọn, trùng khít) → tím (vừa trọng âm vừa dài)
//
// Không phụ thuộc Composable/MaterialTheme để có thể unit-test độc lập —
// màu được truyền vào qua tham số, có default sẵn.
package com.eleap.eleap.feature.reading.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/**
 * Parse 1 chuỗi có ký hiệu markup <...>/[...] thành AnnotatedString có màu.
 * Ký hiệu markup (<, >, [, ]) bị loại bỏ khỏi kết quả hiển thị — chỉ ký tự
 * gốc bên trong được giữ lại, kèm màu tương ứng.
 *
 * @param raw chuỗi gốc có markup (vd "/ɪmˈ[<pɔ>]ːr.tənt/" hoặc "im[<po>]rtant")
 * @param stressColor màu cho vùng chỉ có trọng âm (<...>)
 * @param lengthColor màu cho vùng chỉ có nguyên âm dài ([...])
 * @param bothColor   màu cho vùng vừa trọng âm vừa dài ([<...>] lồng trọn)
 */
fun parseMarkup(
    raw: String?,
    stressColor: Color = Color(0xFFD32F2F), // đỏ
    lengthColor: Color = Color(0xFF1976D2), // xanh dương
    bothColor: Color = Color(0xFF7B1FA2),   // tím
): AnnotatedString {
    if (raw.isNullOrEmpty()) return AnnotatedString(raw ?: "")

    return buildAnnotatedString {
        // Stack lưu loại markup đang "mở" tại vị trí hiện tại:
        // 'A' = đang trong <...> (trọng âm), 'B' = đang trong [...] (độ dài).
        // Dùng stack (thay vì 2 boolean rời) để xử lý đúng nếu có lồng nhau,
        // và để không "nhả" nhầm loại khi gặp dấu đóng không khớp.
        val stack = ArrayDeque<Char>()

        for (ch in raw) {
            when (ch) {
                '<' -> stack.addLast('A')
                '[' -> stack.addLast('B')
                '>' -> if (stack.isNotEmpty() && stack.last() == 'A') stack.removeLast()
                ']' -> if (stack.isNotEmpty() && stack.last() == 'B') stack.removeLast()
                else -> {
                    val hasStress = stack.contains('A')
                    val hasLength = stack.contains('B')
                    val color = when {
                        hasStress && hasLength -> bothColor
                        hasStress -> stressColor
                        hasLength -> lengthColor
                        else -> null
                    }
                    if (color != null) {
                        withStyle(SpanStyle(color = color)) { append(ch) }
                    } else {
                        append(ch)
                    }
                }
            }
        }
    }
}