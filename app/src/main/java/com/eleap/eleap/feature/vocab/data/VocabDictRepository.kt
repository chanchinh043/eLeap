// VocabDictRepository.kt
// Đặt tại: com/eleap/eleap/feature/vocab/data/VocabDictRepository.kt
//
// Tra dict.db độc lập — không import bất kỳ class nào từ feature.reading
package com.eleap.eleap.feature.vocab.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

// ── Entity riêng cho Vocab (không dùng DictEntry của reading) ─────────────────
data class VocabDictEntry(
    val word: String,
    val meaning: String?,
    val shortMeaning: String?,
)

// ── Repository tra dict.db ────────────────────────────────────────────────────
class VocabDictRepository private constructor(context: Context) {

    private val dictDb: SQLiteDatabase
    private val dictCache = mutableMapOf<String, VocabDictEntry>()

    init {
        dictDb = openDictDb(context.applicationContext)
        Log.d("VocabDictRepo", "dict.db path: ${dictDb.path}")
    }

    // ── Mở dict.db từ assets (copy ra nếu chưa có) ───────────────────────────
    private fun openDictDb(context: Context): SQLiteDatabase {
        val dbFile = File(context.getDatabasePath("dict.db").absolutePath)
        if (!dbFile.exists()) {
            dbFile.parentFile?.mkdirs()
            context.assets.open("databases/dict.db").use { input ->
                FileOutputStream(dbFile).use { output -> input.copyTo(output) }
            }
        }
        return SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )
    }

    // ── Preload toàn bộ từ vào RAM — gọi sau khi loadVocab() xong ───────────
    // Query batch 1 lần thay vì N lần đơn lẻ khi tap từng từ
    suspend fun preloadDict(words: List<String>) = withContext(Dispatchers.IO) {
        val keysToLoad = words
            .mapNotNull { normalizeWord(it) }
            .distinct()
            .filterNot { dictCache.containsKey(it) }

        if (keysToLoad.isEmpty()) return@withContext

        keysToLoad.chunked(500).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            val cursor = dictDb.rawQuery(
                "SELECT * FROM dict WHERE word IN ($placeholders)",
                chunk.toTypedArray()
            )
            cursor.use {
                while (it.moveToNext()) {
                    val entry = VocabDictEntry(
                        word         = it.getString(it.getColumnIndexOrThrow("word")),
                        meaning      = it.getString(it.getColumnIndexOrThrow("meaning")),
                        shortMeaning = it.getString(it.getColumnIndexOrThrow("short_meaning")),
                    )
                    dictCache[entry.word] = entry
                }
            }
        }
        Log.d("VocabDictRepo", "preloaded ${keysToLoad.size} dict entries into RAM")
    }

    // ── Tra 1 từ — ưu tiên RAM, fallback DB nếu chưa preload ─────────────────
    suspend fun getDictEntry(textEn: String?): VocabDictEntry? =
        withContext(Dispatchers.IO) {
            val key = normalizeWord(textEn) ?: return@withContext null

            // Trả từ cache nếu đã có (trường hợp thông thường sau preload)
            dictCache[key]?.let { return@withContext it }

            // Fallback: tra DB trực tiếp (phòng khi preload chưa xong)
            val cursor = dictDb.rawQuery(
                "SELECT * FROM dict WHERE word = ? LIMIT 1",
                arrayOf(key)
            )
            cursor.use {
                if (it.moveToFirst()) {
                    VocabDictEntry(
                        word         = it.getString(it.getColumnIndexOrThrow("word")),
                        meaning      = it.getString(it.getColumnIndexOrThrow("meaning")),
                        shortMeaning = it.getString(it.getColumnIndexOrThrow("short_meaning")),
                    ).also { entry -> dictCache[key] = entry }
                } else null
            }
        }

    // ── Chuẩn hoá từ: lowercase, bỏ dấu câu 2 đầu ───────────────────────────
    private fun normalizeWord(text: String?): String? {
        val cleaned = text
            ?.trim()
            ?.lowercase()
            ?.replace(Regex("^[^a-z']+|[^a-z']+$"), "")
        return cleaned?.ifEmpty { null }
    }

    companion object {
        @Volatile private var INSTANCE: VocabDictRepository? = null

        fun getInstance(context: Context): VocabDictRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: VocabDictRepository(context.applicationContext).also { INSTANCE = it }
            }
    }
}