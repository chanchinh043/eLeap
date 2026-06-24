// VocabRepository.kt
// Đặt tại: com/eleap/eleap/feature/vocab/data/VocabRepository.kt
package com.eleap.eleap.feature.vocab.data

import com.eleap.eleap.feature.reading.ui.UserDatabase
import com.eleap.eleap.feature.reading.ui.UserVocabularyEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository cho tính năng "Ôn từ vựng".
 * Dùng lại UserDatabase (users.db) đã có sẵn ở SaveWord.kt — không tạo DB mới.
 */
class VocabRepository(private val userDb: UserDatabase) {

    // ── Lấy toàn bộ từ đã lưu của user hiện tại ──────────────────────────────
    suspend fun getAllVocabulary(userId: Int = 0): List<UserVocabularyEntry> =
        withContext(Dispatchers.IO) {
            userDb.getAllVocabulary(userId)
        }

    // ── Xoá 1 từ khỏi danh sách đã lưu ───────────────────────────────────────
    suspend fun deleteWord(id: Int): Boolean =
        withContext(Dispatchers.IO) {
            userDb.deleteWord(id)
        }
}