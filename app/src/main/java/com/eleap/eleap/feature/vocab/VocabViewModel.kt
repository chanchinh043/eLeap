// VocabViewModel.kt
// Đặt tại: com/eleap/eleap/feature/vocab/VocabViewModel.kt
package com.eleap.eleap.feature.vocab

import android.content.Context
import android.content.SharedPreferences
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
    private val prefs: SharedPreferences,
) : ViewModel() {

    // ── SharedPreferences key per tab: "reading_sel_<readingId>_<tab>" → "1,2,3" ──
    private var currentReadingId: Int = -1

    // Tab names dùng làm key — phải khớp với VocabReadingTab.name
    private val TAB_KEYS = listOf("NEW", "RECENT", "ALL")

    private fun prefsKey(readingId: Int, tabName: String) = "reading_sel_${readingId}_$tabName"
    private fun autoSelectKey(readingId: Int) = "reading_autoselect_$readingId"

    private fun saveAutoSelect(readingId: Int, enabled: Boolean) {
        prefs.edit().putBoolean(autoSelectKey(readingId), enabled).apply()
    }

    private fun loadAutoSelect(readingId: Int): Boolean =
        prefs.getBoolean(autoSelectKey(readingId), true)  // mặc định true

    private fun saveTabSelection(readingId: Int, tabName: String, ids: Set<Int>) {
        prefs.edit()
            .putString(prefsKey(readingId, tabName), ids.joinToString(","))
            .apply()
    }

    private fun loadTabSelection(readingId: Int, tabName: String): Set<Int> {
        val raw = prefs.getString(prefsKey(readingId, tabName), "") ?: ""
        return if (raw.isBlank()) emptySet()
        else raw.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
    }

    private fun loadAllTabSelections(readingId: Int): Map<String, Set<Int>> =
        TAB_KEYS.associateWith { loadTabSelection(readingId, it) }

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

    // ── Tab đang hiển thị trong VocabReadingScreen (nhớ khi back từ Study) ──
    private val _readingActiveTab = MutableStateFlow("NEW")
    val readingActiveTab: StateFlow<String> = _readingActiveTab

    fun setReadingActiveTab(tabName: String) { _readingActiveTab.value = tabName }
    fun resetReadingActiveTab() { _readingActiveTab.value = "NEW" }

    // ── Số từ đang được chọn trong VocabScreen ───────────────────────────────
    private val _selectedCount = MutableStateFlow(0)
    val selectedCount: StateFlow<Int> = _selectedCount

    // ── Từ vựng theo bài đọc (VocabReadingScreen) ────────────────────────────
    private val _readingVocabList = MutableStateFlow<List<UserVocabularyEntry>>(emptyList())
    val readingVocabList: StateFlow<List<UserVocabularyEntry>> = _readingVocabList

    // Khởi tạo true → luôn hiện spinner trước, tránh flash "Chưa có từ nào"
    private val _isLoadingReadingVocab = MutableStateFlow(true)
    val isLoadingReadingVocab: StateFlow<Boolean> = _isLoadingReadingVocab

    // ── Selected theo từng tab — KHÔNG ghi DB, lưu vào SharedPreferences ─────
    // Map<tabName, Set<Int>>: NEW / RECENT / ALL mỗi tab có selection riêng biệt.
    // Tắt app bật lại vẫn còn, reset khi chuyển sang bài đọc khác.
    private val _readingSelectedByTab = MutableStateFlow<Map<String, Set<Int>>>(emptyMap())
    val readingSelectedByTab: StateFlow<Map<String, Set<Int>>> = _readingSelectedByTab

    // ── Trạng thái "Tự động chọn tất cả" tab NEW — lưu vào SharedPreferences ─
    private val _readingAutoSelect = MutableStateFlow(true)
    val readingAutoSelect: StateFlow<Boolean> = _readingAutoSelect

    fun setAutoSelect(enabled: Boolean) {
        _readingAutoSelect.value = enabled
        saveAutoSelect(currentReadingId, enabled)
    }

    // ── Load từ theo bài đọc ──────────────────────────────────────────────────
    fun loadVocabForReading(readingId: Int) {
        viewModelScope.launch {
            _isLoadingReadingVocab.value = true
            if (readingId != currentReadingId) {
                currentReadingId = readingId
                _readingSelectedByTab.value = loadAllTabSelections(readingId)
                _readingAutoSelect.value = loadAutoSelect(readingId)
            }
            val words = repository.getVocabByReadingId(readingId)
            _readingVocabList.value = words

            // Tab NEW: chỉ select all nếu autoSelect đang bật
            if (_readingAutoSelect.value) {
                val newIds = words.filter { it.count < 30 }.map { it.id }.toSet()
                _readingSelectedByTab.value = _readingSelectedByTab.value + ("NEW" to newIds)
                saveTabSelection(readingId, "NEW", newIds)
            }

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

    // ── Toggle selected trong VocabScreen (vocabList) — vẫn ghi DB ───────────
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

    // ── Toggle selected tạm trong VocabReadingScreen theo tab — lưu SharedPreferences ──
    fun toggleSelectedInReading(entry: UserVocabularyEntry, tabName: String) {
        val currentMap = _readingSelectedByTab.value
        val currentSet = currentMap[tabName] ?: emptySet()
        val updatedSet = if (entry.id in currentSet) currentSet - entry.id else currentSet + entry.id
        _readingSelectedByTab.value = currentMap + (tabName to updatedSet)
        saveTabSelection(currentReadingId, tabName, updatedSet)
    }

    // ── Đặt toàn bộ selection cho 1 tab (dùng cho Chọn tất cả / Bỏ chọn tất cả) ──
    fun setAllSelectedInReading(ids: Set<Int>, tabName: String) {
        _readingSelectedByTab.value = _readingSelectedByTab.value + (tabName to ids)
        saveTabSelection(currentReadingId, tabName, ids)
    }

    // ── Lấy selected IDs của 1 tab cụ thể (dùng ở MainScreen để build pool) ──
    fun getSelectedIdsForTab(tabName: String): Set<Int> =
        _readingSelectedByTab.value[tabName] ?: emptySet()

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
                // Xóa ID này khỏi tất cả các tab, đồng bộ vào prefs
                val updated = _readingSelectedByTab.value.mapValues { (tabName, ids) ->
                    val newSet = ids - id
                    saveTabSelection(currentReadingId, tabName, newSet)
                    newSet
                }
                _readingSelectedByTab.value = updated
                // Sync màu từ trong ReadingScreen
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
                prefs      = context.applicationContext.getSharedPreferences(
                    "vocab_reading_selection", Context.MODE_PRIVATE
                ),
            ) as T
        }
    }
}