package com.ymagenuz.snipdotranslate.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The icon's colours, as on the desktop: blue for the source, teal for the translation.
// The blue and teal used for text are a little darker than the icon's, to stay readable on the page.
// Surfaces are cool greys, so neither colour has to compete with a tinted background.
private val LightColors = lightColorScheme(
    primary = Color(0xFF2A64E6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3ECFF),
    onPrimaryContainer = Color(0xFF1D4FC4),
    inversePrimary = Color(0xFF9DBBFF),
    secondary = Color(0xFF4B5566),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDF1F7),
    onSecondaryContainer = Color(0xFF39424F),
    tertiary = Color(0xFF00796B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD9F3EE),
    onTertiaryContainer = Color(0xFF00504A),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    background = Color(0xFFF5F7FB),
    onBackground = Color(0xFF141A24),
    surface = Color(0xFFF5F7FB),
    onSurface = Color(0xFF141A24),
    surfaceVariant = Color(0xFFE9EDF4),
    onSurfaceVariant = Color(0xFF4B5566),
    surfaceTint = Color(0xFF2A64E6),
    inverseSurface = Color(0xFF262D3A),
    inverseOnSurface = Color(0xFFF1F4F9),
    outline = Color(0xFF5F6B7E),
    outlineVariant = Color(0xFFE1E6EF),
    scrim = Color.Black,
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFDDE2EB),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFF0F3F9),
    surfaceContainerHigh = Color(0xFFE9EDF4),
    surfaceContainerHighest = Color(0xFFE4E8F0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9DBBFF),
    onPrimary = Color(0xFF0A2A6B),
    primaryContainer = Color(0xFF1C3F8F),
    onPrimaryContainer = Color(0xFFDCE6FF),
    inversePrimary = Color(0xFF2A64E6),
    secondary = Color(0xFFB9C3D3),
    onSecondary = Color(0xFF232A36),
    secondaryContainer = Color(0xFF2A313D),
    onSecondaryContainer = Color(0xFFD5DCE8),
    tertiary = Color(0xFF66D2C3),
    onTertiary = Color(0xFF00382F),
    tertiaryContainer = Color(0xFF005046),
    onTertiaryContainer = Color(0xFFA6F0E3),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF10141B),
    onBackground = Color(0xFFE6EAF2),
    surface = Color(0xFF10141B),
    onSurface = Color(0xFFE6EAF2),
    surfaceVariant = Color(0xFF2C3442),
    onSurfaceVariant = Color(0xFFB4BDCC),
    surfaceTint = Color(0xFF9DBBFF),
    inverseSurface = Color(0xFFE6EAF2),
    inverseOnSurface = Color(0xFF1B212B),
    outline = Color(0xFF8E99AB),
    outlineVariant = Color(0xFF303846),
    scrim = Color.Black,
    surfaceBright = Color(0xFF353D4B),
    surfaceDim = Color(0xFF10141B),
    surfaceContainerLowest = Color(0xFF0B0E13),
    surfaceContainerLow = Color(0xFF181D26),
    surfaceContainer = Color(0xFF1E242E),
    surfaceContainerHigh = Color(0xFF272E3A),
    surfaceContainerHighest = Color(0xFF313947),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val AppTypography = Typography().run {
    copy(
        titleLarge = titleLarge.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun SnipDoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = AppShapes,
        typography = AppTypography,
        content = content,
    )
}
