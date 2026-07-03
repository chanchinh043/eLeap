// MyReadingDao.kt
// Đặt tại: feature/myreading/data/MyReadingDao.kt
//
// Tách từ MyReadingRepository.kt — tầng truy vấn SQL thuần: đọc bài/câu/
// phrase/word, insert bài mới, xoá bài (có kiểm tra user_id), migrate
// ownership, và pipeline AI (getPendingAiReadings/getSentenceOrdersAndText/
// writeAiResult). Dùng UuidV7 / nowIso8601 / splitMyWords / MyParsedSentence
// từ MyReadingSchema.kt — cùng package nên không cần import.
package com.eleap.eleap.feature.myreading.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.eleap.eleap.feature.reading.data.Reading
import com.eleap.eleap.feature.reading.data.ReadingSentence
import com.eleap.eleap.feature.reading.data.SentencePhrase
import com.eleap.eleap.feature.reading.data.SentenceWord

private const val TAG = "MyReadingRepository"

// ─────────────────────────────────────────────────────────────────────────────
// 3. DAO — đọc + ghi, LUÔN thao tác gắn với 1 user_id cụ thể do caller truyền vào
//    (Repository sẽ luôn truyền CurrentUser.userId.value tại thời điểm gọi)
// ─────────────────────────────────────────────────────────────────────────────

class MyReadingDao(private val db: SQLiteDatabase) {

    fun getAllReadings(userId: String): List<Reading> {
        val list = mutableListOf<Reading>()
        db.rawQuery(
            "SELECT * FROM readings WHERE user_id = ? ORDER BY created_at DESC",
            arrayOf(userId)
        ).use { c ->
            fun nullableString(col: String): String? {
                val idx = c.getColumnIndexOrThrow(col)
                return if (c.isNull(idx)) null else c.getString(idx)
            }
            while (c.moveToNext()) {
                list.add(
                    Reading(
                        readingId     = c.getString(c.getColumnIndexOrThrow("reading_id")),
                        userId        = nullableString("user_id"),
                        titleEn       = nullableString("title_en"),
                        titleVi       = nullableString("title_vi"),
                        level         = nullableString("level"),
                        topic         = nullableString("topic"),
                        isAiProcessed = c.getInt(c.getColumnIndexOrThrow("is_ai_processed")) != 0,
                        createdAt     = nullableString("created_at"),
                        updatedAt     = nullableString("updated_at"),
                    )
                )
            }
        }
        return list
    }

    /**
     * Chỉ lấy sentence_id (không kèm text/phrase/word) — dùng cho VocabRepository
     * để tra "bài MyReading này có những sentence_id nào", đối chiếu với
     * source_sentence_id đã lưu trong users.db.
     */
    fun getSentenceIdsByReadingId(readingId: String): List<String> {
        val list = mutableListOf<String>()
        db.rawQuery(
            "SELECT sentence_id FROM reading_sentences WHERE reading_id = ?",
            arrayOf(readingId)
        ).use { c ->
            while (c.moveToNext()) list.add(c.getString(0))
        }
        return list
    }

    fun getSentencesByReadingId(readingId: String): List<ReadingSentence> {
        val list = mutableListOf<ReadingSentence>()
        db.rawQuery(
            "SELECT * FROM reading_sentences WHERE reading_id = ? ORDER BY sentence_order ASC",
            arrayOf(readingId)
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    ReadingSentence(
                        sentenceId          = c.getString(c.getColumnIndexOrThrow("sentence_id")),
                        readingId           = c.getString(c.getColumnIndexOrThrow("reading_id")),
                        textEn              = c.getString(c.getColumnIndexOrThrow("text_en")),
                        textVi              = c.getString(c.getColumnIndexOrThrow("text_vi")),
                        sentenceExplanation = c.getString(c.getColumnIndexOrThrow("sentence_explanation")),
                        sentenceOrder       = c.getInt(c.getColumnIndexOrThrow("sentence_order")),
                    )
                )
            }
        }
        return list
    }

    fun getPhrasesBySentenceId(sentenceId: String): List<SentencePhrase> {
        val list = mutableListOf<SentencePhrase>()
        db.rawQuery(
            "SELECT * FROM sentence_phrases WHERE sentence_id = ?",
            arrayOf(sentenceId)
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    SentencePhrase(
                        phraseId          = c.getString(c.getColumnIndexOrThrow("phrase_id")),
                        sentenceId        = c.getString(c.getColumnIndexOrThrow("sentence_id")),
                        textEn            = c.getString(c.getColumnIndexOrThrow("text_en")),
                        textVi            = c.getString(c.getColumnIndexOrThrow("text_vi")),
                        phraseExplanation = c.getString(c.getColumnIndexOrThrow("phrase_explanation")),
                        startWordOrder    = c.getInt(c.getColumnIndexOrThrow("start_word_order")),
                        endWordOrder      = c.getInt(c.getColumnIndexOrThrow("end_word_order")),
                    )
                )
            }
        }
        return list
    }

    fun getWordsBySentenceId(sentenceId: String): List<SentenceWord> {
        val list = mutableListOf<SentenceWord>()
        db.rawQuery(
            "SELECT * FROM sentence_words WHERE sentence_id = ? ORDER BY word_order ASC",
            arrayOf(sentenceId)
        ).use { c ->
            val phraseIdIdx = c.getColumnIndexOrThrow("phrase_id")
            while (c.moveToNext()) {
                list.add(
                    SentenceWord(
                        wordId              = c.getString(c.getColumnIndexOrThrow("word_id")),
                        sentenceId          = c.getString(c.getColumnIndexOrThrow("sentence_id")),
                        phraseId            = if (c.isNull(phraseIdIdx)) null else c.getString(phraseIdIdx),
                        textEn              = c.getString(c.getColumnIndexOrThrow("text_en")),
                        textVi              = c.getString(c.getColumnIndexOrThrow("text_vi")),
                        wordExplanation     = c.getString(c.getColumnIndexOrThrow("word_explanation")),
                        wordOrder           = c.getInt(c.getColumnIndexOrThrow("word_order")),
                        pos                 = c.getString(c.getColumnIndexOrThrow("pos")),
                        lemma               = c.getString(c.getColumnIndexOrThrow("lemma")),
                        wordFormExplanation = c.getString(c.getColumnIndexOrThrow("word_form_explanation")),
                    )
                )
            }
        }
        return list
    }

    /**
     * Insert 1 bài đọc + toàn bộ câu + từng word trong 1 transaction.
     * Trả về reading_id (UUID v7) mới, hoặc null nếu thất bại.
     */
    fun insertReadingWithSentences(
        userId: String,
        titleEn: String,
        sentences: List<MyParsedSentence>,
    ): String? {
        val readingId = UuidV7.generate()
        val now = nowIso8601()

        db.beginTransaction()
        try {
            val cv = ContentValues().apply {
                put("reading_id", readingId)
                put("user_id",    userId)
                put("title_en",   titleEn)
                put("created_at", now)
                put("updated_at", now)
                put("is_ai_processed", false)
            }
            val readingRowId = db.insert("readings", null, cv)
            Log.d(TAG, "insert reading: \"$titleEn\" (user=$userId) → reading_id=$readingId (rowId=$readingRowId)")

            if (readingRowId == -1L) {
                db.endTransaction()
                return null
            }

            sentences.forEach { s ->
                val sentenceId = UuidV7.generate()
                val scv = ContentValues().apply {
                    put("sentence_id",     sentenceId)
                    put("reading_id",      readingId)
                    put("text_en",         s.text)
                    put("sentence_order",  s.sentenceOrder)
                    put("paragraph_order", s.paragraphOrder)
                }
                val sentenceRowId = db.insert("reading_sentences", null, scv)
                if (sentenceRowId == -1L) return@forEach

                val wordTokens = splitMyWords(s.text)
                wordTokens.forEachIndexed { index, token ->
                    val wcv = ContentValues().apply {
                        put("word_id",     UuidV7.generate())
                        put("sentence_id", sentenceId)
                        put("text_en",     token)
                        put("word_order",  index + 1)
                    }
                    db.insert("sentence_words", null, wcv)
                }
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return readingId
    }

    /**
     * Xoá hoàn toàn 1 bài đọc: sentence_words → sentence_phrases →
     * reading_sentences → readings.
     *
     * userId: bắt buộc khớp với cột user_id của bài đọc — chặn trường hợp
     * xoá nhầm/xoá hộ bài của user khác nếu readingId bị lộ ra ngoài phạm vi
     * user hiện tại (vd cache cũ, deep link...). Nếu readingId tồn tại nhưng
     * thuộc user khác → không xoá gì cả, trả về false.
     */
    fun deleteReadingById(readingId: String, userId: String): Boolean {
        db.beginTransaction()
        return try {
            // Kiểm tra quyền sở hữu TRƯỚC khi xoá bất kỳ dòng con nào — nếu không
            // khớp user_id thì dừng ngay, không đụng vào sentence_words/
            // sentence_phrases/reading_sentences (tránh xoá "chui" dữ liệu con
            // của bài đọc thuộc user khác trong lúc bảng readings không hề bị xoá).
            val ownerCursor = db.rawQuery(
                "SELECT 1 FROM readings WHERE reading_id = ? AND user_id = ? LIMIT 1",
                arrayOf(readingId, userId)
            )
            val isOwner = ownerCursor.use { it.moveToFirst() }
            if (!isOwner) {
                Log.w(TAG, "deleteReadingById: reading_id=$readingId không thuộc user_id=$userId, huỷ xoá")
                db.setTransactionSuccessful()
                return false
            }

            val sentenceIds = mutableListOf<String>()
            db.rawQuery(
                "SELECT sentence_id FROM reading_sentences WHERE reading_id = ?",
                arrayOf(readingId)
            ).use { c -> while (c.moveToNext()) sentenceIds.add(c.getString(0)) }

            sentenceIds.forEach { sid ->
                db.delete("sentence_words", "sentence_id = ?", arrayOf(sid))
                db.delete("sentence_phrases", "sentence_id = ?", arrayOf(sid))
            }
            db.delete("reading_sentences", "reading_id = ?", arrayOf(readingId))
            val rRows = db.delete(
                "readings",
                "reading_id = ? AND user_id = ?",
                arrayOf(readingId, userId)
            )

            db.setTransactionSuccessful()
            rRows > 0
        } catch (e: Exception) {
            Log.e(TAG, "deleteReadingById error", e)
            false
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Chuyển toàn bộ bài đọc từ 1 user_id (thường là guest) sang user_id khác
     * (thường là uuid thật vừa đăng nhập). Trả về số dòng đã cập nhật.
     */
    fun migrateOwnership(fromUserId: String, toUserId: String): Int {
        val cv = ContentValues().apply { put("user_id", toUserId) }
        val rows = db.update("readings", cv, "user_id = ?", arrayOf(fromUserId))
        Log.d(TAG, "migrateOwnership: $fromUserId → $toUserId, $rows row(s)")
        return rows
    }

    // ─────────────────────────────────────────────────────────────────────
    // AI processing — quét bài của user (user_id != NULL) chưa được AI xử
    // lý (is_ai_processed = 0), lấy câu để build prompt, và ghi kết quả AI.
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Danh sách (reading_id, title_en) của các bài THUỘC USER (user_id không
     * null) mà chưa được AI xử lý — dùng cho watchdog quét ngầm.
     */
    fun getPendingAiReadings(): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        db.rawQuery(
            "SELECT reading_id, title_en FROM readings " +
                    "WHERE user_id IS NOT NULL AND is_ai_processed = 0",
            null
        ).use { c ->
            while (c.moveToNext()) {
                list.add(c.getString(0) to (c.getString(1) ?: ""))
            }
        }
        return list
    }

    /** (sentence_order, text_en) của 1 bài, dùng để build prompt gửi AI. */
    fun getSentenceOrdersAndText(readingId: String): List<Pair<Int, String>> {
        val list = mutableListOf<Pair<Int, String>>()
        db.rawQuery(
            "SELECT sentence_order, text_en FROM reading_sentences " +
                    "WHERE reading_id = ? ORDER BY sentence_order ASC",
            arrayOf(readingId)
        ).use { c ->
            while (c.moveToNext()) {
                list.add(c.getInt(0) to (c.getString(1) ?: ""))
            }
        }
        return list
    }

    /**
     * Ghi kết quả AI vào readings / reading_sentences / sentence_phrases /
     * sentence_words trong 1 transaction.
     *
     * LƯU Ý: đây là dữ liệu MỚI HOÀN TOÀN (bài vừa insert, chưa từng có
     * phrase nào) nên chỉ cần INSERT phrase — không cần xoá/insert-lại như
     * luồng xử lý-lại của readings.db.
     *
     * words đã tồn tại sẵn (từ insertReadingWithSentences) nên chỉ UPDATE
     * theo (sentence_id, word_order).
     */
    fun writeAiResult(readingId: String, aiData: MyAiReading): Boolean {
        val now = nowIso8601()
        db.beginTransaction()
        return try {
            val rcv = ContentValues().apply {
                aiData.titleVi?.let { put("title_vi", it) }
                aiData.level?.let { put("level", it) }
                aiData.topic?.let { put("topic", it) }
                put("is_ai_processed", 1)
                put("updated_at", now)
            }
            db.update("readings", rcv, "reading_id = ?", arrayOf(readingId))

            for (s in aiData.sentences) {
                val sentenceId = db.rawQuery(
                    "SELECT sentence_id FROM reading_sentences " +
                            "WHERE reading_id = ? AND sentence_order = ?",
                    arrayOf(readingId, s.sentenceOrder.toString())
                ).use { c -> if (c.moveToFirst()) c.getString(0) else null }

                if (sentenceId == null) {
                    Log.w(TAG, "writeAiResult: không tìm thấy câu #${s.sentenceOrder} (reading_id=$readingId)")
                    continue
                }

                val scv = ContentValues().apply {
                    s.textVi?.let { put("text_vi", it) }
                    s.explanation?.let { put("sentence_explanation", it) }
                }
                db.update("reading_sentences", scv, "sentence_id = ?", arrayOf(sentenceId))

                // Insert toàn bộ phrase mới (bài mới hoàn toàn → không có phrase cũ để xoá)
                val phraseIdMap = mutableMapOf<String, String>()
                for (p in s.phrases) {
                    val phraseId = UuidV7.generate()
                    val pcv = ContentValues().apply {
                        put("phrase_id", phraseId)
                        put("sentence_id", sentenceId)
                        put("text_en", p.textEn)
                        p.textVi?.let { put("text_vi", it) }
                        p.explanation?.let { put("phrase_explanation", it) }
                        put("start_word_order", p.startWordOrder)
                        put("end_word_order", p.endWordOrder)
                    }
                    val rowId = db.insert("sentence_phrases", null, pcv)
                    if (rowId != -1L) phraseIdMap[p.id] = phraseId
                }

                // Update từng word (đã tồn tại sẵn từ lúc insert ban đầu)
                for (w in s.words) {
                    val dbPhraseId = phraseIdMap[w.phraseId]
                    val wcv = ContentValues().apply {
                        w.textVi?.let { put("text_vi", it) }
                        w.pos?.let { put("pos", it) }
                        w.lemma?.let { put("lemma", it) }
                        w.explanation?.let { put("word_explanation", it) }
                        w.formExplanation?.let { put("word_form_explanation", it) }
                        if (dbPhraseId != null) put("phrase_id", dbPhraseId) else putNull("phrase_id")
                    }
                    val updated = db.update(
                        "sentence_words", wcv,
                        "sentence_id = ? AND word_order = ?",
                        arrayOf(sentenceId, w.wordOrder.toString())
                    )
                    if (updated == 0) {
                        Log.w(TAG, "writeAiResult: word #${w.wordOrder} '${w.textEn}' không tìm thấy để update (sentence_id=$sentenceId)")
                    }
                }
            }

            db.setTransactionSuccessful()
            true
        } catch (e: Exception) {
            Log.e(TAG, "writeAiResult error (reading_id=$readingId)", e)
            false
        } finally {
            db.endTransaction()
        }
    }
}