// UserReadingData.kt
package com.eleap.eleap.feature.userreading

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.eleap.eleap.feature.reading.data.ReadingDatabase
import com.eleap.eleap.feature.reading.data.ReadingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─────────────────────────────────────────────────────────────────────────────
// 1. Tách nội dung thành câu
//    - Mỗi dòng (split theo \n) là 1 paragraph
//    - Trong mỗi dòng, tách theo dấu . ? ! (giữ dấu theo sau từ)
//      → dấu hiệu kết thúc câu = . hoặc ? hoặc !, theo sau là khoảng trắng hoặc hết chuỗi
//    - Bỏ qua câu rỗng sau khi trim
// ─────────────────────────────────────────────────────────────────────────────

data class ParsedSentence(
    val text: String,
    val sentenceOrder: Int,
    val paragraphOrder: Int,
)

fun parseContent(content: String): List<ParsedSentence> {
    val result = mutableListOf<ParsedSentence>()
    var sentenceOrder = 1

    val paragraphs = content.split(Regex("\n+"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    paragraphs.forEachIndexed { paraIndex, paragraph ->
        val paragraphOrder = paraIndex + 1

        // Tách sau [.?!] theo sau bởi khoảng trắng hoặc hết chuỗi
        val sentences = paragraph
            .split(Regex("(?<=[.?!])(?=\\s|$)"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        sentences.forEach { sentence ->
            result.add(
                ParsedSentence(
                    text           = sentence,
                    sentenceOrder  = sentenceOrder,
                    paragraphOrder = paragraphOrder,
                )
            )
            sentenceOrder++
        }
    }

    return result
}

// ─────────────────────────────────────────────────────────────────────────────
// 1b. Tách 1 câu thành từng word theo khoảng trắng
//    - Giữ nguyên dấu câu dính liền từ (vd "world.", "Hello,") — khớp với
//      ReadingRepository.normalizeWord(), nơi tự strip dấu câu đầu/cuối khi
//      tra từ điển, nên KHÔNG cần xử lý dấu câu ở đây.
//    - word_order đánh số tăng dần từ 1, theo đúng thứ tự xuất hiện trong câu.
// ─────────────────────────────────────────────────────────────────────────────

fun splitWords(sentenceText: String): List<String> =
    sentenceText
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }

// ─────────────────────────────────────────────────────────────────────────────
// 2. DAO — insert / delete vào readings + reading_sentences + sentence_words
//    Mỗi câu được tách thành từng word riêng (word_order tăng dần).
//    text_vi / sentence_explanation / word_explanation / pos / lemma / phrase_id
//    đều để NULL vì chưa qua round AI xử lý.
// ─────────────────────────────────────────────────────────────────────────────

class UserReadingDao(private val db: SQLiteDatabase) {

    /**
     * Insert 1 bài đọc + toàn bộ câu + từng word trong 1 transaction.
     * Trả về reading_id mới, hoặc -1 nếu thất bại.
     */
    fun insertReadingWithSentences(
        titleEn: String,
        sentences: List<ParsedSentence>,
        userId: Int,
        now: String,
    ): Long {
        var newReadingId = -1L
        db.beginTransaction()
        try {
            // 1. Insert vào readings
            val cv = ContentValues().apply {
                put("title_en",        titleEn)
                put("user_id",         userId)
                put("is_ai_processed", 0)
                put("created_at",      now)
                put("updated_at",      now)
                // title_vi, level, topic: chưa có → để NULL
            }
            newReadingId = db.insert("readings", null, cv)
            Log.d("UserReadingDao", "insert reading: \"$titleEn\" → reading_id=$newReadingId")

            if (newReadingId == -1L) {
                db.endTransaction()
                return -1L
            }

            // 2. Insert từng câu + từng word của câu đó
            sentences.forEach { s ->
                // 2a. reading_sentences — chỉ có text_en, text_vi để NULL
                val scv = ContentValues().apply {
                    put("reading_id",      newReadingId)
                    put("text_en",         s.text)
                    put("sentence_order",  s.sentenceOrder)
                    put("paragraph_order", s.paragraphOrder)
                    // text_vi, sentence_explanation: chưa có → để NULL
                }
                val sentenceRowId = db.insert("reading_sentences", null, scv)
                Log.d("UserReadingDao",
                    "  sentence #${s.sentenceOrder} (para ${s.paragraphOrder})" +
                            " → sentence_id=$sentenceRowId")

                if (sentenceRowId == -1L) return@forEach

                // 2b. sentence_words — tách câu thành từng word riêng theo khoảng trắng
                val wordTokens = splitWords(s.text)
                wordTokens.forEachIndexed { index, token ->
                    val wcv = ContentValues().apply {
                        put("sentence_id", sentenceRowId)
                        put("text_en",     token)
                        put("word_order",  index + 1)
                        // text_vi, word_explanation, pos, lemma,
                        // word_form_explanation, phrase_id: chưa có → để NULL
                    }
                    val wordRowId = db.insert("sentence_words", null, wcv)
                    if (wordRowId == -1L) {
                        Log.w("UserReadingDao", "    word #${index + 1} \"$token\" insert thất bại")
                    }
                }
                Log.d("UserReadingDao", "    → đã tách ${wordTokens.size} word(s)")
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return newReadingId
    }

    /**
     * Xoá hoàn toàn 1 bài đọc:
     *   sentence_words → reading_sentences → readings  (theo thứ tự FK)
     *
     * SQLite trong Android không enforce ON DELETE CASCADE theo mặc định,
     * nên tự xoá thủ công từ bảng con trước.
     *
     * Trả về true nếu xoá ít nhất 1 hàng trong readings.
     */
    fun deleteReadingById(readingId: Int): Boolean {
        db.beginTransaction()
        return try {
            // 1. Lấy tất cả sentence_id thuộc bài này
            val sentenceIds = mutableListOf<Int>()
            db.rawQuery(
                "SELECT sentence_id FROM reading_sentences WHERE reading_id = ?",
                arrayOf(readingId.toString())
            ).use { c ->
                while (c.moveToNext()) sentenceIds.add(c.getInt(0))
            }
            Log.d("UserReadingDao", "deleteReading: readingId=$readingId, sentences=${sentenceIds.size}")

            // 2. Xoá sentence_words của từng câu
            sentenceIds.forEach { sid ->
                val wRows = db.delete("sentence_words", "sentence_id = ?", arrayOf(sid.toString()))
                Log.d("UserReadingDao", "  sentence_id=$sid → xoá $wRows words")
            }

            // 3. Xoá reading_sentences
            val sRows = db.delete("reading_sentences", "reading_id = ?", arrayOf(readingId.toString()))
            Log.d("UserReadingDao", "  → xoá $sRows sentences")

            // 4. Xoá reading
            val rRows = db.delete("readings", "reading_id = ?", arrayOf(readingId.toString()))
            Log.d("UserReadingDao", "  → xoá $rRows reading row(s)")

            db.setTransactionSuccessful()
            rRows > 0
        } catch (e: Exception) {
            Log.e("UserReadingDao", "deleteReadingById error", e)
            false
        } finally {
            db.endTransaction()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 3. Repository
// ─────────────────────────────────────────────────────────────────────────────

class UserReadingRepository private constructor(context: Context) {

    private val dao: UserReadingDao

    init {
        // Đảm bảo ReadingDatabase đã copy readings.db từ assets về thiết bị
        ReadingDatabase.getInstance(context)

        val dbPath = context.getDatabasePath("readings.db").absolutePath
        val db = SQLiteDatabase.openDatabase(
            dbPath,
            null,
            SQLiteDatabase.OPEN_READWRITE
        )
        dao = UserReadingDao(db)
    }

    /**
     * Tách nội dung thành câu rồi lưu vào readings.db.
     * Sau khi lưu xong, invalidate cache của ReadingRepository để
     * ReadingListScreen tải lại danh sách và hiển thị bài mới.
     */
    suspend fun saveUserReading(title: String, content: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val sentences = parseContent(content)
                if (sentences.isEmpty()) {
                    Log.w("UserReadingRepo", "Nội dung không có câu nào sau khi tách")
                    return@withContext false
                }
                val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(Date())
                val id = dao.insertReadingWithSentences(
                    titleEn   = title.trim(),
                    sentences = sentences,
                    userId    = 0,
                    now       = now,
                )
                if (id != -1L) {
                    // Xoá cache để ReadingViewModel tải lại danh sách và sentences mới
                    ReadingRepository.invalidateCache()
                    Log.d("UserReadingRepo",
                        "saveUserReading OK: reading_id=$id, sentences=${sentences.size}")
                }
                id != -1L
            } catch (e: Exception) {
                Log.e("UserReadingRepo", "saveUserReading error", e)
                false
            }
        }

    /**
     * Xoá bài đọc do user tự tạo: sentence_words → reading_sentences → readings.
     * Sau khi xoá xong, invalidate cả list cache lẫn reading cache để UI cập nhật ngay.
     */
    suspend fun deleteUserReading(readingId: Int): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val ok = dao.deleteReadingById(readingId)
                if (ok) {
                    ReadingRepository.instance?.let { repo ->
                        repo.invalidateListCache()
                        repo.invalidateReadingCache(readingId)
                    }
                    Log.d("UserReadingRepo", "deleteUserReading OK: reading_id=$readingId")
                }
                ok
            } catch (e: Exception) {
                Log.e("UserReadingRepo", "deleteUserReading error", e)
                false
            }
        }

    companion object {
        @Volatile private var INSTANCE: UserReadingRepository? = null

        fun getInstance(context: Context): UserReadingRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: UserReadingRepository(context.applicationContext).also { INSTANCE = it }
            }
    }
}