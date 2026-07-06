// SyncApi.kt
// Đặt tại: com/eleap/eleap/core/sync/SyncApi.kt
//
// Tầng DUY NHẤT gọi Supabase cho việc đồng bộ user_vocabulary. Không biết gì
// về SQLite/sync_status cục bộ — chỉ nhận/trả dữ liệu thô (UserVocabularyDto)
// khớp với schema bảng public.user_vocabulary trên Supabase.
//
// SyncEngine (bước tiếp theo) sẽ là nơi phối hợp: đọc pending rows từ
// VocabRepository → gọi các hàm push ở đây → gọi VocabRepository.markSynced();
// gọi các hàm pull ở đây → gọi VocabRepository.applyServerChange().
//
// Singleton thủ công, KHÔNG dùng Hilt/DI — cùng phong cách với
// SupabaseClientProvider.
package com.eleap.eleap.core.sync

import com.eleap.eleap.core.auth.SupabaseClientProvider
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val TABLE = "user_vocabulary"

// ── DTO khớp cột trên Supabase (snake_case qua @SerialName) ─────────────────
// Không có sync_status — cột này chỉ tồn tại ở local (VocabDatabase.kt).
@Serializable
data class UserVocabularyDto(
    val id: String,
    @SerialName("user_id") val userId: String,

    @SerialName("source_sentence_id") val sourceSentenceId: String? = null,
    @SerialName("source_word_id") val sourceWordId: String? = null,
    @SerialName("source_phrase_id") val sourcePhraseId: String? = null,

    @SerialName("text_en") val textEn: String? = null,
    @SerialName("text_vi") val textVi: String? = null,
    @SerialName("phrase_text_en") val phraseTextEn: String? = null,
    @SerialName("phrase_text_vi") val phraseTextVi: String? = null,
    @SerialName("sentence_text_en") val sentenceTextEn: String? = null,
    @SerialName("sentence_text_vi") val sentenceTextVi: String? = null,

    val selected: Int = 1,
    val count: Int = 0,
    val score: Int = 0,

    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

// Dữ liệu tối thiểu để tạo/sửa 1 dòng khi push — không gửi các trường server
// tự quản (created_at/updated_at do default now()/trigger, nếu bạn thêm
// trigger tương tự ở Supabase; nếu chưa có trigger, PATCH/POST vẫn nên gửi
// kèm để khớp optimistic locking — xem ghi chú ở pushUpdate()).
@Serializable
data class UserVocabularyUpsertDto(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("source_sentence_id") val sourceSentenceId: String? = null,
    @SerialName("source_word_id") val sourceWordId: String? = null,
    @SerialName("source_phrase_id") val sourcePhraseId: String? = null,
    @SerialName("text_en") val textEn: String? = null,
    @SerialName("text_vi") val textVi: String? = null,
    @SerialName("phrase_text_en") val phraseTextEn: String? = null,
    @SerialName("phrase_text_vi") val phraseTextVi: String? = null,
    @SerialName("sentence_text_en") val sentenceTextEn: String? = null,
    @SerialName("sentence_text_vi") val sentenceTextVi: String? = null,
    val selected: Int = 1,
    val count: Int = 0,
    val score: Int = 0,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

object SyncApi {

    private val postgrest get() = SupabaseClientProvider.client.postgrest

    // ── PULL: delta — chỉ lấy dòng có updated_at > cursor ───────────────────
    // cursor == null nghĩa là chưa từng pull → coi như lấy hết (tương đương
    // full, nhưng caller (SyncEngine) nên tự quyết định gọi pullFull() trong
    // trường hợp lần đầu đăng nhập theo đúng thiết kế đã chốt).
    suspend fun pullDelta(userId: String, cursor: String?): List<UserVocabularyDto> {
        return postgrest.from(TABLE).select {
            filter {
                eq("user_id", userId)
                if (cursor != null) {
                    gt("updated_at", cursor)
                }
            }
            order("updated_at", Order.ASCENDING)
        }.decodeList()
    }

    // ── PULL: full — lấy toàn bộ dữ liệu của user, không lọc theo cursor ────
    suspend fun pullFull(userId: String): List<UserVocabularyDto> {
        return postgrest.from(TABLE).select {
            filter {
                eq("user_id", userId)
            }
            order("updated_at", Order.ASCENDING)
        }.decodeList()
    }

    // ── PUSH: tạo mới (dòng đang PENDING_CREATE ở local) ─────────────────────
    suspend fun pushCreate(row: UserVocabularyUpsertDto) {
        postgrest.from(TABLE).insert(row)
    }

    // ── PUSH: cập nhật (dòng đang PENDING_UPDATE ở local) ────────────────────
    // Optimistic locking đơn giản: chỉ update nếu id khớp — conflict thật sự
    // (409) sẽ xử lý ở bản mở rộng sau; hiện tại last-write-wins được xử lý ở
    // chiều pull (VocabRepository.applyServerChange so sánh updated_at).
    suspend fun pushUpdate(row: UserVocabularyUpsertDto) {
        postgrest.from(TABLE).update(row) {
            filter { eq("id", row.id) }
        }
    }

    // ── PUSH: xoá (dòng đang PENDING_DELETE ở local) ─────────────────────────
    // SOFT DELETE trên server: set deleted_at = now(), KHÔNG xoá cứng — để
    // các thiết bị khác còn thấy tombstone khi pull (delta 5h hoặc full 1
    // tuần). Server cần 1 job purge riêng (SQL cron trên Supabase, không
    // phải code app) để dọn các dòng có deleted_at cũ hơn 30–60 ngày.
    suspend fun pushDelete(id: String) {
        postgrest.from(TABLE).update(
            mapOf("deleted_at" to nowUtcIsoForApi())
        ) {
            filter { eq("id", id) }
        }
    }

    private fun nowUtcIsoForApi(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.getDefault())
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())
}