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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    private val _savedWordIds = MutableStateFlow<Set<Int>>(emptySet())
    val savedWordIds: StateFlow<Set<Int>> = _savedWordIds

    private var cachedReadingId: Int = -1

    // Context lưu lại để khởi tạo UserReadingRepository khi cần xoá bài
    private var appContext: Context? = null

    init {
        // Đăng ký instance để UserReadingRepository có thể gọi invalidateCache()
        ReadingRepository.instance = repository
        loadReadings()
        refreshSavedWordIds()
    }

    // ── Tải danh sách bài đọc — luôn reload khi được gọi ─────────────────────
    // ReadingListScreen gọi hàm này mỗi lần compose, nhưng Repository có cache
    // nên chỉ thực sự query DB khi cache bị xoá (sau khi user thêm / xoá bài).
    fun loadReadings() {
        viewModelScope.launch {
            _readings.value = repository.getAllReadings()
        }
    }

    // ── Reload danh sách từ DB (bỏ qua cache) — gọi sau khi thêm / xoá bài ──
    fun reloadReadings() {
        viewModelScope.launch {
            repository.invalidateListCache()
            _readings.value = repository.getAllReadings()
            Log.d("ReadingVM", "reloadReadings: ${_readings.value.size} bài")
        }
    }

    fun loadReading(readingId: Int) {
        if (readingId == cachedReadingId) {
            Log.d("ReadingVM", "readingId=$readingId đã cache, bỏ qua loadReading()")
            return
        }
        viewModelScope.launch {
            _isLoadingReading.value = true

            val before  = System.currentTimeMillis()
            val result  = repository.getReading(readingId)
            val elapsed = System.currentTimeMillis() - before

            Log.d("ReadingVM", "readingId=$readingId | sentences=${result.size} | time=${elapsed}ms")

            _sentences.value    = result
            cachedReadingId     = readingId
            _isLoadingReading.value = false

            launch { repository.preloadDictForReading(result) }
        }
    }

    // ── Xoá bài đọc do user tự tạo ───────────────────────────────────────────
    /**
     * Chỉ gọi cho bài có isAiProcessed = false (bài user tự tạo).
     * Sau khi xoá xong, tự reload danh sách để UI cập nhật ngay.
     */
    fun deleteReading(readingId: Int, context: Context) {
        viewModelScope.launch {
            val ctx  = context.applicationContext
            val repo = UserReadingRepository.getInstance(ctx)
            val ok   = repo.deleteUserReading(readingId)
            if (ok) {
                // Xoá cached sentences của bài vừa xoá nếu đang hiển thị
                if (cachedReadingId == readingId) {
                    _sentences.value  = emptyList()
                    cachedReadingId   = -1
                }
                // Reload danh sách (cache đã bị invalidate bên trong deleteUserReading)
                _readings.value = repository.getAllReadings()
                Log.d("ReadingVM", "deleteReading OK: readingId=$readingId")
            } else {
                Log.w("ReadingVM", "deleteReading thất bại: readingId=$readingId")
            }
        }
    }

    fun refreshSavedWordIds() {
        viewModelScope.launch {
            val ids = withContext(Dispatchers.IO) { userDb.getAllSavedWordIds() }
            _savedWordIds.value = ids
        }
    }

    fun onWordClick(word: SentenceWord, sentence: ReadingSentence) {
        _selectedWord.value = word
        _selectedPhrase.value = word.phraseId?.let { pid ->
            sentence.phrases.find { it.phraseId == pid }
        }
        _selectedDictEntry.value = repository.getDictEntry(word.textEn)
        _isDictExpanded.value = false
        Log.d("ReadingVM",
            "wordClick: \"${word.textEn}\" (id=${word.wordId})" +
                    (_selectedPhrase.value?.let { " → phrase=\"${it.textEn}\"" } ?: " → no phrase"))
    }

    fun toggleDictExpanded() {
        _isDictExpanded.value = !_isDictExpanded.value
    }

    fun dismissWordPopup() {
        _selectedWord.value     = null
        _selectedPhrase.value   = null
        _selectedDictEntry.value = null
        _isDictExpanded.value   = false
    }

    fun onSentenceClick(sentence: ReadingSentence) {
        _selectedSentence.value = sentence
        Log.d("ReadingVM", "sentenceClick: sentenceId=${sentence.sentenceId}")
    }

    fun dismissSentencePopup() {
        _selectedSentence.value = null
    }

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
        }
    }
}