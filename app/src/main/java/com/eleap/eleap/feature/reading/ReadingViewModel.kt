// ReadingViewModel.kt
package com.eleap.eleap.feature.reading

import android.content.Context
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

class ReadingViewModel(private val repository: ReadingRepository) : ViewModel() {

    // ── Flow 2: danh sách bài đọc ─────────────────────────────────────────────
    private val _readings = MutableStateFlow<List<Reading>>(emptyList())
    val readings: StateFlow<List<Reading>> = _readings

    // ── Flow 3: nội dung bài đọc ──────────────────────────────────────────────
    private val _sentences = MutableStateFlow<List<ReadingSentence>>(emptyList())
    val sentences: StateFlow<List<ReadingSentence>> = _sentences

    private val _isLoadingReading = MutableStateFlow(false)
    val isLoadingReading: StateFlow<Boolean> = _isLoadingReading

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
            _sentences.value = repository.getReading(readingId)
            _isLoadingReading.value = false
        }
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