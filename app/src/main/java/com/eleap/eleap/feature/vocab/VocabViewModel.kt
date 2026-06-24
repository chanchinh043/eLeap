// VocabViewModel.kt
// Đặt tại: com/eleap/eleap/feature/vocab/VocabViewModel.kt
package com.eleap.eleap.feature.vocab

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.eleap.eleap.feature.reading.ui.UserDatabase
import com.eleap.eleap.feature.reading.ui.UserVocabularyEntry
import com.eleap.eleap.feature.vocab.data.VocabDictEntry
import com.eleap.eleap.feature.vocab.data.VocabDictRepository
import com.eleap.eleap.feature.vocab.data.VocabRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class VocabViewModel(
    private val repository: VocabRepository,
    private val dictRepository: VocabDictRepository,
) : ViewModel() {

    // ── Danh sách từ đã lưu ───────────────────────────────────────────────────
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

    init {
        loadVocab()
    }

    // ── Nạp danh sách từ users.db, sau đó preload dict vào RAM ───────────────
    fun loadVocab() {
        viewModelScope.launch {
            _isLoading.value = true
            val result = repository.getAllVocabulary()
            _vocabList.value = result
            _isLoading.value = false
            Log.d("VocabVM", "loaded ${result.size} saved words")

            // Preload dict trong background — không chặn UI
            // Khi user tap từ, nghĩa đã có sẵn trong RAM → hiện popup tức thì
            launch {
                val words = result.mapNotNull { it.textEn }
                dictRepository.preloadDict(words)
            }
        }
    }

    // ── Mở popup khi tap vào từ — lấy từ RAM, không truy cập DB ─────────────
    fun onEntryClick(entry: UserVocabularyEntry) {
        _selectedEntry.value = entry
        _isDictExpanded.value = false

        // getDictEntry trả ngay từ RAM (sau preload); fallback DB nếu chưa xong
        viewModelScope.launch {
            _selectedDictEntry.value = dictRepository.getDictEntry(entry.textEn)
        }
    }

    // ── Đóng popup ────────────────────────────────────────────────────────────
    fun dismissPopup() {
        _selectedEntry.value = null
        _selectedDictEntry.value = null
        _isDictExpanded.value = false
    }

    // ── Toggle "Xem thêm" / "Thu gọn" trong popup ────────────────────────────
    fun toggleDictExpanded() {
        _isDictExpanded.value = !_isDictExpanded.value
    }

    // ── Xoá từ ───────────────────────────────────────────────────────────────
    fun deleteWord(id: Int) {
        viewModelScope.launch {
            if (repository.deleteWord(id)) {
                _vocabList.value = _vocabList.value.filterNot { it.id == id }
            }
        }
    }

    // ── Factory ───────────────────────────────────────────────────────────────
    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val userDb   = UserDatabase.getInstance(context)
            val repo     = VocabRepository(userDb)
            val dictRepo = VocabDictRepository.getInstance(context)
            @Suppress("UNCHECKED_CAST")
            return VocabViewModel(repo, dictRepo) as T
        }
    }
}