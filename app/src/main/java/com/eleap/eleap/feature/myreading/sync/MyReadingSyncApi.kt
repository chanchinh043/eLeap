// MyReadingSyncApi.kt
// Đặt tại: feature/myreading/sync/MyReadingSyncApi.kt
//
// Tầng DUY NHẤT gọi Supabase cho việc đồng bộ MyReading. Không biết gì về
// SQLite/sync_status cục bộ — chỉ nhận/trả dữ liệu thô (Dto) khớp 1:1 với 4
// bảng thật trên Supabase: readings, reading_sentences, sentence_phrases,
// sentence_words (xem supabase_myreading_schema.sql).
//
// ⚠️ ĐÃ ĐỔI THIẾT KẾ: trước đây đơn vị đồng bộ là 1 chuỗi JSON gộp (cột
// payload trên bảng my_readings). Giờ đồng bộ THẲNG vào 4 bảng khớp tên với
// local — không còn bảng/cột "my_readings"/"payload" nào nữa. Đơn vị đồng bộ
// vẫn là CẢ 1 BÀI ĐỌC về mặt hành vi (MyReadingSyncEngine luôn đọc/ghi trọn
// vẹn 1 bài cùng lúc), chỉ khác ở chỗ dữ liệu đi qua nhiều bảng thay vì 1 cột.
//
// Không atomic tuyệt đối giữa nhiều round-trip (upsert readings → xoá
// reading_sentences cũ → insert lại sentences/phrases/words) — chấp nhận
// được vì cùng 1 nguyên tắc "ghi đè lại từ đầu" như local (applyServerReading
// ở MyReadingDao.kt cũng làm y hệt: xoá sạch rồi insert lại). Nếu sau này cần
// atomic tuyệt đối, cân nhắc gộp thành 1 Postgres RPC function.
//
// user_id trong mọi filter: lớp chặn thứ 2 độc lập với RLS — cùng lý do đã
// giải thích ở core/sync/SyncApi.kt.
//
// Singleton thủ công, KHÔNG dùng Hilt/DI.
package com.eleap.eleap.feature.myreading.sync

import com.eleap.eleap.core.auth.SupabaseClientProvider
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val TABLE_READINGS  = "readings"
private const val TABLE_SENTENCES = "reading_sentences"
private const val TABLE_PHRASES   = "sentence_phrases"
private const val TABLE_WORDS     = "sentence_words"

// ── DTO khớp 1:1 cột của bảng "readings" — KHÔNG có sync_status (chỉ có ý
// nghĩa cục bộ, không tồn tại trên Supabase). ────────────────────────────────
@Serializable
data class ReadingRowDto(
    @SerialName("reading_id") val readingId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("title_en") val titleEn: String? = null,
    @SerialName("title_vi") val titleVi: String? = null,
    val level: String? = null,
    val topic: String? = null,
    @SerialName("is_ai_processed") val isAiProcessed: Int = 0,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

// Dữ liệu tối thiểu để tạo/sửa dòng "readings" khi push — không có deletedAt
// (xoá là pushDelete() riêng, không đi qua upsert này).
@Serializable
data class ReadingRowUpsertDto(
    @SerialName("reading_id") val readingId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("title_en") val titleEn: String? = null,
    @SerialName("title_vi") val titleVi: String? = null,
    val level: String? = null,
    val topic: String? = null,
    @SerialName("is_ai_processed") val isAiProcessed: Int = 0,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class ReadingSentenceDto(
    @SerialName("sentence_id") val sentenceId: String,
    @SerialName("reading_id") val readingId: String,
    @SerialName("text_en") val textEn: String? = null,
    @SerialName("text_vi") val textVi: String? = null,
    @SerialName("sentence_explanation") val sentenceExplanation: String? = null,
    @SerialName("sentence_order") val sentenceOrder: Int,
    @SerialName("paragraph_order") val paragraphOrder: Int? = null,
)

@Serializable
data class SentencePhraseDto(
    @SerialName("phrase_id") val phraseId: String,
    @SerialName("sentence_id") val sentenceId: String,
    @SerialName("text_en") val textEn: String? = null,
    @SerialName("text_vi") val textVi: String? = null,
    @SerialName("phrase_explanation") val phraseExplanation: String? = null,
    @SerialName("start_word_order") val startWordOrder: Int,
    @SerialName("end_word_order") val endWordOrder: Int,
    val lmwe: String? = null,
    @SerialName("lmwe_explanation") val lmweExplanation: String? = null,
)

@Serializable
data class SentenceWordDto(
    @SerialName("word_id") val wordId: String,
    @SerialName("sentence_id") val sentenceId: String,
    @SerialName("phrase_id") val phraseId: String? = null,
    @SerialName("text_en") val textEn: String? = null,
    @SerialName("text_vi") val textVi: String? = null,
    @SerialName("word_explanation") val wordExplanation: String? = null,
    @SerialName("word_order") val wordOrder: Int,
    val pos: String? = null,
    val lemma: String? = null,
    @SerialName("word_form_explanation") val wordFormExplanation: String? = null,
)

// Cây con (sentences + phrases + words) của 1 bài, đã fetch xong từ server —
// dùng bởi cả pull thường lẫn Realtime (sau khi biết reading_id nào vừa đổi).
data class ReadingChildren(
    val sentences: List<ReadingSentenceDto>,
    val phrases: List<SentencePhraseDto>,
    val words: List<SentenceWordDto>,
)

object MyReadingSyncApi {

    private val postgrest get() = SupabaseClientProvider.client.postgrest

    // ═══ PULL ═══════════════════════════════════════════════════════════════

    // Chỉ lấy dòng "readings" thay đổi (delta theo updated_at) — sentences/
    // phrases/words của từng bài được fetch riêng qua fetchChildrenForReading()
    // ngay sau khi biết reading_id nào cần cập nhật (xem MyReadingSyncEngine).
    suspend fun pullReadingRowsDelta(userId: String, cursor: String?): List<ReadingRowDto> =
        postgrest.from(TABLE_READINGS).select {
            filter {
                eq("user_id", userId)
                if (cursor != null) {
                    gt("updated_at", cursor)
                }
            }
            order("updated_at", Order.ASCENDING)
        }.decodeList()

    suspend fun pullReadingRowsFull(userId: String): List<ReadingRowDto> =
        postgrest.from(TABLE_READINGS).select {
            filter { eq("user_id", userId) }
            order("updated_at", Order.ASCENDING)
        }.decodeList()

    // ── PULL: 1 dòng theo reading_id — dùng cho optimistic locking TRƯỚC khi
    // pushUpdate(), giống hệt cách SyncApi (vocab) đã làm.
    suspend fun fetchOneReadingRow(readingId: String, userId: String): ReadingRowDto? =
        postgrest.from(TABLE_READINGS).select {
            filter {
                eq("reading_id", readingId)
                eq("user_id", userId)
            }
        }.decodeSingleOrNull()

    // ── Toàn bộ sentences + phrases + words của 1 bài — dùng cho cả pull lẫn
    // Realtime, ngay sau khi biết dòng "readings" nào vừa đổi. Nếu bài không
    // còn sentence nào (hiếm, hoặc chưa qua xử lý AI) → trả list rỗng, không
    // gọi thêm 2 query con để đỡ tốn round-trip.
    suspend fun fetchChildrenForReading(readingId: String): ReadingChildren {
        val sentences = postgrest.from(TABLE_SENTENCES).select {
            filter { eq("reading_id", readingId) }
            order("sentence_order", Order.ASCENDING)
        }.decodeList<ReadingSentenceDto>()

        if (sentences.isEmpty()) {
            return ReadingChildren(emptyList(), emptyList(), emptyList())
        }

        val sentenceIds = sentences.map { it.sentenceId }

        // ⚠️ isIn(column, values): tên hàm filter "IN" có thể khác nhau giữa
        // các bản postgrest-kt (đã từng đổi tên vài lần, xem ghi chú tương tự
        // ở SyncRealtime.kt về postgresChangeFlow) — kiểm tra lại tên hàm
        // đúng với version supabase-kt đang dùng trong project nếu bị lỗi
        // biên dịch ở đây.
        val phrases = postgrest.from(TABLE_PHRASES).select {
            filter { isIn("sentence_id", sentenceIds) }
        }.decodeList<SentencePhraseDto>()

        val words = postgrest.from(TABLE_WORDS).select {
            filter { isIn("sentence_id", sentenceIds) }
            order("word_order", Order.ASCENDING)
        }.decodeList<SentenceWordDto>()

        return ReadingChildren(sentences, phrases, words)
    }

    // ═══ PUSH ═══════════════════════════════════════════════════════════════

    // ── Tạo mới HOẶC cập nhật 1 bài — upsert dòng "readings", rồi XOÁ SẠCH +
    // INSERT LẠI toàn bộ cây con (reading_sentences/sentence_phrases/
    // sentence_words), sau đó insert lại phrases/words mới. Giống hệt tinh
    // thần applyServerReading() ở local (ghi đè lại từ đầu, không diff từng
    // dòng).
    //
    // ⚠️ ĐÃ SỬA: KHÔNG còn dựa vào "on delete cascade" của Postgres nữa dù
    // schema SQL có khai báo cascade thật (xem supabase_myreading_schema.sql).
    // Lý do: sentence_phrases/sentence_words đang bật RLS với policy join
    // ngược qua reading_sentences (rồi qua readings) để biết dòng đó có
    // thuộc đúng user hay không. Khi DELETE FROM reading_sentences kéo theo
    // cascade xuống 2 bảng con, Postgres đánh giá lại RLS của TỪNG bảng con
    // NGAY LÚC dòng reading_sentences cha đang bị xoá dở — join ngược lúc đó
    // không còn đảm bảo thấy đúng dòng cha, nên cascade delete cho 2 bảng
    // con dễ bị RLS âm thầm chặn lại (1 cạm bẫy khá phổ biến của Postgres:
    // RLS + cascade delete phụ thuộc join). Hậu quả đúng như quan sát thực
    // tế: "readings" và "reading_sentences" xoá/ghi đúng (RLS của chúng chỉ
    // join tối đa 1 cấp, không bị vướng), còn sentence_phrases/sentence_words
    // thì dữ liệu cũ không được dọn sạch.
    //
    // Fix: tự tay xoá CON TRƯỚC CHA (sentence_words → sentence_phrases →
    // reading_sentences) — đúng thứ tự MyReadingDao.applyServerReading() đã
    // làm ở local (SQLite local cũng không cascade 2 bảng này). Mỗi lệnh xoá
    // lúc đó tự đứng vững trước RLS của chính bảng nó (dòng cha vẫn còn
    // nguyên vẹn tại thời điểm join), không phụ thuộc cascade nữa.
    //
    // Dùng upsert (onConflict = reading_id) cho dòng readings — cùng lý do
    // như pushCreate() bên vocab: an toàn khi retry (mất mạng giữa chừng ở
    // lần push trước).
    suspend fun pushReadingCreateOrUpdate(
        reading: ReadingRowUpsertDto,
        sentences: List<ReadingSentenceDto>,
        phrases: List<SentencePhraseDto>,
        words: List<SentenceWordDto>,
    ) {
        postgrest.from(TABLE_READINGS).upsert(reading) {
            onConflict = "reading_id"
        }

        // Lấy sentence_id cũ của bài này (nếu có) — cần để xoá đúng
        // sentence_phrases/sentence_words cũ TRƯỚC KHI đụng tới
        // reading_sentences (cha). Tái dùng ReadingSentenceDto để decode vì
        // đã có sẵn, không cần cú pháp select-1-cột (Columns.list) vốn có
        // thể khác tên hàm giữa các bản postgrest-kt.
        val oldSentenceIds = postgrest.from(TABLE_SENTENCES).select {
            filter { eq("reading_id", reading.readingId) }
        }.decodeList<ReadingSentenceDto>().map { it.sentenceId }

        if (oldSentenceIds.isNotEmpty()) {
            // ⚠️ isIn: tên hàm filter "IN" có thể khác nhau giữa các bản
            // postgrest-kt — cùng ghi chú như ở fetchChildrenForReading() bên
            // dưới, kiểm tra lại nếu bị lỗi biên dịch.
            postgrest.from(TABLE_WORDS).delete {
                filter { isIn("sentence_id", oldSentenceIds) }
            }
            postgrest.from(TABLE_PHRASES).delete {
                filter { isIn("sentence_id", oldSentenceIds) }
            }
        }

        // Xoá sạch reading_sentences cũ — lúc này 2 bảng con đã dọn xong ở
        // trên rồi, nên dù cascade có chạy hay bị RLS chặn cũng không còn gì
        // để xoá thêm, không ảnh hưởng kết quả cuối cùng.
        postgrest.from(TABLE_SENTENCES).delete {
            filter { eq("reading_id", reading.readingId) }
        }

        // Insert lại từ đầu — CHA TRƯỚC CON (reading_sentences trước, rồi
        // mới tới phrases/words) để RLS insert (join lên reading_sentences/
        // readings) luôn thấy đúng dòng cha vừa insert xong.
        if (sentences.isNotEmpty()) {
            postgrest.from(TABLE_SENTENCES).insert(sentences)
        }
        if (phrases.isNotEmpty()) {
            postgrest.from(TABLE_PHRASES).insert(phrases)
        }
        if (words.isNotEmpty()) {
            postgrest.from(TABLE_WORDS).insert(words)
        }
    }

    // ── PUSH: xoá (dòng đang PENDING_DELETE ở local) ─────────────────────────
    // SOFT DELETE: chỉ set deleted_at trên "readings", KHÔNG đụng tới
    // reading_sentences/sentence_phrases/sentence_words — giữ lại để audit/
    // khôi phục nếu cần, đúng tinh thần soft-delete của user_vocabulary.
    suspend fun pushDelete(readingId: String, userId: String) {
        postgrest.from(TABLE_READINGS).update(
            mapOf("deleted_at" to nowUtcIsoForApi())
        ) {
            filter {
                eq("reading_id", readingId)
                eq("user_id", userId)
            }
        }
    }

    private fun nowUtcIsoForApi(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.getDefault())
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())
}