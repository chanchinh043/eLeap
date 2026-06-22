package com.eleap.eleap.feature.reading

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.eleap.eleap.feature.reading.data.Reading
import com.eleap.eleap.feature.reading.data.ReadingDao
import com.eleap.eleap.feature.reading.data.ReadingDatabase
import com.eleap.eleap.feature.reading.data.ReadingRepository
import com.eleap.eleap.feature.reading.data.ReadingSentence
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ReadingViewModel(private val repository: ReadingRepository) : ViewModel() {

    private val _readings = MutableStateFlow<List<Reading>>(emptyList())
    val readings: StateFlow<List<Reading>> = _readings

    private val _sentences = MutableStateFlow<List<ReadingSentence>>(emptyList())
    val sentences: StateFlow<List<ReadingSentence>> = _sentences

    private val _isLoadingReading = MutableStateFlow(false)
    val isLoadingReading: StateFlow<Boolean> = _isLoadingReading

    init {
        loadReadings()
    }

    private fun loadReadings() {
        viewModelScope.launch {
            _readings.value = repository.getAllReadings()
        }
    }

    fun loadReading(readingId: Int) {
        viewModelScope.launch {
            _isLoadingReading.value = true

            val before = System.currentTimeMillis()
            val result = repository.getReading(readingId)
            val elapsed = System.currentTimeMillis() - before

            Log.d(
                "ReadingVM",
                "readingId=$readingId | sentences=${result.size} | time=${elapsed}ms"
            )

            result.forEachIndexed { sentenceIndex, sentence ->

                Log.d(
                    "ReadingVM",
                    "================ SENTENCE ${sentenceIndex + 1} ================"
                )

                Log.d("ReadingVM", "sentenceId=${sentence.sentenceId}")
                Log.d("ReadingVM", "textEn=${sentence.textEn}")

                // Nếu class có textVi
                try {
                    Log.d("ReadingVM", "textVi=${sentence.textVi}")
                } catch (_: Exception) {
                }

                Log.d(
                    "ReadingVM",
                    "phrases=${sentence.phrases.size}, words=${sentence.words.size}"
                )

                sentence.phrases.forEachIndexed { phraseIndex, phrase ->
                    Log.d(
                        "ReadingVM",
                        "PHRASE ${phraseIndex + 1}: $phrase"
                    )
                }

                sentence.words.forEachIndexed { wordIndex, word ->
                    Log.d(
                        "ReadingVM",
                        "WORD ${wordIndex + 1}: $word"
                    )
                }
            }

            _sentences.value = result
            _isLoadingReading.value = false
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val db   = ReadingDatabase.getInstance(context)
            val dao  = ReadingDao(db.db)
            val repo = ReadingRepository(dao)
            @Suppress("UNCHECKED_CAST")
            return ReadingViewModel(repo) as T
        }
    }
}