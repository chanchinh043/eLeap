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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReadingViewModel(
    private val repository: ReadingRepository,
    private val userDb: UserDatabase,          // thêm mới — để quản lý savedWordIds
) : ViewModel() {

    // ── Flow 2 ────────────────────────────────────────────────────────────────
    private val _readings = MutableStateFlow<List<Reading>>(emptyList())
    val readings: StateFlow<List<Reading>> = _readings

    // ── Flow 3 ────────────────────────────────────────────────────────────────
    private val _sentences = MutableStateFlow<List<ReadingSentence>>(emptyList())
    val sentences: StateFlow<List<ReadingSentence>> = _sentences

    private val _isLoadingReading = MutableStateFlow(false)
    val isLoadingReading: StateFlow<Boolean> = _isLoadingReading

    // ── Flow 6: từ đang được chọn để hiện WordPopup ───────────────────────────
    private val _selectedWord = MutableStateFlow<SentenceWord?>(null)
    val selectedWord: StateFlow<SentenceWord?> = _selectedWord

    // ── Flow 6 (phrase): cụm từ chứa từ đang click ───────────────────────────
    private val _selectedPhrase = MutableStateFlow<SentencePhrase?>(null)
    val selectedPhrase: StateFlow<SentencePhrase?> = _selectedPhrase

    // ── Flow 7: câu đang được chọn để hiện SentencePopup ─────────────────────
    private val _selectedSentence = MutableStateFlow<ReadingSentence?>(null)
    val selectedSentence: StateFlow<ReadingSentence?> = _selectedSentence

    // ── Dict: nghĩa từ điển của từ đang chọn ─────────────────────────────────
    private val _selectedDictEntry = MutableStateFlow<DictEntry?>(null)
    val selectedDictEntry: StateFlow<DictEntry?> = _selectedDictEntry

    // ── Dict: trạng thái "Xem thêm" trong WordPopup ───────────────────────────
    private val _isDictExpanded = MutableStateFlow(false)
    val isDictExpanded: StateFlow<Boolean> = _isDictExpanded

    // ── savedWordIds: tập wordId đã lưu — dùng để tô màu từ trong bài đọc ────
    // Quản lý ở đây để SaveWordButton có thể notify cập nhật mà không cần
    // ReadingScreen phải poll lại DB mỗi khi popup đóng.
    private val _savedWordIds = MutableStateFlow<Set<Int>>(emptySet())
    val savedWordIds: StateFlow<Set<Int>> = _savedWordIds

    // ── readingId đang cache — tránh load lại bài đọc đã load rồi ────────────
    // Repository đã có readingCache (RAM), nhưng ViewModel bị recreate mỗi lần
    // navigate nên loadReading() vẫn bị gọi lại. Giờ ViewModel là singleton
    // (qua Factory companion), nên chỉ cần kiểm tra biến này là đủ.
    private var cachedReadingId: Int = -1

    init {
        loadReadings()
        refreshSavedWordIds()
    }

    // ── Flow 2 ────────────────────────────────────────────────────────────────
    private fun loadReadings() {
        viewModelScope.launch {
            _readings.value = repository.getAllReadings()
        }
    }

    // ── Flow 3: chỉ load khi readingId thay đổi ──────────────────────────────
    fun loadReading(readingId: Int) {
        if (readingId == cachedReadingId) {
            // Bài đọc đã load rồi — không làm gì thêm, ViewModel giữ nguyên state
            Log.d("ReadingVM", "readingId=$readingId đã cache, bỏ qua loadReading()")
            return
        }
        viewModelScope.launch {
            _isLoadingReading.value = true

            val before = System.currentTimeMillis()
            val result = repository.getReading(readingId)
            val elapsed = System.currentTimeMillis() - before

            Log.d("ReadingVM", "readingId=$readingId | sentences=${result.size} | time=${elapsed}ms")
            result.firstOrNull()?.let { s ->
                Log.d("ReadingVM", "  First sentence: \"${s.textEn}\"")
                Log.d("ReadingVM", "  words=${s.words.size}, phrases=${s.phrases.size}")
            }

            _sentences.value = result
            cachedReadingId  = readingId
            _isLoadingReading.value = false

            // Background: nạp Dict RAM cho các từ trong bài, không chặn UI
            launch { repository.preloadDictForReading(result) }
        }
    }

    // ── savedWordIds: nạp lại từ users.db (chạy nền) ─────────────────────────
    // Gọi khi: init, sau khi lưu từ, sau khi bỏ lưu từ.
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

    // ── Toggle "Xem thêm" / "Thu gọn" trong WordPopup ────────────────────────
    fun toggleDictExpanded() {
        _isDictExpanded.value = !_isDictExpanded.value
    }

    // ── Flow 8: đóng WordPopup ────────────────────────────────────────────────
    fun dismissWordPopup() {
        _selectedWord.value = null
        _selectedPhrase.value = null
        _selectedDictEntry.value = null
        _isDictExpanded.value = false
    }

    // ── Flow 7: click vào câu ─────────────────────────────────────────────────
    fun onSentenceClick(sentence: ReadingSentence) {
        _selectedSentence.value = sentence
        Log.d("ReadingVM", "sentenceClick: sentenceId=${sentence.sentenceId}")
    }

    // ── Flow 9: đóng SentencePopup ───────────────────────────────────────────
    fun dismissSentencePopup() {
        _selectedSentence.value = null
    }

    // ── Factory singleton ─────────────────────────────────────────────────────
    // QUAN TRỌNG: dùng companion object để giữ 1 instance duy nhất trong suốt
    // vòng đời app. Khi navigate ra/vào ReadingScreen, ViewModel KHÔNG bị recreate,
    // do đó sentences, cachedReadingId, savedWordIds đều được giữ nguyên.
    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            // Reuse instance nếu đã tạo — tránh recreate khi navigate
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
        }
    }
}