// VocabViewModel.kt
// Đặt tại: com/eleap/eleap/feature/vocab/VocabViewModel.kt
package com.eleap.eleap.feature.vocab

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.eleap.eleap.feature.reading.ui.UserVocabularyEntry
import com.eleap.eleap.feature.vocab.data.VocabDictEntry
import com.eleap.eleap.feature.vocab.data.VocabRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class VocabViewModel(
    private val repository: VocabRepository,
) : ViewModel() {

    private val _vocabList = MutableStateFlow<List<UserVocabularyEntry>>(emptyList())
    val vocabList: StateFlow<List<UserVocabularyEntry>> = _vocabList

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    // ── Popup state ───────────────────────────────────────────────────────────
    private val _selectedEntry = MutableStateFlow<UserVocabularyEntry?>(null)
    val selectedEntry: StateFlow<UserVocabularyEntry?> = _selectedEntry

    private val _selectedDictEntry = MutableStateFlow<VocabDictEntry?>(null)
    val selectedDictEntry: StateFlow<VocabDictEntry?> = _selectedDictEntry

    private val _isDictExpanded = MutableStateFlow(false)
    val isDictExpanded: StateFlow<Boolean> = _isDictExpanded

    // ── Số từ đang được chọn để học ──────────────────────────────────────────
    val selectedCount: StateFlow<Int> get() = _selectedCount
    private val _selectedCount = MutableStateFlow(0)

    // ── Từ vựng theo bài đọc (VocabReadingScreen) ────────────────────────────
    private val _readingVocabList = MutableStateFlow<List<UserVocabularyEntry>>(emptyList())
    val readingVocabList: StateFlow<List<UserVocabularyEntry>> = _readingVocabList

    private val _isLoadingReadingVocab = MutableStateFlow(false)
    val isLoadingReadingVocab: StateFlow<Boolean> = _isLoadingReadingVocab

    fun loadVocabForReading(readingId: Int) {
        viewModelScope.launch {
            _isLoadingReadingVocab.value = true
            _readingVocabList.value = repository.getVocabByReadingId(readingId)
            _isLoadingReadingVocab.value = false
        }
    }



    fun loadVocab() {
        viewModelScope.launch {
            _isLoading.value = true
            val result = repository.getAllVocabulary()
            _vocabList.value = result
            _selectedCount.value = result.count { it.selected == 1 }
            _isLoading.value = false
            Log.d("VocabVM", "loaded ${result.size} words, ${_selectedCount.value} selected")

            launch {
                val words = result.mapNotNull { it.textEn }
                repository.preloadDict(words)
            }
        }
    }

    // ── Toggle checkbox chọn/bỏ chọn từ để học ───────────────────────────────
    fun toggleSelected(entry: UserVocabularyEntry) {
        val newSelected = if (entry.selected == 1) 0 else 1
        viewModelScope.launch {
            if (repository.updateSelected(entry.id, newSelected)) {
                _vocabList.value = _vocabList.value.map {
                    if (it.id == entry.id) it.copy(selected = newSelected) else it
                }
                _selectedCount.value = _vocabList.value.count { it.selected == 1 }
            }
        }
    }

    // ── Mở popup ─────────────────────────────────────────────────────────────
    fun onEntryClick(entry: UserVocabularyEntry) {
        _selectedEntry.value = entry
        _isDictExpanded.value = false
        viewModelScope.launch {
            _selectedDictEntry.value = repository.getDictEntry(entry.textEn)
        }
    }

    fun dismissPopup() {
        _selectedEntry.value = null
        _selectedDictEntry.value = null
        _isDictExpanded.value = false
    }

    fun toggleDictExpanded() {
        _isDictExpanded.value = !_isDictExpanded.value
    }

    fun deleteWord(id: Int) {
        viewModelScope.launch {
            if (repository.deleteWord(id)) {
                _vocabList.value = _vocabList.value.filterNot { it.id == id }
                _selectedCount.value = _vocabList.value.count { it.selected == 1 }
            }
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return VocabViewModel(VocabRepository.getInstance(context)) as T
        }
    }
}