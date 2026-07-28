// SyncRealtime.kt
// Đặt tại: com/eleap/eleap/core/sync/SyncRealtime.kt
//
// Lớp BỔ SUNG cho cơ chế sync định kỳ (SyncPushWorker 3h / SyncPullWorker 5h)
// — KHÔNG thay thế chúng. Khi 1 thiết bị tạo/sửa/xoá 1 dòng user_vocabulary,
// các thiết bị khác đang đăng nhập CÙNG tài khoản và đang mở app sẽ nhận
// được thay đổi gần như ngay lập tức qua WebSocket (Supabase Realtime), thay
// vì phải đợi tự pull hoặc đợi chu kỳ SyncPullWorker (5h).
//
// Nếu app bị kill hẳn (không phải background) hoặc mất mạng, Realtime dĩ
// nhiên không hoạt động — lúc đó pull định kỳ (SyncPullWorker) vẫn là lưới
// an toàn cuối cùng, không bị xoá bỏ bởi lớp này.
//
// Singleton thủ công, KHÔNG dùng Hilt/DI — cùng phong cách với SyncEngine,
// SyncCursor, CurrentUser.
package com.eleap.eleap.core.sync

import android.content.Context
import android.util.Log
import com.eleap.eleap.core.auth.SupabaseClientProvider
import com.eleap.eleap.feature.vocab.data.VocabRepository
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeOldRecord
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

object SyncRealtime {

    private lateinit var repo: VocabRepository

    // Scope RIÊNG cho việc lắng nghe — tạo mới mỗi lần startListening(), huỷ
    // hẳn (cancel()) mỗi lần stopListening(). KHÔNG dùng chung scope tĩnh
    // sống suốt vòng đời app, để tránh trường hợp gọi startListening() 2 lần
    // liên tiếp (vd đổi tài khoản) mà collector cũ vẫn còn sống song song
    // với collector mới, gây áp dụng trùng lặp/race dữ liệu.
    private var listenScope: CoroutineScope? = null
    private var channel: RealtimeChannel? = null

    // userId đang lắng nghe hiện tại — dùng để startListening() gọi lại với
    // CÙNG userId (vd MainActivity gọi lại khi app quay lại foreground) tự
    // biết là "resume", không cần logic riêng phân biệt.
    private var listeningUserId: String? = null

    // Gọi 1 lần ở MainActivity.onCreate(), cùng chỗ với SyncEngine.init().
    fun init(context: Context) {
        repo = VocabRepository.getInstance(context)
    }

    // ── Bắt đầu lắng nghe realtime cho đúng userId đang đăng nhập ────────────
    // Gọi khi: đăng nhập xong (MainActivity.observeSupabaseSession(), nhánh
    // Authenticated), có session cũ từ trước (onCreate()), và mỗi lần app
    // quay lại foreground (ProcessLifecycleOwner onStart — xem ghi chú ở
    // MainActivity). An toàn khi gọi lại nhiều lần với cùng userId: tự
    // unsubscribe channel cũ trước khi tạo channel mới, không tạo lắng nghe
    // trùng lặp.
    fun startListening(userId: String) {
        if (userId == "guest") {
            Log.d("SyncRealtime", "startListening: bỏ qua vì là guest")
            return
        }

        // Nếu đang lắng nghe đúng userId này rồi (vd app chỉ background
        // ngắn, WebSocket vẫn còn sống) thì không cần tạo lại channel mới —
        // tránh unsubscribe/subscribe vô ích liên tục mỗi lần onStart().
        if (listeningUserId == userId && channel != null) {
            Log.d("SyncRealtime", "startListening: đã lắng nghe userId=$userId từ trước, bỏ qua")
            return
        }

        // Dọn channel/scope cũ (nếu có, vd đổi từ userId khác sang) trước khi
        // tạo mới — tránh 2 channel cùng sống song song.
        stopListening()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        listenScope = scope

        // Đặt tên channel riêng theo userId — nếu 2 tài khoản khác nhau đăng
        // nhập nối tiếp nhau trên cùng thiết bị (không restart app), channel
        // cũ đã bị unsubscribe ở stopListening() nên không lo trùng tên.
        val newChannel = SupabaseClientProvider.client.channel("user_vocabulary_changes_$userId")
        channel = newChannel
        listeningUserId = userId

        // ⚠️ KHÔNG filter ở tầng builder — API filter của postgresChangeFlow
        // đổi khác nhau giữa các bản realtime-kt (từng có setter public
        // "filter", rồi bị đổi thành private trong bản đang dùng ở project
        // này), nên để tránh phụ thuộc vào chi tiết version dễ đổi, ta nhận
        // TẤT CẢ sự kiện trên bảng user_vocabulary rồi tự lọc lại đúng
        // userId ở hàm handleAction() bên dưới. Điều này AN TOÀN vì RLS
        // trên Supabase (auth.uid() = user_id) đã tự giới hạn: mỗi client
        // vốn dĩ chỉ nhận được sự kiện của CHÍNH tài khoản mình, không thể
        // nhận được dòng của user khác dù không filter ở đây. Việc lọc lại
        // ở Kotlin chỉ là lớp phòng vệ thêm, không phải lớp bảo mật chính.
        val changeFlow = newChannel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "user_vocabulary"
        }

        changeFlow.onEach { action -> handleAction(action, userId) }.launchIn(scope)

        scope.launch {
            try {
                newChannel.subscribe()
                Log.d("SyncRealtime", "startListening: đã subscribe userId=$userId")
            } catch (e: Exception) {
                // Lỗi subscribe (vd mất mạng ngay lúc gọi) — không throw ra
                // ngoài, chỉ log. Lần app quay lại foreground kế tiếp
                // (MainActivity onStart) sẽ tự gọi lại startListening() và
                // thử subscribe lại.
                Log.e("SyncRealtime", "startListening: lỗi subscribe userId=$userId", e)
            }
        }
    }

    // ── Dừng lắng nghe — gọi khi đăng xuất, đổi tài khoản, hoặc app vào
    // background lâu (ProcessLifecycleOwner onStop) để không giữ kết nối
    // WebSocket sống vô ích khi app không hiển thị. ──────────────────────────
    fun stopListening() {
        channel?.let { ch ->
            listenScope?.launch {
                try {
                    ch.unsubscribe()
                } catch (e: Exception) {
                    Log.e("SyncRealtime", "stopListening: lỗi unsubscribe", e)
                }
            }
        }
        // Huỷ toàn bộ coroutine đang collect flow — làm SAU khi đã enqueue
        // lệnh unsubscribe ở trên (unsubscribe chạy trong chính scope này,
        // cancel() sẽ huỷ luôn job đó nếu chưa kịp chạy xong, nhưng đó là
        // chấp nhận được: server tự dọn subscription chết sau 1 khoảng thời
        // gian không nhận heartbeat).
        listenScope?.cancel()
        listenScope = null
        channel = null
        listeningUserId = null
        Log.d("SyncRealtime", "stopListening: đã dừng")
    }

    // ── Xử lý 1 sự kiện realtime — áp dụng thẳng vào local DB, không gọi lại
    // pullDelta/pullFull toàn bộ. ─────────────────────────────────────────────
    private suspend fun handleAction(action: PostgresAction, userId: String) {
        try {
            when (action) {
                is PostgresAction.Insert -> {
                    val dto = action.decodeRecord<UserVocabularyDto>()
                    if (dto.userId != userId) return
                    applyDto(dto, isTombstone = dto.deletedAt != null, userId = userId)
                }
                is PostgresAction.Update -> {
                    val dto = action.decodeRecord<UserVocabularyDto>()
                    if (dto.userId != userId) return
                    applyDto(dto, isTombstone = dto.deletedAt != null, userId = userId)
                }
                is PostgresAction.Delete -> {
                    // Thiết kế hiện tại xoá bằng SOFT DELETE (UPDATE deleted_at),
                    // nên nhánh này hiếm khi xảy ra (chỉ nếu có ai đó xoá cứng
                    // trực tiếp trên Supabase Dashboard). oldRecord thường chỉ
                    // có cột khoá chính (id) trừ khi bảng bật REPLICA IDENTITY
                    // FULL — decode có thể thiếu field, nên bọc try/catch riêng
                    // và bỏ qua nếu không đủ dữ liệu để áp dụng an toàn.
                    try {
                        val dto = action.decodeOldRecord<UserVocabularyDto>()
                        if (dto.userId != userId) return
                        applyDto(dto, isTombstone = true, userId = userId)
                    } catch (e: Exception) {
                        Log.d("SyncRealtime", "handleAction: DELETE thiếu dữ liệu để áp dụng, bỏ qua")
                    }
                }
                else -> { /* PostgresAction.Select — không xảy ra với INSERT/UPDATE/DELETE listener */ }
            }
        } catch (e: Exception) {
            Log.e("SyncRealtime", "handleAction lỗi", e)
        }
    }

    private suspend fun applyDto(dto: UserVocabularyDto, isTombstone: Boolean, userId: String) {
        repo.applyServerChange(dto.toEntry(), isTombstone)

        // Cập nhật cursor nếu dòng này mới hơn — để lần pull định kỳ tiếp
        // theo không phải tải lại đúng dòng vừa được realtime áp dụng rồi.
        // So sánh string trực tiếp an toàn vì cùng format ISO8601 cố định.
        val currentCursor = SyncCursor.getLastSyncCursor(userId)
        if (currentCursor == null || dto.updatedAt > currentCursor) {
            SyncCursor.setLastSyncCursor(userId, dto.updatedAt)
        }

        // Tái sử dụng đúng cơ chế dataChanged đã có ở SyncEngine — để
        // VocabViewModel/ReadingViewModel đang collect sẵn tự reload UI,
        // không cần tạo cơ chế signal riêng.
        SyncEngine.notifyDataChanged(userId)

        Log.d("SyncRealtime", "applyDto: áp dụng id=${dto.id}, tombstone=$isTombstone")
    }
}

// Mapping dùng lại logic giống SyncEngine.kt (UserVocabularyDto.toEntry())
// — không import chéo private fun của file khác được, nên khai báo lại ở
// đây với cùng nội dung. Nếu sau này đổi schema DTO/Entry, nhớ sửa cả 2 nơi.
private fun UserVocabularyDto.toEntry(): com.eleap.eleap.feature.vocab.data.UserVocabularyEntry =
    com.eleap.eleap.feature.vocab.data.UserVocabularyEntry(
        id               = id,
        userId           = userId,
        sourceSentenceId = sourceSentenceId,
        sourceWordId     = sourceWordId,
        sourcePhraseId   = sourcePhraseId,
        readingId        = readingId,
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
        syncStatus       = com.eleap.eleap.feature.vocab.data.SyncStatus.SYNCED,
    )