// LoginScreen.kt
// Đặt tại: feature/auth/LoginScreen.kt
package com.eleap.eleap.feature.auth

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eleap.eleap.core.auth.CurrentUser
import com.eleap.eleap.core.auth.SupabaseClientProvider
import kotlinx.coroutines.launch

// Màn đăng nhập — chỉ có 1 nút "Đăng nhập với Google" để test luồng OAuth.
// Sau khi Supabase redirect về qua deep link (xử lý ở MainActivity), CurrentUser
// sẽ tự cập nhật userId, màn này chỉ cần hiển thị trạng thái hiện tại.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val userId by CurrentUser.userId.collectAsState()

    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Đăng nhập") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // ── Trạng thái hiện tại — chỉ để test, xoá sau khi luồng đã ổn định ──
            Text(
                text = if (userId == CurrentUser.GUEST_ID)
                    "Chưa đăng nhập (guest)"
                else
                    "Đã đăng nhập: $userId"
            )

            Spacer(modifier = Modifier.height(24.dp))

            errorMessage?.let {
                Text(text = it, color = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(12.dp))
            }

            Button(
                onClick = {
                    isLoading = true
                    errorMessage = null
                    scope.launch {
                        try {
                            SupabaseClientProvider.signInWithGoogle()
                        } catch (e: Exception) {
                            errorMessage = "Lỗi đăng nhập: ${e.message}"
                        } finally {
                            isLoading = false
                        }
                    }
                },
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Đăng nhập với Google")
                }
            }
        }
    }
}