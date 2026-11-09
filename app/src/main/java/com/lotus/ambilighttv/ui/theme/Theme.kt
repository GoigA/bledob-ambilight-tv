package com.lotus.ambilighttv.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * CompositionLocal holding current [AmbilightColorTokens].
 */
val LocalAmbilightColors = staticCompositionLocalOf { DefaultAmbilightColors }

/**
 * Semantic accessor for [AmbilightColorTokens] in Composable scopes.
 */
val MaterialTheme.ambilightColors: AmbilightColorTokens
    @Composable
    @ReadOnlyComposable
    get() = LocalAmbilightColors.current

/**
 * Material 3 Dark Color Scheme mapped directly to our semantic Ambilight tokens.
 */
val AmbilightDarkColorScheme = darkColorScheme(
    primary = AccentCyan,
    onPrimary = OnAccentDark,
    primaryContainer = PrimaryAction,
    onPrimaryContainer = TextPrimary,
    secondary = TelemetryOrange,
    onSecondary = OnAccentDark,
    background = BackgroundDark,
    onBackground = TextPrimary,
    surface = SurfaceCard,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = BorderDefault,
    outlineVariant = BorderSubtle,
    error = StatusError,
    onError = TextPrimary
)

/**
 * Lotus Ambilight TV Root Theme.
 *
 * Configures Material 3 with cinema-optimized dark contrast ratios and provides
 * [AmbilightColorTokens] through [LocalAmbilightColors].
 */
@Composable
fun LotusAmbilightTheme(
    tokens: AmbilightColorTokens = DefaultAmbilightColors,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalAmbilightColors provides tokens) {
        MaterialTheme(
            colorScheme = AmbilightDarkColorScheme,
            content = content
        )
    }
}

/**
 * Convenience alias for [LotusAmbilightTheme].
 */
@Composable
fun AmbilightTheme(
    tokens: AmbilightColorTokens = DefaultAmbilightColors,
    content: @Composable () -> Unit
) {
    LotusAmbilightTheme(tokens = tokens, content = content)
}
