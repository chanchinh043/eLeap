// MyReadingListScreen.kt
// Đặt tại: feature/myreading/MyReadingListScreen.kt
package com.eleap.eleap.feature.myreading

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eleap.eleap.feature.reading.ReadingViewModel
import com.eleap.eleap.feature.reading.data.Reading

// Màn "Bài đọc của tôi" — hiển thị danh sách bài đọc của user hiện tại
// (userId != null), lấy từ ReadingViewModel.myReadings (đã gộp sẵn qua
// ReadingRepository + MyReadingRepository, filter theo CurrentUser).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyReadingListScreen(
    onBack: () -> Unit,
    onAddClick: () -> Unit,          // nút (+) FAB → chuyển sang ReadingListScreen
    onAddReadingClick: () -> Unit,   // ← mục đầu tiên trong menu → AddMyReadingScreen
    onReadingClick: (readingId: String) -> Unit,   // ← bấm vào 1 bài → mở ReadingScreen
) {
    val context = LocalContext.current
    val vm: ReadingViewModel = viewModel(factory = ReadingViewModel.Factory(context))
    val readings by vm.myReadings.collectAsState()

    // Menu danh mục — mở từ icon ở TopAppBar (góc trên-phải), giống ReadingListScreen
    var showMenu by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Bài đọc của tôi") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Menu")
                        }
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = onAddClick) {
                    Icon(Icons.Filled.Add, contentDescription = "Thêm bài đọc")
                }
            }
        ) { padding ->
            when {
                readings.isEmpty() -> EmptyMyReadingContent(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    onAddReadingClick = onAddReadingClick,
                )

                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    items(readings, key = { it.readingId }) { reading ->
                        MyReadingCard(
                            reading  = reading,
                            onClick  = { onReadingClick(reading.readingId) },
                            onDelete = { vm.deleteMyReading(reading.readingId) }
                        )
                    }
                }
            }
        }

        MyReadingMenuDrawer(
            visible = showMenu,
            onDismiss = { showMenu = false },
            onAddReadingClick = {
                showMenu = false
                onAddReadingClick()
            }
        )
    }
}

// ── Thẻ 1 bài đọc trong danh sách ────────────────────────────────────────────
@Composable
private fun MyReadingCard(
    reading: Reading,
    onClick: () -> Unit,
    onDelete: () -> Unit,
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text  = reading.titleEn ?: "",
                    style = MaterialTheme.typography.titleMedium
                )
                reading.titleVi?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text  = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (reading.level != null || reading.topic != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        reading.level?.let { AssistChip(onClick = {}, label = { Text(it) }) }
                        reading.topic?.let { AssistChip(onClick = {}, label = { Text(it) }) }
                    }
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Xoá bài đọc",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

// ── Trạng thái trống: chưa có bài đọc nào → hiện nút "Thêm bài đọc" ở giữa ──
@Composable
private fun EmptyMyReadingContent(
    modifier: Modifier = Modifier,
    onAddReadingClick: () -> Unit,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Bạn chưa có bài đọc nào",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onAddReadingClick) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Thêm bài đọc")
            }
        }
    }
}

// ── Menu — kéo từ bên phải sang, mở từ icon Menu trên TopAppBar ─────────────
@Composable
private fun MyReadingMenuDrawer(
    visible: Boolean,
    onDismiss: () -> Unit,
    onAddReadingClick: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onDismiss
                    )
            )

            AnimatedVisibility(
                visible = visible,
                enter = slideInHorizontally(initialOffsetX = { it }),
                exit = slideOutHorizontally(targetOffsetX = { it }),
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(280.dp),
                    tonalElevation = 3.dp,
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Menu",
                            style = MaterialTheme.typography.titleLarge
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                        TextButton(onClick = onAddReadingClick) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Thêm bài đọc")
                        }
                    }
                }
            }
        }
    }
}