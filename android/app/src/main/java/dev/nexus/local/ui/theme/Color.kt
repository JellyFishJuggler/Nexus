package dev.nexus.local.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Nexus design tokens.
 *
 * The light values are copied 1:1 from the desktop companion
 * (`desktop/nexus-desktop/ui/style.css`) so both apps read as the same
 * product: warm off-white surfaces, white cards, charcoal type, hairline
 * borders and a single muted green accent. Amber marks pairing/pending
 * states; red is reserved for destructive actions. There is deliberately no
 * blue and no purple.
 *
 * A dark variant exists for the opt-in dark theme. Light stays the default,
 * so the shipped look is always the desktop-matching one.
 */

// ---- Light: identical to the desktop `:root` tokens -----------------------

val NexusLightBackground = Color(0xFFF5F6F4)
val NexusLightSidebar = Color(0xFFEEF0EC)
val NexusLightSurface = Color(0xFFFFFFFF)
val NexusLightSurfaceMuted = Color(0xFFFAFBF9)
val NexusLightBorder = Color(0xFFE6E8E3)
val NexusLightBorderStrong = Color(0xFFD9DCD5)

val NexusLightInk = Color(0xFF1D201E)
val NexusLightInkMuted = Color(0xFF6C726D)
val NexusLightInkFaint = Color(0xFF9AA09A)

val NexusLightGreen = Color(0xFF2F8F5B)
val NexusLightGreenSoft = Color(0xFF7FB695)
val NexusLightGreenContainer = Color(0xFFECF6F0)
val NexusLightGreenBorder = Color(0xFFCFE7DA)
val NexusLightOnGreenContainer = Color(0xFF23663F)

val NexusLightAmber = Color(0xFFB4781D)
val NexusLightAmberContainer = Color(0xFFFCF4E4)
val NexusLightAmberBorder = Color(0xFFEEDDB8)
val NexusLightAmberHeading = Color(0xFF7C500E)
val NexusLightOnAmberContainer = Color(0xFF8A5A10)

val NexusLightRed = Color(0xFFBF4030)
val NexusLightRedContainer = Color(0xFFFBEEEC)
val NexusLightRedBorder = Color(0xFFEDCFC9)
val NexusLightOnRedContainer = Color(0xFF8F3124)

val NexusLightCharcoal = Color(0xFF23262A)
val NexusLightCharcoalHover = Color(0xFF34383D)
val NexusLightOnCharcoal = Color(0xFFFFFFFF)

// ---- Dark: opt-in alternative, same hues lifted for contrast -------------

val NexusDarkBackground = Color(0xFF16181A)
val NexusDarkSidebar = Color(0xFF1A1C1F)
val NexusDarkSurface = Color(0xFF1E2124)
val NexusDarkSurfaceMuted = Color(0xFF24272B)
val NexusDarkBorder = Color(0xFF2C3034)
val NexusDarkBorderStrong = Color(0xFF3A3F44)

val NexusDarkInk = Color(0xFFE9EBE8)
val NexusDarkInkMuted = Color(0xFFA6ACA6)
val NexusDarkInkFaint = Color(0xFF7B827C)

val NexusDarkGreen = Color(0xFF5ABE8A)
val NexusDarkGreenSoft = Color(0xFF7FB695)
val NexusDarkGreenContainer = Color(0xFF17301F)
val NexusDarkGreenBorder = Color(0xFF2A543C)
val NexusDarkOnGreenContainer = Color(0xFFA9DFC4)

val NexusDarkAmber = Color(0xFFE0A94A)
val NexusDarkAmberContainer = Color(0xFF2E2411)
val NexusDarkAmberBorder = Color(0xFF55401A)
val NexusDarkAmberHeading = Color(0xFFE7C27A)
val NexusDarkOnAmberContainer = Color(0xFFE7C27A)

val NexusDarkRed = Color(0xFFE4796A)
val NexusDarkRedContainer = Color(0xFF331A16)
val NexusDarkRedBorder = Color(0xFF5E2A22)
val NexusDarkOnRedContainer = Color(0xFFF0B6AC)

val NexusDarkCharcoal = Color(0xFF3A3F44)
val NexusDarkCharcoalHover = Color(0xFF464C52)
val NexusDarkOnCharcoal = Color(0xFFF4F6F3)
