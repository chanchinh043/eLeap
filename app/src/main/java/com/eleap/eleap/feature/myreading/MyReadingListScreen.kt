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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Khung màn hình "Bài đọc của tôi" — chỉ điều hướng trước,
// nội dung/logic (danh sách bài đọc đã lưu, v.v.) sẽ thêm sau.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyReadingListScreen(
    onBack: () -> Unit,
    onAddClick: () -> Unit,          // nút (+) FAB → chuyển sang ReadingListScreen
    onAddReadingClick: () -> Unit,   // ← mới: mục đầu tiên trong menu → AddMyReadingScreen
) {
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
            // TODO: khi có danh sách bài đọc thật, thay isEmpty bằng readings.isEmpty()
            val isEmpty = true

            if (isEmpty) {
                EmptyMyReadingContent(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    onAddReadingClick = onAddReadingClick,
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    // TODO: danh sách bài đọc của tôi sẽ thêm sau
                    Text("Chưa có nội dung")
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