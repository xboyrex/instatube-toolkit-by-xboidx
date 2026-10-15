package com.vakarux.instadownload

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val RexLightScheme = lightColorScheme(
    primary = Color(0xFFD72E6F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9E5),
    onPrimaryContainer = Color(0xFF3A0019),
    secondary = Color(0xFF6554A6),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9DDFF),
    onSecondaryContainer = Color(0xFF21124F),
    tertiary = Color(0xFFAA4A35),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDBD1),
    onTertiaryContainer = Color(0xFF3F0800),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF2F2F7),
    onBackground = Color(0xFF17171B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF17171B),
    surfaceVariant = Color(0xFFE6E6EB),
    onSurfaceVariant = Color(0xFF66666F),
    outline = Color(0xFF96969F),
    outlineVariant = Color(0xFFD6D6DE),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF2D2D32),
    inverseOnSurface = Color(0xFFF7F7FA),
    inversePrimary = Color(0xFFFFB1C8),
    surfaceDim = Color(0xFFE0E0E8),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF9F9FC),
    surfaceContainer = Color(0xFFF2F2F7),
    surfaceContainerHigh = Color(0xFFECECF2),
    surfaceContainerHighest = Color(0xFFE4E4EC)
)

private val RexDarkScheme = darkColorScheme(
    primary = Color(0xFFFF6C9C),
    onPrimary = Color(0xFF3D001A),
    primaryContainer = Color(0xFF7D2044),
    onPrimaryContainer = Color(0xFFFFD9E5),
    secondary = Color(0xFFC7B8FF),
    onSecondary = Color(0xFF30215B),
    secondaryContainer = Color(0xFF493A75),
    onSecondaryContainer = Color(0xFFE9DDFF),
    tertiary = Color(0xFFFFB4A0),
    onTertiary = Color(0xFF5E1608),
    tertiaryContainer = Color(0xFF7F2D1E),
    onTertiaryContainer = Color(0xFFFFDBD1),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0D0D11),
    onBackground = Color(0xFFF3F0F5),
    surface = Color(0xFF1A1A20),
    onSurface = Color(0xFFF3F0F5),
    surfaceVariant = Color(0xFF292930),
    onSurfaceVariant = Color(0xFFB8B6C1),
    outline = Color(0xFF8C8995),
    outlineVariant = Color(0xFF3A3942),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFF3F0F5),
    inverseOnSurface = Color(0xFF28262D),
    inversePrimary = Color(0xFFD72E6F),
    surfaceDim = Color(0xFF0D0D11),
    surfaceBright = Color(0xFF393841),
    surfaceContainerLowest = Color(0xFF09090C),
    surfaceContainerLow = Color(0xFF17171C),
    surfaceContainer = Color(0xFF1A1A20),
    surfaceContainerHigh = Color(0xFF24232B),
    surfaceContainerHighest = Color(0xFF302F37)
)

@Composable
fun InstaDownloadTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    // Fixed neutral colors keep the Xboy Rex brand coherent across Android device themes.
    val colors = if (darkTheme) RexDarkScheme else RexLightScheme
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
