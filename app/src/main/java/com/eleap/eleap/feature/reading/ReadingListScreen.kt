// ReadingListScreen.kt
package com.eleap.eleap.feature.reading

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eleap.eleap.feature.reading.data.Reading

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingListScreen(
    onBack: () -> Unit,
    onReadingClick: (readingId: Int) -> Unit,
    onAddReadingClick: () -> Unit,
) {
    val context = LocalContext.current
    val vm: ReadingViewModel = viewModel(factory = ReadingViewModel.Factory(context))
    val readings by vm.readings.collectAsState()

    // Bài đang chờ xác nhận xoá — null nghĩa là dialog đóng
    var pendingDeleteReading by remember { mutableStateOf<Reading?>(null) }

    // Reload mỗi khi màn hình này được hiển thị — bắt được bài mới user vừa thêm
    LaunchedEffect(Unit) {
        vm.reloadReadings()
    }

    // ── Confirm dialog xoá ────────────────────────────────────────────────────
    pendingDeleteReading?.let { reading ->
        AlertDialog(
            onDismissRequest = { pendingDeleteReading = null },
            title   = { Text("Xoá bài đọc") },
            text    = {
                Text("Bạn có chắc muốn xoá \"${reading.titleEn.orEmpty()}\"? Thao tác này không thể hoàn tác.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteReading(reading.readingId, context)
                        pendingDeleteReading = null
                    }
                ) {
                    Text("Xoá", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteReading = null }) {
                    Text("Huỷ")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reading") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = onAddReadingClick) {
                        Text("Thêm bài đọc")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            items(readings, key = { it.readingId }) { reading ->
                ReadingCard(
                    reading   = reading,
                    onClick   = { onReadingClick(reading.readingId) },
                    onDelete  = if (reading.userId != null) {
                        { pendingDeleteReading = reading }
                    } else null,
                )
            }
        }
    }
}

@Composable
private fun ReadingCard(
    reading: Reading,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,        // null = không hiện nút xoá (bài có sẵn)
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ── Nội dung chính ───────────────────────────────────────────────
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text  = reading.titleEn ?: "",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text  = reading.titleVi ?: "",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (reading.level != null || reading.topic != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        reading.level?.let { AssistChip(onClick = {}, label = { Text(it) }) }
                        reading.topic?.let { AssistChip(onClick = {}, label = { Text(it) }) }
                    }
                }
            }

            // ── Nút xoá — chỉ hiện với bài user tự tạo ──────────────────────
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector        = Icons.Outlined.Delete,
                        contentDescription = "Xoá bài đọc",
                        tint               = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}