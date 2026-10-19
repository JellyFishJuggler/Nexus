package dev.nexus.local.ui.components

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.nexus.local.ui.theme.nexus
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Shared building blocks for the Nexus Android UI.
 *
 * Every visual decision here is deliberately traceable to the desktop
 * companion's stylesheet: 12dp card radius with a hairline border and a
 * 1dp shadow, 9dp buttons, pill badges, muted green accents and charcoal
 * primary actions.
 */

// ---- surfaces -------------------------------------------------------------

@Composable
fun NexusCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val p = MaterialTheme.nexus
    val shape = RoundedCornerShape(12.dp)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clip(shape).clickable { onClick() } else Modifier
            ),
        shape = shape,
        color = p.surface,
        border = BorderStroke(1.dp, p.border),
        shadowElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(18.dp), content = content)
    }
}

/** Uppercase section eyebrow, as used for card and section labels. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.nexus.inkFaint,
        modifier = modifier
    )
}

@Composable
fun CardHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.nexus.ink,
        modifier = modifier
    )
}

// ---- status ---------------------------------------------------------------

@Composable
fun StatusDot(color: Color, size: Dp = 8.dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
    )
}

/** Dot + label, matching the desktop device-row state treatment. */
@Composable
fun StatusLabel(
    text: String,
    dotColor: Color,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.nexus.inkMuted
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        StatusDot(dotColor)
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = textColor)
    }
}

enum class PillKind { NEUTRAL, GREEN, AMBER, RED }

@Composable
fun StatusPill(
    text: String,
    kind: PillKind = PillKind.NEUTRAL,
    modifier: Modifier = Modifier
) {
    val p = MaterialTheme.nexus
    val foreground = when (kind) {
        PillKind.GREEN -> p.onGreenContainer
        PillKind.AMBER -> p.onAmberContainer
        PillKind.RED -> p.onRedContainer
        PillKind.NEUTRAL -> p.inkMuted
    }
    val background = when (kind) {
        PillKind.GREEN -> p.greenContainer
        PillKind.AMBER -> p.amberContainer
        PillKind.RED -> p.redContainer
        PillKind.NEUTRAL -> p.surfaceMuted
    }
    val outline = when (kind) {
        PillKind.GREEN -> p.greenBorder
        PillKind.AMBER -> p.amberBorder
        PillKind.RED -> p.redBorder
        PillKind.NEUTRAL -> p.border
    }
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(background)
            .border(1.dp, outline, shape)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = text, style = MaterialTheme.typography.labelMedium, color = foreground)
    }
}

/** Thin progress track, mirroring the desktop pairing progress bar. */
@Composable
fun ProgressTrack(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(shape)
            .background(color.copy(alpha = 0.18f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(shape)
                .background(color)
        )
    }
}

/**
 * 0..1 progress that runs down over [totalMs], starting at first composition.
 *
 * The countdown is a presentation of the real deadline the controller
 * enforces; it does not gate anything itself.
 */
@Composable
fun rememberCountdownProgress(totalMs: Long): Float {
    var progress by remember(totalMs) { mutableStateOf(1f) }
    LaunchedEffect(totalMs) {
        val start = SystemClock.elapsedRealtime()
        while (true) {
            val elapsed = SystemClock.elapsedRealtime() - start
            val fraction = (totalMs - elapsed).toFloat() / totalMs.toFloat()
            progress = fraction.coerceIn(0f, 1f)
            if (fraction <= 0f) break
            delay(100)
        }
    }
    return progress
}

/** Whole seconds left for a [progress] value produced by [rememberCountdownProgress]. */
fun secondsLeft(progress: Float, totalMs: Long): Int =
    ((progress * totalMs) / 1000f).roundToInt().coerceAtLeast(0)

// ---- buttons --------------------------------------------------------------

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = RoundedCornerShape(9.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 44.dp),
        enabled = enabled,
        shape = RoundedCornerShape(9.dp),
        border = BorderStroke(1.dp, MaterialTheme.nexus.borderStrong),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.nexus.surface,
            contentColor = MaterialTheme.nexus.ink
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Outlined destructive action — red text, hairline red border. */
@Composable
fun DangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 44.dp),
        enabled = enabled,
        shape = RoundedCornerShape(9.dp),
        border = BorderStroke(1.dp, MaterialTheme.nexus.redBorder),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.nexus.surface,
            contentColor = MaterialTheme.nexus.red
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.nexus.inkMuted)
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Row-sized outlined button for list rows. */
@Composable
fun CompactButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 36.dp),
        enabled = enabled,
        shape = RoundedCornerShape(9.dp),
        border = BorderStroke(1.dp, MaterialTheme.nexus.borderStrong),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.nexus.surface,
            contentColor = MaterialTheme.nexus.ink
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(text = text, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * Two-option segmented control. Selection uses the same green tint as the
 * desktop's active navigation item.
 */
@Composable
fun SegmentedTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val p = MaterialTheme.nexus
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.surface)
            .border(1.dp, p.border, shape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val itemShape = RoundedCornerShape(8.dp)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(itemShape)
                    .background(if (selected) p.greenContainer else Color.Transparent)
                    .then(
                        if (selected) Modifier.border(1.dp, p.greenBorder, itemShape) else Modifier
                    )
                    .clickable { onSelect(index) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) p.onGreenContainer else p.inkMuted
                )
            }
        }
    }
}

// ---- rows -----------------------------------------------------------------

/** Hairline-separated label/value row. */
@Composable
fun KeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.nexus.inkMuted,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.nexus.ink,
            textAlign = TextAlign.End
        )
    }
}

/**
 * Inset info row: title + supporting copy, an optional trailing value and an
 * optional chevron. Clickable only when [onClick] is supplied, so a row never
 * looks interactive without doing something.
 */
@Composable
fun InfoRow(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    onClick: (() -> Unit)? = null
) {
    val p = MaterialTheme.nexus
    val shape = RoundedCornerShape(9.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.surfaceMuted)
            .border(1.dp, p.border, shape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = p.ink
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = p.inkMuted,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = p.inkMuted
            )
        }
        if (onClick != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = p.inkFaint,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ---- empty states ---------------------------------------------------------

@Composable
fun EmptyState(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val p = MaterialTheme.nexus
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 26.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(p.surfaceMuted)
                    .border(1.dp, p.border, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) { icon() }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = p.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = if (icon != null) 12.dp else 0.dp)
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = p.inkMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 3.dp)
        )
        if (actionLabel != null && onAction != null) {
            SecondaryButton(
                text = actionLabel,
                onClick = onAction,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}

// ---- header ---------------------------------------------------------------

/** Page header: title + optional subtitle, with a settings shortcut. */
@Composable
fun NexusTopBar(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    onOpenSettings: (() -> Unit)? = null
) {
    val p = MaterialTheme.nexus
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(p.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = p.ink
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = p.inkMuted,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        if (onOpenSettings != null) {
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = p.inkMuted,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

// ---- device glyphs --------------------------------------------------------

/** Rounded-square platform badge holding a notebook or phone outline. */
@Composable
fun DeviceGlyph(
    platform: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp
) {
    val p = MaterialTheme.nexus
    val shape = RoundedCornerShape(size * 0.28f)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(p.surfaceMuted)
            .border(1.dp, p.border, shape),
        contentAlignment = Alignment.Center
    ) {
        if (isPhonePlatform(platform)) {
            PhoneIcon(modifier = Modifier.size(size * 0.46f), tint = p.inkMuted)
        } else {
            LaptopIcon(modifier = Modifier.size(size * 0.54f), tint = p.inkMuted)
        }
    }
}

/**
 * Laptop outline. Drawn rather than imported: the icon dependency that ships
 * with Material 3 has no notebook glyph, and the brief asks for a simple
 * outline illustration of a computer and a phone.
 */
@Composable
fun LaptopIcon(modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.075f
        val screenW = size.width * 0.76f
        val screenH = size.height * 0.58f
        val left = (size.width - screenW) / 2f
        val top = size.height * 0.12f
        val corner = stroke * 1.6f
        drawRoundRect(
            color = tint,
            topLeft = Offset(left, top),
            size = Size(screenW, screenH),
            cornerRadius = CornerRadius(corner, corner),
            style = Stroke(width = stroke)
        )
        val baseY = (top + screenH + size.height * 0.13f).coerceAtMost(size.height - stroke)
        drawLine(
            color = tint,
            start = Offset(size.width * 0.06f, baseY),
            end = Offset(size.width * 0.94f, baseY),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

/** Phone outline. */
@Composable
fun PhoneIcon(modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.09f
        val bodyW = size.width * 0.64f
        val bodyH = size.height * 0.94f
        val left = (size.width - bodyW) / 2f
        val top = (size.height - bodyH) / 2f
        val corner = bodyW * 0.24f
        drawRoundRect(
            color = tint,
            topLeft = Offset(left, top),
            size = Size(bodyW, bodyH),
            cornerRadius = CornerRadius(corner, corner),
            style = Stroke(width = stroke)
        )
        drawLine(
            color = tint,
            start = Offset(left + bodyW * 0.33f, top + bodyH * 0.86f),
            end = Offset(left + bodyW * 0.67f, top + bodyH * 0.86f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

@Composable
fun ConnectionDots(modifier: Modifier = Modifier) {
    val p = MaterialTheme.nexus
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        StatusDot(color = p.inkFaint, size = 6.dp)
        StatusDot(color = p.green, size = 8.dp)
        StatusDot(color = p.inkFaint, size = 6.dp)
    }
}

/** "Laptop — local link — phone" hero illustration. */
@Composable
fun ConnectionIllustration(modifier: Modifier = Modifier) {
    val p = MaterialTheme.nexus
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        LaptopIcon(modifier = Modifier.size(50.dp), tint = p.inkMuted)
        ConnectionDots()
        PhoneIcon(modifier = Modifier.size(34.dp), tint = p.inkMuted)
    }
}

// ---- helpers --------------------------------------------------------------

/** Platform keys announced by discovery are lowercase protocol values. */
fun isPhonePlatform(platform: String): Boolean {
    val value = platform.lowercase()
    return value.startsWith("android") || value.startsWith("ios") || value.startsWith("phone")
}

/** Human label for a peer's platform, e.g. "windows" -> "Windows". */
fun platformLabel(platform: String): String = when (platform.lowercase()) {
    "" -> "Unknown device"
    "android" -> "Android"
    "ios" -> "iPhone"
    "windows", "win32" -> "Windows"
    "macos", "mac", "darwin" -> "macOS"
    "linux" -> "Linux"
    "desktop" -> "Computer"
    else -> platform.replaceFirstChar { it.uppercase() }
}

/** A short, ellipsised preview of clipboard text. */
fun shorten(text: String, max: Int = 72): String =
    if (text.length <= max) text else text.take(max - 1).trimEnd() + "…"
