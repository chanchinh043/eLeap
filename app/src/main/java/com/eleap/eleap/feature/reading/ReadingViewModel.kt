package com.eleap.eleap.feature.reading

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.eleap.eleap.feature.reading.data.Reading
import com.eleap.eleap.feature.reading.data.ReadingDao
import com.eleap.eleap.feature.reading.data.ReadingDatabase
import com.eleap.eleap.feature.reading.data.ReadingRepository
import com.eleap.eleap.feature.reading.data.ReadingSentence
import com.eleap.eleap.feature.reading.data.DictEntry
import com.eleap.eleap.feature.reading.data.SentencePhrase
import com.eleap.eleap.feature.reading.data.SentenceWord
import com.eleap.eleap.feature.reading.ui.UserDatabase
import com.eleap.eleap.feature.userreading.UserReadingRepository
import com.eleap.eleap.feature.userreading.processSingleReading
import com.eleap.eleap.feature.userreading.processUnhandledReadings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReadingViewModel(
    private val repository: ReadingRepository,
    private val userDb: UserDatabase,
) : ViewModel() {

    private val _readings = MutableStateFlow<List<Reading>>(emptyList())
    val readings: StateFlow<List<Reading>> = _readings

    private val _sentences = MutableStateFlow<List<ReadingSentence>>(emptyList())
    val sentences: StateFlow<List<ReadingSentence>> = _sentences

    private val _isLoadingReading = MutableStateFlow(false)
    val isLoadingReading: StateFlow<Boolean> = _isLoadingReading

    // ── Selection: CHỈ lưu ID, không lưu snapshot object ─────────────────────
    // Đây là nguồn sự thật DUY NHẤT cho việc "đang chọn từ/câu nào". Object
    // SentenceWord/ReadingSentence/SentencePhrase tương ứng được DERIVE (tính
    // lại) từ _sentences mỗi khi _sentences đổi — nhờ vậy khi AI ghi xong và
    // _sentences được reload, popup đang mở LUÔN tự thấy đúng data mới nhất,
    // không có khe hở thời gian nào khiến nó "lỡ" giữ instance cũ.
    private val _selectedWordId = MutableStateFlow<Int?>(null)
    private val _selectedSentenceId = MutableStateFlow<Int?>(null)

    val selectedWord: StateFlow<SentenceWord?> =
        combine(_sentences, _selectedWordId) { sentences, wordId ->
            if (wordId == null) null
            else sentences.firstNotNullOfOrNull { s -> s.words.find { it.wordId == wordId } }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val selectedSentence: StateFlow<ReadingSentence?> =
        combine(_sentences, _selectedSentenceId) { sentences, sentenceId ->
            if (sentenceId == null) null
            else sentences.find { it.sentenceId == sentenceId }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Phrase được derive theo selectedWord (chứ không lưu riêng) — luôn khớp
    // 100% với word đang chọn, không thể lệch pha.
    val selectedPhrase: StateFlow<SentencePhrase?> =
        combine(_sentences, selectedWord) { sentences, word ->
            val pid = word?.phraseId ?: return@combine null
            sentences.firstNotNullOfOrNull { s -> s.phrases.find { it.phraseId == pid } }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _selectedDictEntry = MutableStateFlow<DictEntry?>(null)
    val selectedDictEntry: StateFlow<DictEntry?> = _selectedDictEntry

    private val _isDictExpanded = MutableStateFlow(false)
    val isDictExpanded: StateFlow<Boolean> = _isDictExpanded

    private val _savedWordIds = MutableStateFlow<Set<Int>>(emptySet())
    val savedWordIds: StateFlow<Set<Int>> = _savedWordIds

    // Thông báo trạng thái AI (snackbar) — dùng Channel, KHÔNG dùng StateFlow.
    // StateFlow luôn replay giá trị hiện tại cho collector mới → nếu user back
    // ra giữa lúc snackbar đang hiện (chưa kịp tiêu thụ xong giá trị), giá trị
    // cũ vẫn còn trong StateFlow và hiện lại ngay khi quay lại màn hình.
    // Channel chỉ gửi value cho ĐÚNG 1 collector, 1 lần duy nhất, không lưu lại
    // sau khi đã nhận — đúng bản chất "sự kiện one-shot" của thông báo này.
    private val _aiStatusEvents = Channel<String>(capacity = Channel.BUFFERED)
    val aiStatusEvents = _aiStatusEvents.receiveAsFlow()

    // ── Notify ReadingScreen khi AI xử lý xong bài đang mở ───────────────────
    private val _aiCompletedReadingId = MutableStateFlow<Int>(-1)
    val aiCompletedReadingId: StateFlow<Int> = _aiCompletedReadingId

    fun notifyAiCompleted(readingId: Int) {
        invalidateReadingCache(readingId)
        // emit qua StateFlow — ReadingScreen (nếu đang mở đúng bài) sẽ tự
        // âm thầm reload lại dữ liệu, không hiện loading spinner.
        _aiCompletedReadingId.value = readingId
    }

    fun consumeAiCompleted() {
        _aiCompletedReadingId.value = -1
    }

    private fun invalidateReadingCache(readingId: Int) {
        if (cachedReadingId == readingId) {
            cachedReadingId = -1
        }
        repository.invalidateReadingCache(readingId)
    }

    @Volatile
    private var cachedReadingId: Int = -1

    init {
        ReadingRepository.instance = repository
        loadReadings()
        refreshSavedWordIds()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AI processing
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Xử lý DUY NHẤT 1 bài vừa insert — gọi ngay sau saveUserReading() trả về readingId.
     * Không scan toàn bảng, gọi API đúng 1 lần, không bao giờ bỏ sót (withLock).
     * viewModelScope → không bị cancel khi navigate.
     */
    fun processSingleReading(context: Context, readingId: Int) {
        val appCtx = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            processSingleReading(appCtx, readingId) { msg ->
                _aiStatusEvents.send(msg)
            }
        }
    }

    /**
     * Safety-net: quét toàn bộ bài tồn đọng (is_ai_processed = 0).
     * Gọi từ ReadingListScreen.LaunchedEffect để xử lý bài bị bỏ sót
     * do crash, mất mạng, v.v. Dùng tryLock nên bỏ qua nếu đang bận —
     * bài mới đã được processSingleReading lo rồi.
     * viewModelScope → không bị cancel khi navigate.
     */
    fun triggerAiProcessing(context: Context) {
        val appCtx = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            processUnhandledReadings(appCtx) { msg ->
                _aiStatusEvents.send(msg)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Readings list
    // ─────────────────────────────────────────────────────────────────────────

    fun loadReadings() {
        viewModelScope.launch {
            _readings.value = repository.getAllReadings()
        }
    }

    fun reloadReadings() {
        viewModelScope.launch {
            repository.invalidateListCache()
            _readings.value = repository.getAllReadings()
            Log.d("ReadingVM", "reloadReadings: ${_readings.value.size} bài")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reading detail
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Tải nội dung bài đọc.
     *
     * @param silent true khi đây là reload ngầm (ví dụ: AI vừa xử lý xong bài
     *   đang mở) — không hiện loading spinner, không clear danh sách câu cũ
     *   trước khi có data mới, để tránh nháy UI khi đang đọc.
     *   false khi đây là lần load đầu tiên mở bài (cần hiện spinner vì
     *   chưa có gì hiển thị).
     */
    fun loadReading(readingId: Int, silent: Boolean = false) {
        if (!silent && readingId == cachedReadingId) {
            Log.d("ReadingVM", "readingId=$readingId đã cache, bỏ qua loadReading()")
            return
        }
        viewModelScope.launch {
            if (!silent) _isLoadingReading.value = true

            val before  = System.currentTimeMillis()
            val result  = repository.getReading(readingId)
            val elapsed = System.currentTimeMillis() - before

            Log.d("ReadingVM", "readingId=$readingId | sentences=${result.size} | time=${elapsed}ms | silent=$silent")

            // Cập nhật _sentences.value chỉ 1 lần, sau khi đã có đủ data mới.
            // Compose chỉ recompose lại các phần dữ liệu thực sự đổi (Vietnamese
            // text, explanation, pos, lemma...) — phần khung câu/word không đổi
            // vị trí nên không có hiệu ứng giật/nháy.
            _sentences.value        = result
            cachedReadingId         = readingId
            _isLoadingReading.value = false

            // Không cần resync popup thủ công nữa: selectedWord / selectedSentence /
            // selectedPhrase đều là derived StateFlow tính từ _sentences, nên khi
            // _sentences đổi ở trên, chúng tự động phát ra giá trị mới ngay lập tức.

            launch { repository.preloadDictForReading(result) }
        }
    }

    fun deleteReading(readingId: Int, context: Context) {
        viewModelScope.launch {
            val ctx  = context.applicationContext
            val repo = UserReadingRepository.getInstance(ctx)
            val ok   = repo.deleteUserReading(readingId)
            if (ok) {
                if (cachedReadingId == readingId) {
                    _sentences.value = emptyList()
                    cachedReadingId  = -1
                }
                _readings.value = repository.getAllReadings()
                Log.d("ReadingVM", "deleteReading OK: readingId=$readingId")
            } else {
                Log.w("ReadingVM", "deleteReading thất bại: readingId=$readingId")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Word / sentence interaction
    // ─────────────────────────────────────────────────────────────────────────

    fun refreshSavedWordIds() {
        viewModelScope.launch {
            val ids = withContext(Dispatchers.IO) { userDb.getAllSavedWordIds() }
            _savedWordIds.value = ids
        }
    }

    fun onWordClick(word: SentenceWord, sentence: ReadingSentence) {
        _selectedSentenceId.value = null   // đảm bảo không mở đồng thời 2 loại popup
        _selectedWordId.value = word.wordId
        _selectedDictEntry.value = repository.getDictEntry(word.textEn)
        _isDictExpanded.value = false
        Log.d("ReadingVM", "wordClick: \"${word.textEn}\" (id=${word.wordId})")
    }

    fun toggleDictExpanded() {
        _isDictExpanded.value = !_isDictExpanded.value
    }

    fun dismissWordPopup() {
        _selectedWordId.value    = null
        _selectedDictEntry.value = null
        _isDictExpanded.value    = false
    }

    fun onSentenceClick(sentence: ReadingSentence) {
        _selectedWordId.value = null   // đảm bảo không mở đồng thời 2 loại popup
        _selectedSentenceId.value = sentence.sentenceId
        Log.d("ReadingVM", "sentenceClick: sentenceId=${sentence.sentenceId}")
    }

    fun dismissSentencePopup() {
        _selectedSentenceId.value = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Factory (singleton)
    // ─────────────────────────────────────────────────────────────────────────

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return (INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val appCtx = context.applicationContext
                    val db     = ReadingDatabase.getInstance(appCtx)
                    val dao    = ReadingDao(db.db, db.dictDb)
                    val repo   = ReadingRepository(dao)
                    val userDb = UserDatabase.getInstance(appCtx)
                    ReadingViewModel(repo, userDb).also { INSTANCE = it }
                }
            }) as T
        }

        companion object {
            @Volatile private var INSTANCE: ReadingViewModel? = null

            fun getInstance(): ReadingViewModel? = INSTANCE
        }
    }
}