// VocabViewModel.kt
// Đặt tại: com/eleap/eleap/feature/vocab/VocabViewModel.kt
package com.eleap.eleap.feature.vocab

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.eleap.eleap.feature.reading.ReadingViewModel
import com.eleap.eleap.feature.reading.ui.UserVocabularyEntry
import com.eleap.eleap.feature.vocab.data.VocabDictEntry
import com.eleap.eleap.feature.vocab.data.VocabRepository
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class VocabViewModel(
    private val repository: VocabRepository,
    // ReadingViewModel singleton — để gọi refreshSavedWordIds() sau khi xóa từ,
    // giúp màu từ trong ReadingScreen cập nhật ngay mà không cần sửa thêm file nào.
    private val readingVm: ReadingViewModel,
) : ViewModel() {

    // ── Toàn bộ từ vựng (VocabScreen) ────────────────────────────────────────
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

    // ── Vị trí anchor cho popup (chia sẻ toàn app, 1 popup duy nhất) ────────
    private val _anchorRect = MutableStateFlow<Rect?>(null)
    val anchorRect: StateFlow<Rect?> = _anchorRect

    fun setAnchorRect(rect: Rect?) { _anchorRect.value = rect }

    // ── Số từ đang được chọn trong VocabScreen ───────────────────────────────
    private val _selectedCount = MutableStateFlow(0)
    val selectedCount: StateFlow<Int> = _selectedCount

    // ── Từ vựng theo bài đọc (VocabReadingScreen) ────────────────────────────
    private val _readingVocabList = MutableStateFlow<List<UserVocabularyEntry>>(emptyList())
    val readingVocabList: StateFlow<List<UserVocabularyEntry>> = _readingVocabList

    // Khởi tạo true → luôn hiện spinner trước, tránh flash "Chưa có từ nào"
    private val _isLoadingReadingVocab = MutableStateFlow(true)
    val isLoadingReadingVocab: StateFlow<Boolean> = _isLoadingReadingVocab

    // ── Load từ theo bài đọc ──────────────────────────────────────────────────
    fun loadVocabForReading(readingId: Int) {
        viewModelScope.launch {
            _isLoadingReadingVocab.value = true
            _readingVocabList.value = repository.getVocabByReadingId(readingId)
            _isLoadingReadingVocab.value = false
        }
    }

    // ── Load toàn bộ từ vựng ─────────────────────────────────────────────────
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

    // ── Toggle selected trong VocabScreen (vocabList) ─────────────────────────
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

    // ── Toggle selected trong VocabReadingScreen (readingVocabList) ───────────
    fun toggleSelectedInReading(entry: UserVocabularyEntry) {
        val newSelected = if (entry.selected == 1) 0 else 1
        viewModelScope.launch {
            if (repository.updateSelected(entry.id, newSelected)) {
                _readingVocabList.value = _readingVocabList.value.map {
                    if (it.id == entry.id) it.copy(selected = newSelected) else it
                }
            }
        }
    }

    // ── Xóa từ trong VocabScreen ──────────────────────────────────────────────
    fun deleteWord(id: Int) {
        viewModelScope.launch {
            if (repository.deleteWord(id)) {
                _vocabList.value = _vocabList.value.filterNot { it.id == id }
                _selectedCount.value = _vocabList.value.count { it.selected == 1 }
                // Sync màu từ trong ReadingScreen
                readingVm.refreshSavedWordIds()
            }
        }
    }

    // ── Xóa từ trong VocabReadingScreen ──────────────────────────────────────
    fun deleteWordFromReading(id: Int) {
        viewModelScope.launch {
            if (repository.deleteWord(id)) {
                _readingVocabList.value = _readingVocabList.value.filterNot { it.id == id }
                // Sync màu từ trong ReadingScreen — 1 dòng duy nhất cần thêm
                readingVm.refreshSavedWordIds()
            }
        }
    }

    // ── Popup ─────────────────────────────────────────────────────────────────
    fun onEntryClick(entry: UserVocabularyEntry) {
        _selectedEntry.value = entry
        _isDictExpanded.value = false
        viewModelScope.launch {
            _selectedDictEntry.value = repository.getDictEntry(entry.textEn)
        }
    }

    // ── Tăng count mỗi lần từ xuất hiện khi quay flashcard ──────────────────
    fun incrementCount(entry: UserVocabularyEntry) {
        viewModelScope.launch {
            repository.incrementCount(entry.id)
            // Cập nhật local state để tab phân loại phản ánh ngay
            val updated = entry.copy(count = entry.count + 1)
            _vocabList.value = _vocabList.value.map {
                if (it.id == entry.id) updated else it
            }
            _readingVocabList.value = _readingVocabList.value.map {
                if (it.id == entry.id) updated else it
            }
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

    // ── Factory ───────────────────────────────────────────────────────────────
    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return VocabViewModel(
                repository = VocabRepository.getInstance(context),
                // Dùng lại singleton của ReadingViewModel — cùng instance với ReadingScreen
                readingVm  = ReadingViewModel.Factory(context).create(ReadingViewModel::class.java),
            ) as T
        }
    }
}