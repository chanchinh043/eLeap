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
import com.eleap.eleap.feature.vocab.data.VocabRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class VocabViewModel(private val repository: VocabRepository) : ViewModel() {

    // ── Danh sách từ đã lưu ───────────────────────────────────────────────────
    private val _vocabList = MutableStateFlow<List<UserVocabularyEntry>>(emptyList())
    val vocabList: StateFlow<List<UserVocabularyEntry>> = _vocabList

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    init {
        loadVocab()
    }

    // ── Nạp lại danh sách từ users.db ─────────────────────────────────────────
    fun loadVocab() {
        viewModelScope.launch {
            _isLoading.value = true
            val result = repository.getAllVocabulary()
            _vocabList.value = result
            _isLoading.value = false
            Log.d("VocabVM", "loaded ${result.size} saved words")
        }
    }

    // ── Xoá 1 từ khỏi danh sách (vuốt/nhấn nút xoá trên VocabScreen) ─────────
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
            val userDb = UserDatabase.getInstance(context)
            val repo = VocabRepository(userDb)
            @Suppress("UNCHECKED_CAST")
            return VocabViewModel(repo) as T
        }
    }
}