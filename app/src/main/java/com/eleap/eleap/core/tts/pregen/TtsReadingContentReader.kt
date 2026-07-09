// TtsReadingContentReader.kt
// Đặt tại: com/eleap/eleap/core/tts/pregen/TtsReadingContentReader.kt
//
// Lớp đọc dữ liệu THÔ, READ-ONLY, trực tiếp từ readings.db (bài hệ thống) và
// myreading.db (bài của user) bằng SQLite API thuần — KHÔNG import
// ReadingRepository/ReadingDao/MyReadingRepository/MyReadingDao. Đây là điểm
// DUY NHẤT trong package pregen/ "chạm" tới dữ liệu bài đọc, nhưng vẫn tách
// biệt kiến trúc: chỉ phụ thuộc vào TÊN BẢNG/CỘT (schema), không phụ thuộc
// code của feature/reading hay feature/myreading — nếu sau này đổi schema,
// chỉ cần sửa đúng 1 file này.
//
// ⚠️ 2 file .db có SCHEMA GIỐNG HỆT NHAU (xem ReadingRepository.kt/
// MyReadingSchema.kt) — cùng tên bảng readings/reading_sentences/
// sentence_phrases/sentence_words, cùng tên cột. Khác biệt DUY NHẤT quan
// trọng với file này: readings.db (bài hệ thống, asset copy sẵn, chỉ đọc)
// KHÔNG có cột deleted_at trên bảng readings (không cần soft-delete vì
// app không tự xoá bài hệ thống), còn myreading.db (bài user tự tạo) CÓ cột
// deleted_at — phải lọc "WHERE deleted_at IS NULL" khi lấy danh sách bài từ
// myreading.db để không pre-cache nhầm bài đã bị xoá (tombstone).
//
// ⚠️ Đường dẫn 2 file .db: CẢ HAI đều nằm ở thư mục database chuẩn của app
// (context.getDatabasePath(fileName).absolutePath) — readings.db được
// ReadingDatabase.openDatabase() copy từ assets/databases/readings.db ra
// đúng vị trí này (kèm kiểm tra MD5 để cập nhật lại nếu asset đổi mới hơn),
// còn myreading.db do MyReadingDbHelper (SQLiteOpenHelper) tự tạo ngay tại
// vị trí chuẩn đó. Vì vậy ở đây chỉ cần gọi thẳng
// context.getDatabasePath(fileName) để mở — KHÔNG cần tự copy asset (đã có
// ReadingDatabase lo việc đó mỗi khi app khởi động, và Worker chỉ chạy SAU
// khi app đã mở nên coi như file luôn đã tồn tại đúng chỗ).
//
// Mở CSDL ở chế độ CHỈ ĐỌC (OPEN_READONLY) — đúng tinh thần "read-only" đã
// chốt, và tránh mọi rủi ro ghi nhầm/khoá tranh chấp (lock contention) với
// ReadingRepository/MyReadingRepository đang có thể ghi cùng lúc ở luồng UI.
//
// KHÔNG phải singleton giữ kết nối lâu dài — mỗi lần Worker cần đọc, tự mở
// rồi tự đóng ngay (dùng SQLiteDatabase.use {}), vì Worker có thể chạy rất
// lâu (duyệt hết lịch sử nhiều bài) và không có lý do gì phải giữ 2 kết nối
// DB sống suốt thời gian đó — tránh giữ file handle không cần thiết, đồng
// thời nếu readings.db được ReadingDatabase copy lại (asset đổi mới hơn)
// giữa lúc Worker đang chạy, lần mở kế tiếp sẽ tự thấy đúng bản mới nhất.
package com.eleap.eleap.core.tts.pregen

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log

private const val TAG = "TtsReadingContentReader"

// ── Nguồn của 1 readingId — dùng để TtsReadingContentReader biết nên mở
// đúng file .db nào khi cần lấy words/sentences/phrases của readingId đó
// (tránh phải "dò" cả 2 file mỗi lần, biết trước nguồn sẽ nhanh hơn và rõ
// ràng hơn). TtsPregenWorker lưu lại nguồn này cùng với readingId khi duyệt
// danh sách từ getAllReadingIds().
enum class TtsReadingSource {
    SYSTEM,      // readings.db
    MY_READING,  // myreading.db
}

// ── 1 readingId kèm nguồn của nó — trả về từ getAllReadingIds() để caller
// (TtsPregenWorker) không cần tự đoán lại nguồn ở các lời gọi sau.
data class TtsReadingRef(
    val readingId: String,
    val source: TtsReadingSource,
)

// ── Item thô đọc được từ DB — chỉ giữ đúng những gì cần để generate TTS
// (id + text_en), không cần các cột khác (text_vi, explanation...) vì
// TtsPregenWorker chỉ đọc tiếng Anh ra để generate audio.
data class TtsWordItem(val wordId: String, val textEn: String)
data class TtsSentenceItem(val sentenceId: String, val textEn: String)
data class TtsPhraseItem(val phraseId: String, val textEn: String)

object TtsReadingContentReader {

    private const val SYSTEM_DB_NAME     = "readings.db"
    private const val MY_READING_DB_NAME = "myreading.db"

    // ── Mở 1 trong 2 file .db ở chế độ READ-ONLY — dùng chung cho mọi hàm
    // bên dưới. context.getDatabasePath(fileName) trỏ đúng vị trí chuẩn mà
    // ReadingDatabase (readings.db, copy từ asset) và MyReadingDbHelper
    // (myreading.db, SQLiteOpenHelper tự tạo) đã/đang dùng — xem ghi chú ở
    // đầu file để biết vì sao không cần tự copy asset ở đây. ────────────────
    private fun openReadOnly(context: Context, dbName: String): SQLiteDatabase {
        val path = context.getDatabasePath(dbName).absolutePath
        return SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)
    }

    // ── Toàn bộ readingId "còn sống" từ CẢ HAI file .db ─────────────────────
    // readings.db: lấy hết (không có deleted_at nên không cần lọc).
    // myreading.db: chỉ lấy dòng có deleted_at IS NULL (loại bỏ tombstone).
    // Nếu 1 trong 2 file lỗi (chưa tồn tại, hỏng...) → log lỗi và coi như
    // nguồn đó rỗng, KHÔNG throw để không làm hỏng luôn phần còn lại (vd
    // readings.db đọc lỗi thì vẫn nên tiếp tục pre-cache được myreading.db).
    fun getAllReadingIds(context: Context): List<TtsReadingRef> {
        val result = mutableListOf<TtsReadingRef>()

        try {
            openReadOnly(context, SYSTEM_DB_NAME).use { db ->
                val cursor = db.rawQuery("SELECT reading_id FROM readings", null)
                cursor.use {
                    val idIdx = it.getColumnIndexOrThrow("reading_id")
                    while (it.moveToNext()) {
                        result.add(TtsReadingRef(it.getString(idIdx), TtsReadingSource.SYSTEM))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getAllReadingIds: lỗi đọc $SYSTEM_DB_NAME, bỏ qua nguồn này", e)
        }

        try {
            openReadOnly(context, MY_READING_DB_NAME).use { db ->
                val cursor = db.rawQuery(
                    "SELECT reading_id FROM readings WHERE deleted_at IS NULL",
                    null
                )
                cursor.use {
                    val idIdx = it.getColumnIndexOrThrow("reading_id")
                    while (it.moveToNext()) {
                        result.add(TtsReadingRef(it.getString(idIdx), TtsReadingSource.MY_READING))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getAllReadingIds: lỗi đọc $MY_READING_DB_NAME, bỏ qua nguồn này", e)
        }

        return result
    }

    // ── Toàn bộ từ (words) của 1 bài — JOIN qua reading_sentences để lọc
    // đúng reading_id, vì sentence_words không có cột reading_id trực tiếp
    // (chỉ có sentence_id, xem schema ở ReadingRepository.kt/MyReadingSchema.kt).
    // Bỏ qua các dòng text_en NULL/rỗng — không có gì để generate TTS.
    fun getWordsForReading(context: Context, ref: TtsReadingRef): List<TtsWordItem> {
        val dbName = dbNameFor(ref.source)
        val result = mutableListOf<TtsWordItem>()
        try {
            openReadOnly(context, dbName).use { db ->
                val cursor = db.rawQuery(
                    """
                    SELECT w.word_id AS word_id, w.text_en AS text_en
                    FROM sentence_words w
                    INNER JOIN reading_sentences s ON s.sentence_id = w.sentence_id
                    WHERE s.reading_id = ?
                    """.trimIndent(),
                    arrayOf(ref.readingId)
                )
                cursor.use {
                    val idIdx   = it.getColumnIndexOrThrow("word_id")
                    val textIdx = it.getColumnIndexOrThrow("text_en")
                    while (it.moveToNext()) {
                        val text = it.getString(textIdx)
                        if (!text.isNullOrBlank()) {
                            result.add(TtsWordItem(it.getString(idIdx), text))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getWordsForReading: lỗi đọc $dbName cho readingId=${ref.readingId}", e)
        }
        return result
    }

    // ── Toàn bộ câu (sentences) của 1 bài ────────────────────────────────────
    fun getSentencesForReading(context: Context, ref: TtsReadingRef): List<TtsSentenceItem> {
        val dbName = dbNameFor(ref.source)
        val result = mutableListOf<TtsSentenceItem>()
        try {
            openReadOnly(context, dbName).use { db ->
                val cursor = db.rawQuery(
                    "SELECT sentence_id, text_en FROM reading_sentences WHERE reading_id = ?",
                    arrayOf(ref.readingId)
                )
                cursor.use {
                    val idIdx   = it.getColumnIndexOrThrow("sentence_id")
                    val textIdx = it.getColumnIndexOrThrow("text_en")
                    while (it.moveToNext()) {
                        val text = it.getString(textIdx)
                        if (!text.isNullOrBlank()) {
                            result.add(TtsSentenceItem(it.getString(idIdx), text))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getSentencesForReading: lỗi đọc $dbName cho readingId=${ref.readingId}", e)
        }
        return result
    }

    // ── Toàn bộ cụm từ (phrases) của 1 bài — JOIN qua reading_sentences,
    // cùng lý do như getWordsForReading() (sentence_phrases không có cột
    // reading_id trực tiếp). ─────────────────────────────────────────────────
    fun getPhrasesForReading(context: Context, ref: TtsReadingRef): List<TtsPhraseItem> {
        val dbName = dbNameFor(ref.source)
        val result = mutableListOf<TtsPhraseItem>()
        try {
            openReadOnly(context, dbName).use { db ->
                val cursor = db.rawQuery(
                    """
                    SELECT p.phrase_id AS phrase_id, p.text_en AS text_en
                    FROM sentence_phrases p
                    INNER JOIN reading_sentences s ON s.sentence_id = p.sentence_id
                    WHERE s.reading_id = ?
                    """.trimIndent(),
                    arrayOf(ref.readingId)
                )
                cursor.use {
                    val idIdx   = it.getColumnIndexOrThrow("phrase_id")
                    val textIdx = it.getColumnIndexOrThrow("text_en")
                    while (it.moveToNext()) {
                        val text = it.getString(textIdx)
                        if (!text.isNullOrBlank()) {
                            result.add(TtsPhraseItem(it.getString(idIdx), text))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getPhrasesForReading: lỗi đọc $dbName cho readingId=${ref.readingId}", e)
        }
        return result
    }

    private fun dbNameFor(source: TtsReadingSource): String = when (source) {
        TtsReadingSource.SYSTEM     -> SYSTEM_DB_NAME
        TtsReadingSource.MY_READING -> MY_READING_DB_NAME
    }
}