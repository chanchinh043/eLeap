// MyReadingSyncEngine.kt
// Đặt tại: feature/myreading/sync/MyReadingSyncEngine.kt
//
// Nơi DUY NHẤT điều phối toàn bộ logic đồng bộ cho MyReading — bản tương
// đương core/sync/SyncEngine.kt (dùng cho user_vocabulary) nhưng cho 4 bảng
// readings/reading_sentences/sentence_phrases/sentence_words. Gọi
// MyReadingRepository để lấy/ghi dữ liệu cục bộ, gọi MyReadingSyncApi để nói
// chuyện với Supabase, gọi MyReadingSyncCursor để đọc/ghi mốc pull.
//
// ⚠️ ĐÃ ĐỔI THIẾT KẾ: trước đây đóng gói cả bài thành 1 chuỗi JSON (cột
// payload). Giờ mapping thẳng Entity (Reading/ReadingSentence/SentencePhrase/
// SentenceWord ở feature/reading/data) ↔ Dto (ReadingRowDto/ReadingSentenceDto/
// SentencePhraseDto/SentenceWordDto ở MyReadingSyncApi.kt) — không còn
// buildPayloadJson()/parsePayloadJson() nào nữa.
//
// Dùng chung cho cả 3 nơi gọi vào (giống hệt bản cũ):
//   - Nút "Đồng bộ" ở MainScreen → gọi syncNow() ngay lập tức, 1 lần.
//   - MyReadingSyncWorker (chạy nền theo lịch 3h/5h) → gọi lại đúng các hàm
//     ở đây, không viết lại logic.
//   - MyReadingSyncRealtime → khi nhận sự kiện qua WebSocket, gọi
//     notifyDataChanged() để tái sử dụng đúng tín hiệu dataChanged.
//
// Singleton thủ công, KHÔNG dùng Hilt/DI.
package com.eleap.eleap.feature.myreading.sync

import android.content.Context
import android.util.Log
import com.eleap.eleap.core.tts.kokoro.myreading.TtsMyReadingSyncTrigger
import com.eleap.eleap.feature.myreading.data.MyReadingRepository
import com.eleap.eleap.feature.myreading.data.MyReadingSyncStatus
import com.eleap.eleap.feature.reading.data.Reading
import com.eleap.eleap.feature.reading.data.ReadingSentence
import com.eleap.eleap.feature.reading.data.SentencePhrase
import com.eleap.eleap.feature.reading.data.SentenceWord
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "MyReadingSyncEngine"

object MyReadingSyncEngine {

    private lateinit var repo: MyReadingRepository

    // Giữ lại applicationContext — CHỈ dùng để chuyển tiếp cho
    // TtsMyReadingSyncTrigger.onReadingsSynced() sau khi push thành công
    // (xem pushPendingLocked() bên dưới). KHÔNG dùng cho việc gì khác trong
    // file này — mọi thao tác DB vẫn đi qua repo như trước.
    private lateinit var appContext: Context

    // ── Tín hiệu "vừa có dữ liệu MyReading thay đổi do sync" ─────────────────
    private val _dataChanged = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 1,
    )
    val dataChanged: SharedFlow<String> = _dataChanged.asSharedFlow()

    // ── Điểm vào công khai DUY NHẤT để phát tín hiệu dataChanged từ BÊN
    // NGOÀI MyReadingSyncEngine — hiện chỉ dùng bởi MyReadingSyncRealtime.
    fun notifyDataChanged(userId: String) {
        _dataChanged.tryEmit(userId)
    }

    // ── Khoá dùng chung giữa MyReadingSyncPushWorker (gọi pushPending() trực
    // tiếp, định kỳ 3h) và MyReadingSyncPullWorker (gọi syncNow(), bên trong
    // tự gọi lại pushPending() rồi mới pull, định kỳ 5h). Mutex non-reentrant
    // nên syncNow() KHÔNG gọi lại pushPending() công khai — dùng
    // pushPendingLocked() nội bộ cho trường hợp đó.
    private val syncMutex = Mutex()

    // Gọi 1 lần ở MainActivity.onCreate(), sau khi MyReadingSyncCursor.init()
    // đã chạy.
    fun init(context: Context) {
        appContext = context.applicationContext
        repo = MyReadingRepository.getInstance(context)
    }

    // ── Kết quả trả về cho UI hiển thị (vd Toast/Snackbar ở nút Đồng bộ) ─────
    data class SyncOutcome(
        val pushedCount: Int,
        val pulledCount: Int,
        val ranFullPull: Boolean,
        val error: String? = null,
    )

    // ══ HÀM CHÍNH — gọi từ nút bấm hoặc từ worker nền ═══════════════════════
    suspend fun syncNow(userId: String): SyncOutcome {
        if (userId == "guest") {
            return SyncOutcome(pushedCount = 0, pulledCount = 0, ranFullPull = false)
        }

        return try {
            val outcome = syncMutex.withLock {
                val pushed = pushPendingLocked(userId)
                val (pulled, ranFull) = pullSmart(userId)
                SyncOutcome(pushedCount = pushed, pulledCount = pulled, ranFullPull = ranFull)
            }
            if (outcome.pushedCount > 0 || outcome.pulledCount > 0) {
                _dataChanged.tryEmit(userId)
            }
            outcome
        } catch (e: Exception) {
            Log.e(TAG, "syncNow error", e)
            SyncOutcome(pushedCount = 0, pulledCount = 0, ranFullPull = false, error = e.message)
        }
    }

    // ══ PUSH — đẩy toàn bộ bài đang pending_create/update/delete ═══════════
    suspend fun pushPending(userId: String): Int {
        val count = syncMutex.withLock { pushPendingLocked(userId) }
        if (count > 0) {
            _dataChanged.tryEmit(userId)
        }
        return count
    }

    // Logic push thật sự — KHÔNG tự khoá mutex, để syncNow() gọi lại được từ
    // bên trong lúc đã giữ khoá sẵn.
    private suspend fun pushPendingLocked(userId: String): Int {
        val pendingReadings = repo.getPendingReadings(userId)
        if (pendingReadings.isEmpty()) return 0

        val succeededIds = mutableListOf<String>()

        // Chỉ thu thập cho nhánh CREATE/UPDATE (không phải DELETE) — dùng
        // freshReading (đọc lại từ DB ngay TRƯỚC lúc push, có isAiProcessed
        // mới nhất) thay vì `reading` gốc trong pendingReadings, phòng
        // trường hợp AI vừa ghi xong is_ai_processed=1 SAU khi
        // getPendingReadings() đã lấy snapshot cũ. Xem TtsMyReadingSyncTrigger.kt.
        val syncedForTts = mutableListOf<Reading>()

        for (reading in pendingReadings) {
            try {
                when (reading.syncStatus) {
                    MyReadingSyncStatus.PENDING_CREATE, MyReadingSyncStatus.PENDING_UPDATE -> {
                        val aggregate = repo.getReadingForSync(reading.readingId)
                        if (aggregate == null) {
                            Log.w(TAG, "pushPendingLocked: reading_id=${reading.readingId} không còn ở local, bỏ qua")
                            continue
                        }
                        val (freshReading, sentences) = aggregate
                        val rowDto = freshReading.toRowUpsertDto(fallbackUserId = userId)

                        if (reading.syncStatus == MyReadingSyncStatus.PENDING_CREATE) {
                            // KHÔNG cần logic chống trùng phức tạp như bên
                            // vocab — reading_id do app tự sinh UUID v7,
                            // không có unique key nghiệp vụ nào cần check.
                            MyReadingSyncApi.pushReadingCreateOrUpdate(
                                reading   = rowDto,
                                sentences = sentences.map { it.toDto() },
                                phrases   = sentences.flatMap { s -> s.phrases.map { it.toDto() } },
                                words     = sentences.flatMap { s -> s.words.map { it.toDto() } },
                            )
                            succeededIds += reading.readingId
                            syncedForTts += freshReading
                        } else {
                            if (pushUpdateWithLocking(freshReading, sentences, rowDto)) {
                                succeededIds += reading.readingId
                                syncedForTts += freshReading
                            }
                            // false nghĩa là conflict và server đã thắng —
                            // local đã bị áp lại từ server bên trong
                            // pushUpdateWithLocking(), không cần markSynced
                            // thêm ở ngoài (applyServerReading tự set
                            // sync_status = synced).
                        }
                    }

                    MyReadingSyncStatus.PENDING_DELETE -> {
                        MyReadingSyncApi.pushDelete(reading.readingId, reading.userId ?: userId)
                        succeededIds += reading.readingId
                    }

                    else -> { /* SYNCED không lọt vào đây vì getPendingReadings đã loại */ }
                }
            } catch (e: Exception) {
                // 1 bài lỗi (vd mất mạng giữa chừng) không chặn các bài còn
                // lại — bài lỗi vẫn giữ nguyên sync_status, sẽ retry ở lần
                // syncNow() kế tiếp.
                Log.e(TAG, "push lỗi cho reading_id=${reading.readingId}", e)
            }
        }

        if (succeededIds.isNotEmpty()) {
            repo.markSynced(succeededIds)
        }

        // ── Sau khi biết CHẮC CHẮN đã push thành công lên Supabase — báo
        // cho lớp TTS (nếu bài nào đó đã is_ai_processed=1) để xin server
        // tổng hợp giọng đọc. "Bắn rồi quên": lỗi ở đây KHÔNG được làm hỏng
        // kết quả push readings/sentences (đã xong xuôi ở trên), nên bọc
        // try/catch riêng, không để exception thoát ra khỏi pushPendingLocked().
        if (syncedForTts.isNotEmpty()) {
            try {
                TtsMyReadingSyncTrigger.onReadingsSynced(appContext, syncedForTts)
            } catch (e: Exception) {
                Log.e(TAG, "pushPendingLocked: lỗi khi báo TtsMyReadingSyncTrigger", e)
            }
        }

        return succeededIds.size
    }

    // ══ PUSH UPDATE với optimistic locking thật sự ═══════════════════════════
    //   - Server không còn dòng này (đã bị xoá nơi khác) → coi local thua, áp
    //     tombstone vào local bằng chính dữ liệu local đang có (không cần gọi
    //     thêm server), KHÔNG push nữa.
    //   - Server.updated_at MỚI HƠN local.updated_at → conflict thật sự,
    //     SERVER THẮNG: fetch cây con mới nhất từ server, ghi đè local, KHÔNG
    //     push đè lên server nữa.
    //   - Ngược lại → local thắng, PATCH như cũ.
    // Trả về true nếu đã push thành công (cần markSynced ở caller), false nếu
    // conflict và server đã thắng (local đã tự cập nhật xong).
    private suspend fun pushUpdateWithLocking(
        localReading: Reading,
        localSentences: List<ReadingSentence>,
        rowDto: ReadingRowUpsertDto,
    ): Boolean {
        val serverRow = MyReadingSyncApi.fetchOneReadingRow(localReading.readingId, rowDto.userId)

        if (serverRow == null) {
            Log.d(TAG, "pushUpdate: reading_id=${localReading.readingId} không còn trên server, áp tombstone")
            repo.applyServerReading(localReading, localSentences, isTombstone = true)
            return false
        }

        val serverNewer = serverRow.updatedAt > rowDto.updatedAt
        if (serverNewer) {
            Log.d(TAG, "pushUpdate: conflict thật sự cho reading_id=${localReading.readingId}, server thắng")
            val children = MyReadingSyncApi.fetchChildrenForReading(localReading.readingId)
            val entitySentences = assembleSentences(children.sentences, children.phrases, children.words)
            repo.applyServerReading(
                reading     = serverRow.toEntity(),
                sentences   = entitySentences,
                isTombstone = serverRow.deletedAt != null,
                deletedAt   = serverRow.deletedAt,
            )
            return false
        }

        MyReadingSyncApi.pushReadingCreateOrUpdate(
            reading   = rowDto,
            sentences = localSentences.map { it.toDto() },
            phrases   = localSentences.flatMap { s -> s.phrases.map { it.toDto() } },
            words     = localSentences.flatMap { s -> s.words.map { it.toDto() } },
        )
        return true
    }

    // ══ PULL — tự chọn delta hay full theo đúng quy tắc đã chốt ═════════════
    private suspend fun pullSmart(userId: String): Pair<Int, Boolean> {
        val runFull = MyReadingSyncCursor.shouldRunFullPull(userId)
        return if (runFull) {
            pullFull(userId) to true
        } else {
            pullDelta(userId) to false
        }
    }

    // ── Delta pull: chỉ lấy dòng "readings" có updated_at > cursor ──────────
    // Mọi thay đổi nội dung (sentences/phrases/words, kể cả do AI ghi xong)
    // đều làm readings.updated_at đổi theo (xem writeAiResult() ở
    // MyReadingDao.kt và trigger set_readings_updated_at ở server) — nên chỉ
    // cần delta theo đúng 1 mốc này là đủ, không cần theo dõi riêng 3 bảng con.
    suspend fun pullDelta(userId: String): Int {
        val cursor = MyReadingSyncCursor.getLastSyncCursor(userId)
        val rows = MyReadingSyncApi.pullReadingRowsDelta(userId, cursor)
        val applied = applyReadingRows(rows)
        // ⚠️ CHỈ tiến cursor tới mốc updated_at của những dòng ĐÃ ÁP DỤNG
        // THÀNH CÔNG (applied), KHÔNG dùng rows (toàn bộ, kể cả dòng bị lỗi
        // hoặc bị safety guard ở applyServerReading() chặn vì dữ liệu server
        // trông như ghi chưa xong — xem MyReadingDao.applyServerReading()).
        // Trước đây dùng rows.maxByOrNull, nên hễ 1 dòng bị chặn/lỗi là
        // cursor vẫn nhảy qua nó — delta pull các lần sau KHÔNG BAO GIỜ thấy
        // lại dòng đó nữa (chỉ full pull theo chu kỳ 7 ngày mới tình cờ vớt
        // lại), khiến 1 bài bị "kẹt trống" mãi mãi dù server đã ghi xong.
        applied.maxByOrNull { it.updatedAt }?.let {
            MyReadingSyncCursor.setLastSyncCursor(userId, it.updatedAt)
        }
        Log.d(TAG, "pullDelta: ${rows.size} bài, ${applied.size} áp dụng thành công, cursor cũ=$cursor")
        return applied.size
    }

    // ── Full pull: lấy toàn bộ dữ liệu user, không lọc theo cursor ──────────
    suspend fun pullFull(userId: String): Int {
        val rows = MyReadingSyncApi.pullReadingRowsFull(userId)
        val applied = applyReadingRows(rows)
        // Cùng lý do như pullDelta() — chỉ tiến cursor theo dòng áp thành công.
        applied.maxByOrNull { it.updatedAt }?.let {
            MyReadingSyncCursor.setLastSyncCursor(userId, it.updatedAt)
        }
        MyReadingSyncCursor.setLastFullPullAt(userId, nowUtcIso())
        Log.d(TAG, "pullFull: ${rows.size} bài, ${applied.size} áp dụng thành công")
        return applied.size
    }

    // Áp dụng từng dòng "readings" nhận từ server vào local. Nếu là tombstone
    // (deleted_at != null) → áp thẳng, không cần fetch cây con. Ngược lại →
    // fetch sentences/phrases/words của đúng reading_id đó rồi ráp lại thành
    // cây ReadingSentence trước khi gọi applyServerReading().
    //
    // Trả về danh sách CHỈ những dòng ĐÃ ÁP DỤNG THÀNH CÔNG (dùng để tiến
    // cursor an toàn ở pullDelta()/pullFull()) — dòng bị lỗi hoặc bị
    // applyServerReading() từ chối (safety guard chống ghi đè rỗng do race
    // với Realtime) sẽ KHÔNG có mặt trong kết quả trả về, để lần pull kế
    // tiếp còn cơ hội thử lại đúng dòng đó.
    private suspend fun applyReadingRows(rows: List<ReadingRowDto>): List<ReadingRowDto> {
        val applied = mutableListOf<ReadingRowDto>()
        for (row in rows) {
            try {
                val ok = if (row.deletedAt != null) {
                    repo.applyServerReading(
                        reading     = row.toEntity(),
                        sentences   = emptyList(),
                        isTombstone = true,
                        deletedAt   = row.deletedAt,
                    )
                } else {
                    val children = MyReadingSyncApi.fetchChildrenForReading(row.readingId)
                    val entitySentences = assembleSentences(children.sentences, children.phrases, children.words)
                    repo.applyServerReading(
                        reading     = row.toEntity(),
                        sentences   = entitySentences,
                        isTombstone = false,
                    )
                }
                if (ok) {
                    applied += row
                } else {
                    Log.w(TAG, "applyReadingRows: reading_id=${row.readingId} không áp dụng " +
                            "(bị safety guard chặn) → giữ nguyên cursor cho dòng này, thử lại lần sau")
                }
            } catch (e: Exception) {
                Log.e(TAG, "applyReadingRows: lỗi áp dụng reading_id=${row.readingId}", e)
            }
        }
        return applied
    }

    private fun nowUtcIso(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.getDefault())
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())
}

// ─────────────────────────────────────────────────────────────────────────────
// ── Mapping Entity (feature/reading/data) ↔ Dto (MyReadingSyncApi.kt) ──────
// Không đặt private vì MyReadingSyncRealtime (file sync khác cùng package)
// cũng cần assembleSentences() để ráp cây từ Dto sau khi nhận sự kiện realtime.
// ─────────────────────────────────────────────────────────────────────────────

fun Reading.toRowUpsertDto(fallbackUserId: String): ReadingRowUpsertDto = ReadingRowUpsertDto(
    readingId     = readingId,
    userId        = userId ?: fallbackUserId,
    titleEn       = titleEn,
    titleVi       = titleVi,
    level         = level,
    topic         = topic,
    isAiProcessed = if (isAiProcessed) 1 else 0,
    createdAt     = createdAt ?: nowUtcIsoStatic(),
    updatedAt     = updatedAt ?: nowUtcIsoStatic(),
)

fun ReadingRowDto.toEntity(syncStatus: String? = MyReadingSyncStatus.SYNCED): Reading = Reading(
    readingId     = readingId,
    userId        = userId,
    titleEn       = titleEn,
    titleVi       = titleVi,
    level         = level,
    topic         = topic,
    isAiProcessed = isAiProcessed != 0,
    createdAt     = createdAt,
    updatedAt     = updatedAt,
    syncStatus    = syncStatus,
)

fun ReadingSentence.toDto(): ReadingSentenceDto = ReadingSentenceDto(
    sentenceId          = sentenceId,
    readingId           = readingId,
    textEn              = textEn,
    textVi              = textVi,
    sentenceExplanation = sentenceExplanation,
    sentenceOrder       = sentenceOrder,
    paragraphOrder      = paragraphOrder,
)

fun SentencePhrase.toDto(): SentencePhraseDto = SentencePhraseDto(
    phraseId          = phraseId,
    sentenceId        = sentenceId,
    textEn            = textEn,
    textVi            = textVi,
    phraseExplanation = phraseExplanation,
    startWordOrder    = startWordOrder,
    endWordOrder      = endWordOrder,
)

fun SentenceWord.toDto(): SentenceWordDto = SentenceWordDto(
    wordId              = wordId,
    sentenceId          = sentenceId,
    phraseId            = phraseId,
    textEn              = textEn,
    textVi              = textVi,
    wordExplanation     = wordExplanation,
    wordOrder           = wordOrder,
    pos                 = pos,
    lemma               = lemma,
    wordFormExplanation = wordFormExplanation,
)

// Ráp lại List<ReadingSentence> (kèm phrases/words lồng bên trong, đúng shape
// mà repo.applyServerReading() cần) từ 3 danh sách Dto phẳng nhận từ server.
fun assembleSentences(
    sentenceDtos: List<ReadingSentenceDto>,
    phraseDtos: List<SentencePhraseDto>,
    wordDtos: List<SentenceWordDto>,
): List<ReadingSentence> {
    val phrasesBySentence = phraseDtos.groupBy { it.sentenceId }
    val wordsBySentence = wordDtos.groupBy { it.sentenceId }

    return sentenceDtos.sortedBy { it.sentenceOrder }.map { s ->
        ReadingSentence(
            sentenceId          = s.sentenceId,
            readingId           = s.readingId,
            textEn              = s.textEn,
            textVi              = s.textVi,
            sentenceExplanation = s.sentenceExplanation,
            sentenceOrder       = s.sentenceOrder,
            paragraphOrder      = s.paragraphOrder ?: 1,
            phrases = (phrasesBySentence[s.sentenceId] ?: emptyList()).map { p ->
                SentencePhrase(
                    phraseId          = p.phraseId,
                    sentenceId        = p.sentenceId,
                    textEn            = p.textEn,
                    textVi            = p.textVi,
                    phraseExplanation = p.phraseExplanation,
                    startWordOrder    = p.startWordOrder,
                    endWordOrder      = p.endWordOrder,
                )
            },
            words = (wordsBySentence[s.sentenceId] ?: emptyList()).sortedBy { it.wordOrder }.map { w ->
                SentenceWord(
                    wordId              = w.wordId,
                    sentenceId          = w.sentenceId,
                    phraseId            = w.phraseId,
                    textEn              = w.textEn,
                    textVi              = w.textVi,
                    wordExplanation     = w.wordExplanation,
                    wordOrder           = w.wordOrder,
                    pos                 = w.pos,
                    lemma               = w.lemma,
                    wordFormExplanation = w.wordFormExplanation,
                )
            },
        )
    }
}

private fun nowUtcIsoStatic(): String =
    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.getDefault())
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        .format(java.util.Date())