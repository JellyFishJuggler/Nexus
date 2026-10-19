package dev.nexus.local.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The semantic colours of the Nexus design system.
 *
 * [androidx.compose.material3.MaterialTheme.colorScheme] only carries a
 * fraction of these slots, so the full set lives here and is exposed as
 * `MaterialTheme.nexus`. Components read from it instead of importing raw
 * colour constants, which is what lets the opt-in dark theme work without
 * touching the screens.
 */
@Immutable
data class NexusPalette(
    val background: Color,
    val sidebar: Color,
    val surface: Color,
    val surfaceMuted: Color,
    val border: Color,
    val borderStrong: Color,
    val ink: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    val green: Color,
    val greenSoft: Color,
    val greenContainer: Color,
    val greenBorder: Color,
    val onGreenContainer: Color,
    val amber: Color,
    val amberContainer: Color,
    val amberBorder: Color,
    val amberHeading: Color,
    val onAmberContainer: Color,
    val red: Color,
    val redContainer: Color,
    val redBorder: Color,
    val onRedContainer: Color,
    val charcoal: Color,
    val charcoalHover: Color,
    val onCharcoal: Color
)

val NexusLightPalette = NexusPalette(
    background = NexusLightBackground,
    sidebar = NexusLightSidebar,
    surface = NexusLightSurface,
    surfaceMuted = NexusLightSurfaceMuted,
    border = NexusLightBorder,
    borderStrong = NexusLightBorderStrong,
    ink = NexusLightInk,
    inkMuted = NexusLightInkMuted,
    inkFaint = NexusLightInkFaint,
    green = NexusLightGreen,
    greenSoft = NexusLightGreenSoft,
    greenContainer = NexusLightGreenContainer,
    greenBorder = NexusLightGreenBorder,
    onGreenContainer = NexusLightOnGreenContainer,
    amber = NexusLightAmber,
    amberContainer = NexusLightAmberContainer,
    amberBorder = NexusLightAmberBorder,
    amberHeading = NexusLightAmberHeading,
    onAmberContainer = NexusLightOnAmberContainer,
    red = NexusLightRed,
    redContainer = NexusLightRedContainer,
    redBorder = NexusLightRedBorder,
    onRedContainer = NexusLightOnRedContainer,
    charcoal = NexusLightCharcoal,
    charcoalHover = NexusLightCharcoalHover,
    onCharcoal = NexusLightOnCharcoal
)

val NexusDarkPalette = NexusPalette(
    background = NexusDarkBackground,
    sidebar = NexusDarkSidebar,
    surface = NexusDarkSurface,
    surfaceMuted = NexusDarkSurfaceMuted,
    border = NexusDarkBorder,
    borderStrong = NexusDarkBorderStrong,
    ink = NexusDarkInk,
    inkMuted = NexusDarkInkMuted,
    inkFaint = NexusDarkInkFaint,
    green = NexusDarkGreen,
    greenSoft = NexusDarkGreenSoft,
    greenContainer = NexusDarkGreenContainer,
    greenBorder = NexusDarkGreenBorder,
    onGreenContainer = NexusDarkOnGreenContainer,
    amber = NexusDarkAmber,
    amberContainer = NexusDarkAmberContainer,
    amberBorder = NexusDarkAmberBorder,
    amberHeading = NexusDarkAmberHeading,
    onAmberContainer = NexusDarkOnAmberContainer,
    red = NexusDarkRed,
    redContainer = NexusDarkRedContainer,
    redBorder = NexusDarkRedBorder,
    onRedContainer = NexusDarkOnRedContainer,
    charcoal = NexusDarkCharcoal,
    charcoalHover = NexusDarkCharcoalHover,
    onCharcoal = NexusDarkOnCharcoal
)

val LocalNexusPalette = staticCompositionLocalOf { NexusLightPalette }

/** Nexus colours for the active theme. */
val MaterialTheme.nexus: NexusPalette
    @Composable
    @ReadOnlyComposable
    get() = LocalNexusPalette.current

/**
 * Material 3 slots fed from the palette. Material components (buttons,
 * navigation bar, switch, progress, dialogs) pick these up automatically.
 */
private val LightColors = lightColorScheme(
    // Primary stays charcoal in the light theme: it matches the desktop's
    // `.btn-primary` so the two apps share one visual language.
    primary = NexusLightCharcoal,
    onPrimary = NexusLightOnCharcoal,
    primaryContainer = NexusLightGreenContainer,
    onPrimaryContainer = NexusLightOnGreenContainer,

    secondary = NexusLightGreen,
    onSecondary = Color.White,
    secondaryContainer = NexusLightGreenContainer,
    onSecondaryContainer = NexusLightOnGreenContainer,

    tertiary = NexusLightAmber,
    onTertiary = Color.White,
    tertiaryContainer = NexusLightAmberContainer,
    onTertiaryContainer = NexusLightOnAmberContainer,

    error = NexusLightRed,
    onError = Color.White,
    errorContainer = NexusLightRedContainer,
    onErrorContainer = NexusLightOnRedContainer,

    background = NexusLightBackground,
    onBackground = NexusLightInk,
    surface = NexusLightSurface,
    onSurface = NexusLightInk,
    surfaceVariant = NexusLightSurfaceMuted,
    onSurfaceVariant = NexusLightInkMuted,
    outline = NexusLightBorderStrong,
    outlineVariant = NexusLightBorder,
    surfaceTint = Color.Transparent,
    inverseSurface = NexusLightCharcoal,
    inverseOnSurface = NexusLightOnCharcoal
)

private val DarkColors = darkColorScheme(
    primary = NexusDarkGreen,
    onPrimary = Color(0xFF0E1A13),
    primaryContainer = NexusDarkGreenContainer,
    onPrimaryContainer = NexusDarkOnGreenContainer,

    secondary = NexusDarkGreen,
    onSecondary = Color(0xFF0E1A13),
    secondaryContainer = NexusDarkGreenContainer,
    onSecondaryContainer = NexusDarkOnGreenContainer,

    tertiary = NexusDarkAmber,
    onTertiary = Color(0xFF241A06),
    tertiaryContainer = NexusDarkAmberContainer,
    onTertiaryContainer = NexusDarkOnAmberContainer,

    error = NexusDarkRed,
    onError = Color(0xFF2A100C),
    errorContainer = NexusDarkRedContainer,
    onErrorContainer = NexusDarkOnRedContainer,

    background = NexusDarkBackground,
    onBackground = NexusDarkInk,
    surface = NexusDarkSurface,
    onSurface = NexusDarkInk,
    surfaceVariant = NexusDarkSurfaceMuted,
    onSurfaceVariant = NexusDarkInkMuted,
    outline = NexusDarkBorderStrong,
    outlineVariant = NexusDarkBorder,
    surfaceTint = Color.Transparent,
    inverseSurface = NexusDarkInk,
    inverseOnSurface = NexusDarkBackground
)

@Composable
fun NexusTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalNexusPalette provides if (darkTheme) NexusDarkPalette else NexusLightPalette
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = NexusTypography,
            content = content
        )
    }
}
