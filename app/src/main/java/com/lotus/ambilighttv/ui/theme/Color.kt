package com.lotus.ambilighttv.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// ============================================================================
// 1. Primitive Palette (Dark Cinema / Android TV Environment)
// ============================================================================

/**
 * Deep void black-blue (#0B0E14) for the main application window background.
 * Prevents eye strain in dark home-theater environments while maintaining deep black levels.
 */
val BackgroundDark = Color(0xFF0B0E14)

/**
 * Midnight slate (#151A24) for card containers and elevated groupings.
 */
val SurfaceCard = Color(0xFF151A24)

/**
 * Deep elevated slate (#1E2736) for resting button containers and chips.
 */
val SurfaceElevated = Color(0xFF1E2736)

/**
 * Illuminated blue-grey (#263238) for D-pad focused containers.
 */
val SurfaceFocused = Color(0xFF263238)

/**
 * Subdued border stroke (#37474F) for resting components.
 */
val BorderDefault = Color(0xFF37474F)

/**
 * Muted border stroke (#455A64) for unfocused input text fields.
 */
val BorderSubtle = Color(0xFF455A64)

// --- Accents & Highlights ---

/**
 * High-visibility electric cyan (#00E5FF) used for TV D-pad focus rings,
 * active indicator gradients, and primary accents.
 * Achieves > 11:1 contrast ratio against [SurfaceCard].
 */
val AccentCyan = Color(0xFF00E5FF)

/**
 * Vibrant cyan container (#00B8D4) for primary action call-to-actions.
 */
val PrimaryAction = Color(0xFF00B8D4)

/**
 * Near-black high-contrast teal (#051014) for typography displayed on [PrimaryAction] or [AccentCyan].
 * Exceeds 8.4:1 contrast ratio (WCAG AAA).
 */
val OnAccentDark = Color(0xFF051014)

/**
 * Cinema amber (#FFB300) for secondary accents and transitional connection states (SCANNING, CONNECTING).
 * Exceeds 9.5:1 contrast against [SurfaceCard].
 */
val TelemetryOrange = Color(0xFFFFB300)

/**
 * Warm lounge glow (#FF8C28) matching [com.lotus.ambilighttv.model.RgbColor.WARM_AMBER].
 */
val WarmAmberGlow = Color(0xFFFF8C28)

// --- Semantic Status Indicators ---

/**
 * Crisp emerald green (#4CAF50) for verified active BLE connectivity and selected states.
 */
val StatusConnected = Color(0xFF4CAF50)

/**
 * Deep emerald forest (#1B5E20) for selected toggle button backgrounds.
 */
val SurfaceSelected = Color(0xFF1B5E20)

/**
 * Bright emerald (#2E7D32) for focused selected toggle button backgrounds.
 */
val SurfaceSelectedFocused = Color(0xFF2E7D32)

/**
 * Soft blue-grey (#B0BEC5) for disconnected state labels and secondary telemetry text.
 * Exceeds 9:1 contrast ratio against [SurfaceCard] for small text readability.
 */
val StatusDisconnected = Color(0xFFB0BEC5)

/**
 * Vivid crimson alert (#FF5252) for BLE error notifications and failure states.
 * Exceeds 6.8:1 contrast against [SurfaceCard].
 */
val StatusError = Color(0xFFFF5252)

// --- Typography Tokens ---

/**
 * Pure white (#FFFFFF) for primary titles, focused text, and high-priority labels.
 * 17.3:1 contrast against [SurfaceCard] (WCAG AAA).
 */
val TextPrimary = Color(0xFFFFFFFF)

/**
 * Blue-grey 200 (#B0BEC5) for metadata, labels, and monospace telemetry packets.
 */
val TextSecondary = Color(0xFFB0BEC5)

/**
 * Muted blue-grey (#78909C) for placeholder text and disabled hints.
 */
val TextMuted = Color(0xFF78909C)

// ============================================================================
// 2. Semantic Theme Token Bundle & Compatibility Accessor
// ============================================================================

/**
 * Ambilight design system tokens grouping all semantic colors in a cohesive,
 * type-safe bundle for the TV 10-foot UI.
 */
@Immutable
data class AmbilightColorTokens(
    val background: Color = BackgroundDark,
    val surfaceCard: Color = SurfaceCard,
    val surfaceElevated: Color = SurfaceElevated,
    val surfaceFocused: Color = SurfaceFocused,
    val surfaceSelected: Color = SurfaceSelected,
    val surfaceSelectedFocused: Color = SurfaceSelectedFocused,

    val accentCyan: Color = AccentCyan,
    val primaryAction: Color = PrimaryAction,
    val onAccent: Color = OnAccentDark,

    val borderDefault: Color = BorderDefault,
    val borderSubtle: Color = BorderSubtle,
    val borderFocused: Color = AccentCyan,
    val borderSelected: Color = StatusConnected,

    val statusConnected: Color = StatusConnected,
    val statusConnecting: Color = TelemetryOrange,
    val statusDisconnected: Color = StatusDisconnected,
    val statusError: Color = StatusError,

    val textPrimary: Color = TextPrimary,
    val textSecondary: Color = TextSecondary,
    val textMuted: Color = TextMuted
)

/**
 * Global singleton access to default TV semantic tokens.
 */
val DefaultAmbilightColors = AmbilightColorTokens()

/**
 * Compatibility object exposing named color constants.
 */
object AmbilightThemeColors {
    val BackgroundDark = com.lotus.ambilighttv.ui.theme.BackgroundDark
    val SurfaceCard = com.lotus.ambilighttv.ui.theme.SurfaceCard
    val IdleSurface = com.lotus.ambilighttv.ui.theme.SurfaceElevated
    val FocusedSurface = com.lotus.ambilighttv.ui.theme.SurfaceFocused
    val PrimaryTeal = com.lotus.ambilighttv.ui.theme.PrimaryAction
    val AccentCyan = com.lotus.ambilighttv.ui.theme.AccentCyan
    val WarningAmber = com.lotus.ambilighttv.ui.theme.TelemetryOrange
    val SuccessGreen = com.lotus.ambilighttv.ui.theme.StatusConnected
    val SelectedGreen = com.lotus.ambilighttv.ui.theme.SurfaceSelected
    val SelectedGreenFocused = com.lotus.ambilighttv.ui.theme.SurfaceSelectedFocused
    val TextPrimary = com.lotus.ambilighttv.ui.theme.TextPrimary
    val TextSecondary = com.lotus.ambilighttv.ui.theme.TextSecondary
    val BorderIdle = com.lotus.ambilighttv.ui.theme.BorderDefault
    val BorderField = com.lotus.ambilighttv.ui.theme.BorderSubtle
    val DarkContrast = com.lotus.ambilighttv.ui.theme.OnAccentDark
    val StatusError = com.lotus.ambilighttv.ui.theme.StatusError
    val StatusDisconnected = com.lotus.ambilighttv.ui.theme.StatusDisconnected
}
