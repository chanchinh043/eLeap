// ReadingRepository.kt
package com.eleap.eleap.feature.reading.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ═════════════════════════════════════════════════════════════════════════════
// 1. ENTITIES
// ═════════════════════════════════════════════════════════════════════════════

data class Reading(
    val readingId: Int,
    val titleEn: String?,
    val titleVi: String?,
    val level: String?,
    val topic: String?,
    val createdAt: String?,
    val updatedAt: String?,
    val userId: Int? = null,              // null = bài hệ thống; có giá trị = bài user tạo → hiện nút xoá
)

data class ReadingSentence(
    val sentenceId: Int,
    val readingId: Int,
    val textEn: String?,
    val textVi: String?,
    val sentenceExplanation: String?,
    val sentenceOrder: Int,
    val phrases: List<SentencePhrase> = emptyList(),
    val words: List<SentenceWord>     = emptyList(),
)

data class SentencePhrase(
    val phraseId: Int,
    val sentenceId: Int,
    val textEn: String?,
    val textVi: String?,
    val phraseExplanation: String?,
    val startWordOrder: Int,
    val endWordOrder: Int,
)

data class SentenceWord(
    val wordId: Int,
    val sentenceId: Int,
    val phraseId: Int?,
    val textEn: String?,
    val textVi: String?,
    val wordExplanation: String?,
    val wordOrder: Int,
    val pos: String?,
    val lemma: String?,
    val wordFormExplanation: String?,
)

data class DictEntry(
    val word: String,
    val ipa: String?,
    val ipaVi: String?,
    val meaning: String?,
    val shortMeaning: String?,
)

// ═════════════════════════════════════════════════════════════════════════════
// 2. DAO
// ═════════════════════════════════════════════════════════════════════════════

class ReadingDao(
    val db: SQLiteDatabase,
    private val dictDb: SQLiteDatabase,
) {
    fun getAllReadings(): List<Reading> {
        val list = mutableListOf<Reading>()
        val cursor = db.rawQuery("SELECT * FROM readings ORDER BY reading_id ASC", null)
        cursor.use {
            while (it.moveToNext()) {
                val userIdIdx = it.getColumnIndex("user_id")
                list.add(
                    Reading(
                        readingId = it.getInt(it.getColumnIndexOrThrow("reading_id")),
                        titleEn   = it.getString(it.getColumnIndexOrThrow("title_en")),
                        titleVi   = it.getString(it.getColumnIndexOrThrow("title_vi")),
                        level     = it.getString(it.getColumnIndexOrThrow("level")),
                        topic     = it.getString(it.getColumnIndexOrThrow("topic")),
                        createdAt = it.getString(it.getColumnIndexOrThrow("created_at")),
                        updatedAt = it.getString(it.getColumnIndexOrThrow("updated_at")),
                        // user_id NULL = bài hệ thống; có giá trị = bài user tạo
                        userId    = if (userIdIdx == -1 || it.isNull(userIdIdx)) null
                        else it.getInt(userIdIdx),
                    )
                )
            }
        }
        return list
    }

    fun getSentencesByReadingId(readingId: Int): List<ReadingSentence> {
        val list = mutableListOf<ReadingSentence>()
        val cursor = db.rawQuery(
            "SELECT * FROM reading_sentences WHERE reading_id = ? ORDER BY sentence_order ASC",
            arrayOf(readingId.toString())
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    ReadingSentence(
                        sentenceId          = it.getInt(it.getColumnIndexOrThrow("sentence_id")),
                        readingId           = it.getInt(it.getColumnIndexOrThrow("reading_id")),
                        textEn              = it.getString(it.getColumnIndexOrThrow("text_en")),
                        textVi              = it.getString(it.getColumnIndexOrThrow("text_vi")),
                        sentenceExplanation = it.getString(it.getColumnIndexOrThrow("sentence_explanation")),
                        sentenceOrder       = it.getInt(it.getColumnIndexOrThrow("sentence_order")),
                    )
                )
            }
        }
        return list
    }

    fun getPhrasesBySentenceId(sentenceId: Int): List<SentencePhrase> {
        val list = mutableListOf<SentencePhrase>()
        val cursor = db.rawQuery(
            "SELECT * FROM sentence_phrases WHERE sentence_id = ?",
            arrayOf(sentenceId.toString())
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    SentencePhrase(
                        phraseId          = it.getInt(it.getColumnIndexOrThrow("phrase_id")),
                        sentenceId        = it.getInt(it.getColumnIndexOrThrow("sentence_id")),
                        textEn            = it.getString(it.getColumnIndexOrThrow("text_en")),
                        textVi            = it.getString(it.getColumnIndexOrThrow("text_vi")),
                        phraseExplanation = it.getString(it.getColumnIndexOrThrow("phrase_explanation")),
                        startWordOrder    = it.getInt(it.getColumnIndexOrThrow("start_word_order")),
                        endWordOrder      = it.getInt(it.getColumnIndexOrThrow("end_word_order")),
                    )
                )
            }
        }
        return list
    }

    fun getWordsBySentenceId(sentenceId: Int): List<SentenceWord> {
        val list = mutableListOf<SentenceWord>()
        val cursor = db.rawQuery(
            "SELECT * FROM sentence_words WHERE sentence_id = ? ORDER BY word_order ASC",
            arrayOf(sentenceId.toString())
        )
        cursor.use {
            while (it.moveToNext()) {
                val phraseIdIdx = it.getColumnIndexOrThrow("phrase_id")
                list.add(
                    SentenceWord(
                        wordId              = it.getInt(it.getColumnIndexOrThrow("word_id")),
                        sentenceId          = it.getInt(it.getColumnIndexOrThrow("sentence_id")),
                        phraseId            = if (it.isNull(phraseIdIdx)) null else it.getInt(phraseIdIdx),
                        textEn              = it.getString(it.getColumnIndexOrThrow("text_en")),
                        textVi              = it.getString(it.getColumnIndexOrThrow("text_vi")),
                        wordExplanation     = it.getString(it.getColumnIndexOrThrow("word_explanation")),
                        wordOrder           = it.getInt(it.getColumnIndexOrThrow("word_order")),
                        pos                 = it.getString(it.getColumnIndexOrThrow("pos")),
                        lemma               = it.getString(it.getColumnIndexOrThrow("lemma")),
                        wordFormExplanation = it.getString(it.getColumnIndexOrThrow("word_form_explanation")),
                    )
                )
            }
        }
        return list
    }

    fun getDictEntries(words: List<String>): List<DictEntry> {
        if (words.isEmpty()) return emptyList()
        val list = mutableListOf<DictEntry>()
        words.chunked(500).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            val cursor = dictDb.rawQuery(
                "SELECT * FROM dict WHERE word IN ($placeholders)",
                chunk.toTypedArray()
            )
            cursor.use {
                while (it.moveToNext()) {
                    list.add(
                        DictEntry(
                            word         = it.getString(it.getColumnIndexOrThrow("word")),
                            ipa          = it.getString(it.getColumnIndexOrThrow("ipa")),
                            ipaVi        = it.getString(it.getColumnIndexOrThrow("ipa_vi")),
                            meaning      = it.getString(it.getColumnIndexOrThrow("meaning")),
                            shortMeaning = it.getString(it.getColumnIndexOrThrow("short_meaning")),
                        )
                    )
                }
            }
        }
        return list
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// 3. DATABASE
// ═════════════════════════════════════════════════════════════════════════════

class ReadingDatabase private constructor(context: Context) {

    val db: SQLiteDatabase
    val dictDb: SQLiteDatabase

    init {
        db     = openDatabase(context, "readings.db", readWrite = true)
        dictDb = openDatabase(context, "dict.db", readWrite = false)
    }

    /**
     * Mở database từ assets, copy lại nếu checksum thay đổi.
     *
     * QUAN TRỌNG: "readings.db" cần là READWRITE + WAL vì cả app (đọc) và
     * AiReadingProcessor / UserReadingData (ghi) đều phải dùng CHUNG MỘT
     * connection này. Trước đây mỗi bên tự mở connection riêng tới cùng file
     * → 2 connection tranh khoá file SQLite, gây SQLiteDatabaseLockedException
     * hoặc đọc dữ liệu nửa-vá khi AI đang ghi đúng lúc user đang mở bài đọc.
     *
     * WAL (Write-Ahead Logging) cho phép nhiều reader đọc đồng thời với
     * đúng 1 writer mà không bị khoá nhau — đây là cách giải quyết gốc,
     * không phải chỉ né tránh bằng delay/lock ở tầng app.
     *
     * "dict.db" chỉ đọc, không ai ghi → vẫn mở READONLY như cũ.
     */
    private fun openDatabase(
        context: Context,
        fileName: String,
        readWrite: Boolean = false,
    ): SQLiteDatabase {
        val prefs     = context.getSharedPreferences("db_checksums", Context.MODE_PRIVATE)
        val prefKey   = "md5_$fileName"
        val assetPath = "databases/$fileName"
        val dbFile    = File(context.getDatabasePath(fileName).absolutePath)

        val assetMd5 = context.assets.open(assetPath).use { md5OfStream(it) }
        Log.d("ReadingDB", "$fileName | asset MD5  = $assetMd5")

        val savedMd5 = prefs.getString(prefKey, null)
        Log.d("ReadingDB", "$fileName | saved MD5  = $savedMd5")

        val needsCopy = !dbFile.exists() || assetMd5 != savedMd5

        if (needsCopy) {
            Log.d("ReadingDB", "$fileName | DB thay đổi → copy lại từ assets")
            dbFile.parentFile?.mkdirs()
            try {
                SQLiteDatabase.openDatabase(
                    dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY
                ).close()
            } catch (_: Exception) { }

            context.assets.open(assetPath).use { input ->
                FileOutputStream(dbFile).use { output -> input.copyTo(output) }
            }
            prefs.edit().putString(prefKey, assetMd5).apply()
            Log.d("ReadingDB", "$fileName | copy hoàn tất, MD5 đã lưu")
        } else {
            Log.d("ReadingDB", "$fileName | DB đã mới nhất, bỏ qua copy")
        }

        val flags = if (readWrite) SQLiteDatabase.OPEN_READWRITE else SQLiteDatabase.OPEN_READONLY
        val database = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, flags)

        if (readWrite) {
            // Bật WAL: reader (app đang hiển thị bài đọc) và writer (AI processor)
            // không còn khoá lẫn nhau nữa.
            database.enableWriteAheadLogging()
            Log.d("ReadingDB", "$fileName | đã bật WAL mode")
        }

        return database
    }

    private fun md5OfStream(stream: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(8192)
        var read: Int
        while (stream.read(buffer).also { read = it } != -1) {
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        @Volatile private var INSTANCE: ReadingDatabase? = null

        fun getInstance(context: Context): ReadingDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ReadingDatabase(context.applicationContext).also { INSTANCE = it }
            }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// 4. REPOSITORY
// ═════════════════════════════════════════════════════════════════════════════

class ReadingRepository(private val dao: ReadingDao) {

    private var readingListCache: List<Reading>? = null
    private val readingCache = mutableMapOf<Int, List<ReadingSentence>>()
    private val dictCache    = mutableMapOf<String, DictEntry>()

    // ── Flow 2 ────────────────────────────────────────────────────────────────
    suspend fun getAllReadings(): List<Reading> = withContext(Dispatchers.IO) {
        readingListCache ?: dao.getAllReadings().also { readingListCache = it }
    }

    // ── Flow 3 ────────────────────────────────────────────────────────────────
    suspend fun getReading(readingId: Int): List<ReadingSentence> =
        withContext(Dispatchers.IO) {
            readingCache[readingId] ?: buildReading(readingId).also {
                readingCache[readingId] = it
            }
        }

    private fun buildReading(readingId: Int): List<ReadingSentence> {
        val sentences = dao.getSentencesByReadingId(readingId)
        return sentences.map { sentence ->
            val phrases = dao.getPhrasesBySentenceId(sentence.sentenceId)
            val words   = dao.getWordsBySentenceId(sentence.sentenceId)
            sentence.copy(phrases = phrases, words = words)
        }
    }

    suspend fun preloadDictForReading(sentences: List<ReadingSentence>) =
        withContext(Dispatchers.IO) {
            val keysToLoad = sentences
                .flatMap { it.words }
                .mapNotNull { normalizeWord(it.textEn) }
                .distinct()
                .filterNot { dictCache.containsKey(it) }

            if (keysToLoad.isEmpty()) return@withContext

            dao.getDictEntries(keysToLoad).forEach { entry ->
                normalizeWord(entry.word)?.let { key -> dictCache[key] = entry }
            }
        }

    fun getDictEntry(textEn: String?): DictEntry? =
        normalizeWord(textEn)?.let { dictCache[it] }

    private fun normalizeWord(text: String?): String? {
        val cleaned = text
            ?.trim()
            ?.lowercase()
            ?.replace(Regex("^[^a-z']+|[^a-z']+$"), "")
        return cleaned?.ifEmpty { null }
    }

    // ── Xoá cache để force reload sau khi user thêm / xoá bài ───────────────
    fun invalidateListCache() {
        readingListCache = null
        Log.d("ReadingRepository", "invalidateListCache: danh sách sẽ được tải lại")
    }

    fun invalidateReadingCache(readingId: Int) {
        readingCache.remove(readingId)
        Log.d("ReadingRepository", "invalidateReadingCache: readingId=$readingId")
    }

    companion object {
        // Trỏ đến instance đang dùng — được set bởi ReadingViewModel.Factory
        @Volatile var instance: ReadingRepository? = null

        /**
         * Xoá toàn bộ list cache từ bên ngoài (UserReadingRepository gọi sau khi insert/delete).
         * ViewModel sẽ tự reload khi màn hình ReadingList được hiển thị lại.
         */
        fun invalidateCache() {
            instance?.invalidateListCache()
        }
    }
}