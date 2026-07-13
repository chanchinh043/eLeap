package com.eleap.eleap.feature.reading

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.eleap.eleap.core.auth.CurrentUser
import com.eleap.eleap.core.sync.SyncEngine
// ⚠️ MỚI (core/tts/remote/): cần biết sid Kokoro mục tiêu (nếu có) để enqueue
// tải gói giọng đọc cho ĐÚNG bài vừa mở — xem loadReading() bên dưới.
import com.eleap.eleap.core.tts.TtsVoiceSnapshot
import com.eleap.eleap.core.tts.remote.TtsRemotePackScheduler
import com.eleap.eleap.feature.myreading.data.MyReadingRepository
import com.eleap.eleap.feature.myreading.data.processUnhandledMyReadings
import com.eleap.eleap.feature.myreading.sync.MyReadingSyncEngine
import com.eleap.eleap.feature.myreading.sync.MyReadingSyncScheduler
import com.eleap.eleap.feature.reading.data.Reading
import com.eleap.eleap.feature.reading.data.ReadingDao
import com.eleap.eleap.feature.reading.data.ReadingDatabase
import com.eleap.eleap.feature.reading.data.ReadingRepository
import com.eleap.eleap.feature.reading.data.ReadingSentence
import com.eleap.eleap.feature.reading.data.DictEntry
import com.eleap.eleap.feature.reading.data.SentencePhrase
import com.eleap.eleap.feature.reading.data.SentenceWord
import com.eleap.eleap.feature.vocab.data.VocabRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ReadingViewModel(
    private val repository: ReadingRepository,
    private val myReadingRepository: MyReadingRepository,
    private val vocabRepository: VocabRepository,   // ← thay cho UserDatabase
    private val appContext: Context,
) : ViewModel() {

    // ── Flow 2 ────────────────────────────────────────────────────────────────
    private val _readings = MutableStateFlow<List<Reading>>(emptyList())
    val readings: StateFlow<List<Reading>> = _readings

    val systemReadings: StateFlow<List<Reading>> =
        readings.map { list -> list.filter { it.userId == null } }
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    val myReadings: StateFlow<List<Reading>> =
        readings.map { list -> list.filter { it.userId != null } }
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    // ── Flow 3 ────────────────────────────────────────────────────────────────
    private val _sentences = MutableStateFlow<List<ReadingSentence>>(emptyList())
    val sentences: StateFlow<List<ReadingSentence>> = _sentences

    private val _isLoadingReading = MutableStateFlow(false)
    val isLoadingReading: StateFlow<Boolean> = _isLoadingReading

    // ── Flow 6: từ đang được chọn để hiện WordPopup ───────────────────────────
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

    private val _savedWordIds = MutableStateFlow<Set<String>>(emptySet())
    val savedWordIds: StateFlow<Set<String>> = _savedWordIds

    private var cachedReadingId: String? = null

    init {
        loadReadings()
        refreshSavedWordIds()

        viewModelScope.launch {
            CurrentUser.userId.drop(1).collect {
                loadReadings(forceRefresh = true)
                refreshSavedWordIds()
            }
        }

        // Tự refresh highlight từ đã lưu khi SyncEngine vừa push/pull xong VÀ
        // thực sự có thay đổi (bấm nút Đồng bộ, SyncPushWorker/SyncPullWorker
        // chạy nền, hoặc syncNow() sau khi đăng nhập ở MainActivity) — không
        // cần rời ReadingScreen rồi quay lại mới thấy màu từ cập nhật.
        // filter đúng userId đang active — phòng tín hiệu cũ của 1 lượt sync
        // trước đó (vd tài khoản khác) lọt về trễ.
        viewModelScope.launch {
            SyncEngine.dataChanged
                .filter { changedUserId -> changedUserId == CurrentUser.userId.value }
                .collect {
                    refreshSavedWordIds()
                }
        }

        // Tự refresh danh sách readings (myReadings/systemReadings) khi
        // MyReadingSyncEngine vừa push/pull xong VÀ thực sự có thay đổi (bấm
        // nút Đồng bộ, MyReadingSyncPushWorker/MyReadingSyncPullWorker chạy
        // nền, hoặc syncNow() sau khi đăng nhập ở MainActivity, hoặc
        // MyReadingSyncRealtime vừa áp 1 sự kiện realtime) — để danh sách bài
        // đọc tự cập nhật khi 1 thiết bị khác vừa tạo/sửa/xoá bài đọc và đồng
        // bộ về, không cần rời màn rồi quay lại mới thấy. filter đúng userId
        // đang active — cùng lý do như khối SyncEngine.dataChanged ở trên.
        viewModelScope.launch {
            MyReadingSyncEngine.dataChanged
                .filter { changedUserId -> changedUserId == CurrentUser.userId.value }
                .collect {
                    loadReadings(forceRefresh = true)
                }
        }

        viewModelScope.launch {
            while (true) {
                runMyReadingAiWatchdog()
                delay(15_000L)
            }
        }
    }

    private suspend fun runMyReadingAiWatchdog() {
        processUnhandledMyReadings(
            context = appContext,
            onStatus = { msg -> Log.d("ReadingVM.AiWatchdog", msg) },
            onUpdated = {
                loadReadings(forceRefresh = true)
                cachedReadingId?.let { reloadCurrentReading(it) }
                // AI vừa ghi xong dữ liệu (title_vi/phrases/words) — đẩy lên
                // server NGAY, phòng trường hợp lượt push trước đó (lúc mới
                // tạo bài) đã chạy TRƯỚC khi AI kịp xong, khiến bài bị đánh
                // dấu synced khi payload còn thiếu title_vi/phrases.
                MyReadingSyncScheduler.enqueueImmediatePush(appContext)
            },
        )
    }

    private fun reloadCurrentReading(readingId: String) {
        viewModelScope.launch {
            val result = repository.getReading(readingId, forceRefresh = true)
            _sentences.value = result
            resyncSelectedAfterReload(result)
        }
    }

    private fun resyncSelectedAfterReload(freshSentences: List<ReadingSentence>) {
        _selectedSentence.value?.let { old ->
            _selectedSentence.value = freshSentences.find { it.sentenceId == old.sentenceId } ?: old
        }

        val oldWord = _selectedWord.value
        if (oldWord != null) {
            val freshSentence = freshSentences.find { it.sentenceId == oldWord.sentenceId }
            val freshWord = freshSentence?.words?.find { it.wordId == oldWord.wordId }
            if (freshWord != null) {
                _selectedWord.value = freshWord
                _selectedDictEntry.value = repository.getDictEntry(freshWord.textEn)
                _selectedPhrase.value = freshWord.phraseId?.let { pid ->
                    freshSentence.phrases.find { it.phraseId == pid }
                }
            }
        } else {
            val oldPhrase = _selectedPhrase.value
            if (oldPhrase != null) {
                val freshPhrase = freshSentences
                    .flatMap { it.phrases }
                    .find { it.phraseId == oldPhrase.phraseId }
                if (freshPhrase != null) _selectedPhrase.value = freshPhrase
            }
        }
    }

    private fun loadReadings(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _readings.value = repository.getAllReadings(forceRefresh)
        }
    }

    fun refreshReadings() {
        loadReadings(forceRefresh = true)
    }

    fun loadReading(readingId: String) {
        if (readingId == cachedReadingId) {
            Log.d("ReadingVM", "readingId=$readingId đã cache, bỏ qua loadReading()")
            return
        }

        // ── MỚI (core/tts/remote/): enqueue tải gói giọng đọc từ xa cho
        // ĐÚNG (readingId, sid) vừa mở — chỉ khi đang dùng Kokoro (sid !=
        // null, xem TtsVoiceSnapshot.currentTargetSid()). Đặt TRƯỚC phần
        // load nội dung bài bên dưới, không CHỜ nó — đây là việc chạy nền
        // qua WorkManager (TtsRemotePackWorker), không block UI. Nếu chưa
        // cấu hình nguồn tải (TtsRemoteSourceRegistry rỗng) hoặc mất mạng,
        // Worker tự bỏ qua an toàn — pregen/ vẫn tự sinh audio như cũ.
        TtsVoiceSnapshot.currentTargetSid()?.let { sid ->
            TtsRemotePackScheduler.enqueueDownload(appContext, readingId, sid)
        }

        viewModelScope.launch {
            _isLoadingReading.value = true

            val before = System.currentTimeMillis()
            val result = repository.getReading(readingId)
            val elapsed = System.currentTimeMillis() - before

            Log.d("ReadingVM", "readingId=$readingId | sentences=${result.size} | time=${elapsed}ms")

            _sentences.value = result
            cachedReadingId  = readingId
            _isLoadingReading.value = false

            launch { repository.preloadDictForReading(result) }
        }
    }

    fun addMyReading(title: String, content: String, onDone: (readingId: String?) -> Unit) {
        viewModelScope.launch {
            val id = myReadingRepository.saveMyReading(title, content)
            loadReadings(forceRefresh = true)
            onDone(id)

            if (id != null) {
                launch { runMyReadingAiWatchdog() }
                // Tạo mới là thao tác cần đồng bộ NGAY, không đợi chu kỳ 3h.
                MyReadingSyncScheduler.enqueueImmediatePush(appContext)
            }
        }
    }

    fun deleteMyReading(readingId: String, onDone: (success: Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = myReadingRepository.deleteMyReading(readingId)
            if (ok) {
                loadReadings(forceRefresh = true)
                // Xoá là thao tác cần đồng bộ NGAY, không đợi chu kỳ 3h.
                MyReadingSyncScheduler.enqueueImmediatePush(appContext)
            }
            onDone(ok)
        }
    }

    // ── savedWordIds: nạp lại qua VocabRepository (bỏ deleted_at IS NULL) ────
    fun refreshSavedWordIds() {
        viewModelScope.launch {
            _savedWordIds.value = vocabRepository.getAllSavedWordIds()
        }
    }

    // ── Flow 6: click vào từ ──────────────────────────────────────────────────
    fun onWordClick(word: SentenceWord, sentence: ReadingSentence) {
        _selectedWord.value = word

        _selectedPhrase.value = word.phraseId?.let { pid ->
            sentence.phrases.find { it.phraseId == pid }
        }

        _selectedDictEntry.value = repository.getDictEntry(word.textEn)
        _isDictExpanded.value = false

        Log.d(
            "ReadingVM",
            "wordClick: \"${word.textEn}\" (id=${word.wordId})" +
                    (_selectedPhrase.value?.let { " → phrase=\"${it.textEn}\"" } ?: " → no phrase")
        )
    }

    fun toggleDictExpanded() {
        _isDictExpanded.value = !_isDictExpanded.value
    }

    fun dismissWordPopup() {
        _selectedWord.value = null
        _selectedPhrase.value = null
        _selectedDictEntry.value = null
        _isDictExpanded.value = false
    }

    fun onSentenceClick(sentence: ReadingSentence) {
        _selectedSentence.value = sentence
        Log.d("ReadingVM", "sentenceClick: sentenceId=${sentence.sentenceId}")
    }

    fun dismissSentencePopup() {
        _selectedSentence.value = null
    }

    fun onPhraseRangeSelect(anchorWord: SentenceWord, sentence: ReadingSentence) {
        val phrase = anchorWord.phraseId?.let { pid ->
            sentence.phrases.find { it.phraseId == pid }
        }

        if (phrase == null) {
            Log.d(
                "ReadingVM",
                "phraseRangeSelect: \"${anchorWord.textEn}\" không có phrase hợp lệ → bỏ qua"
            )
            return
        }

        _selectedWord.value = null
        _selectedDictEntry.value = null
        _isDictExpanded.value = false

        _selectedPhrase.value = phrase
        Log.d(
            "ReadingVM",
            "phraseRangeSelect: \"${anchorWord.textEn}\" → phrase=\"${phrase.textEn}\" (id=${phrase.phraseId})"
        )
    }

    fun dismissPhrasePopup() {
        _selectedPhrase.value = null
    }

    // ── Factory singleton ─────────────────────────────────────────────────────
    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return (INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val appCtx = context.applicationContext
                    val db     = ReadingDatabase.getInstance(appCtx)
                    val dao    = ReadingDao(db.db, db.dictDb)
                    val myRepo = MyReadingRepository.getInstance(appCtx)
                    val repo   = ReadingRepository(dao, myRepo)
                    val vocabRepo = VocabRepository.getInstance(appCtx)   // thay UserDatabase
                    ReadingViewModel(repo, myRepo, vocabRepo, appCtx).also { INSTANCE = it }
                }
            }) as T
        }

        companion object {
            @Volatile private var INSTANCE: ReadingViewModel? = null
        }
    }
}