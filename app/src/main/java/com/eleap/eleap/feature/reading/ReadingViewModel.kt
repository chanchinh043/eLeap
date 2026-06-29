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

    // Snackbar message từ AI processing — UI collect để hiển thị
    private val _aiStatusMessage = MutableStateFlow<String?>(null)
    val aiStatusMessage: StateFlow<String?> = _aiStatusMessage

    fun consumeAiStatusMessage() { _aiStatusMessage.value = null }

    // ── Notify ReadingScreen khi AI xử lý xong bài đang mở ───────────────────
    private val _aiCompletedReadingId = MutableStateFlow<Int>(-1)
    val aiCompletedReadingId: StateFlow<Int> = _aiCompletedReadingId

    fun notifyAiCompleted(readingId: Int) {
        invalidateReadingCache(readingId)
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
                _aiStatusMessage.value = msg
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
                _aiStatusMessage.value = msg
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

            _sentences.value        = result
            cachedReadingId         = readingId
            _isLoadingReading.value = false

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
        _selectedWord.value      = null
        _selectedPhrase.value    = null
        _selectedDictEntry.value = null
        _isDictExpanded.value    = false
    }

    fun onSentenceClick(sentence: ReadingSentence) {
        _selectedSentence.value = sentence
        Log.d("ReadingVM", "sentenceClick: sentenceId=${sentence.sentenceId}")
    }

    fun dismissSentencePopup() {
        _selectedSentence.value = null
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