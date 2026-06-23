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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// ── ReadingMode ───────────────────────────────────────────────────────────────


class ReadingViewModel(private val repository: ReadingRepository) : ViewModel() {

    // ── Flow 2 ────────────────────────────────────────────────────────────────
    private val _readings = MutableStateFlow<List<Reading>>(emptyList())
    val readings: StateFlow<List<Reading>> = _readings

    // ── Flow 3 ────────────────────────────────────────────────────────────────
    private val _sentences = MutableStateFlow<List<ReadingSentence>>(emptyList())
    val sentences: StateFlow<List<ReadingSentence>> = _sentences

    private val _isLoadingReading = MutableStateFlow(false)
    val isLoadingReading: StateFlow<Boolean> = _isLoadingReading

    // ── Flow 4/5: mode dịch ───────────────────────────────────────────────────


    // ── Flow 6: từ đang được chọn để hiện WordPopup ───────────────────────────
    private val _selectedWord = MutableStateFlow<SentenceWord?>(null)
    val selectedWord: StateFlow<SentenceWord?> = _selectedWord

    // ── Flow 6 (phrase): cụm từ chứa từ đang click (null nếu từ không thuộc cụm) ──
    private val _selectedPhrase = MutableStateFlow<SentencePhrase?>(null)
    val selectedPhrase: StateFlow<SentencePhrase?> = _selectedPhrase

    // ── Flow 7: câu đang được chọn để hiện SentencePopup ─────────────────────
    private val _selectedSentence = MutableStateFlow<ReadingSentence?>(null)
    val selectedSentence: StateFlow<ReadingSentence?> = _selectedSentence

    // ── Dict: nghĩa từ điển (dict.db) của từ đang chọn ────────────────────────
    private val _selectedDictEntry = MutableStateFlow<DictEntry?>(null)
    val selectedDictEntry: StateFlow<DictEntry?> = _selectedDictEntry

    // ── Dict: trạng thái "Xem thêm" (hiện meaning đầy đủ) trong WordPopup ────
    private val _isDictExpanded = MutableStateFlow(false)
    val isDictExpanded: StateFlow<Boolean> = _isDictExpanded

    init {
        loadReadings()
    }

    // ── Flow 2 ────────────────────────────────────────────────────────────────
    private fun loadReadings() {
        viewModelScope.launch {
            _readings.value = repository.getAllReadings()
        }
    }

    // ── Flow 3 ────────────────────────────────────────────────────────────────
    fun loadReading(readingId: Int) {
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
            _isLoadingReading.value = false

            // ── Background: nạp Dict RAM cho các từ trong bài, không chặn UI ──
            launch { repository.preloadDictForReading(result) }
        }
    }

    // ── Flow 4: toggle dịch từ ────────────────────────────────────────────────


    // ── Flow 6: click vào từ — lấy từ RAM, không truy cập DB ─────────────────
    // sentence được truyền vào để lookup phrase từ sentence.phrases (RAM)
    fun onWordClick(word: SentenceWord, sentence: ReadingSentence) {
        _selectedWord.value = word

        // Nếu từ có phraseId → tìm phrase tương ứng trong sentence.phrases (RAM)
        _selectedPhrase.value = word.phraseId?.let { pid ->
            sentence.phrases.find { it.phraseId == pid }
        }

        // Dict RAM: tra nghĩa từ điển (không truy cập DB)
        _selectedDictEntry.value = repository.getDictEntry(word.textEn)
        _isDictExpanded.value = false

        Log.d(
            "ReadingVM",
            "wordClick: \"${word.textEn}\" (id=${word.wordId})" +
                    (_selectedPhrase.value?.let { " → phrase=\"${it.textEn}\"" } ?: " → no phrase")
        )
    }

    // ── Toggle "Xem thêm" / "Thu gọn" nghĩa đầy đủ (dict.db) trong WordPopup ──
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

    // ── Flow 7: click vào câu — lấy từ RAM, không truy cập DB ────────────────
    fun onSentenceClick(sentence: ReadingSentence) {
        _selectedSentence.value = sentence
        Log.d("ReadingVM", "sentenceClick: sentenceId=${sentence.sentenceId}")
    }

    // ── Flow 9: đóng SentencePopup ───────────────────────────────────────────
    fun dismissSentencePopup() {
        _selectedSentence.value = null
    }

    // ── Factory ───────────────────────────────────────────────────────────────
    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val db   = ReadingDatabase.getInstance(context)
            val dao  = ReadingDao(db.db, db.dictDb)
            val repo = ReadingRepository(dao)
            @Suppress("UNCHECKED_CAST")
            return ReadingViewModel(repo) as T
        }
    }
}