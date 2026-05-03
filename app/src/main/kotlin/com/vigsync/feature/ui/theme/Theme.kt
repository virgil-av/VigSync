package com.vigsync.feature.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6650a4),
    secondary = Color(0xFF625b71),
    tertiary = Color(0xFF7D5260)
)

@Composable
fun VigSyncTheme(
    content: @Composable () -> Unit
) {
    // We ignore the system theme and always use light mode
    val colorScheme = LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
