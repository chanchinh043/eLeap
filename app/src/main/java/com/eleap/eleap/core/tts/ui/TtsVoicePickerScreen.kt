// TtsVoicePickerScreen.kt
// Đặt tại: com/eleap/eleap/core/tts/ui/TtsVoicePickerScreen.kt
//
// Màn chọn giọng đọc — điểm gọi DUY NHẤT tới
// TtsVoiceSnapshot.setSelectedSid() trong toàn app (trước đây KHÔNG có nơi
// nào gọi hàm này, sid luôn cố định ở DEFAULT_SID=0, xem ghi chú review
// trước đó). Dùng TtsVoiceCatalog.englishVoices làm nguồn danh sách hiển
// thị — eLeap chỉ dạy tiếng Anh nên không cần hiện 53 giọng đủ ngôn ngữ của
// TtsVoiceCatalog.allVoices.
//
// ⚠️ Tham số readingId là OPTIONAL: màn này có thể mở từ 2 nơi —
//   (a) Từ TRONG 1 bài đọc cụ thể (vd nút cài đặt ở ReadingScreen) — có
//       readingId, đổi giọng ở đây sẽ enqueue tải NGAY gói giọng mới cho
//       đúng bài đang đọc, để cache có sẵn kịp lúc quay lại đọc tiếp.
//   (b) Từ màn cài đặt CHUNG (không gắn với bài nào) — readingId=null, chỉ
//       lưu lựa chọn, KHÔNG enqueue gì cả (không biết tải gói cho bài nào).
//       Gói của các bài khác sẽ tự được tải khi người dùng MỞ bài đó (xem
//       LaunchedEffect(readingId, speechSid) ở ReadingScreen.kt).
//
// KHÔNG có audio xem trước (preview) ở bước này — bấm chọn xong chỉ lưu +
// enqueue tải, không phát thử ngay (gói vừa chọn thường CHƯA có trong cache
// nên phát thử sẽ chỉ nghe được Android TTS fallback, dễ gây hiểu lầm là
// giọng mới nghe "dở" hơn giọng cũ). Có thể thêm sau nếu cần.
package com.eleap.eleap.core.tts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.eleap.eleap.core.tts.TtsVoiceCatalog
import com.eleap.eleap.core.tts.TtsVoiceOption
import com.eleap.eleap.core.tts.TtsVoiceSnapshot
import com.eleap.eleap.core.tts.remote.TtsRemotePackScheduler

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsVoicePickerScreen(
    onBack: () -> Unit,
    readingId: String? = null,
) {
    val context = LocalContext.current
    var selectedSid by remember { mutableStateOf(TtsVoiceSnapshot.currentSid()) }

    fun onVoiceSelected(voice: TtsVoiceOption) {
        if (voice.sid == selectedSid) return   // đã đang chọn đúng giọng này, không làm gì thêm

        TtsVoiceSnapshot.setSelectedSid(voice.sid)
        selectedSid = voice.sid

        // ⚠️ Chỉ enqueue khi biết ĐANG đọc bài nào (xem ghi chú đầu file) —
        // TtsVoiceSnapshot không tự làm việc này (đã bỏ từ bước tách bạch
        // trách nhiệm, xem TtsVoiceSnapshot.kt).
        if (readingId != null) {
            TtsRemotePackScheduler.enqueueDownload(context, readingId, voice.sid)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chọn giọng đọc") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(vertical = 8.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            items(TtsVoiceCatalog.englishVoices, key = { it.sid }) { voice ->
                VoiceRow(
                    voice      = voice,
                    isSelected = voice.sid == selectedSid,
                    onClick    = { onVoiceSelected(voice) }
                )
            }
        }
    }
}

@Composable
private fun VoiceRow(
    voice: TtsVoiceOption,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                else MaterialTheme.colorScheme.surface
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = voice.displayName,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (isSelected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Đang chọn",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}