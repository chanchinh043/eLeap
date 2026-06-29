// UserReadingScreen.kt
package com.eleap.eleap.feature.userreading

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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserReadingScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit = {},   // callback sau khi lưu thành công (tuỳ chọn)
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    val repo    = remember { UserReadingRepository.getInstance(context) }

    var title   by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }

    var isSaving    by remember { mutableStateOf(false) }
    var saveResult  by remember { mutableStateOf<Boolean?>(null) }   // null = chưa lưu

    val titleError   = title.isBlank() && saveResult == false
    val contentError = content.isBlank() && saveResult == false

    Scaffold(
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
                        onClick  = {
                            if (isSaving) return@TextButton
                            // Validate
                            if (title.isBlank() || content.isBlank()) {
                                saveResult = false
                                return@TextButton
                            }
                            isSaving = true
                            scope.launch {
                                val ok = repo.saveUserReading(title, content)
                                isSaving   = false
                                saveResult = ok
                                if (ok) onSaved()
                            }
                        },
                        enabled = !isSaving
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier  = Modifier.size(18.dp),
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

            // ── Thông báo lỗi chung ──────────────────────────────────────────
            if (saveResult == false && (title.isBlank() || content.isBlank())) {
                Text(
                    text  = "Vui lòng điền đầy đủ tiêu đề và nội dung.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            // ── Thông báo lỗi khi DB insert thất bại ────────────────────────
            if (saveResult == false && title.isNotBlank() && content.isNotBlank()) {
                Text(
                    text  = "Lưu không thành công. Vui lòng thử lại.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            // ── Tiêu đề ──────────────────────────────────────────────────────
            OutlinedTextField(
                value         = title,
                onValueChange = { title = it; saveResult = null },
                label         = { Text("Tiêu đề") },
                placeholder   = { Text("Nhập tiêu đề bài đọc…") },
                singleLine    = true,
                isError       = titleError,
                supportingText = if (titleError) {
                    { Text("Tiêu đề không được để trống") }
                } else null,
                modifier = Modifier.fillMaxWidth()
            )

            // ── Nội dung bài đọc ─────────────────────────────────────────────
            OutlinedTextField(
                value         = content,
                onValueChange = { content = it; saveResult = null },
                label         = { Text("Nội dung bài đọc") },
                placeholder   = { Text("Soạn hoặc dán nội dung bài đọc vào đây…") },
                isError       = contentError,
                supportingText = if (contentError) {
                    { Text("Nội dung không được để trống") }
                } else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 260.dp),
                maxLines  = Int.MAX_VALUE,
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}