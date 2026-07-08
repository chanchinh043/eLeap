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
        // ⚠️ BẮT BUỘC lọc deleted_at IS NULL — xoá bài là SOFT DELETE (xem
        // deleteReadingById/applyServerReading), dòng "readings" vẫn còn
        // trong bảng sau khi xoá (kể cả sau khi đã sync xong với server) để
        // giữ tombstone cho các thiết bị khác. Thiếu điều kiện này khiến bài
        // đã xoá vẫn hiện trong danh sách vĩnh viễn dù sync_status=synced.
        db.rawQuery(
            "SELECT * FROM readings WHERE user_id = ? AND deleted_at IS NULL ORDER BY created_at DESC",
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
            // paragraph_order đã có sẵn trong schema (MyReadingSchema.kt) từ
            // trước, nhưng trước đây KHÔNG được đọc lại vào ReadingSentence —
            // đọc ở đây để có thể đẩy lên Supabase (bảng reading_sentences,
            // cột paragraph_order) đúng khớp local khi push/pull.
            val paragraphOrderIdx = c.getColumnIndexOrThrow("paragraph_order")
            while (c.moveToNext()) {
                list.add(
                    ReadingSentence(
                        sentenceId          = c.getString(c.getColumnIndexOrThrow("sentence_id")),
                        readingId           = c.getString(c.getColumnIndexOrThrow("reading_id")),
                        textEn              = c.getString(c.getColumnIndexOrThrow("text_en")),
                        textVi              = c.getString(c.getColumnIndexOrThrow("text_vi")),
                        sentenceExplanation = c.getString(c.getColumnIndexOrThrow("sentence_explanation")),
                        sentenceOrder       = c.getInt(c.getColumnIndexOrThrow("sentence_order")),
                        paragraphOrder      = if (c.isNull(paragraphOrderIdx)) 1 else c.getInt(paragraphOrderIdx),
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
                // Bài mới tạo cục bộ — chưa từng lên server, đánh dấu tường
                // minh dù cột đã có DEFAULT 'pending_create', để không phụ
                // thuộc ngầm vào default của schema.
                put("sync_status", MyReadingSyncStatus.PENDING_CREATE)
                putNull("deleted_at")
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
     * Xoá 1 bài đọc — áp dụng đúng quy tắc vòng đời sync (giống cách
     * VocabRepository xử lý xoá 1 từ vựng):
     *   - sync_status hiện tại là PENDING_CREATE (chưa từng lên server) →
     *     HARD DELETE thật sự: xoá luôn readings + toàn bộ bảng con liên
     *     quan (sentence_words → sentence_phrases → reading_sentences),
     *     vì server không hề biết bài này tồn tại nên không cần báo lại.
     *   - sync_status khác (đã từng SYNCED hoặc đang PENDING_UPDATE) →
     *     SOFT DELETE: chỉ set deleted_at + sync_status = PENDING_DELETE
     *     trên bảng readings, KHÔNG đụng tới bảng con — để lần sync sau
     *     còn biết mà gửi tombstone lên server/thiết bị khác.
     *
     * userId: bắt buộc khớp với cột user_id của bài đọc — chặn trường hợp
     * xoá nhầm/xoá hộ bài của user khác nếu readingId bị lộ ra ngoài phạm vi
     * user hiện tại (vd cache cũ, deep link...). Nếu readingId tồn tại nhưng
     * thuộc user khác → không xoá gì cả, trả về false.
     */
    fun deleteReadingById(readingId: String, userId: String): Boolean {
        db.beginTransaction()
        return try {
            // Kiểm tra quyền sở hữu + lấy sync_status hiện tại TRONG CÙNG 1
            // lượt đọc — nếu không khớp user_id (hoặc không tồn tại) thì
            // dừng ngay, không đụng vào bất kỳ bảng nào khác.
            val syncStatus = db.rawQuery(
                "SELECT sync_status FROM readings WHERE reading_id = ? AND user_id = ? LIMIT 1",
                arrayOf(readingId, userId)
            ).use { c -> if (c.moveToFirst()) c.getString(0) else null }

            if (syncStatus == null) {
                Log.w(TAG, "deleteReadingById: reading_id=$readingId không thuộc user_id=$userId hoặc không tồn tại, huỷ xoá")
                db.setTransactionSuccessful()
                return false
            }

            val affected = if (syncStatus == MyReadingSyncStatus.PENDING_CREATE) {
                // ── HARD DELETE — bài chưa từng lên server ───────────────────
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
                Log.d(TAG, "deleteReadingById: HARD DELETE reading_id=$readingId (đang PENDING_CREATE)")
                rRows > 0
            } else {
                // ── SOFT DELETE — bài đã/đang có liên hệ với server ──────────
                val cv = ContentValues().apply {
                    put("deleted_at", nowIso8601())
                    put("sync_status", MyReadingSyncStatus.PENDING_DELETE)
                }
                val rRows = db.update(
                    "readings", cv,
                    "reading_id = ? AND user_id = ?",
                    arrayOf(readingId, userId)
                )
                Log.d(TAG, "deleteReadingById: SOFT DELETE reading_id=$readingId (sync_status cũ=$syncStatus → PENDING_DELETE)")
                rRows > 0
            }

            db.setTransactionSuccessful()
            affected
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
            // Đọc sync_status hiện tại TRƯỚC khi update — quy tắc không hạ
            // cấp trạng thái (xem comment ở MyReadingSyncStatus,
            // MyReadingSchema.kt):
            //   - Đang SYNCED (đã lên server từ trước, giờ có nội dung AI
            //     mới) → hạ xuống PENDING_UPDATE để lần sync sau đẩy lên.
            //   - Đang PENDING_CREATE/PENDING_UPDATE/PENDING_DELETE → GIỮ
            //     NGUYÊN, vì bài chưa từng lên server thì "update" không có
            //     ý nghĩa (PENDING_CREATE sẽ tự mang theo dữ liệu AI này khi
            //     push lần đầu), còn PENDING_DELETE thì không được ghi đè
            //     ngược lại chỉ vì watchdog AI chạy trễ.
            val currentSyncStatus = db.rawQuery(
                "SELECT sync_status FROM readings WHERE reading_id = ?",
                arrayOf(readingId)
            ).use { c -> if (c.moveToFirst()) c.getString(0) else null }

            val newSyncStatus = if (currentSyncStatus == MyReadingSyncStatus.SYNCED) {
                MyReadingSyncStatus.PENDING_UPDATE
            } else {
                currentSyncStatus ?: MyReadingSyncStatus.PENDING_CREATE
            }

            val rcv = ContentValues().apply {
                aiData.titleVi?.let { put("title_vi", it) }
                aiData.level?.let { put("level", it) }
                aiData.topic?.let { put("topic", it) }
                put("is_ai_processed", 1)
                // Set updated_at thủ công ở đây thay vì trông cậy hết vào
                // trigger trg_myreading_updated_at — trigger chỉ tự chạy khi
                // NEW.updated_at IS OLD.updated_at (tức câu UPDATE không tự
                // đổi cột này); còn ở đây ta CHỦ ĐỘNG set updated_at = now
                // luôn cho chắc chắn, không phụ thuộc điều kiện đó.
                put("updated_at", now)
                put("sync_status", newSyncStatus)
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

    // ─────────────────────────────────────────────────────────────────────
    // ── Dùng cho MyReadingSyncEngine ──
    // Đơn vị đồng bộ là CẢ 1 bài đọc (đóng gói reading + sentences/phrases/
    // words thành JSON). Các hàm dưới đây KHÔNG lọc theo CurrentUser — nhận
    // userId/readingId trực tiếp từ caller (SyncEngine chạy nền có thể xử
    // lý userId khác với CurrentUser tại thời điểm gọi).
    // ─────────────────────────────────────────────────────────────────────

    /** Map 1 dòng cursor đang trỏ vào bảng readings (SELECT *) thành Reading. */
    private fun cursorToReading(c: android.database.Cursor): Reading {
        fun nullableString(col: String): String? {
            val idx = c.getColumnIndexOrThrow(col)
            return if (c.isNull(idx)) null else c.getString(idx)
        }
        return Reading(
            readingId     = c.getString(c.getColumnIndexOrThrow("reading_id")),
            userId        = nullableString("user_id"),
            titleEn       = nullableString("title_en"),
            titleVi       = nullableString("title_vi"),
            level         = nullableString("level"),
            topic         = nullableString("topic"),
            isAiProcessed = c.getInt(c.getColumnIndexOrThrow("is_ai_processed")) != 0,
            createdAt     = nullableString("created_at"),
            updatedAt     = nullableString("updated_at"),
            // Khác với readings.db (hệ thống), bảng readings ở myreading.db
            // LUÔN có cột sync_status (NOT NULL DEFAULT 'pending_create') —
            // map thẳng vào field mới thêm ở Reading để MyReadingSyncEngine
            // biết cần push create/update/delete cho đúng dòng nào.
            syncStatus    = nullableString("sync_status"),
        )
    }

    /**
     * Toàn bộ bài đọc của 1 user đang có thay đổi cục bộ chưa đẩy lên server
     * (sync_status khác 'synced') — dùng cho MyReadingSyncEngine.pushPendingLocked().
     * Sắp theo updated_at ASC để bài cũ được đẩy trước (giống thứ tự xử lý
     * bên VocabRepository.getPendingRows()).
     */
    fun getPendingReadings(userId: String): List<Reading> {
        val list = mutableListOf<Reading>()
        // ⚠️ Điều kiện "sẵn sàng push" (chặn bài pending_create chưa qua AI)
        // định nghĩa DUY NHẤT ở MyReadingPushReadiness.SQL_CONDITION — xem
        // giải thích lý do ở MyReadingSchema.kt. Muốn đổi quy tắc thì sửa Ở
        // ĐÓ, không sửa trực tiếp chuỗi SQL ở đây.
        db.rawQuery(
            "SELECT * FROM readings WHERE user_id = ? AND sync_status != ? " +
                    "AND (${MyReadingPushReadiness.SQL_CONDITION}) ORDER BY updated_at ASC",
            arrayOf(userId, MyReadingSyncStatus.SYNCED)
        ).use { c ->
            while (c.moveToNext()) list.add(cursorToReading(c))
        }
        return list
    }

    /** Đánh dấu synced cho danh sách reading_id — gọi sau khi push thành công. */
    fun markSynced(readingIds: List<String>) {
        if (readingIds.isEmpty()) return
        val placeholders = readingIds.joinToString(",") { "?" }
        val bindArgs = (listOf(MyReadingSyncStatus.SYNCED) + readingIds).toTypedArray()
        db.execSQL(
            "UPDATE readings SET sync_status = ? WHERE reading_id IN ($placeholders)",
            bindArgs
        )
        Log.d(TAG, "markSynced: đã đánh dấu synced cho ${readingIds.size} bài: $readingIds")
    }

    /** 1 dòng readings theo reading_id, không lọc user_id — null nếu không tồn tại. */
    fun getReadingRow(readingId: String): Reading? {
        return db.rawQuery(
            "SELECT * FROM readings WHERE reading_id = ? LIMIT 1",
            arrayOf(readingId)
        ).use { c -> if (c.moveToFirst()) cursorToReading(c) else null }
    }

    /**
     * Toàn bộ câu + phrase + word của 1 bài, dùng để build payload JSON khi
     * push lên server — logic giống hệt buildReading() private hiện có ở
     * MyReadingRepository, chỉ khác là public ở tầng Dao để Repository gọi
     * lại được cho mục đích sync (tách khỏi sentenceCache dùng cho UI).
     */
    fun getFullSentencesForSync(readingId: String): List<ReadingSentence> {
        val sentences = getSentencesByReadingId(readingId)
        return sentences.map { s ->
            s.copy(
                phrases = getPhrasesBySentenceId(s.sentenceId),
                words   = getWordsBySentenceId(s.sentenceId),
            )
        }
    }

    /**
     * Ghi đè toàn bộ cây dữ liệu 1 bài đọc từ server vào local, trong 1
     * transaction — dùng bởi MyReadingSyncEngine (pull định kỳ) và
     * MyReadingSyncRealtime (nhận sự kiện WebSocket).
     *
     *   - isTombstone = true: bài đã bị xoá ở nơi khác → chỉ soft-delete
     *     dòng readings (set deleted_at, sync_status = synced), KHÔNG đụng
     *     tới bảng con. Nếu local chưa từng có dòng này thì bỏ qua luôn,
     *     không cần "tạo ra rồi xoá".
     *   - isTombstone = false: UPSERT dòng readings với sync_status =
     *     synced, rồi XOÁ SẠCH reading_sentences/sentence_phrases/
     *     sentence_words cũ của readingId này và INSERT LẠI TỪ ĐẦU theo
     *     đúng dữ liệu server truyền vào — đơn giản và đúng hơn nhiều so
     *     với diff từng dòng, chấp nhận được vì đơn vị đồng bộ vốn là cả 1
     *     bài (không phải diff từng câu/từ).
     *
     * reading.deletedAt hiện KHÔNG có sẵn trong data class Reading (sẽ bổ
     * sung khi cần ở bước nối MyReadingSyncEngine) — tạm dùng giờ hiện tại
     * cho deleted_at khi áp tombstone, vì giá trị chính xác không ảnh hưởng
     * tới logic nghiệp vụ (chỉ cần khác NULL để coi là đã xoá).
     */
    fun applyServerReading(
        reading: Reading,
        sentences: List<ReadingSentence>,
        isTombstone: Boolean,
        // Thời điểm xoá THẬT từ server (cột deleted_at ở bảng readings trên
        // Supabase) — trước đây không có tham số này nên phải tự bịa giờ
        // local (nowIso8601()) mỗi lần áp tombstone, không phản ánh đúng lúc
        // xoá thật sự. null = không có (vd Realtime DELETE cứng hiếm gặp,
        // không có cột deleted_at kèm theo) → vẫn fallback về giờ local.
        deletedAt: String? = null,
    ): Boolean {
        db.beginTransaction()
        return try {
            if (isTombstone) {
                val exists = db.rawQuery(
                    "SELECT 1 FROM readings WHERE reading_id = ? LIMIT 1",
                    arrayOf(reading.readingId)
                ).use { it.moveToFirst() }

                if (exists) {
                    val cv = ContentValues().apply {
                        put("deleted_at", deletedAt ?: nowIso8601())
                        put("sync_status", MyReadingSyncStatus.SYNCED)
                    }
                    db.update("readings", cv, "reading_id = ?", arrayOf(reading.readingId))
                    Log.d(TAG, "applyServerReading: áp tombstone reading_id=${reading.readingId}")
                } else {
                    Log.d(TAG, "applyServerReading: tombstone reading_id=${reading.readingId} nhưng local chưa từng có, bỏ qua")
                }
            } else {
                // ⚠️ SAFETY GUARD — chống mất nội dung do race với Realtime:
                // MyReadingSyncApi.pushReadingCreateOrUpdate() KHÔNG atomic —
                // upsert dòng "readings" commit TRƯỚC, rồi mới xoá/insert lại
                // sentences/phrases/words ở CÁC REQUEST RIÊNG BIỆT sau đó.
                // MyReadingSyncRealtime lắng nghe đúng lúc dòng "readings" vừa
                // đổi → có thể fetchChildrenForReading() NGAY LÚC server chưa
                // kịp ghi xong sentences/words, trả về rỗng hoặc thiếu. Nếu áp
                // thẳng dữ liệu "cụt" đó vào đây (vốn XOÁ SẠCH rồi ghi lại từ
                // đầu), nội dung local đang có sẽ bị xoá mất vĩnh viễn dù
                // server thực ra vẫn còn đủ (chỉ là ghi chưa xong ở thời điểm
                // fetch). Nên: nếu local đang có sẵn câu/từ mà dữ liệu server
                // truyền vào lại ít hơn hẳn (đặc biệt về 0) → coi là dữ liệu
                // chưa ghi xong, BỎ QUA toàn bộ lần áp này (không đụng gì tới
                // local), để lần pull/realtime sau (khi server đã ghi xong)
                // tự sửa đúng.
                val localSentenceCount = db.rawQuery(
                    "SELECT COUNT(*) FROM reading_sentences WHERE reading_id = ?",
                    arrayOf(reading.readingId)
                ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

                val localWordCount = if (localSentenceCount > 0) {
                    db.rawQuery(
                        "SELECT COUNT(*) FROM sentence_words w " +
                                "JOIN reading_sentences s ON s.sentence_id = w.sentence_id " +
                                "WHERE s.reading_id = ?",
                        arrayOf(reading.readingId)
                    ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
                } else 0

                val incomingSentenceCount = sentences.size
                val incomingWordCount = sentences.sumOf { it.words.size }

                // ⚠️ SỬA: 1 bài KHÔNG phải tombstone thì KHÔNG BAO GIỜ hợp lệ
                // với 0 câu — insertReadingWithSentences() luôn tạo ≥1 câu
                // NGAY khi tạo bài, cùng 1 transaction với dòng readings (xem
                // hàm đó ở trên). Nên "incomingSentenceCount == 0" luôn luôn
                // là dấu hiệu server CHƯA GHI XONG, kể cả khi local cũng đang
                // 0 câu (bài hoàn toàn mới nhận qua Realtime lần đầu — trường
                // hợp trước đây bị lọt qua guard vì chỉ so với local).
                // Tương tự, nếu server trả về ÍT câu/từ hơn hẳn local đang có
                // (không chỉ riêng bằng 0) cũng coi là ghi dở, không chỉ chặn
                // khi về đúng 0.
                val looksIncomplete =
                    incomingSentenceCount == 0 ||
                            (localSentenceCount > 0 && incomingSentenceCount < localSentenceCount) ||
                            (localWordCount > 0 && incomingWordCount < localWordCount)

                if (looksIncomplete) {
                    Log.w(
                        TAG,
                        "applyServerReading: reading_id=${reading.readingId} dữ liệu server " +
                                "có vẻ CHƯA GHI XONG (local: $localSentenceCount câu/" +
                                "$localWordCount từ, server gửi: $incomingSentenceCount câu/" +
                                "$incomingWordCount từ) → BỎ QUA lần áp này, giữ nguyên nội " +
                                "dung local."
                    )
                    return false
                }

                // 1. UPSERT dòng readings — INSERT OR REPLACE theo reading_id.
                val rcv = ContentValues().apply {
                    put("reading_id",      reading.readingId)
                    put("user_id",         reading.userId)
                    put("title_en",        reading.titleEn)
                    put("title_vi",        reading.titleVi)
                    put("level",           reading.level)
                    put("topic",           reading.topic)
                    put("is_ai_processed", if (reading.isAiProcessed) 1 else 0)
                    put("created_at",      reading.createdAt ?: nowIso8601())
                    put("updated_at",      reading.updatedAt ?: nowIso8601())
                    putNull("deleted_at")
                    put("sync_status",     MyReadingSyncStatus.SYNCED)
                }
                db.insertWithOnConflict("readings", null, rcv, SQLiteDatabase.CONFLICT_REPLACE)

                // 2. Xoá sạch cây con cũ — con trước cha để không vướng
                //    ràng buộc REFERENCES.
                val oldSentenceIds = mutableListOf<String>()
                db.rawQuery(
                    "SELECT sentence_id FROM reading_sentences WHERE reading_id = ?",
                    arrayOf(reading.readingId)
                ).use { c -> while (c.moveToNext()) oldSentenceIds.add(c.getString(0)) }

                oldSentenceIds.forEach { sid ->
                    db.delete("sentence_words", "sentence_id = ?", arrayOf(sid))
                    db.delete("sentence_phrases", "sentence_id = ?", arrayOf(sid))
                }
                db.delete("reading_sentences", "reading_id = ?", arrayOf(reading.readingId))

                // 3. Insert lại toàn bộ từ dữ liệu server.
                sentences.forEach { s ->
                    val scv = ContentValues().apply {
                        put("sentence_id",          s.sentenceId)
                        put("reading_id",           reading.readingId)
                        put("text_en",              s.textEn)
                        put("text_vi",              s.textVi)
                        put("sentence_explanation", s.sentenceExplanation)
                        put("sentence_order",       s.sentenceOrder)
                        put("paragraph_order",      s.paragraphOrder)
                    }
                    db.insert("reading_sentences", null, scv)

                    s.phrases.forEach { p ->
                        val pcv = ContentValues().apply {
                            put("phrase_id",          p.phraseId)
                            put("sentence_id",        s.sentenceId)
                            put("text_en",            p.textEn)
                            put("text_vi",            p.textVi)
                            put("phrase_explanation", p.phraseExplanation)
                            put("start_word_order",   p.startWordOrder)
                            put("end_word_order",     p.endWordOrder)
                        }
                        db.insert("sentence_phrases", null, pcv)
                    }

                    s.words.forEach { w ->
                        val wcv = ContentValues().apply {
                            put("word_id",               w.wordId)
                            put("sentence_id",            s.sentenceId)
                            if (w.phraseId != null) put("phrase_id", w.phraseId) else putNull("phrase_id")
                            put("text_en",                w.textEn)
                            put("text_vi",                w.textVi)
                            put("word_explanation",       w.wordExplanation)
                            put("word_order",             w.wordOrder)
                            put("pos",                    w.pos)
                            put("lemma",                  w.lemma)
                            put("word_form_explanation",  w.wordFormExplanation)
                        }
                        db.insert("sentence_words", null, wcv)
                    }
                }

                Log.d(TAG, "applyServerReading: ghi đè reading_id=${reading.readingId} (${sentences.size} câu) từ server")
            }

            db.setTransactionSuccessful()
            true
        } catch (e: Exception) {
            Log.e(TAG, "applyServerReading error (reading_id=${reading.readingId})", e)
            false
        } finally {
            db.endTransaction()
        }
    }
}