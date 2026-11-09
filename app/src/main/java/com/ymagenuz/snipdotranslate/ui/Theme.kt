package com.ymagenuz.snipdotranslate.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// The icon's colours, as on the desktop: blue for the source, teal for the translation.
private val LightColors = lightColorScheme(
    primary = Color(0xFF2F6FED),
    secondary = Color(0xFF52627A),
    tertiary = Color(0xFF00897B),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9DBBFF),
    secondary = Color(0xFFB9C7DF),
    tertiary = Color(0xFF66D2C3),
    error = Color(0xFFFFB4AB),
)

@Composable
fun SnipDoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
