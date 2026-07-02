package com.eleap.eleap.feature.reading

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.eleap.eleap.core.auth.CurrentUser
import com.eleap.eleap.feature.myreading.data.MyReadingRepository
import com.eleap.eleap.feature.myreading.data.processUnhandledMyReadings
import com.eleap.eleap.feature.reading.data.Reading
import com.eleap.eleap.feature.reading.data.ReadingDao
import com.eleap.eleap.feature.reading.data.ReadingDatabase
import com.eleap.eleap.feature.reading.data.ReadingRepository
import com.eleap.eleap.feature.reading.data.ReadingSentence
import com.eleap.eleap.feature.reading.data.DictEntry
import com.eleap.eleap.feature.reading.data.SentencePhrase
import com.eleap.eleap.feature.reading.data.SentenceWord
import com.eleap.eleap.feature.reading.ui.UserDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReadingViewModel(
    private val repository: ReadingRepository,
    private val myReadingRepository: MyReadingRepository,   // ← mới: để ViewModel expose ghi/sửa/xoá
    private val userDb: UserDatabase,
    private val appContext: Context,   // ← mới: dùng cho watchdog AI xử lý MyReading
) : ViewModel() {

    // ── Flow 2 ────────────────────────────────────────────────────────────────
    private val _readings = MutableStateFlow<List<Reading>>(emptyList())
    val readings: StateFlow<List<Reading>> = _readings

    // ── Danh sách tách sẵn theo nguồn — UI (ReadingListScreen / MyReadingListScreen)
    // dùng trực tiếp, không cần tự filter userId ở lớp Compose.
    val systemReadings: StateFlow<List<Reading>> =
        readings.map { list -> list.filter { it.userId == null } }
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    val myReadings: StateFlow<List<Reading>> =
        readings.map { list -> list.filter { it.userId != null } }
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    // ── Flow 3 ────────────────────────────────────────────────────────────────
    private val _sentences = MutableStateFlow<List<ReadingSentence>>(emptyList())
    val sentences: StateFlow<List<ReadingSentence>> = _sentences

    private val _isLoadingReading = MutableStateFlow(false)
    val isLoadingReading: StateFlow<Boolean> = _isLoadingReading

    // ── Flow 6: từ đang được chọn để hiện WordPopup ───────────────────────────
    private val _selectedWord = MutableStateFlow<SentenceWord?>(null)
    val selectedWord: StateFlow<SentenceWord?> = _selectedWord

    private val _selectedPhrase = MutableStateFlow<SentencePhrase?>(null)
    val selectedPhrase: StateFlow<SentencePhrase?> = _selectedPhrase

    private val _selectedSentence = MutableStateFlow<ReadingSentence?>(null)
    val selectedSentence: StateFlow<ReadingSentence?> = _selectedSentence

    private val _selectedDictEntry = MutableStateFlow<DictEntry?>(null)
    val selectedDictEntry: StateFlow<DictEntry?> = _selectedDictEntry

    private val _isDictExpanded = MutableStateFlow(false)
    val isDictExpanded: StateFlow<Boolean> = _isDictExpanded

    private val _savedWordIds = MutableStateFlow<Set<String>>(emptySet())
    val savedWordIds: StateFlow<Set<String>> = _savedWordIds

    private var cachedReadingId: String? = null

    init {
        loadReadings()
        refreshSavedWordIds()

        // Tự load lại danh sách khi tài khoản đổi (login/logout) — không cần
        // UI tự gọi. drop(1) để bỏ giá trị hiện tại lúc khởi động (đã load ở trên).
        viewModelScope.launch {
            CurrentUser.userId.drop(1).collect {
                loadReadings(forceRefresh = true)
            }
        }

        // ── Watchdog AI xử lý MyReading — quét NGẦM liên tục, hoàn toàn im
        // lặng (không snackbar/toast). ReadingViewModel là singleton
        // (Factory.INSTANCE) nên vòng lặp này sống xuyên suốt vòng đời app,
        // không phụ thuộc màn hình nào đang hiển thị. Chạy ngay lần đầu (để
        // xử lý các bài lỡ bị bỏ sót từ phiên trước), sau đó lặp mỗi 15s.
        viewModelScope.launch {
            while (true) {
                runMyReadingAiWatchdog()
                delay(15_000L)
            }
        }
    }

    private suspend fun runMyReadingAiWatchdog() {
        processUnhandledMyReadings(
            context = appContext,
            onStatus = { msg -> Log.d("ReadingVM.AiWatchdog", msg) },
            onUpdated = { loadReadings(forceRefresh = true) },
        )
    }

    // ── Flow 2 ────────────────────────────────────────────────────────────────
    private fun loadReadings(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _readings.value = repository.getAllReadings(forceRefresh)
        }
    }

    // ── Flow 3: chỉ load khi readingId thay đổi ──────────────────────────────
    fun loadReading(readingId: String) {
        if (readingId == cachedReadingId) {
            Log.d("ReadingVM", "readingId=$readingId đã cache, bỏ qua loadReading()")
            return
        }
        viewModelScope.launch {
            _isLoadingReading.value = true

            val before = System.currentTimeMillis()
            val result = repository.getReading(readingId)
            val elapsed = System.currentTimeMillis() - before

            Log.d("ReadingVM", "readingId=$readingId | sentences=${result.size} | time=${elapsed}ms")

            _sentences.value = result
            cachedReadingId  = readingId
            _isLoadingReading.value = false

            launch { repository.preloadDictForReading(result) }
        }
    }

    // ── Thêm / xoá bài của user — delegate sang MyReadingRepository ─────────
    fun addMyReading(title: String, content: String, onDone: (readingId: String?) -> Unit) {
        viewModelScope.launch {
            val id = myReadingRepository.saveMyReading(title, content)
            loadReadings(forceRefresh = true)   // để bài mới xuất hiện trong `readings`/`myReadings`
            onDone(id)

            // Kích hoạt AI dịch ngay cho bài vừa thêm, không chờ vòng watchdog
            // 15s kế tiếp. Chạy trong coroutine riêng, không chặn onDone().
            if (id != null) {
                launch { runMyReadingAiWatchdog() }
            }
        }
    }

    fun deleteMyReading(readingId: String, onDone: (success: Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = myReadingRepository.deleteMyReading(readingId)
            if (ok) loadReadings(forceRefresh = true)
            onDone(ok)
        }
    }

    // ── savedWordIds: nạp lại từ users.db (chạy nền) ─────────────────────────
    fun refreshSavedWordIds() {
        viewModelScope.launch {
            val ids = withContext(Dispatchers.IO) { userDb.getAllSavedWordIds() }
            _savedWordIds.value = ids
        }
    }

    // ── Flow 6: click vào từ ──────────────────────────────────────────────────
    fun onWordClick(word: SentenceWord, sentence: ReadingSentence) {
        _selectedWord.value = word

        _selectedPhrase.value = word.phraseId?.let { pid ->
            sentence.phrases.find { it.phraseId == pid }
        }

        _selectedDictEntry.value = repository.getDictEntry(word.textEn)
        _isDictExpanded.value = false

        Log.d(
            "ReadingVM",
            "wordClick: \"${word.textEn}\" (id=${word.wordId})" +
                    (_selectedPhrase.value?.let { " → phrase=\"${it.textEn}\"" } ?: " → no phrase")
        )
    }

    fun toggleDictExpanded() {
        _isDictExpanded.value = !_isDictExpanded.value
    }

    fun dismissWordPopup() {
        _selectedWord.value = null
        _selectedPhrase.value = null
        _selectedDictEntry.value = null
        _isDictExpanded.value = false
    }

    fun onSentenceClick(sentence: ReadingSentence) {
        _selectedSentence.value = sentence
        Log.d("ReadingVM", "sentenceClick: sentenceId=${sentence.sentenceId}")
    }

    fun dismissSentencePopup() {
        _selectedSentence.value = null
    }

    fun onPhraseRangeSelect(anchorWord: SentenceWord, sentence: ReadingSentence) {
        val phrase = anchorWord.phraseId?.let { pid ->
            sentence.phrases.find { it.phraseId == pid }
        }

        if (phrase == null) {
            Log.d(
                "ReadingVM",
                "phraseRangeSelect: \"${anchorWord.textEn}\" không có phrase hợp lệ → bỏ qua"
            )
            return
        }

        _selectedWord.value = null
        _selectedDictEntry.value = null
        _isDictExpanded.value = false

        _selectedPhrase.value = phrase
        Log.d(
            "ReadingVM",
            "phraseRangeSelect: \"${anchorWord.textEn}\" → phrase=\"${phrase.textEn}\" (id=${phrase.phraseId})"
        )
    }

    fun dismissPhrasePopup() {
        _selectedPhrase.value = null
    }

    // ── Factory singleton ─────────────────────────────────────────────────────
    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return (INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val appCtx = context.applicationContext
                    val db     = ReadingDatabase.getInstance(appCtx)
                    val dao    = ReadingDao(db.db, db.dictDb)
                    val myRepo = MyReadingRepository.getInstance(appCtx)
                    val repo   = ReadingRepository(dao, myRepo)
                    val userDb = UserDatabase.getInstance(appCtx)
                    ReadingViewModel(repo, myRepo, userDb, appCtx).also { INSTANCE = it }
                }
            }) as T
        }

        companion object {
            @Volatile private var INSTANCE: ReadingViewModel? = null
        }
    }
}