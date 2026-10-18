package dev.nexus.local.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The single Nexus light colour scheme.
 *
 * Dynamic colour and dark mode are intentionally disabled for v0.1 so the app
 * looks the same as the desktop companion on every device. `surfaceTint` is
 * transparent to keep cards flat white instead of picking up tonal elevation.
 */
private val NexusLightColors = lightColorScheme(
    primary = NexusCharcoal,
    onPrimary = NexusOnCharcoal,
    primaryContainer = NexusGreenContainer,
    onPrimaryContainer = NexusOnGreenContainer,

    secondary = NexusGreen,
    onSecondary = Color.White,
    secondaryContainer = NexusGreenContainer,
    onSecondaryContainer = NexusOnGreenContainer,

    tertiary = NexusAmber,
    onTertiary = Color.White,
    tertiaryContainer = NexusAmberContainer,
    onTertiaryContainer = NexusOnAmberContainer,

    error = NexusRed,
    onError = Color.White,
    errorContainer = NexusRedContainer,
    onErrorContainer = Color(0xFF410E0B),

    background = NexusBackground,
    onBackground = NexusInk,
    surface = NexusSurface,
    onSurface = NexusInk,
    surfaceVariant = NexusSurfaceMuted,
    onSurfaceVariant = NexusInkMuted,
    outline = NexusBorder,
    outlineVariant = NexusBorder,
    surfaceTint = Color.Transparent,
    inverseSurface = NexusCharcoal,
    inverseOnSurface = NexusOnCharcoal
)

@Composable
fun NexusTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NexusLightColors,
        typography = Typography,
        content = content
    )
}
