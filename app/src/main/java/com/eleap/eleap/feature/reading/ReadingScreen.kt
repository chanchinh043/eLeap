package com.eleap.eleap.feature.reading

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.foundation.rememberScrollState
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
import com.eleap.eleap.core.tts.pregen.TtsForegroundReading
import com.eleap.eleap.core.tts.pregen.TtsPregenScheduler
import com.eleap.eleap.core.tts.pregen.TtsReadingHistory
import com.eleap.eleap.core.tts.pregen.TtsVoiceSnapshot
import com.eleap.eleap.feature.reading.ui.PhrasePopup
import com.eleap.eleap.feature.reading.ui.PopupAnchorInfo
import com.eleap.eleap.feature.reading.ui.SentencePopup
import com.eleap.eleap.feature.reading.ui.WordClickableRow
import com.eleap.eleap.feature.reading.ui.WordPopup

// ── DEBUG TẠM THỜI: danh sách các lựa chọn giọng đọc để thử nghiệm/so sánh.
// Bấm nút "V" ở TopAppBar để mở dropdown, chọn thẳng giọng cần nghe (nhanh
// hơn xoay vòng từng cái). Xoá khối này (và đoạn nút "V" tương ứng bên
// dưới) khi đã chọn được giọng cuối cùng dùng chính thức. ──────────────────
//
// Model kokoro-int8-multi-lang-v1_0 (53 giọng, sid 0-52) — CHỈ liệt kê sid
// 0-27 (tiếng Anh), KHÔNG dùng sid 28+ (các ngôn ngữ khác) vì app chỉ đọc
// tiếng Anh.
private data class VoiceOption(val label: String, val apply: () -> Unit)

private fun kokoroOption(sid: Int, name: String) = VoiceOption("#$sid $name") {
    TtsManager.switchEngine(TtsManager.EngineType.KOKORO)
    TtsManager.setKokoroSpeaker(sid)
}

private val voiceOptions = listOf(
    // af_* — nữ, Mỹ (sid 0-10)
    kokoroOption(0, "af_alloy"),
    kokoroOption(1, "af_aoede"),
    kokoroOption(2, "af_bella"),
    kokoroOption(3, "af_heart"),
    kokoroOption(4, "af_jessica"),
    kokoroOption(5, "af_kore"),
    kokoroOption(6, "af_nicole"),
    kokoroOption(7, "af_nova"),
    kokoroOption(8, "af_river"),
    kokoroOption(9, "af_sarah"),
    kokoroOption(10, "af_sky"),
    // am_* — nam, Mỹ (sid 11-19)
    kokoroOption(11, "am_adam"),
    kokoroOption(12, "am_echo"),
    kokoroOption(13, "am_eric"),
    kokoroOption(14, "am_fenrir"),
    kokoroOption(15, "am_liam"),
    kokoroOption(16, "am_michael"),
    kokoroOption(17, "am_onyx"),
    kokoroOption(18, "am_puck"),
    kokoroOption(19, "am_santa"),
    // bf_* — nữ, Anh-Anh (sid 20-23)
    kokoroOption(20, "bf_alice"),
    kokoroOption(21, "bf_emma"),
    kokoroOption(22, "bf_isabella"),
    kokoroOption(23, "bf_lily"),
    // bm_* — nam, Anh-Anh (sid 24-27)
    kokoroOption(24, "bm_daniel"),
    kokoroOption(25, "bm_fable"),
    kokoroOption(26, "bm_george"),
    kokoroOption(27, "bm_lewis"),
    VoiceOption("Android") {
        TtsManager.switchEngine(TtsManager.EngineType.ANDROID)
    },
)

// ── MỚI: suy ra ĐÚNG index trong voiceOptions đang thực sự active — dùng để
// khởi tạo voiceIndex khi ReadingScreen được compose lại (vd quay ra rồi
// quay lại màn đọc), thay vì luôn hard-code về 0 (#0 af_alloy) như trước,
// khiến label ở nút "V" hiển thị sai lệch với giọng THẬT đang phát.
//
// voiceOptions được xây TUẦN TỰ 0..27 đúng bằng sid Kokoro tương ứng (xem
// kokoroOption() ở trên: index i trong danh sách == sid i), nên chỉ cần lấy
// đúng sid đã lưu là ra đúng index, không cần dò tìm gì thêm.
//
// TtsVoiceSnapshot.currentTargetSid() đã tự trả về null nếu engine đang
// active KHÔNG phải Kokoro (đang là Android) — đúng lúc đó fallback về
// voiceOptions.lastIndex (phần tử "Android" luôn là phần tử CUỐI trong danh
// sách). Nếu sid lưu được (hiếm khi xảy ra, chỉ nếu dữ liệu cũ/hỏng) nằm
// ngoài phạm vi hợp lệ, cũng fallback an toàn về "Android" thay vì crash do
// index âm hoặc vượt danh sách.
private fun currentVoiceIndex(): Int {
    val sid = TtsVoiceSnapshot.currentTargetSid()
    return if (sid != null && sid in voiceOptions.indices) sid else voiceOptions.lastIndex
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingScreen(
    readingId: String,
    onBack: () -> Unit,
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

    // ── DEBUG TẠM THỜI: index đang chọn trong voiceOptions (để hiện label
    // trên nút "V"), và trạng thái đóng/mở dropdown chọn giọng.
    //
    // ⚠️ MỚI: khởi tạo từ currentVoiceIndex() (đọc trạng thái THẬT đang lưu
    // ở TtsVoiceSnapshot/TtsManager) thay vì hard-code 0 — để khi rời màn
    // đọc rồi quay lại (Composable bị huỷ và tạo mới), label ở nút "V" hiện
    // ĐÚNG giọng lần cuối đã chọn, không bị nhảy về về "#0 af_alloy". Không
    // lưu SharedPreferences RIÊNG cho voiceIndex — không cần, vì bản thân
    // TtsManager (engine active)/TtsVoiceSnapshot (sid Kokoro) đã là nguồn
    // sự thật duy nhất, currentVoiceIndex() chỉ đơn thuần ánh xạ ngược lại
    // thành vị trí trong danh sách UI. remember{} (không key) chỉ tính 1 lần
    // đúng lúc Composable này được tạo, đúng ý muốn "khôi phục lúc vào lại
    // màn", không cần tính lại mỗi lần recompose.
    var voiceIndex by remember { mutableStateOf(currentVoiceIndex()) }
    var isVoiceMenuExpanded by remember { mutableStateOf(false) }

    var anchorInfo   by remember { mutableStateOf<PopupAnchorInfo?>(null) }
    var viewportRect by remember { mutableStateOf<Rect?>(null) }

    LaunchedEffect(readingId) {
        vm.loadReading(readingId)
    }

    // ── MỚI (điểm chạm B — core/tts/pregen/): báo cho TtsPregenWorker biết
    // "đang mở bài nào NGAY LÚC NÀY" (RAM only, qua TtsForegroundReading) và
    // ghi nhận vào lịch sử đã mở (SharedPreferences, qua TtsReadingHistory) —
    // đúng 2 việc đã chốt ở điểm chạm B trong thiết kế tổng thể.
    //
    // key = readingId: nếu người dùng điều hướng sang readingId KHÁC MÀ
    // KHÔNG rời khỏi ReadingScreen (Composable này được tái sử dụng, hiếm
    // khi xảy ra với cách điều hướng hiện tại nhưng vẫn đúng về mặt logic),
    // onDispose của effect CŨ chạy trước (clear() giọng cũ) rồi effect MỚI
    // chạy lại đúng cho readingId mới — không để sót "đang mở" trỏ nhầm bài.
    //
    // enqueueWork(context) gọi ở đây tương ứng điểm gọi (b) đã chốt ở thiết
    // kế TtsPregenScheduler — an toàn gọi lại nhiều lần nhờ
    // ExistingWorkPolicy.KEEP, đảm bảo có 1 lượt Worker "sống" biết ngay bài
    // vừa mở, kể cả khi lượt chạy trước đó (vd từ lúc mở app) đã tự hết việc
    // và dừng hẳn.
    //
    // onDispose gọi clear() khi rời màn đọc (back, hoặc Composable bị huỷ) —
    // để Worker biết "không còn ưu tiên tuyệt đối bài nào nữa", tự rơi
    // xuống xử lý lịch sử như bình thường (xem TtsForegroundReading.kt).
    DisposableEffect(readingId) {
        TtsForegroundReading.set(readingId)
        TtsReadingHistory.markOpened(readingId)
        TtsPregenScheduler.enqueueWork(context)
        onDispose {
            TtsForegroundReading.clear()
        }
    }

    // ── WordPopup ─────────────────────────────────────────────────────────────
    selectedWord?.let { word ->
        WordPopup(
            word                 = word,
            phrase               = selectedPhrase,
            dictEntry            = selectedDictEntry,
            isDictExpanded       = isDictExpanded,
            anchorInfo           = anchorInfo,
            viewportRect         = viewportRect,
            readingId            = readingId,
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
                phrase       = phrase,
                anchorInfo   = anchorInfo,
                viewportRect = viewportRect,
                readingId    = readingId,
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
            sentence     = sentence,
            anchorInfo   = anchorInfo,
            viewportRect = viewportRect,
            readingId    = readingId,
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
                    // ── Bọc toàn bộ actions trong Row có thể cuộn ngang — TopAppBar
                    // actions mặc định KHÔNG tự wrap/cuộn, nên khi có nhiều nút
                    // (S/P, F, V, R, rồi cụm −/giá trị/+) tổng chiều rộng dễ vượt
                    // quá màn hình, các nút bên phải sẽ bị cắt mất mà không báo
                    // lỗi gì (đây chính là lý do nút "V" trước đó không hiện ra).
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
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
                        // ── DEBUG TẠM THỜI: nút "V" — bấm để mở dropdown chọn
                        // thẳng giọng đọc (Kokoro nhiều sid khác nhau + Android
                        // TTS), thay vì phải xoay vòng từng cái. Dropdown giới
                        // hạn chiều cao ~10 mục, quá số đó thì cuộn để xem tiếp.
                        // Xoá nút này khi đã chốt giọng dùng chính thức. ───────
                        Box {
                            TextButton(
                                onClick = { isVoiceMenuExpanded = true }
                            ) {
                                Text(
                                    text = "V:${voiceOptions[voiceIndex].label}",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                            DropdownMenu(
                                expanded = isVoiceMenuExpanded,
                                onDismissRequest = { isVoiceMenuExpanded = false },
                                // ~10 dòng thì cuộn — mỗi DropdownMenuItem mặc định
                                // cao khoảng 48dp, 10*48=480dp là ngưỡng vừa đủ hiện
                                // ~10 giọng rồi kéo lên xem tiếp các giọng còn lại.
                                modifier = Modifier.heightIn(max = 480.dp)
                            ) {
                                voiceOptions.forEachIndexed { index, option ->
                                    DropdownMenuItem(
                                        text = { Text(option.label) },
                                        onClick = {
                                            voiceIndex = index
                                            option.apply()
                                            isVoiceMenuExpanded = false
                                        }
                                    )
                                }
                            }
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
                    } // đóng Row cuộn ngang
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