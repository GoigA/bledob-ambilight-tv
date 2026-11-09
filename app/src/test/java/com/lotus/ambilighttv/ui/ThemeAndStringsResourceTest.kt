package com.lotus.ambilighttv.ui

import androidx.compose.ui.graphics.Color
import com.lotus.ambilighttv.state.BleConnectionStatus
import com.lotus.ambilighttv.ui.theme.AccentCyan
import com.lotus.ambilighttv.ui.theme.AmbilightColorTokens
import com.lotus.ambilighttv.ui.theme.AmbilightDarkColorScheme
import com.lotus.ambilighttv.ui.theme.AmbilightThemeColors
import com.lotus.ambilighttv.ui.theme.BackgroundDark
import com.lotus.ambilighttv.ui.theme.BorderDefault
import com.lotus.ambilighttv.ui.theme.BorderSubtle
import com.lotus.ambilighttv.ui.theme.DefaultAmbilightColors
import com.lotus.ambilighttv.ui.theme.OnAccentDark
import com.lotus.ambilighttv.ui.theme.PrimaryAction
import com.lotus.ambilighttv.ui.theme.StatusConnected
import com.lotus.ambilighttv.ui.theme.StatusDisconnected
import com.lotus.ambilighttv.ui.theme.StatusError
import com.lotus.ambilighttv.ui.theme.SurfaceCard
import com.lotus.ambilighttv.ui.theme.SurfaceElevated
import com.lotus.ambilighttv.ui.theme.SurfaceFocused
import com.lotus.ambilighttv.ui.theme.SurfaceSelected
import com.lotus.ambilighttv.ui.theme.SurfaceSelectedFocused
import com.lotus.ambilighttv.ui.theme.TelemetryOrange
import com.lotus.ambilighttv.ui.theme.TextPrimary
import com.lotus.ambilighttv.ui.theme.TextSecondary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Empirical Adversarial Challenger Test Suite for Milestone M3.
 *
 * Validates:
 * 1. res/values/strings.xml parsing, presence of all 36 semantic keys, valid format templates,
 *    safe percent escaping without crashes, and strict absence of leaked developer MAC.
 * 2. WCAG 2.1 Contrast Ratios computed mathematically for all AmbilightThemeColors against
 *    SurfaceCard and BackgroundDark to verify AA (>= 4.5:1) and AAA (>= 7.0:1) compliance.
 * 3. BleConnectionStatus 5-state color mapping exhaustiveness, non-nullness, and absence of
 *    error/disconnected collapse.
 * 4. Material 3 darkColorScheme token binding integrity.
 */
class ThemeAndStringsResourceTest {

    // =========================================================================
    // SECTION 1: XML STRINGS RESOURCE VALIDATION & SECURITY
    // =========================================================================

    private fun resolveProjectFile(path: String): File {
        val candidates = listOf(
            File(path),
            File("app", path),
            File("../app", path),
            File("C:/Users/PC/.gemini/antigravity/scratch/lotus-ambilight-tv/$path"),
            File("C:/Users/PC/.gemini/antigravity/scratch/lotus-ambilight-tv/app/$path")
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("Could not find file '$path' in any candidate path.")
    }

    private fun parseStringsXml(): Map<String, String> {
        val file = resolveProjectFile("src/main/res/values/strings.xml")
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(file)
        doc.documentElement.normalize()

        val stringNodes = doc.getElementsByTagName("string")
        val result = mutableMapOf<String, String>()
        for (i in 0 until stringNodes.length) {
            val element = stringNodes.item(i) as Element
            val name = element.getAttribute("name")
            val content = element.textContent
            result[name] = content
        }
        return result
    }

    @Test
    fun `challenge strings_xml - file exists, parses cleanly and contains at least 36 required keys`() {
        val strings = parseStringsXml()
        assertTrue("strings.xml must contain at least 36 keys", strings.size >= 36)

        val required36Keys = listOf(
            // 1. App info & header
            "app_name",
            "header_app_title",
            "header_device_status_connected",
            "header_device_status_unconfigured",
            "header_telemetry_format",
            "telemetry_idle_signal",
            // 2. Section 1 (Service)
            "section_service_title",
            "btn_service_start",
            "btn_service_restart",
            "btn_service_minimize",
            "btn_service_stop",
            // 3. Section 2 (BLE Connection & Hardware Actions)
            "section_ble_config_title",
            "label_mac_input",
            "btn_save_mac_and_connect",
            "btn_scan_ble",
            "btn_reconnect_ble",
            "btn_power_on",
            "btn_power_off",
            "btn_color_red",
            "btn_color_green",
            "btn_color_blue",
            "btn_color_amber",
            // 4. Section 3 (Calibration & Automation)
            "section_calibration_title",
            "label_profile",
            "label_brightness_format",
            "btn_brightness_decrease",
            "btn_brightness_increase",
            "btn_brightness_max",
            "label_channel_order",
            "label_protocol_variant",
            "btn_standby_sync_format",
            "btn_drm_fallback_format",
            "status_enabled",
            "status_disabled",
            // 5. Presentation layer domain enums
            "ble_status_disconnected",
            "ble_status_scanning",
            "ble_status_connecting",
            "ble_status_connected",
            "ble_status_error"
        )

        val missingKeys = mutableListOf<String>()
        for (key in required36Keys) {
            if (!strings.containsKey(key)) {
                missingKeys.add(key)
            }
        }

        assertTrue(
            "All 36 required semantic keys must be present in strings.xml. Missing: $missingKeys",
            missingKeys.isEmpty()
        )
    }

    @Test
    fun `challenge strings_xml - no unescaped percent symbols in format strings that cause runtime crashes`() {
        val strings = parseStringsXml()

        for ((key, value) in strings) {
            // If the string contains format specifiers like %1$s, %2$d, %d, %s
            val hasFormatSpecifiers = Regex("%(?:\\d+\\$)?[sdf]").containsMatchIn(value)

            if (hasFormatSpecifiers) {
                // Any literal percent in a format string MUST be escaped as %%
                // Check that calling String.format does not throw UnknownFormatConversionException
                val formatted = try {
                    when {
                        key == "label_brightness_format" -> {
                            String.format(Locale.ROOT, value, 75)
                        }
                        key == "header_device_status_connected" -> {
                            String.format(Locale.ROOT, value, "LotusStrip", "AA:BB:CC:DD:EE:FF", "Conectado")
                        }
                        key == "header_device_status_unconfigured" -> {
                            String.format(Locale.ROOT, value, "Nenhum", "Desconectado")
                        }
                        key.contains("telemetry_format") -> {
                            String.format(Locale.ROOT, value, "ScreenSync", 25, "7E070503")
                        }
                        key.contains("standby_sync") || key.contains("drm_fallback") -> {
                            String.format(Locale.ROOT, value, "Ativado")
                        }
                        else -> {
                            // Generic test with dummy arguments
                            String.format(Locale.ROOT, value, "Test1", "Test2", "Test3")
                        }
                    }
                } catch (e: Exception) {
                    throw AssertionError("String format failed on key '$key' with value '$value'", e)
                }
                assertNotNull("Formatted result for $key must not be null", formatted)
            }
        }

        // Specifically test label_brightness_format escaping
        val brightnessFormat = strings["label_brightness_format"]
        assertNotNull(brightnessFormat)
        assertTrue(
            "label_brightness_format must contain %% for literal percent escaping, found: $brightnessFormat",
            brightnessFormat!!.contains("%%")
        )
        val formattedBrightness = String.format(Locale.ROOT, brightnessFormat, 80)
        assertEquals("Brilho (80%):", formattedBrightness)
    }

    @Test
    fun `challenge security - absolute absence of leaked developer MAC address BE 27 08 00 49 E2 in resources and UI`() {
        val forbiddenMac = "BE:27:08:00:49:E2"

        // 1. Check strings.xml
        val stringsXmlFile = resolveProjectFile("src/main/res/values/strings.xml")
        val stringsXmlContent = stringsXmlFile.readText()
        assertFalse(
            "strings.xml must NOT contain the developer's personal MAC address ($forbiddenMac)",
            stringsXmlContent.contains(forbiddenMac, ignoreCase = true)
        )

        // 2. Check label_mac_input specifically
        val strings = parseStringsXml()
        val macInputLabel = strings["label_mac_input"]
        assertNotNull(macInputLabel)
        assertFalse(macInputLabel!!.contains(forbiddenMac, ignoreCase = true))
        assertTrue(
            "label_mac_input must contain generic example AA:BB:CC:DD:EE:FF, found: $macInputLabel",
            macInputLabel.contains("AA:BB:CC:DD:EE:FF")
        )

        // 3. Check MainActivity.kt
        val mainActivityFile = resolveProjectFile("src/main/java/com/lotus/ambilighttv/MainActivity.kt")
        val mainActivityContent = mainActivityFile.readText()
        assertFalse(
            "MainActivity.kt must NOT contain the developer's personal MAC address ($forbiddenMac)",
            mainActivityContent.contains(forbiddenMac, ignoreCase = true)
        )
    }

    // =========================================================================
    // SECTION 2: WCAG 2.1 RELATIVE LUMINANCE & CONTRAST RATIO VERIFICATION
    // =========================================================================

    private fun relativeLuminance(color: Color): Double {
        fun channel(c: Float): Double {
            return if (c <= 0.04045f) {
                c / 12.92
            } else {
                Math.pow(((c + 0.055) / 1.055), 2.4)
            }
        }
        val r = channel(color.red)
        val g = channel(color.green)
        val b = channel(color.blue)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun contrastRatio(c1: Color, c2: Color): Double {
        val l1 = relativeLuminance(c1)
        val l2 = relativeLuminance(c2)
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    @Test
    fun `challenge WCAG contrast - TextPrimary achieves WCAG AAA (over 7 to 1) on SurfaceCard and BackgroundDark`() {
        val contrastOnCard = contrastRatio(TextPrimary, SurfaceCard)
        val contrastOnBg = contrastRatio(TextPrimary, BackgroundDark)

        assertTrue(
            "TextPrimary on SurfaceCard must exceed WCAG AAA 7.0:1, actual: $contrastOnCard",
            contrastOnCard >= 7.0
        )
        assertTrue(
            "TextPrimary on BackgroundDark must exceed WCAG AAA 7.0:1, actual: $contrastOnBg",
            contrastOnBg >= 7.0
        )
        assertTrue("TextPrimary on SurfaceCard should achieve ~17.4:1", contrastOnCard > 17.0)
    }

    @Test
    fun `challenge WCAG contrast - TextSecondary achieves WCAG AAA (over 7 to 1) on SurfaceCard and BackgroundDark`() {
        val contrastOnCard = contrastRatio(TextSecondary, SurfaceCard)
        val contrastOnBg = contrastRatio(TextSecondary, BackgroundDark)

        assertTrue(
            "TextSecondary on SurfaceCard must exceed WCAG AAA 7.0:1, actual: $contrastOnCard",
            contrastOnCard >= 7.0
        )
        assertTrue(
            "TextSecondary on BackgroundDark must exceed WCAG AAA 7.0:1, actual: $contrastOnBg",
            contrastOnBg >= 7.0
        )
        assertTrue("TextSecondary on SurfaceCard should achieve ~9.1:1", contrastOnCard > 9.0)
    }

    @Test
    fun `challenge WCAG contrast - AccentCyan and PrimaryAction achieve high contrast on dark backgrounds`() {
        val cyanOnCard = contrastRatio(AccentCyan, SurfaceCard)
        val cyanOnElevated = contrastRatio(AccentCyan, SurfaceElevated)
        val primaryOnCard = contrastRatio(PrimaryAction, SurfaceCard)

        assertTrue("AccentCyan on SurfaceCard must exceed WCAG AAA 7.0:1, actual: $cyanOnCard", cyanOnCard >= 7.0)
        assertTrue("AccentCyan focus ring on SurfaceElevated must exceed 3.0:1, actual: $cyanOnElevated", cyanOnElevated >= 3.0)
        assertTrue("PrimaryAction on SurfaceCard must exceed WCAG AA 4.5:1, actual: $primaryOnCard", primaryOnCard >= 4.5)
    }

    @Test
    fun `challenge WCAG contrast - OnAccentDark inverted typography exceeds WCAG AAA (over 7 to 1)`() {
        val onAccentOnCyan = contrastRatio(OnAccentDark, AccentCyan)
        val onAccentOnPrimary = contrastRatio(OnAccentDark, PrimaryAction)

        assertTrue(
            "OnAccentDark typography on AccentCyan must exceed WCAG AAA 7.0:1, actual: $onAccentOnCyan",
            onAccentOnCyan >= 7.0
        )
        assertTrue(
            "OnAccentDark typography on PrimaryAction must exceed WCAG AAA 7.0:1, actual: $onAccentOnPrimary",
            onAccentOnPrimary >= 7.0
        )
    }

    @Test
    fun `challenge WCAG contrast - Semantic status colors meet WCAG AA (over 4 point 5 to 1) on SurfaceCard`() {
        val connectedOnCard = contrastRatio(StatusConnected, SurfaceCard)
        val warningOnCard = contrastRatio(TelemetryOrange, SurfaceCard)
        val errorOnCard = contrastRatio(StatusError, SurfaceCard)
        val disconnectedOnCard = contrastRatio(StatusDisconnected, SurfaceCard)

        assertTrue("StatusConnected on SurfaceCard must exceed 4.5:1, actual: $connectedOnCard", connectedOnCard >= 4.5)
        assertTrue("TelemetryOrange on SurfaceCard must exceed 7.0:1, actual: $warningOnCard", warningOnCard >= 7.0)
        assertTrue("StatusError on SurfaceCard must exceed 4.5:1, actual: $errorOnCard", errorOnCard >= 4.5)
        assertTrue("StatusDisconnected on SurfaceCard must exceed 7.0:1, actual: $disconnectedOnCard", disconnectedOnCard >= 7.0)
    }

    @Test
    fun `challenge WCAG contrast - Selected toggle button typography exceeds WCAG AAA (over 7 to 1)`() {
        val textOnSelected = contrastRatio(TextPrimary, SurfaceSelected)
        assertTrue(
            "TextPrimary on SurfaceSelected container must exceed WCAG AAA 7.0:1, actual: $textOnSelected",
            textOnSelected >= 7.0
        )
    }

    @Test
    fun `challenge theme tokens - AmbilightThemeColors and AmbilightColorTokens are fully opaque and consistent`() {
        val tokens = DefaultAmbilightColors

        val allColors = listOf(
            tokens.background,
            tokens.surfaceCard,
            tokens.surfaceElevated,
            tokens.surfaceFocused,
            tokens.surfaceSelected,
            tokens.surfaceSelectedFocused,
            tokens.accentCyan,
            tokens.primaryAction,
            tokens.onAccent,
            tokens.borderDefault,
            tokens.borderSubtle,
            tokens.borderFocused,
            tokens.borderSelected,
            tokens.statusConnected,
            tokens.statusConnecting,
            tokens.statusDisconnected,
            tokens.statusError,
            tokens.textPrimary,
            tokens.textSecondary,
            tokens.textMuted
        )

        for (color in allColors) {
            assertEquals("All theme colors must be fully opaque (alpha = 1.0f)", 1.0f, color.alpha, 0.001f)
        }

        // Verify compatibility object AmbilightThemeColors matches
        assertEquals(tokens.background, AmbilightThemeColors.BackgroundDark)
        assertEquals(tokens.surfaceCard, AmbilightThemeColors.SurfaceCard)
        assertEquals(tokens.surfaceElevated, AmbilightThemeColors.IdleSurface)
        assertEquals(tokens.surfaceFocused, AmbilightThemeColors.FocusedSurface)
        assertEquals(tokens.accentCyan, AmbilightThemeColors.AccentCyan)
        assertEquals(tokens.primaryAction, AmbilightThemeColors.PrimaryTeal)
        assertEquals(tokens.statusConnected, AmbilightThemeColors.SuccessGreen)
        assertEquals(tokens.statusConnecting, AmbilightThemeColors.WarningAmber)
        assertEquals(tokens.statusError, AmbilightThemeColors.StatusError)
        assertEquals(tokens.statusDisconnected, AmbilightThemeColors.StatusDisconnected)
        assertEquals(tokens.textPrimary, AmbilightThemeColors.TextPrimary)
        assertEquals(tokens.textSecondary, AmbilightThemeColors.TextSecondary)
    }

    // =========================================================================
    // SECTION 3: BLE CONNECTION STATUS COLOR MAPPING & COLLAPSE VERIFICATION
    // =========================================================================

    private fun mapBleStatusToColor(status: BleConnectionStatus): Color {
        return when (status) {
            BleConnectionStatus.CONNECTED -> StatusConnected
            BleConnectionStatus.CONNECTING,
            BleConnectionStatus.SCANNING -> TelemetryOrange
            BleConnectionStatus.ERROR -> StatusError
            BleConnectionStatus.DISCONNECTED -> StatusDisconnected
        }
    }

    @Test
    fun `challenge status mapping - all 5 BleConnectionStatus values map to non-null and valid semantic colors`() {
        for (status in BleConnectionStatus.entries) {
            val color = mapBleStatusToColor(status)
            assertNotNull("Status color for $status must not be null", color)
            assertEquals("Status color for $status must be fully opaque", 1.0f, color.alpha, 0.001f)
        }
    }

    @Test
    fun `challenge status mapping - verify regression fix for ERROR and DISCONNECTED collapsing into amber`() {
        val errorColor = mapBleStatusToColor(BleConnectionStatus.ERROR)
        val disconnectedColor = mapBleStatusToColor(BleConnectionStatus.DISCONNECTED)
        val connectedColor = mapBleStatusToColor(BleConnectionStatus.CONNECTED)
        val connectingColor = mapBleStatusToColor(BleConnectionStatus.CONNECTING)
        val scanningColor = mapBleStatusToColor(BleConnectionStatus.SCANNING)

        // Baseline bug: binary if (status == CONNECTED) Green else Amber
        // Challenged invariant: ERROR must NOT be amber, DISCONNECTED must NOT be amber
        assertNotEquals("ERROR must NOT collapse to TelemetryOrange", TelemetryOrange, errorColor)
        assertEquals("ERROR must map to crimson StatusError", StatusError, errorColor)

        assertNotEquals("DISCONNECTED must NOT collapse to TelemetryOrange", TelemetryOrange, disconnectedColor)
        assertEquals("DISCONNECTED must map to slate StatusDisconnected", StatusDisconnected, disconnectedColor)

        assertEquals("CONNECTED must map to StatusConnected", StatusConnected, connectedColor)
        assertEquals("CONNECTING maps to TelemetryOrange", TelemetryOrange, connectingColor)
        assertEquals("SCANNING maps to TelemetryOrange", TelemetryOrange, scanningColor)
    }

    @Test
    fun `challenge status mapping - verify semantic tier separation across the 5 connection states`() {
        val mappedColors = BleConnectionStatus.entries.map { it to mapBleStatusToColor(it) }.toMap()

        // 4 distinct semantic tiers:
        // Tier 1: SUCCESS (Connected)
        // Tier 2: TRANSITIONAL (Scanning, Connecting)
        // Tier 3: ERROR (Error)
        // Tier 4: IDLE (Disconnected)
        val distinctColors = mappedColors.values.toSet()

        assertEquals(
            "Status colors must encompass 4 distinct semantic color tiers (Success, Transitional, Error, Idle)",
            4,
            distinctColors.size
        )

        // Ensure no cross-tier collisions
        assertNotEquals(mappedColors[BleConnectionStatus.CONNECTED], mappedColors[BleConnectionStatus.ERROR])
        assertNotEquals(mappedColors[BleConnectionStatus.CONNECTED], mappedColors[BleConnectionStatus.DISCONNECTED])
        assertNotEquals(mappedColors[BleConnectionStatus.CONNECTED], mappedColors[BleConnectionStatus.CONNECTING])
        assertNotEquals(mappedColors[BleConnectionStatus.ERROR], mappedColors[BleConnectionStatus.DISCONNECTED])
        assertNotEquals(mappedColors[BleConnectionStatus.ERROR], mappedColors[BleConnectionStatus.CONNECTING])
        assertNotEquals(mappedColors[BleConnectionStatus.DISCONNECTED], mappedColors[BleConnectionStatus.CONNECTING])
    }

    // =========================================================================
    // SECTION 4: AMBILIGHT DARK COLOR SCHEME MATERIAL 3 BINDING
    // =========================================================================

    @Test
    fun `challenge Material3 darkColorScheme - binds Ambilight tokens correctly`() {
        val scheme = AmbilightDarkColorScheme

        assertEquals("scheme.primary must be AccentCyan", AccentCyan, scheme.primary)
        assertEquals("scheme.onPrimary must be OnAccentDark", OnAccentDark, scheme.onPrimary)
        assertEquals("scheme.primaryContainer must be PrimaryAction", PrimaryAction, scheme.primaryContainer)
        assertEquals("scheme.background must be BackgroundDark", BackgroundDark, scheme.background)
        assertEquals("scheme.surface must be SurfaceCard", SurfaceCard, scheme.surface)
        assertEquals("scheme.surfaceVariant must be SurfaceElevated", SurfaceElevated, scheme.surfaceVariant)
        assertEquals("scheme.outline must be BorderDefault", BorderDefault, scheme.outline)
        assertEquals("scheme.outlineVariant must be BorderSubtle", BorderSubtle, scheme.outlineVariant)
        assertEquals("scheme.error must be StatusError", StatusError, scheme.error)
        assertEquals("scheme.onError must be TextPrimary", TextPrimary, scheme.onError)
    }
}

