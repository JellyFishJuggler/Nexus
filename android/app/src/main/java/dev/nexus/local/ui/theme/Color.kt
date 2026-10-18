package dev.nexus.local.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Nexus design tokens.
 *
 * A light, paper-like utility palette shared with the desktop app: off-white
 * surfaces, white cards, charcoal type, hairline borders and a single muted
 * green accent. Amber marks pairing/warnings; red is reserved for destructive
 * actions. There is deliberately no blue and no purple.
 */

// Surfaces
val NexusBackground = Color(0xFFF4F5F3)
val NexusSurface = Color(0xFFFFFFFF)
val NexusSurfaceMuted = Color(0xFFF7F8F6)
val NexusBorder = Color(0xFFE4E6E2)

// Type
val NexusInk = Color(0xFF1D201E)
val NexusInkMuted = Color(0xFF636A65)
val NexusInkFaint = Color(0xFF8A908B)

// Accent — muted green
val NexusGreen = Color(0xFF3E6B54)
val NexusGreenContainer = Color(0xFFE2EBE4)
val NexusOnGreenContainer = Color(0xFF24402F)

// Warning — amber (pairing)
val NexusAmber = Color(0xFF8A6414)
val NexusAmberContainer = Color(0xFFF6EAD3)
val NexusOnAmberContainer = Color(0xFF4A3608)

// Destructive — restrained red
val NexusRed = Color(0xFFB3261E)
val NexusRedContainer = Color(0xFFF7E3E1)

// Primary action — charcoal, matching the desktop primary button
val NexusCharcoal = Color(0xFF23262A)
val NexusOnCharcoal = Color(0xFFF7F8F6)
