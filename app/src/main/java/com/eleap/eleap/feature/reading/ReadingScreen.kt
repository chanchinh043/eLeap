package com.eleap.eleap.feature.reading

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eleap.eleap.feature.reading.ui.ReadingBottomBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingScreen(
    readingId: Int,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val vm: ReadingViewModel = viewModel(factory = ReadingViewModel.Factory(context))
    val sentences    by vm.sentences.collectAsState()
    val isLoading    by vm.isLoadingReading.collectAsState()
    val readingMode  by vm.readingMode.collectAsState()

    LaunchedEffect(readingId) {
        vm.loadReading(readingId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reading") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            ReadingBottomBar(
                mode = readingMode,
                onWordClick = { vm.toggleWordMode() }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(sentences, key = { it.sentenceId }) { sentence ->
                        Text(
                            text = sentence.textEn ?: "",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }
    }
}