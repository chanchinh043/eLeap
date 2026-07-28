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
// ⚠️ user_id trong mọi filter update/delete/fetchOne: đây là lớp chặn thứ 2
// độc lập với RLS trên Supabase. RLS vẫn là hàng rào chính (phải cấu hình
// đúng policy UPDATE/DELETE theo auth.uid() = user_id), nhưng thêm eq(user_id)
// ở đây giúp: (1) nếu RLS bị sửa nhầm/tắt sau này, code vẫn tự chặn được;
// (2) nếu có bug gán sai user_id cục bộ, request tự fail thay vì âm thầm
// sửa/xoá nhầm dòng của người khác.
//
// Singleton thủ công, KHÔNG dùng Hilt/DI — cùng phong cách với
// SupabaseClientProvider.
package com.eleap.eleap.core.sync

import com.eleap.eleap.core.auth.SupabaseClientProvider
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val TABLE = "user_vocabulary"

// ── Ném ra khi pushCreate() đụng unique constraint (user_id, source_word_id)
// trên server — nghĩa là 1 thiết bị KHÁC đã tạo/push 1 dòng khác cho cùng
// source_word_id này TRƯỚC, ngay trong khoảng thời gian giữa lúc
// SyncEngine gọi fetchByWordId() (trả về null) và lúc gọi pushCreate() thật
// sự (race condition hiếm). SyncEngine bắt exception này để chạy lại
// fetchByWordId() (lúc này chắc chắn có kết quả) rồi merge, thay vì coi là
// lỗi mạng chung chung rồi retry vô ích ở chu kỳ sync kế tiếp.
class VocabDuplicateWordException(val sourceWordId: String?) : Exception(
    "Trùng source_word_id=$sourceWordId với 1 dòng khác đã có trên server"
)

// ── DTO khớp cột trên Supabase (snake_case qua @SerialName) ─────────────────
// Không có sync_status — cột này chỉ tồn tại ở local (VocabDatabase.kt).
@Serializable
data class UserVocabularyDto(
    val id: String,
    @SerialName("user_id") val userId: String,

    @SerialName("source_sentence_id") val sourceSentenceId: String? = null,
    @SerialName("source_word_id") val sourceWordId: String? = null,
    @SerialName("source_phrase_id") val sourcePhraseId: String? = null,

    @SerialName("reading_id") val readingId: String? = null,

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
    @SerialName("reading_id") val readingId: String? = null,
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

    // ── PULL: 1 dòng theo id — dùng để kiểm tra optimistic locking TRƯỚC khi
    // pushUpdate(): so updated_at hiện tại trên server với updated_at cục bộ,
    // để phát hiện conflict thật sự thay vì ghi đè vô điều kiện. Trả về null
    // nếu dòng không còn tồn tại trên server (đã bị xoá ở nơi khác — trường
    // hợp này SyncEngine coi như "server thắng" theo hướng khác, xử lý riêng).
    //
    // Kèm theo eq("user_id", userId) — chặn thêm 1 lớp: không cho fetch dòng
    // của user khác dù id có bị lộ/đoán trúng thế nào.
    suspend fun fetchOne(id: String, userId: String): UserVocabularyDto? {
        return postgrest.from(TABLE).select {
            filter {
                eq("id", id)
                eq("user_id", userId)
            }
        }.decodeSingleOrNull()
    }

    // ── Tìm dòng "còn sống" trên server theo (user_id, source_word_id) ──────
    // Dùng để CHỐNG TRÙNG LẶP GIỮA NHIỀU THIẾT BỊ: gọi trước pushCreate() —
    // nếu trả về khác null nghĩa là 1 thiết bị khác đã tạo/push xong dòng
    // này rồi (id khác với dòng local đang PENDING_CREATE), SyncEngine sẽ
    // merge thay vì tạo thêm 1 dòng trùng trên server.
    //
    // Lọc "deleted_at IS NULL" ở phía KOTLIN (sau khi lấy về) thay vì trong
    // câu query — tránh phụ thuộc vào tên hàm filter-null cụ thể của từng
    // version supabase-kt (đã đổi tên nhiều lần: FilterOperator.IS, is_,
    // isNull...). Với unique index uq_vocab_user_word_alive trên server,
    // tối đa CHỈ CÓ 1 dòng "còn sống" cho mỗi (user_id, source_word_id) nên
    // decodeList() rồi lọc + lấy dòng đầu là an toàn, không lo có 2 dòng
    // cùng sống trở lên.
    suspend fun fetchByWordId(userId: String, sourceWordId: String): UserVocabularyDto? {
        val rows = postgrest.from(TABLE).select {
            filter {
                eq("user_id", userId)
                eq("source_word_id", sourceWordId)
            }
        }.decodeList<UserVocabularyDto>()
        return rows.firstOrNull { it.deletedAt == null }
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
    // Dùng upsert (theo id) thay vì insert thuần: nếu lần push trước ĐÃ tạo
    // thành công ở server nhưng phản hồi bị mất (mất mạng giữa chừng), local
    // vẫn còn PENDING_CREATE và sẽ gọi lại hàm này ở lần sync kế tiếp. Với
    // insert() thuần, lần gọi lại đó sẽ báo lỗi trùng khoá chính (id đã tồn
    // tại) và không bao giờ tự phục hồi — dòng này kẹt PENDING_CREATE mãi
    // mãi, mỗi chu kỳ sync đều log lỗi. upsert() theo "id" cho phép lần gọi
    // lại ghi đè an toàn lên đúng dòng đã tạo trước đó (dữ liệu giống hệt vì
    // cùng 1 lần tạo cục bộ), rồi trả về thành công để markSynced() chạy
    // bình thường ở SyncEngine.
    // Bắt riêng lỗi 23505 (unique_violation) từ unique index
    // uq_vocab_user_word_alive trên Supabase — xảy ra khi 2 thiết bị push
    // gần như đồng thời cho cùng source_word_id (race condition, hiếm hơn
    // trường hợp SyncEngine đã chặn trước qua fetchByWordId()). Ném lại
    // dưới dạng VocabDuplicateWordException để SyncEngine phân biệt được với
    // lỗi mạng/lỗi khác — và biết cần chạy lại fetchByWordId() để merge,
    // thay vì để nguyên PENDING_CREATE rồi lỗi lặp lại y hệt ở lần sync sau.
    suspend fun pushCreate(row: UserVocabularyUpsertDto) {
        try {
            postgrest.from(TABLE).upsert(row) {
                onConflict = "id"
            }
        } catch (e: RestException) {
            val message = e.message.orEmpty()
            val isUniqueViolation = message.contains("23505") ||
                    message.contains("duplicate key", ignoreCase = true) ||
                    message.contains("uq_vocab_user_word_alive", ignoreCase = true)
            if (isUniqueViolation) {
                throw VocabDuplicateWordException(row.sourceWordId)
            }
            throw e
        }
    }

    // ── PUSH: cập nhật (dòng đang PENDING_UPDATE ở local) ────────────────────
    // Optimistic locking đơn giản: chỉ update nếu id khớp — conflict thật sự
    // (409) sẽ xử lý ở bản mở rộng sau; hiện tại last-write-wins được xử lý ở
    // chiều pull (VocabRepository.applyServerChange so sánh updated_at).
    //
    // Thêm eq("user_id", row.userId) — chỉ update đúng dòng thuộc về user
    // đang đăng nhập, không cho phép sửa nhầm/sửa lén dòng của user khác dù
    // id có bị trùng hay bị đoán trúng.
    suspend fun pushUpdate(row: UserVocabularyUpsertDto) {
        postgrest.from(TABLE).update(row) {
            filter {
                eq("id", row.id)
                eq("user_id", row.userId)
            }
        }
    }

    // ── PUSH: xoá (dòng đang PENDING_DELETE ở local) ─────────────────────────
    // SOFT DELETE trên server: set deleted_at = now(), KHÔNG xoá cứng — để
    // các thiết bị khác còn thấy tombstone khi pull (delta 5h hoặc full 1
    // tuần). Server cần 1 job purge riêng (SQL cron trên Supabase, không
    // phải code app) để dọn các dòng có deleted_at cũ hơn 30–60 ngày.
    //
    // Thêm userId + eq("user_id", userId) — cùng lý do như pushUpdate().
    suspend fun pushDelete(id: String, userId: String) {
        postgrest.from(TABLE).update(
            mapOf("deleted_at" to nowUtcIsoForApi())
        ) {
            filter {
                eq("id", id)
                eq("user_id", userId)
            }
        }
    }

    private fun nowUtcIsoForApi(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.getDefault())
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())
}