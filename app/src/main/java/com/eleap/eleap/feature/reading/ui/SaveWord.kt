// SaveWord.kt
// Đặt tại: com/eleap/eleap/feature/reading/ui/SaveWord.kt
//
// Chỉ còn UI (SaveWordButton) — entity, schema (UserDatabase/UserVocabularyEntry),
// hàm tiện ích (generateUuidV7/nowUtcIso) VÀ việc gán id/created_at/updated_at/
// sync_status đều đã chuyển hẳn vào VocabRepository.saveWord() — file này giờ
// chỉ truyền dữ liệu NỘI DUNG thô (textEn/textVi/...), không đụng tới bất kỳ
// cột sync nào nữa.
package com.eleap.eleap.feature.reading.ui

import android.content.Context
import android.util.Log
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.eleap.eleap.core.auth.CurrentUser
import com.eleap.eleap.feature.reading.data.SentenceWord
import com.eleap.eleap.feature.reading.data.SentencePhrase
import com.eleap.eleap.feature.reading.data.ReadingSentence
import com.eleap.eleap.feature.reading.ReadingViewModel
import com.eleap.eleap.feature.vocab.data.VocabRepository
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// SaveWordButton — chỉ gọi VocabRepository, không đụng UserDatabase trực tiếp
// và không tự tay tạo/gán bất kỳ cột sync nào (id/created_at/updated_at/
// sync_status) — toàn bộ do VocabRepository.saveWord() tự sinh bên trong.
// ─────────────────────────────────────────────────────────────────────────────

private fun findSentenceTexts(context: Context, sentenceId: String): Pair<String?, String?>? {
    return try {
        val readingVm = ReadingViewModel.Factory(context).create(ReadingViewModel::class.java)
        readingVm.sentences.value
            .find { it.sentenceId == sentenceId }
            ?.let { it.textEn to it.textVi }
    } catch (e: Exception) {
        Log.e("SaveWordButton", "findSentenceTexts error", e)
        null
    }
}

@Composable
fun SaveWordButton(
    word: SentenceWord,
    phrase: SentencePhrase?,
    sentence: ReadingSentence? = null,
    onSaveStateChanged: () -> Unit = {},
) {
    val context = LocalContext.current
    val repo = remember { VocabRepository.getInstance(context) }
    val scope = rememberCoroutineScope()

    // null = chưa xác định (đang chờ query), true/false = đã biết trạng thái
    var isSaved by remember(word.wordId) { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(word.wordId) {
        isSaved = repo.isWordSaved(word.wordId)
    }

    TextButton(
        enabled = isSaved != null,
        onClick = {
            val currentlySaved = isSaved ?: return@TextButton
            scope.launch {
                if (currentlySaved) {
                    if (repo.unsaveWord(word.wordId)) {
                        isSaved = false
                        onSaveStateChanged()
                    }
                } else {
                    val sentenceTexts = sentence?.let { it.textEn to it.textVi }
                        ?: findSentenceTexts(context, word.sentenceId)
                    val saved = repo.saveWord(
                        userId           = CurrentUser.userId.value,
                        sourceSentenceId = word.sentenceId,
                        sourceWordId     = word.wordId,
                        sourcePhraseId   = phrase?.phraseId,
                        textEn           = word.textEn,
                        textVi           = word.textVi,
                        phraseTextEn     = phrase?.textEn,
                        phraseTextVi     = phrase?.textVi,
                        sentenceTextEn   = sentenceTexts?.first,
                        sentenceTextVi   = sentenceTexts?.second,
                    )
                    if (saved) {
                        isSaved = true
                        onSaveStateChanged()
                    }
                }
            }
        }
    ) {
        Text(
            when (isSaved) {
                true  -> "Bỏ lưu"
                false -> "Lưu từ"
                null  -> "..."
            }
        )
    }
}