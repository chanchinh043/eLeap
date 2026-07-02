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
import com.eleap.eleap.feature.myreading.data.MyReading
import com.eleap.eleap.feature.myreading.data.MyReadingRepository
import kotlinx.coroutines.launch

// Màn "Bài đọc của tôi" — hiển thị danh sách bài đọc lấy từ myreading.db
// qua MyReadingRepository. Bấm vào 1 bài chỉ log tạm (chưa có màn đọc riêng
// cho MyReading — sẽ nối onReadingClick khi có màn đó).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyReadingListScreen(
    onBack: () -> Unit,
    onAddClick: () -> Unit,          // nút (+) FAB → chuyển sang ReadingListScreen
    onAddReadingClick: () -> Unit,   // ← mới: mục đầu tiên trong menu → AddMyReadingScreen
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    val repo    = remember { MyReadingRepository.getInstance(context) }

    var readings  by remember { mutableStateOf<List<MyReading>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    // Load lại mỗi khi màn này được vào (kể cả sau khi quay lại từ AddMyReadingScreen,
    // vì navigate trong MainScreen tạo lại composable này từ đầu).
    LaunchedEffect(Unit) {
        isLoading = true
        readings  = repo.getAllReadings()
        isLoading = false
    }

    fun reload() {
        scope.launch {
            readings = repo.getAllReadings()
        }
    }

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
                isLoading && readings.isEmpty() -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }

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
                            onClick  = { /* TODO: mở màn đọc MyReading khi có */ },
                            onDelete = {
                                scope.launch {
                                    if (repo.deleteMyReading(reading.readingId)) {
                                        reload()
                                    }
                                }
                            }
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
    reading: MyReading,
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
// Mục đầu tiên: "Thêm bài đọc" → AddMyReadingScreen. Các mục khác thêm sau.
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
            // Scrim — chạm ra ngoài để đóng menu
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
                        // TODO: các mục khác sẽ thêm sau
                    }
                }
            }
        }
    }
}