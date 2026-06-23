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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// ── ReadingMode ───────────────────────────────────────────────────────────────
enum class ReadingMode { NONE, WORD, SENTENCE }

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
    private val _readingMode = MutableStateFlow(ReadingMode.NONE)
    val readingMode: StateFlow<ReadingMode> = _readingMode

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
        }
    }

    // ── Flow 4: toggle dịch từ ────────────────────────────────────────────────
    fun toggleWordMode() {
        _readingMode.value = if (_readingMode.value == ReadingMode.WORD) {
            ReadingMode.NONE
        } else {
            ReadingMode.WORD
        }
        Log.d("ReadingVM", "mode=${_readingMode.value}")
    }

    // ── Factory ───────────────────────────────────────────────────────────────
    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val db   = ReadingDatabase.getInstance(context)
            val dao  = ReadingDao(db.db)
            val repo = ReadingRepository(dao)
            @Suppress("UNCHECKED_CAST")
            return ReadingViewModel(repo) as T
        }
    }
}