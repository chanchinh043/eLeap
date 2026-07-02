package com.eleap.eleap

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.eleap.eleap.core.auth.CurrentUser
import com.eleap.eleap.ui.theme.ELeapTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Khởi tạo CurrentUser TRƯỚC setContent — để lúc UI vẽ ra,
        // userId đã sẵn sàng đọc được ngay (không bị null/chưa init).
        CurrentUser.init(this)

        enableEdgeToEdge()
        setContent {
            ELeapTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen()
                }
            }
        }
    }
}