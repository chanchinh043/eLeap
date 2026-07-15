// TtsVoicePickerScreen.kt
// Đặt tại: com/eleap/eleap/core/tts/ui/TtsVoicePickerScreen.kt
// (đã cập nhật cho kiến trúc đa-vendor: TtsVoiceSnapshot giờ nhớ CẢ vendor
// lẫn sid — xem TtsVoiceSnapshot.kt — và việc enqueue tải gói giờ CHỈ áp
// dụng cho giọng thuộc vendor KOKORO, không phải mọi vendor)
//
// Màn chọn giọng đọc — điểm gọi DUY NHẤT tới
// TtsVoiceSnapshot.setSelectedVoice() trong toàn app. Dùng
// TtsVoiceCatalog.englishVoices làm nguồn danh sách hiển thị — eLeap chỉ
// dạy tiếng Anh nên không cần hiện toàn bộ giọng đủ ngôn ngữ của
// TtsVoiceCatalog.allVoices (danh sách này giờ gộp từ MỌI vendor đã khai
// báo, không chỉ riêng Kokoro).
//
// ⚠️ Tham số readingId là OPTIONAL: màn này có thể mở từ 2 nơi —
//   (a) Từ TRONG 1 bài đọc cụ thể (vd nút "V" ở ReadingScreen) — có
//       readingId, đổi giọng ở đây sẽ (CHỈ khi giọng vừa chọn thuộc vendor
//       KOKORO):
//         • Bài HỆ THỐNG (isMyReading=false): enqueue tải NGAY gói giọng
//           mới qua TtsKokoroPackScheduler.enqueueDownload() — Drive luôn
//           có sẵn mọi giọng từ trước (build batch thủ công), an toàn tải
//           ngay lập tức.
//         • Bài MYREADING (isMyReading=true): TUYỆT ĐỐI KHÔNG gọi
//           enqueueDownload() ở đây — Drive CHẮC CHẮN CHƯA có gói cho
//           giọng vừa chọn (server chưa kịp xử lý). Gọi enqueueDownload()
//           lúc này sẽ khiến TtsKokoroPackDownloader ghi "đã kiểm tra
//           Drive lúc X" cho đúng sid này (dù không tìm thấy gì trên
//           Drive) → khoá 24 GIỜ trước khi syncIfNeeded() chịu hỏi lại
//           Drive, kể cả sau khi server đã upload xong (xem
//           TtsKokoroPackDownloader.CHECK_INTERVAL_MS). Thay vào đó, CHỈ
//           gửi request tổng hợp cho server qua
//           TtsMyReadingSyncTrigger.onVoiceChangedForReading() — việc tải
//           Drive để ReadingScreen (qua TtsMyReadingDownloadGate) hoặc
//           TtsMyReadingPrecacheWorker tự làm SAU KHI server xác nhận
//           READY, không có gì bị khoá trước vì đó là lần gọi
//           enqueueDownload() ĐẦU TIÊN cho sid này.
//   (b) Từ màn cài đặt CHUNG (không gắn với bài nào) — readingId=null, chỉ
//       lưu lựa chọn, KHÔNG enqueue/xin gì cả (không biết bài nào). Gói của
//       các bài khác sẽ tự được tải/xin khi người dùng MỞ bài đó (xem
//       LaunchedEffect(readingId, speechVendor, speechSid) ở
//       ReadingScreen.kt, và TtsMyReadingDownloadGate cho riêng MyReading).
//
// ⚠️ VÌ SAO CHỈ ENQUEUE/XIN KHI VENDOR == KOKORO: TtsKokoroPackScheduler và
// luồng MyReading TTS đều là cơ chế ĐẶC THÙ của riêng Kokoro — 1 nhà cung
// cấp khác (vd dịch vụ synth on-demand) có thể không cần đồng bộ gì cả (tự
// phát trực tiếp hoặc tự cache theo cách riêng khi phát lần đầu), nên KHÔNG
// có khái niệm "enqueue tải gói"/"xin server tổng hợp" tương ứng. Màn này
// KHÔNG gọi 1 "scheduler chung" nào — mỗi vendor tự quyết định cần làm gì
// sau khi được chọn, màn chọn giọng chỉ biết rẽ nhánh theo đúng vendor.
//
// KHÔNG có audio xem trước (preview) ở bước này — bấm chọn xong chỉ lưu +
// enqueue tải (nếu cần), không phát thử ngay (gói vừa chọn thường CHƯA có
// trong cache nên phát thử sẽ chỉ nghe được Android TTS fallback, dễ gây
// hiểu lầm là giọng mới nghe "dở" hơn giọng cũ). Có thể thêm sau nếu cần.
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.eleap.eleap.core.tts.TtsVendor
import com.eleap.eleap.core.tts.TtsVoiceCatalog
import com.eleap.eleap.core.tts.TtsVoiceOption
import com.eleap.eleap.core.tts.TtsVoiceSnapshot
import com.eleap.eleap.core.tts.kokoro.TtsKokoroPackScheduler
import com.eleap.eleap.core.tts.kokoro.myreading.TtsMyReadingSyncTrigger
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsVoicePickerScreen(
    onBack: () -> Unit,
    readingId: String? = null,
    isMyReading: Boolean = false,   // ← MỚI — caller tự xác định, xem ReadingViewModel.isMyReadingId()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ── Trạng thái lựa chọn hiện tại — giờ là CẶP (vendor, sid), không còn
    // chỉ 1 con số sid như bản cũ. So sánh "đang chọn giọng nào" PHẢI xét cả
    // 2 giá trị, vì 2 vendor khác nhau hoàn toàn có thể trùng số sid mà
    // không liên quan gì tới nhau (vd Kokoro sid=0 và 1 vendor khác cũng
    // đánh sid=0 cho giọng đầu tiên của họ). ─────────────────────────────
    var selectedVendor by remember { mutableStateOf(TtsVoiceSnapshot.currentVendor()) }
    var selectedSid by remember { mutableStateOf(TtsVoiceSnapshot.currentSid()) }

    fun onVoiceSelected(voice: TtsVoiceOption) {
        if (voice.vendor == selectedVendor && voice.sid == selectedSid) return   // đã đang chọn đúng giọng này, không làm gì thêm

        TtsVoiceSnapshot.setSelectedVoice(voice.vendor, voice.sid)
        selectedVendor = voice.vendor
        selectedSid = voice.sid

        // ⚠️ Chỉ enqueue/xin khi (a) biết ĐANG đọc bài nào (xem ghi chú đầu
        // file) VÀ (b) giọng vừa chọn thuộc vendor KOKORO.
        if (readingId != null && voice.vendor == TtsVendor.KOKORO) {
            if (isMyReading) {
                // Bài MyReading — CHỈ gửi request tổng hợp cho server, KHÔNG
                // đụng gì tới TtsKokoroPackScheduler ở đây (xem ghi chú ⚠️
                // đầu file — tránh khoá cooldown 24h oan cho sid này).
                scope.launch {
                    TtsMyReadingSyncTrigger.onVoiceChangedForReading(context, readingId)
                }
            } else {
                // Bài hệ thống — Drive luôn có sẵn TOÀN BỘ giọng từ trước,
                // tải ngay an toàn.
                TtsKokoroPackScheduler.enqueueDownload(context, readingId, voice.sid)
            }
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
            // ── key phải là CẶP (vendor, sid) — dùng riêng sid làm key như
            // bản cũ sẽ đụng độ nếu 2 vendor cùng đánh trùng số sid. ────────
            items(TtsVoiceCatalog.englishVoices, key = { "${it.vendor}_${it.sid}" }) { voice ->
                VoiceRow(
                    voice      = voice,
                    isSelected = voice.vendor == selectedVendor && voice.sid == selectedSid,
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