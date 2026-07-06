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

object SyncEngine {

    private lateinit var repo: VocabRepository

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
            val pushed = pushPending(userId)
            val (pulled, ranFull) = pullSmart(userId)
            SyncOutcome(pushedCount = pushed, pulledCount = pulled, ranFullPull = ranFull)
        } catch (e: Exception) {
            Log.e("SyncEngine", "syncNow error", e)
            SyncOutcome(pushedCount = 0, pulledCount = 0, ranFullPull = false, error = e.message)
        }
    }

    // ══ PUSH — đẩy toàn bộ dòng đang pending_create/update/delete ═══════════
    // Dùng cho cả: create/delete "ngay lập tức" (gọi syncNow ngay sau khi
    // user bấm lưu/xoá từ) lẫn update batch mỗi 3h lẫn bước "flush phụ"
    // trước mỗi lần pull — cùng 1 hàm, không cần phân biệt.
    suspend fun pushPending(userId: String): Int {
        val pendingRows = repo.getPendingRows(userId)
        if (pendingRows.isEmpty()) return 0

        val succeededIds = mutableListOf<String>()

        for (row in pendingRows) {
            try {
                when (row.syncStatus) {
                    SyncStatus.PENDING_CREATE -> {
                        SyncApi.pushCreate(row.toUpsertDto())
                        succeededIds += row.id
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