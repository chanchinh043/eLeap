package com.eleap.eleap.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary   = Indigo80,
    secondary = IndigoGrey80,
    tertiary  = Teal80
)

private val LightColorScheme = lightColorScheme(
    primary   = Indigo40,
    secondary = IndigoGrey40,
    tertiary  = Teal40
)

@Composable
fun ELeapTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}