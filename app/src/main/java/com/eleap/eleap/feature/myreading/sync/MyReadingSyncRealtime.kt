// MyReadingSyncRealtime.kt
// Đặt tại: feature/myreading/sync/MyReadingSyncRealtime.kt
//
// Lớp BỔ SUNG cho cơ chế sync định kỳ (MyReadingSyncPushWorker 3h /
// MyReadingSyncPullWorker 5h) — KHÔNG thay thế chúng. Khi 1 thiết bị
// tạo/sửa/xoá 1 bài MyReading, các thiết bị khác đang đăng nhập CÙNG tài
// khoản và đang mở app sẽ nhận được thay đổi gần như ngay lập tức qua
// WebSocket (Supabase Realtime).
//
// ⚠️ ĐÃ ĐỔI THIẾT KẾ: trước đây 1 sự kiện Insert/Update trên bảng my_readings
// mang sẵn toàn bộ payload JSON, parse thẳng ra là đủ. Giờ dữ liệu nằm ở 4
// bảng (readings/reading_sentences/sentence_phrases/sentence_words) — CHỈ
// lắng nghe sự kiện trên bảng "readings" (đăng ký ở
// supabase_myreading_schema.sql, mục "Bật Realtime"); khi nhận được 1 sự
// kiện, gọi thêm MyReadingSyncApi.fetchChildrenForReading() để lấy
// sentences/phrases/words của ĐÚNG reading_id đó, rồi mới ráp cây và áp vào
// local. Đổi lại 1 round-trip mạng mỗi sự kiện, nhưng không cần subscribe
// riêng 3 kênh realtime cho 3 bảng con (vốn không tự biết reading_id nào nếu
// tách lẻ ra theo dõi).
//
// Nếu app bị kill hẳn hoặc mất mạng, Realtime dĩ nhiên không hoạt động — lúc
// đó pull định kỳ (MyReadingSyncPullWorker) vẫn là lưới an toàn cuối cùng.
//
// Singleton thủ công, KHÔNG dùng Hilt/DI.
package com.eleap.eleap.feature.myreading.sync

import android.content.Context
import android.util.Log
import com.eleap.eleap.core.auth.SupabaseClientProvider
import com.eleap.eleap.feature.myreading.data.MyReadingRepository
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

object MyReadingSyncRealtime {

    private lateinit var repo: MyReadingRepository

    private var listenScope: CoroutineScope? = null
    private var channel: RealtimeChannel? = null
    private var listeningUserId: String? = null

    // Gọi 1 lần ở MainActivity.onCreate(), cùng chỗ với MyReadingSyncEngine.init().
    fun init(context: Context) {
        repo = MyReadingRepository.getInstance(context)
    }

    fun startListening(userId: String) {
        if (userId == "guest") {
            Log.d("MyReadingSyncRealtime", "startListening: bỏ qua vì là guest")
            return
        }

        if (listeningUserId == userId && channel != null) {
            Log.d("MyReadingSyncRealtime", "startListening: đã lắng nghe userId=$userId từ trước, bỏ qua")
            return
        }

        stopListening()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        listenScope = scope

        val newChannel = SupabaseClientProvider.client.channel("myreading_readings_changes_$userId")
        channel = newChannel
        listeningUserId = userId

        // ⚠️ CHỈ lắng nghe bảng "readings" — KHÔNG lắng nghe
        // reading_sentences/sentence_phrases/sentence_words. KHÔNG filter ở
        // tầng builder (cùng lý do như SyncRealtime.kt bên vocab: API filter
        // đổi khác nhau giữa các bản realtime-kt) — nhận tất cả sự kiện trên
        // bảng readings rồi tự lọc lại đúng userId ở handleAction(). An toàn
        // vì RLS trên Supabase (auth.uid() = user_id) đã tự giới hạn từ trước.
        val changeFlow = newChannel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "readings"
        }

        changeFlow.onEach { action -> handleAction(action, userId) }.launchIn(scope)

        scope.launch {
            try {
                newChannel.subscribe()
                Log.d("MyReadingSyncRealtime", "startListening: đã subscribe userId=$userId")
            } catch (e: Exception) {
                Log.e("MyReadingSyncRealtime", "startListening: lỗi subscribe userId=$userId", e)
            }
        }
    }

    fun stopListening() {
        channel?.let { ch ->
            listenScope?.launch {
                try {
                    ch.unsubscribe()
                } catch (e: Exception) {
                    Log.e("MyReadingSyncRealtime", "stopListening: lỗi unsubscribe", e)
                }
            }
        }
        listenScope?.cancel()
        listenScope = null
        channel = null
        listeningUserId = null
        Log.d("MyReadingSyncRealtime", "stopListening: đã dừng")
    }

    private suspend fun handleAction(action: PostgresAction, userId: String) {
        try {
            when (action) {
                is PostgresAction.Insert -> {
                    val dto = action.decodeRecord<ReadingRowDto>()
                    if (dto.userId != userId) return
                    applyDto(dto, userId = userId)
                }
                is PostgresAction.Update -> {
                    val dto = action.decodeRecord<ReadingRowDto>()
                    if (dto.userId != userId) return
                    applyDto(dto, userId = userId)
                }
                is PostgresAction.Delete -> {
                    // Thiết kế hiện tại xoá bằng SOFT DELETE (UPDATE deleted_at
                    // trên bảng readings), nên nhánh này hiếm khi xảy ra (chỉ
                    // nếu ai đó xoá cứng trực tiếp trên Supabase Dashboard).
                    try {
                        val dto = action.decodeOldRecord<ReadingRowDto>()
                        if (dto.userId != userId) return
                        applyDto(dto.copy(deletedAt = dto.deletedAt ?: nowUtcIsoLocal()), userId = userId)
                    } catch (e: Exception) {
                        Log.d("MyReadingSyncRealtime", "handleAction: DELETE thiếu dữ liệu để áp dụng, bỏ qua")
                    }
                }
                else -> { /* PostgresAction.Select — không xảy ra với INSERT/UPDATE/DELETE listener */ }
            }
        } catch (e: Exception) {
            Log.e("MyReadingSyncRealtime", "handleAction lỗi", e)
        }
    }

    // Áp dụng 1 dòng "readings" vừa nhận từ realtime — nếu là tombstone thì
    // áp thẳng; ngược lại fetch thêm cây con (sentences/phrases/words) của
    // ĐÚNG reading_id này trước khi áp, giống hệt cách applyReadingRows() ở
    // MyReadingSyncEngine xử lý cho pull thường.
    //
    // ⚠️ RETRY NGẮN: vì sự kiện Realtime trên "readings" bắn ra NGAY khi
    // dòng readings vừa commit — TRƯỚC KHI các bước insert sentences/
    // phrases/words riêng biệt phía sau (cùng 1 lần push, xem
    // MyReadingSyncApi.pushReadingCreateOrUpdate()) kịp chạy xong — lần fetch
    // ĐẦU TIÊN rất dễ rơi đúng vào khoảng trống đó (đặc biệt với bài MỚI TẠO,
    // khi thiết bị nhận chưa hề có sẵn dữ liệu local để so sánh). Thay vì bỏ
    // cuộc ngay sau 1 lần fetch, thử lại vài lần trong vài giây — thường đủ
    // để 2-3 request insert phía sau của lần push đó kịp hoàn tất, tránh phải
    // đợi tới lần pull định kỳ (3h/5h) mới tự sửa lại đúng nội dung.
    private val fetchRetryDelaysMs = longArrayOf(500, 1000, 2000)

    private suspend fun applyDto(dto: ReadingRowDto, userId: String) {
        val applied = if (dto.deletedAt != null) {
            repo.applyServerReading(
                reading     = dto.toEntity(),
                sentences   = emptyList(),
                isTombstone = true,
                deletedAt   = dto.deletedAt,
            )
        } else {
            applyNonTombstoneWithRetry(dto)
        }

        if (!applied) {
            // KHÔNG tiến cursor, KHÔNG notifyDataChanged — coi như chưa nhận
            // được sự kiện này. Đã thử lại vài lần trong applyNonTombstoneWithRetry()
            // mà vẫn bị safety guard chặn (dữ liệu server trông chưa ghi
            // xong) — chờ sự kiện tiếp theo (nếu bài còn được sửa thêm, vd
            // AI viết xong phrase/word) hoặc lần pull định kỳ (3h/5h) làm
            // lưới an toàn cuối cùng.
            Log.w(
                "MyReadingSyncRealtime",
                "applyDto: reading_id=${dto.readingId} vẫn bị safety guard chặn sau khi đã " +
                        "retry → KHÔNG tiến cursor, chờ sự kiện/pull sau."
            )
            return
        }

        // Cập nhật cursor nếu dòng này mới hơn — để lần pull định kỳ tiếp
        // theo không phải tải lại đúng dòng vừa được realtime áp dụng rồi.
        val currentCursor = MyReadingSyncCursor.getLastSyncCursor(userId)
        if (currentCursor == null || dto.updatedAt > currentCursor) {
            MyReadingSyncCursor.setLastSyncCursor(userId, dto.updatedAt)
        }

        // Tái sử dụng đúng cơ chế dataChanged đã có ở MyReadingSyncEngine —
        // để ReadingViewModel đang collect sẵn tự gọi loadReadings(forceRefresh
        // = true), không cần tạo cơ chế signal riêng.
        MyReadingSyncEngine.notifyDataChanged(userId)

        Log.d("MyReadingSyncRealtime", "applyDto: áp dụng reading_id=${dto.readingId}, tombstone=${dto.deletedAt != null}")
    }

    // Fetch cây con + áp dụng, thử lại vài lần (delay tăng dần) nếu
    // applyServerReading() từ chối vì dữ liệu server trông "cụt" — xem safety
    // guard ở MyReadingDao.applyServerReading(). Dừng thử lại ngay khi 1 lần
    // áp thành công; nếu hết số lần thử mà vẫn bị chặn thì trả về false,
    // applyDto() sẽ chờ sự kiện/pull định kỳ sau lo tiếp.
    private suspend fun applyNonTombstoneWithRetry(dto: ReadingRowDto): Boolean {
        for ((attempt, delayMs) in (longArrayOf(0L) + fetchRetryDelaysMs).withIndex()) {
            if (delayMs > 0) kotlinx.coroutines.delay(delayMs)

            val children = MyReadingSyncApi.fetchChildrenForReading(dto.readingId)
            val entitySentences = assembleSentences(children.sentences, children.phrases, children.words)
            val applied = repo.applyServerReading(
                reading     = dto.toEntity(),
                sentences   = entitySentences,
                isTombstone = false,
            )

            if (applied) {
                if (attempt > 0) {
                    Log.d(
                        "MyReadingSyncRealtime",
                        "applyNonTombstoneWithRetry: reading_id=${dto.readingId} áp dụng " +
                                "thành công sau lần thử lại thứ $attempt"
                    )
                }
                return true
            }
        }
        return false
    }

    private fun nowUtcIsoLocal(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.getDefault())
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())
}