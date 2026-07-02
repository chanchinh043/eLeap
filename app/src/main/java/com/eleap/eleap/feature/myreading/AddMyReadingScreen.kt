// AddMyReadingScreen.kt
// Đặt tại: feature/myreading/AddMyReadingScreen.kt
package com.eleap.eleap.feature.myreading

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eleap.eleap.feature.myreading.data.MyReadingRepository
import kotlinx.coroutines.launch

// Màn "Thêm bài đọc" trong luồng MyReading — giao diện giống UserReadingScreen
// (feature/userreading), nhưng lưu vào myreading.db (file DB riêng, độc lập
// hoàn toàn với readings.db và users.db) qua MyReadingRepository.
// saveMyReading() trả về reading_id dạng UUID v7 (String), hoặc null nếu lỗi.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddMyReadingScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    val repo    = remember { MyReadingRepository.getInstance(context) }

    var title   by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }

    var isSaving by remember { mutableStateOf(false) }
    // null = chưa thử lưu, "" = thất bại, non-empty = thành công với readingId (UUID) đó
    var savedId  by remember { mutableStateOf<String?>(null) }

    val saveResult    = savedId?.let { it.isNotEmpty() }
    val titleError    = title.isBlank() && saveResult == false
    val contentError  = content.isBlank() && saveResult == false

    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Thêm bài đọc") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Quay lại"
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            if (isSaving) return@TextButton
                            if (title.isBlank() || content.isBlank()) {
                                savedId = ""
                                return@TextButton
                            }
                            isSaving = true
                            scope.launch {
                                val id = repo.saveMyReading(title, content)
                                isSaving = false
                                savedId  = id ?: ""

                                if (id != null) {
                                    onSaved()
                                }
                            }
                        },
                        enabled = !isSaving
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier    = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Lưu", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            )
        }
    ) { padding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            if (saveResult == false && (title.isBlank() || content.isBlank())) {
                Text(
                    text  = "Vui lòng điền đầy đủ tiêu đề và nội dung.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (saveResult == false && title.isNotBlank() && content.isNotBlank()) {
                Text(
                    text  = "Lưu không thành công. Vui lòng thử lại.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            OutlinedTextField(
                value          = title,
                onValueChange  = { title = it; savedId = null },
                label          = { Text("Tiêu đề") },
                placeholder    = { Text("Nhập tiêu đề bài đọc…") },
                singleLine     = true,
                isError        = titleError,
                supportingText = if (titleError) {
                    { Text("Tiêu đề không được để trống") }
                } else null,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value          = content,
                onValueChange  = { content = it; savedId = null },
                label          = { Text("Nội dung bài đọc") },
                placeholder    = { Text("Soạn hoặc dán nội dung bài đọc vào đây…") },
                isError        = contentError,
                supportingText = if (contentError) {
                    { Text("Nội dung không được để trống") }
                } else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 260.dp),
                maxLines = Int.MAX_VALUE,
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}