package com.tripguard.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val TripGuardColorScheme = lightColorScheme(
    primary = Emerald,
    onPrimary = Color.White,
    primaryContainer = EmeraldContainer,
    onPrimaryContainer = OnEmeraldContainer,
    secondary = Navy,
    onSecondary = Color.White,
    secondaryContainer = SurfaceMuted,
    onSecondaryContainer = Navy,
    tertiary = InfoBlue,
    onTertiary = Color.White,
    background = Background,
    onBackground = TextPrimary,
    surface = SurfaceWhite,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceMuted,
    onSurfaceVariant = TextSecondary,
    surfaceTint = Emerald,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBFCFD),
    surfaceContainer = SurfaceMuted,
    surfaceContainerHigh = Color(0xFFE9EEF2),
    outline = BorderColor,
    outlineVariant = DividerColor,
    error = ErrorRed,
    onError = Color.White,
    errorContainer = SoftRedBg,
    onErrorContainer = SoftRedFg,
)

val TripGuardShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * TripGuard is intentionally a light, airy product surface, so a single light colour scheme
 * is applied regardless of the system setting. Edge-to-edge system bar styling is configured
 * in [com.tripguard.app.MainActivity].
 */
@Composable
fun TripGuardTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = TripGuardColorScheme,
        typography = TripGuardTypography,
        shapes = TripGuardShapes,
        content = content,
    )
}
