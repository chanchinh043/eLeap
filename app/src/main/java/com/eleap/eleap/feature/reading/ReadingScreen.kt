package com.eleap.eleap.feature.reading

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eleap.eleap.core.tts.TtsManager
import com.eleap.eleap.core.tts.TtsVendor
import com.eleap.eleap.core.tts.TtsVoiceSnapshot
import com.eleap.eleap.core.tts.kokoro.TtsKokoroPackScheduler
import com.eleap.eleap.core.tts.kokoro.myreading.TtsMyReadingDownloadGate
import com.eleap.eleap.feature.reading.ui.PhrasePopup
import com.eleap.eleap.feature.reading.ui.PopupAnchorInfo
import com.eleap.eleap.feature.reading.ui.SentencePopup
import com.eleap.eleap.feature.reading.ui.WordClickableRow
import com.eleap.eleap.feature.reading.ui.WordPopup

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingScreen(
    readingId: String,
    onBack: () -> Unit,
    onOpenVoicePicker: () -> Unit,
) {
    val context = LocalContext.current
    val vm: ReadingViewModel = viewModel(factory = ReadingViewModel.Factory(context))
    val sentences         by vm.sentences.collectAsState()
    val isLoading         by vm.isLoadingReading.collectAsState()
    val selectedWord      by vm.selectedWord.collectAsState()
    val selectedPhrase    by vm.selectedPhrase.collectAsState()   // dùng chung cho WordPopup context & PhrasePopup độc lập
    val selectedSentence  by vm.selectedSentence.collectAsState()
    val selectedDictEntry by vm.selectedDictEntry.collectAsState()
    val isDictExpanded    by vm.isDictExpanded.collectAsState()
    val savedWordIds      by vm.savedWordIds.collectAsState()

    val prefs = remember { context.getSharedPreferences("reading_settings", android.content.Context.MODE_PRIVATE) }
    var fontSize by remember { mutableStateOf(prefs.getInt("font_size", 16)) }

    // ── Tốc độ đọc (TTS) — đọc giá trị đã lưu từ TtsManager (persist qua
    // SharedPreferences riêng của nó, xem TtsManager.kt), không dùng chung
    // `prefs` (reading_settings) vì tốc độ đọc là thuộc tính CHUNG toàn app,
    // không riêng cho reading. ────────────────────────────────────────────
    var speechRate by remember { mutableStateOf(TtsManager.getSpeechRate()) }

    // ── Toggle hiển thị: false = đang hiện cụm chỉnh cỡ chữ (-/16/+),
    // true = đang hiện cụm chỉnh tốc độ đọc (-/1.0x/+). Bấm nút "R" để
    // chuyển qua lại — không lưu trạng thái này (luôn về lại font size mỗi
    // khi mở lại màn hình, đúng hành vi mặc định quen thuộc). ─────────────
    var showSpeedControl by remember { mutableStateOf(false) }

    // ── Chế độ dịch khi kéo bôi đen ≥2 từ: "S" = dịch câu, "P" = dịch cụm từ ──
    var translateMode by remember { mutableStateOf(prefs.getString("translate_mode", "S") ?: "S") }

    // ── Chế độ hiển thị cụm từ, chỉ áp dụng khi translateMode == "P":
    //    "underline" = gạch chân nhẹ dưới các từ cùng phrase, vẫn chảy chữ bình thường
    //    "line"      = mỗi phrase xuống 1 dòng riêng
    var phraseFormat by remember { mutableStateOf(prefs.getString("phrase_format", "underline") ?: "underline") }

    var anchorInfo   by remember { mutableStateOf<PopupAnchorInfo?>(null) }
    var viewportRect by remember { mutableStateOf<Rect?>(null) }

    LaunchedEffect(readingId) {
        vm.loadReading(readingId)
    }

    // ── Enqueue tải giọng Kokoro cho bài đang mở, ưu tiên giọng đang chọn
    // tải TRƯỚC — TtsVoiceSnapshot KHÔNG còn tự enqueue khi đổi giọng (xem
    // TtsVoiceSnapshot.kt) — nơi mở bài đọc PHẢI tự gọi việc này. Gọi lại
    // mỗi khi readingId HOẶC speechVendor/speechSid đổi (vd người dùng đổi
    // giọng ngay trong lúc đang đọc bài).
    //
    // ⚠️ CHỈ enqueue khi vendor đang chọn là KOKORO — TtsKokoroPackScheduler
    // là cơ chế đồng bộ ĐẶC THÙ của riêng Kokoro (tải gói .zip pregenerated
    // từ Drive). Nếu sau này có vendor khác (vd dịch vụ synth on-demand) mà
    // người dùng đang chọn, KHÔNG enqueue gì ở đây — vendor đó (nếu cần) tự
    // có cơ chế riêng trong thư mục của nó, không đi qua scheduler này.
    val speechVendor = remember { TtsVoiceSnapshot.currentVendor() }
    val speechSid = remember { TtsVoiceSnapshot.currentSid() }

    // ⚠️ GATE CHO MYREADING (xem TtsMyReadingDownloadGate.kt): bài MyReading
    // tổng hợp audio BẤT ĐỒNG BỘ ở server — nếu gọi thẳng bất kỳ hàm tải
    // Drive nào ngay cả khi server CHƯA xử lý xong, nó sẽ hiểu nhầm "Drive
    // không có gì" thành "đã xử lý xong". Gate hỏi thẳng server (không phải
    // Drive) xem job có READY chưa; false thì bỏ qua lượt này, thử lại lần
    // mở bài sau — KHÔNG có gì bị mất, chỉ đơn giản là chưa enqueue lần này.
    //
    // Với bài HỆ THỐNG (isMyReading=false) hoặc khi tính năng MyReading TTS
    // chưa cấu hình (TtsMyReadingConfig.baseUrl()==null), gate trả về true
    // NGAY LẬP TỨC — giữ nguyên 100% hành vi cũ, không có gì thay đổi.
    //
    // key thêm `sentences`: cần đợi sentences load xong (gate tự return
    // false nếu rỗng) VÀ tự chạy lại gate khi nội dung bài đổi (vd AI vừa
    // ghi xong phrase/word, sentences reload) — trường hợp lần mở bài đầu
    // tiên rơi đúng lúc AI chưa xong thì lần reload sau đó sẽ tự thử lại.
    LaunchedEffect(readingId, speechVendor, speechSid, sentences) {
        if (speechVendor == TtsVendor.KOKORO && sentences.isNotEmpty()) {
            val isMyReading = vm.isMyReadingId(readingId)
            val canProceed = TtsMyReadingDownloadGate.shouldProceedToDriveSync(
                context     = context,
                readingId   = readingId,
                sid         = speechSid,
                sentences   = sentences,
                isMyReading = isMyReading,
            )
            if (canProceed) {
                // ⚠️ TÁCH THEO isMyReading — bài HỆ THỐNG dùng
                // enqueueEnsureReadingSynced() như cũ (marker "đã tải ĐỦ"
                // vĩnh viễn là ĐÚNG cho bài hệ thống, vì Drive luôn có sẵn
                // TOÀN BỘ giọng ngay từ đầu — không có khái niệm "giọng mới
                // xuất hiện sau"). Bài MYREADING PHẢI dùng enqueueDownload()
                // (per-sid, KHÔNG có marker cả bài) — vì server tổng hợp
                // TỪNG GIỌNG THEO YÊU CẦU, có thể có giọng MỚI xuất hiện bất
                // kỳ lúc nào sau khi 1 giọng khác đã "tải đủ" từ trước. Nếu
                // dùng enqueueEnsureReadingSynced() cho MyReading, ngay khi
                // giọng ĐẦU TIÊN tải xong, marker vĩnh viễn bị ghi và app sẽ
                // KHÔNG BAO GIỜ kiểm tra lại Drive cho các giọng khác được
                // yêu cầu sau đó, dù server đã xử lý xong (xem
                // TtsKokoroPackDownloader.ensureReadingFullySynced():
                // isReadingFullySynced() short-circuit ngay dòng đầu).
                if (isMyReading) {
                    TtsKokoroPackScheduler.enqueueDownload(context, readingId, speechSid)
                } else {
                    TtsKokoroPackScheduler.enqueueEnsureReadingSynced(context, readingId, speechSid)
                }
            }
        }
    }

    // ── WordPopup ─────────────────────────────────────────────────────────────
    selectedWord?.let { word ->
        WordPopup(
            readingId            = readingId,
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

    // ── PhrasePopup (độc lập, chế độ "P") ───────────────────────────────────────
    // Chỉ hiện khi selectedWord == null — tránh trùng với phrase context bên
    // trong WordPopup (trường hợp đó selectedWord != null, đã xử lý ở trên).
    if (selectedWord == null) {
        selectedPhrase?.let { phrase ->
            PhrasePopup(
                readingId    = readingId,
                phrase       = phrase,
                anchorInfo   = anchorInfo,
                viewportRect = viewportRect,
                onDismiss    = {
                    vm.dismissPhrasePopup()
                    anchorInfo = null
                }
            )
        }
    }

    // ── SentencePopup ─────────────────────────────────────────────────────────
    selectedSentence?.let { sentence ->
        SentencePopup(
            readingId    = readingId,
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
                    // ── Toggle chế độ dịch khi kéo bôi đen ≥2 từ: S (câu) ↔ P (cụm từ) ──
                    TextButton(
                        onClick = {
                            translateMode = if (translateMode == "S") "P" else "S"
                            prefs.edit().putString("translate_mode", translateMode).apply()
                        }
                    ) {
                        Text(
                            text = translateMode,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    // ── Toggle định dạng hiển thị cụm từ (chỉ có ý nghĩa ở mode "P"):
                    //    "underline" ↔ "line". Mờ đi khi đang ở mode "S" vì không áp dụng.
                    TextButton(
                        onClick = {
                            phraseFormat = if (phraseFormat == "underline") "line" else "underline"
                            prefs.edit().putString("phrase_format", phraseFormat).apply()
                        },
                        enabled = translateMode == "P"
                    ) {
                        Text(
                            text = "F",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    // ── Mở màn chọn giọng đọc — truyền readingId hiện tại để
                    // đổi giọng xong tự enqueue tải gói mới cho đúng bài này
                    // ngay (xem TtsVoicePickerScreen.kt).
                    TextButton(
                        onClick = onOpenVoicePicker
                    ) {
                        Text(
                            text = "V",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    // ── Toggle chuyển đổi cụm điều khiển bên cạnh: cỡ chữ ↔ tốc độ đọc.
                    //    Bấm lại lần nữa quay về cỡ chữ — chỉ đổi hiển thị, không
                    //    lưu trạng thái này (luôn mặc định về cỡ chữ khi mở lại màn).
                    TextButton(
                        onClick = { showSpeedControl = !showSpeedControl }
                    ) {
                        Text(
                            text = "R",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (showSpeedControl)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurface
                        )
                    }
                    if (showSpeedControl) {
                        // ── Cụm chỉnh tốc độ đọc (-/1.0x/+), bước 0.1, giới hạn
                        // theo TtsManager.MIN_RATE..MAX_RATE — thay thế đúng chỗ
                        // của cụm cỡ chữ khi đang ở chế độ này.
                        IconButton(
                            onClick = {
                                val next = (speechRate - 0.1f).coerceAtLeast(TtsManager.MIN_RATE)
                                speechRate = next
                                TtsManager.setSpeechRate(next)
                            },
                            enabled = speechRate > TtsManager.MIN_RATE
                        ) {
                            Text(text = "−", style = MaterialTheme.typography.titleLarge)
                        }
                        Text(
                            text = String.format("%.1fx", speechRate),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.widthIn(min = 40.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        IconButton(
                            onClick = {
                                val next = (speechRate + 0.1f).coerceAtMost(TtsManager.MAX_RATE)
                                speechRate = next
                                TtsManager.setSpeechRate(next)
                            },
                            enabled = speechRate < TtsManager.MAX_RATE
                        ) {
                            Text(text = "+", style = MaterialTheme.typography.titleLarge)
                        }
                    } else {
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
                            selectedPhrase      = selectedPhrase,
                            selectedSentence    = selectedSentence,
                            fontSize            = fontSize,
                            savedWordIds        = savedWordIds,
                            translateMode       = translateMode,
                            phraseFormat        = phraseFormat,
                            onWordClick         = { word -> vm.onWordClick(word, sentence) },
                            onSentenceClick     = { vm.onSentenceClick(sentence) },
                            onPhraseRangeSelect = { anchorWord -> vm.onPhraseRangeSelect(anchorWord, sentence) },
                            onAnchorInfoChanged = { info -> anchorInfo = info }
                        )
                    }
                }
            }
        }
    }
}