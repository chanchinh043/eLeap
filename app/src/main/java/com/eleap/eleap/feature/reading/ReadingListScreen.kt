// ReadingListScreen.kt
package com.eleap.eleap.feature.reading

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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eleap.eleap.feature.reading.data.Reading
import androidx.compose.material.icons.filled.Person

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingListScreen(
    onBack: () -> Unit,
    onReadingClick: (readingId: Int) -> Unit,
    onMyReadingClick: () -> Unit,   // ← mở "Bài đọc của tôi"
) {
    val context = LocalContext.current
    val vm: ReadingViewModel = viewModel(factory = ReadingViewModel.Factory(context))
    val readings by vm.readings.collectAsState()

    // Menu danh mục bài đọc — giờ mở từ icon ở TopAppBar (góc trên-phải)
    var showCategoryMenu by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
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
                        IconButton(onClick = { showCategoryMenu = true }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Danh mục bài đọc")
                        }
                    }
                )
            },
            floatingActionButton = {
                // (+) giờ đi thẳng tới "Bài đọc của tôi", không mở menu nữa
                FloatingActionButton(onClick = onMyReadingClick) {
                    Icon(Icons.Filled.Add, contentDescription = "Bài đọc của tôi")
                }
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
                        reading = reading,
                        onClick = { onReadingClick(reading.readingId) }
                    )
                }
            }
        }

        ReadingCategoryDrawer(
            visible = showCategoryMenu,
            onDismiss = { showCategoryMenu = false },
            onMyReadingClick = {
                showCategoryMenu = false
                onMyReadingClick()
            }
        )
    }
}

@Composable
private fun ReadingCard(reading: Reading, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = reading.titleEn ?: "",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = reading.titleVi ?: "",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                reading.level?.let { AssistChip(onClick = {}, label = { Text(it) }) }
                reading.topic?.let { AssistChip(onClick = {}, label = { Text(it) }) }
            }
        }
    }
}

// ── Menu danh mục bài đọc — kéo từ bên phải sang, mở từ icon Menu trên TopAppBar ──
// Hiện chỉ có "Bài đọc của tôi". Các danh mục khác sẽ thêm sau ở đây.
@Composable
private fun ReadingCategoryDrawer(
    visible: Boolean,
    onDismiss: () -> Unit,
    onMyReadingClick: () -> Unit,
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

                        TextButton(onClick = onMyReadingClick) {
                            Icon(Icons.Filled.Person, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Bài đọc của tôi")
                        }
                        // TODO: danh mục bài đọc khác sẽ thêm sau
                    }
                }
            }
        }
    }
}