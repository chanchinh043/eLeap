package com.eleap.eleap.feature.reading

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.eleap.eleap.feature.reading.data.ReadingDao
import com.eleap.eleap.feature.reading.data.ReadingDatabase
import com.eleap.eleap.feature.reading.data.ReadingRepository
import com.eleap.eleap.feature.reading.data.Entities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ReadingViewModel(private val repository: ReadingRepository) : ViewModel() {

    private val _readings = MutableStateFlow<List<Entities>>(emptyList())
    val readings: StateFlow<List<Entities>> = _readings

    init {
        loadReadings()
    }

    private fun loadReadings() {
        viewModelScope.launch {
            _readings.value = repository.getAllReadings()
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val db  = ReadingDatabase.getInstance(context)
            val dao = ReadingDao(db.db)
            val repo = ReadingRepository(dao)
            @Suppress("UNCHECKED_CAST")
            return ReadingViewModel(repo) as T
        }
    }
}