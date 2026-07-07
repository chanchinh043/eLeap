// SyncEngine.kt
// Đặt tại: com/eleap/eleap/core/sync/SyncEngine.kt
//
// Nơi DUY NHẤT điều phối toàn bộ logic đồng bộ: gọi VocabRepository để lấy/
// ghi dữ liệu cục bộ, gọi SyncApi để nói chuyện với Supabase, gọi SyncCursor
// để đọc/ghi mốc pull. VocabRepository không tự gọi mạng; SyncApi không biết
// SQLite; SyncCursor không biết gì về cả hai — SyncEngine là tầng ráp nối.
//
// Dùng chung cho cả 2 nơi gọi vào:
//   - Nút "Đồng bộ" ở MainScreen → gọi syncNow() ngay lập tức, 1 lần.
//   - SyncWorker (bước sau, chạy nền theo lịch 3h/5h/1 tuần) → cũng gọi lại
//     đúng các hàm ở đây, không viết lại logic.
//
// Singleton thủ công, KHÔNG dùng Hilt/DI.
package com.eleap.eleap.core.sync

import android.content.Context
import android.util.Log
import com.eleap.eleap.feature.vocab.data.SyncStatus
import com.eleap.eleap.feature.vocab.data.UserVocabularyEntry
import com.eleap.eleap.feature.vocab.data.VocabRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object SyncEngine {

    private lateinit var repo: VocabRepository

    // ── Tín hiệu "vừa có dữ liệu vocab thay đổi do sync" ─────────────────────
    // Phát ra userId mỗi khi syncNow()/pushPending() vừa xong VÀ thực sự có
    // thay đổi (pushed > 0 hoặc pulled > 0) — dùng để VocabViewModel tự gọi
    // lại loadVocab()/loadVocabForReading() mà không cần UI tự nhớ gọi.
    // Không phát khi không có gì thay đổi, tránh reload vô ích mỗi lần bấm
    // nút Đồng bộ dù server không có gì mới.
    //
    // extraBufferCapacity = 1 — nếu lúc phát ra chưa có ai collect (màn hình
    // vocab chưa mở), tín hiệu vẫn không bị mất hoàn toàn cho lần collect
    // đầu tiên ngay sau đó; replay = 0 vì không cần phát lại tín hiệu cũ cho
    // collector mới (collector mới nên tự load 1 lần khi khởi tạo, không
    // dựa vào tín hiệu quá khứ).
    private val _dataChanged = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 1,
    )
    val dataChanged: SharedFlow<String> = _dataChanged.asSharedFlow()

    // ── Khoá dùng chung giữa SyncPushWorker (gọi pushPending() trực tiếp,
    // định kỳ 3h) và SyncPullWorker (gọi syncNow(), bên trong tự gọi lại
    // pushPending() rồi mới pull, định kỳ 5h). 2 lịch này chạy độc lập nên
    // hoàn toàn có thể trùng thời điểm — nếu không khoá, 2 luồng có thể cùng
    // đọc/ghi pending rows đồng thời (vd cùng push 1 dòng PENDING_CREATE 2
    // lần cùng lúc). Mutex non-reentrant nên syncNow() KHÔNG được gọi lại
    // pushPending() công khai (sẽ tự deadlock) — dùng pushPendingLocked() nội
    // bộ cho trường hợp đó.
    private val syncMutex = Mutex()

    // Gọi 1 lần ở MainActivity.onCreate(), sau khi SyncCursor.init() và
    // CurrentUser.init() đã chạy.
    fun init(context: Context) {
        repo = VocabRepository.getInstance(context)
    }

    // ── Kết quả trả về cho UI hiển thị (vd Toast/Snackbar ở nút Đồng bộ) ─────
    data class SyncOutcome(
        val pushedCount: Int,
        val pulledCount: Int,
        val ranFullPull: Boolean,
        val error: String? = null,
    )

    // ══ HÀM CHÍNH — gọi từ nút bấm hoặc từ worker nền ═══════════════════════
    // Thứ tự đúng theo thiết kế: push toàn bộ pending trước (đóng vai trò cả
    // "gửi ngay create/delete" lẫn "flush trước khi pull"), rồi mới pull.
    suspend fun syncNow(userId: String): SyncOutcome {
        // Guest không có tài khoản trên server để đồng bộ.
        if (userId == "guest") {
            return SyncOutcome(pushedCount = 0, pulledCount = 0, ranFullPull = false)
        }

        return try {
            // Khoá quanh CẢ push lẫn pull trong 1 lần syncNow — để nếu
            // SyncPushWorker (chạy pushPending() riêng) rơi đúng vào lúc này
            // đang chạy, nó phải đợi xong rồi mới push, không đụng độ dữ
            // liệu pending giữa chừng.
            val outcome = syncMutex.withLock {
                val pushed = pushPendingLocked(userId)
                val (pulled, ranFull) = pullSmart(userId)
                SyncOutcome(pushedCount = pushed, pulledCount = pulled, ranFullPull = ranFull)
            }
            // Chỉ báo cho VocabViewModel reload khi thực sự có thay đổi —
            // tránh reload vô ích nếu bấm Đồng bộ mà server không có gì mới.
            if (outcome.pushedCount > 0 || outcome.pulledCount > 0) {
                _dataChanged.tryEmit(userId)
            }
            outcome
        } catch (e: Exception) {
            Log.e("SyncEngine", "syncNow error", e)
            SyncOutcome(pushedCount = 0, pulledCount = 0, ranFullPull = false, error = e.message)
        }
    }

    // ══ PUSH — đẩy toàn bộ dòng đang pending_create/update/delete ═══════════
    // Điểm vào công khai, dùng riêng bởi SyncPushWorker (chu kỳ 3h). Khoá
    // syncMutex ở đây — nếu SyncPullWorker đang chạy syncNow() (đã giữ khoá),
    // lệnh push riêng lẻ này phải đợi thay vì chạy chồng lên.
    suspend fun pushPending(userId: String): Int {
        val count = syncMutex.withLock { pushPendingLocked(userId) }
        if (count > 0) {
            _dataChanged.tryEmit(userId)
        }
        return count
    }

    // Logic push thật sự — KHÔNG tự khoá mutex (mutex không tái nhập được),
    // để syncNow() gọi lại được từ bên trong lúc đã giữ khoá sẵn.
    private suspend fun pushPendingLocked(userId: String): Int {
        val pendingRows = repo.getPendingRows(userId)
        if (pendingRows.isEmpty()) return 0

        val succeededIds = mutableListOf<String>()

        for (row in pendingRows) {
            try {
                when (row.syncStatus) {
                    SyncStatus.PENDING_CREATE -> {
                        if (handlePendingCreate(row)) {
                            succeededIds += row.id
                        }
                    }
                    SyncStatus.PENDING_UPDATE -> {
                        if (pushUpdateWithLocking(row)) {
                            succeededIds += row.id
                        }
                        // false nghĩa là conflict và server đã thắng — không
                        // thêm vào succeededIds vì đây không phải "push thành
                        // công", mà là local đã bị ghi đè bởi server (đã tự
                        // chuyển sang SYNCED bên trong pushUpdateWithLocking
                        // qua applyServerChange, không cần markSynced nữa).
                    }
                    SyncStatus.PENDING_DELETE -> {
                        // row.userId luôn khớp với userId đang đăng nhập (đã
                        // được lọc từ getPendingRows(userId) ở trên), nên
                        // dùng thẳng row.userId cho filter phía server —
                        // vừa đúng ngữ nghĩa "xoá đúng chủ sở hữu", vừa không
                        // cần truyền thêm tham số userId qua nhiều lớp hàm.
                        SyncApi.pushDelete(row.id, row.userId)
                        succeededIds += row.id
                    }
                    else -> { /* SYNCED không lọt vào đây vì getPendingRows đã loại */ }
                }
            } catch (e: Exception) {
                // 1 dòng lỗi (vd mất mạng giữa chừng) không chặn các dòng còn
                // lại — dòng lỗi vẫn giữ nguyên sync_status, sẽ retry ở lần
                // syncNow() kế tiếp.
                Log.e("SyncEngine", "push lỗi cho id=${row.id}", e)
            }
        }

        if (succeededIds.isNotEmpty()) {
            repo.markSynced(succeededIds)
        }
        return succeededIds.size
    }

    // ══ CHỐNG TRÙNG LẶP GIỮA NHIỀU THIẾT BỊ (cùng source_word_id) ══════════
    // Trường hợp: máy A và máy B cùng offline, cùng lưu 1 từ (cùng
    // source_word_id) → 2 id khác nhau, đều PENDING_CREATE ở local riêng.
    // Máy nào push trước sẽ tạo dòng thật trên server; máy push sau phải
    // NHẬN RA điều đó và merge, thay vì tạo thêm 1 dòng trùng.
    //
    // Trả về true nếu đã xử lý xong (server đã có đúng 1 dòng cho word này,
    // local đã nhất quán) → caller thêm row.id vào succeededIds để
    // markSynced() dọn sync_status. Trả về false nếu push lỗi thật sự (mạng,
    // v.v.) → giữ nguyên PENDING_CREATE, retry ở lần sync kế tiếp.
    private suspend fun handlePendingCreate(row: UserVocabularyEntry): Boolean {
        val wordId = row.sourceWordId

        // Không có source_word_id (vd từ tự nhập tay, nếu app có luồng đó)
        // → không có gì để so khớp trùng lặp, push bình thường.
        if (wordId == null) {
            return try {
                SyncApi.pushCreate(row.toUpsertDto())
                true
            } catch (e: Exception) {
                Log.e("SyncEngine", "pushCreate lỗi cho id=${row.id}", e)
                false
            }
        }

        // Bước 1: hỏi trước — tránh phần lớn trường hợp trùng lặp mà không
        // cần chạm tới ràng buộc unique trên server (rẻ hơn, ít lỗi hơn).
        val existingBeforePush = try {
            SyncApi.fetchByWordId(row.userId, wordId)
        } catch (e: Exception) {
            Log.e("SyncEngine", "fetchByWordId lỗi cho wordId=$wordId", e)
            return false // lỗi mạng thật sự — retry ở lần sync sau
        }

        if (existingBeforePush != null && existingBeforePush.id != row.id) {
            mergeIntoWinner(existingBeforePush.toEntry(), loserId = row.id, loserCount = row.count)
            return true
        }

        // Bước 2: không thấy trùng → push như bình thường. Vẫn có thể race
        // (máy kia push đúng lúc giữa bước 1 và bước 2) — bắt riêng
        // VocabDuplicateWordException để merge lại thay vì coi là lỗi.
        return try {
            SyncApi.pushCreate(row.toUpsertDto())
            true
        } catch (e: VocabDuplicateWordException) {
            Log.d("SyncEngine", "handlePendingCreate: race condition cho wordId=$wordId, merge lại")
            val winner = try {
                SyncApi.fetchByWordId(row.userId, wordId)
            } catch (e2: Exception) {
                Log.e("SyncEngine", "fetchByWordId (retry sau race) lỗi cho wordId=$wordId", e2)
                null
            }
            if (winner != null && winner.id != row.id) {
                mergeIntoWinner(winner.toEntry(), loserId = row.id, loserCount = row.count)
                true
            } else {
                // Không tìm lại được — để nguyên PENDING_CREATE, retry sau.
                false
            }
        } catch (e: Exception) {
            Log.e("SyncEngine", "pushCreate lỗi cho id=${row.id}", e)
            false
        }
    }

    // Ghi dữ liệu server (đã merge count) vào local dưới đúng id server, rồi
    // xoá cứng dòng local trùng (loserId). Xem chi tiết ở
    // VocabRepository.mergeDuplicateIntoServerRow().
    private suspend fun mergeIntoWinner(
        winner: UserVocabularyEntry,
        loserId: String,
        loserCount: Int,
    ) {
        repo.mergeDuplicateIntoServerRow(winner = winner, loserId = loserId, loserCount = loserCount)
    }

    // ══ PUSH UPDATE với optimistic locking thật sự ═══════════════════════════
    // Trước khi PATCH, GET lại đúng dòng này trên server và so updated_at:
    //   - Server không còn dòng này (đã bị xoá nơi khác) → coi local thua,
    //     áp dụng tombstone vào local (applyServerChange), không push nữa.
    //   - Server.updated_at MỚI HƠN local.updated_at → có người khác đã sửa
    //     sau lần local biết tới → conflict thật sự, SERVER THẮNG (khớp đúng
    //     quy tắc last-write-wins đã áp dụng ở chiều pull/applyServerChange):
    //     ghi đè local bằng dữ liệu server, KHÔNG push đè lên server nữa.
    //   - Ngược lại (local mới hơn hoặc bằng) → local thắng, PATCH như cũ.
    // Trả về true nếu đã push thành công (cần markSynced ở caller), false nếu
    // conflict và server đã thắng (local đã tự cập nhật xong, không cần
    // markSynced nữa vì applyServerChange đã set SYNCED).
    private suspend fun pushUpdateWithLocking(row: UserVocabularyEntry): Boolean {
        // row.userId dùng để scope cả fetchOne lẫn pushUpdate — chặn thêm 1
        // lớp độc lập với RLS, không cho đọc/sửa nhầm dòng của user khác.
        val serverRow = SyncApi.fetchOne(row.id, row.userId)

        if (serverRow == null) {
            // Server không còn dòng này — coi như đã bị xoá ở thiết bị khác.
            // Áp dụng tombstone cục bộ để nhất quán, không push update nữa.
            Log.d("SyncEngine", "pushUpdate: id=${row.id} không còn trên server, áp tombstone")
            repo.applyServerChange(row.copy(syncStatus = SyncStatus.SYNCED), isTombstone = true)
            return false
        }

        val serverNewer = (serverRow.updatedAt) > (row.updatedAt ?: row.createdAt)
        if (serverNewer) {
            Log.d("SyncEngine", "pushUpdate: conflict thật sự cho id=${row.id}, server thắng")
            repo.applyServerChange(serverRow.toEntry(), isTombstone = serverRow.deletedAt != null)
            return false
        }

        SyncApi.pushUpdate(row.toUpsertDto())
        return true
    }

    // ══ PULL — tự chọn delta hay full theo đúng quy tắc đã chốt ═════════════
    // Trả về (số dòng đã áp dụng, có phải vừa chạy full pull không).
    private suspend fun pullSmart(userId: String): Pair<Int, Boolean> {
        val runFull = SyncCursor.shouldRunFullPull(userId)
        return if (runFull) {
            pullFull(userId) to true
        } else {
            pullDelta(userId) to false
        }
    }

    // ── Delta pull: chỉ lấy dòng có updated_at > cursor ─────────────────────
    suspend fun pullDelta(userId: String): Int {
        val cursor = SyncCursor.getLastSyncCursor(userId)
        val rows = SyncApi.pullDelta(userId, cursor)
        applyRows(rows)
        rows.maxByOrNull { it.updatedAt }?.let {
            SyncCursor.setLastSyncCursor(userId, it.updatedAt)
        }
        Log.d("SyncEngine", "pullDelta: ${rows.size} dòng, cursor cũ=$cursor")
        return rows.size
    }

    // ── Full pull: lấy toàn bộ dữ liệu user, không lọc theo cursor ──────────
    suspend fun pullFull(userId: String): Int {
        val rows = SyncApi.pullFull(userId)
        applyRows(rows)
        rows.maxByOrNull { it.updatedAt }?.let {
            SyncCursor.setLastSyncCursor(userId, it.updatedAt)
        }
        SyncCursor.setLastFullPullAt(userId, nowUtcIso())
        Log.d("SyncEngine", "pullFull: ${rows.size} dòng")
        return rows.size
    }

    // Áp dụng từng dòng nhận từ server vào local qua VocabRepository —
    // Repository tự quyết định insert/ghi đè/hard-delete/bỏ qua.
    private suspend fun applyRows(rows: List<UserVocabularyDto>) {
        for (dto in rows) {
            val isTombstone = dto.deletedAt != null
            repo.applyServerChange(dto.toEntry(), isTombstone)
        }
    }

    private fun nowUtcIso(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.getDefault())
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())
}

// ── Mapping giữa entity local và DTO Supabase ────────────────────────────────
// Đặt cạnh SyncEngine vì chỉ dùng nội bộ cho việc đồng bộ, không phải hàm
// nghiệp vụ chung của UserVocabularyEntry.

private fun UserVocabularyEntry.toUpsertDto(): UserVocabularyUpsertDto = UserVocabularyUpsertDto(
    id               = id,
    userId           = userId,
    sourceSentenceId = sourceSentenceId,
    sourceWordId     = sourceWordId,
    sourcePhraseId   = sourcePhraseId,
    textEn           = textEn,
    textVi           = textVi,
    phraseTextEn     = phraseTextEn,
    phraseTextVi     = phraseTextVi,
    sentenceTextEn   = sentenceTextEn,
    sentenceTextVi   = sentenceTextVi,
    selected         = selected,
    count            = count,
    score            = score,
    createdAt        = createdAt,
    updatedAt        = updatedAt ?: createdAt,
)

private fun UserVocabularyDto.toEntry(): UserVocabularyEntry = UserVocabularyEntry(
    id               = id,
    userId           = userId,
    sourceSentenceId = sourceSentenceId,
    sourceWordId     = sourceWordId,
    sourcePhraseId   = sourcePhraseId,
    textEn           = textEn,
    textVi           = textVi,
    phraseTextEn     = phraseTextEn,
    phraseTextVi     = phraseTextVi,
    sentenceTextEn   = sentenceTextEn,
    sentenceTextVi   = sentenceTextVi,
    selected         = selected,
    count            = count,
    score            = score,
    createdAt        = createdAt,
    updatedAt        = updatedAt,
    deletedAt        = deletedAt,
    syncStatus       = SyncStatus.SYNCED, // không dùng tới khi applyServerChange, chỉ để khớp constructor
)