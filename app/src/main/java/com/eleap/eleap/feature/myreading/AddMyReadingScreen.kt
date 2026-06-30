// AddMyReadingScreen.kt
// Đặt tại: feature/myreading/AddMyReadingScreen.kt
package com.eleap.eleap.feature.myreading

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

// Khung màn hình "Thêm bài đọc" — mở từ menu ở MyReadingListScreen.
// Logic (nhập nội dung, lưu bài đọc, v.v.) sẽ thêm sau.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddMyReadingScreen(
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Thêm bài đọc") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            // TODO: nội dung sẽ thêm sau
            Text("Chưa có nội dung")
        }
    }
}