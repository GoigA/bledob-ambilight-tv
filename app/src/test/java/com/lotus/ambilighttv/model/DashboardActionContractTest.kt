package com.lotus.ambilighttv.model

import com.lotus.ambilighttv.ble.BleDobProtocol
import com.lotus.ambilighttv.engine.VividEdgeColorEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Empirical Adversarial Challenger Test Suite for Milestone M3.
 *
 * Stress-tests DashboardAction sealed hierarchy, reflection exhaustiveness,
 * value class interop, boundary conditions, concurrency, and UDF dispatch contracts.
 */
class DashboardActionContractTest {

    // =========================================================================
    // SECTION 1: EXHAUSTIVE SEALED SUBCLASS REFLECTION & STRUCTURE
    // =========================================================================

    @Test
    fun `challenge reflection - all declared classes under DashboardAction implement the sealed interface`() {
        val declaredClasses = DashboardAction::class.java.declaredClasses
        val actionSubclasses = declaredClasses.filter {
            DashboardAction::class.java.isAssignableFrom(it)
        }

        assertEquals(
            "DashboardAction must have exactly 15 action subclasses declared",
            15,
            actionSubclasses.size
        )

        val expectedSubclassNames = setOf(
            "StartAmbilight",
            "StopAmbilight",
            "MinimizeToBackground",
            "ConnectBle",
            "ScanBle",
            "SaveMacAddress",
            "PowerOn",
            "PowerOff",
            "TestColor",
            "SelectProfile",
            "SelectChannelOrder",
            "SelectProtocolVariant",
            "ChangeBrightness",
            "ToggleStandbySync",
            "ToggleDrmFallback"
        )

        val actualSubclassSimpleNames = actionSubclasses.map { it.simpleName }.toSet()
        assertEquals(
            "All 15 expected subclasses must be present in declared classes",
            expectedSubclassNames,
            actualSubclassSimpleNames
        )
    }

    @Test
    fun `challenge data objects - verify 9 data object singletons have proper identity, string representation, and hash code`() {
        val dataObjects = listOf<DashboardAction>(
            DashboardAction.StartAmbilight,
            DashboardAction.StopAmbilight,
            DashboardAction.MinimizeToBackground,
            DashboardAction.ConnectBle,
            DashboardAction.ScanBle,
            DashboardAction.PowerOn,
            DashboardAction.PowerOff,
            DashboardAction.ToggleStandbySync,
            DashboardAction.ToggleDrmFallback
        )

        assertEquals("There must be exactly 9 data object singletons", 9, dataObjects.size)

        for (obj in dataObjects) {
            val simpleName = obj::class.java.simpleName
            assertEquals(
                "Data object toString should match its simple name",
                simpleName,
                obj.toString()
            )
            assertEquals("Data object must equal itself", obj, obj)
            assertFalse("Data object must not equal null", obj.equals(null))
            assertFalse("Data object must not equal another object", obj.equals(Any()))
            assertNotEquals("Hash code must be non-zero", 0, obj.hashCode())
        }

        // Verify distinctness across all singletons
        val distinctCount = dataObjects.distinct().size
        assertEquals("All 9 data objects must be distinct from each other", 9, distinctCount)
    }

    @Test
    fun `challenge data classes - verify 6 data classes have copy, destructuring, and proper equality`() {
        // 1. SaveMacAddress
        val mac1 = DashboardAction.SaveMacAddress("AA:BB:CC:DD:EE:FF")
        val (extractedMac) = mac1
        assertEquals("AA:BB:CC:DD:EE:FF", extractedMac)
        val mac2 = mac1.copy(mac = "11:22:33:44:55:66")
        assertNotEquals(mac1, mac2)
        assertEquals("11:22:33:44:55:66", mac2.mac)

        // 2. TestColor
        val color1 = DashboardAction.TestColor(RgbColor.WARM_AMBER)
        val (extractedColor) = color1
        assertEquals(RgbColor.WARM_AMBER, extractedColor)
        val color2 = color1.copy(color = RgbColor.BLUE)
        assertNotEquals(color1, color2)
        assertEquals(RgbColor.BLUE, color2.color)

        // 3. SelectProfile
        val prof1 = DashboardAction.SelectProfile(VividEdgeColorEngine.AmbilightProfile.CINEMA)
        val (extractedProf) = prof1
        assertEquals(VividEdgeColorEngine.AmbilightProfile.CINEMA, extractedProf)
        val prof2 = prof1.copy(profile = VividEdgeColorEngine.AmbilightProfile.GAMING)
        assertNotEquals(prof1, prof2)

        // 4. SelectChannelOrder
        val order1 = DashboardAction.SelectChannelOrder(BleDobProtocol.ChannelOrder.RGB)
        val (extractedOrder) = order1
        assertEquals(BleDobProtocol.ChannelOrder.RGB, extractedOrder)
        val order2 = order1.copy(order = BleDobProtocol.ChannelOrder.BGR)
        assertNotEquals(order1, order2)

        // 5. SelectProtocolVariant
        val var1 = DashboardAction.SelectProtocolVariant(BleDobProtocol.ProtocolVariant.BLEDOB_07)
        val (extractedVar) = var1
        assertEquals(BleDobProtocol.ProtocolVariant.BLEDOB_07, extractedVar)
        val var2 = var1.copy(variant = BleDobProtocol.ProtocolVariant.BLEDOM_04)
        assertNotEquals(var1, var2)

        // 6. ChangeBrightness
        val bright1 = DashboardAction.ChangeBrightness(75)
        val (extractedBright) = bright1
        assertEquals(75, extractedBright)
        val bright2 = bright1.copy(percent = 90)
        assertNotEquals(bright1, bright2)
        assertEquals(90, bright2.percent)
    }

    // =========================================================================
    // SECTION 2: VALUE CLASS INTEROPERABILITY & BOUNDARY STRESS
    // =========================================================================

    @Test
    fun `challenge SaveMacAddress - BleMacAddress companion invoke vs String primary constructor`() {
        val parsedMac = BleMacAddress.parse("be:27:08:aa:bb:cc")
        val actionFromValueClass = DashboardAction.SaveMacAddress(parsedMac)
        val actionFromString = DashboardAction.SaveMacAddress("BE:27:08:AA:BB:CC")

        assertEquals(
            "Companion invoke(BleMacAddress) must match primary constructor with formatted MAC",
            actionFromString,
            actionFromValueClass
        )
        assertEquals("BE:27:08:AA:BB:CC", actionFromValueClass.mac)
    }

    @Test
    fun `challenge SaveMacAddress - stress test adversarial and malformed inputs against normalization logic`() {
        // Exact normalization logic from MainActivity.kt:
        fun normalizeMac(input: String): String =
            BleMacAddress.fromOrNull(input)?.formatted ?: input.trim().uppercase()

        val validInputs = listOf(
            "aa:bb:cc:dd:ee:ff" to "AA:BB:CC:DD:EE:FF",
            "AA-BB-CC-DD-EE-FF" to "AA:BB:CC:DD:EE:FF",
            "  12:34:56:78:9A:BC  " to "12:34:56:78:9A:BC",
            "00:11:22:33:44:55" to "00:11:22:33:44:55"
        )

        for ((raw, expected) in validInputs) {
            val action = DashboardAction.SaveMacAddress(raw)
            assertEquals("Raw input must be preserved in action", raw, action.mac)
            val normalized = normalizeMac(action.mac)
            assertEquals("Normalization must format correctly", expected, normalized)
        }

        val adversarialInputs = listOf(
            "",
            "   ",
            "\t\n\r",
            "INVALID_MAC_STRING",
            "AA:BB:CC",
            "AA:BB:CC:DD:EE:FF:11",
            "GG:HH:II:JJ:KK:LL",
            "' OR 1=1; --",
            "<script>alert('xss')</script>",
            "A".repeat(10_000)
        )

        for (adversarial in adversarialInputs) {
            try {
                val action = DashboardAction.SaveMacAddress(adversarial)
                val normalized = normalizeMac(action.mac)
                assertNotNull("Normalization must never produce null", normalized)
                assertEquals(adversarial.trim().uppercase(), normalized)
            } catch (t: Throwable) {
                fail("Adversarial MAC '$adversarial' caused unexpected exception: ${t.message}")
            }
        }
    }

    // =========================================================================
    // SECTION 3: ZERO-ALLOCATION COLOR & CHANNEL BOUNDS STRESS
    // =========================================================================

    @Test
    fun `challenge TestColor - secondary constructor clamps out-of-range integer channels`() {
        val actionUnderflow = DashboardAction.TestColor(-50, -100, -1)
        assertEquals("Red channel must clamp -50 to 0", 0, actionUnderflow.color.r)
        assertEquals("Green channel must clamp -100 to 0", 0, actionUnderflow.color.g)
        assertEquals("Blue channel must clamp -1 to 0", 0, actionUnderflow.color.b)
        assertEquals(RgbColor.BLACK, actionUnderflow.color)

        val actionOverflow = DashboardAction.TestColor(300, 500, 1000)
        assertEquals("Red channel must clamp 300 to 255", 255, actionOverflow.color.r)
        assertEquals("Green channel must clamp 500 to 255", 255, actionOverflow.color.g)
        assertEquals("Blue channel must clamp 1000 to 255", 255, actionOverflow.color.b)
        assertEquals(RgbColor.WHITE, actionOverflow.color)

        val actionExtremeInts = DashboardAction.TestColor(Int.MIN_VALUE, 128, Int.MAX_VALUE)
        assertEquals(0, actionExtremeInts.color.r)
        assertEquals(128, actionExtremeInts.color.g)
        assertEquals(255, actionExtremeInts.color.b)
    }

    @Test
    fun `challenge TestColor - direct packed value preserves masked channel boundaries`() {
        // Even if an arbitrary 32-bit int with high bits set is given to RgbColor
        val colorWithGarbageAlpha = RgbColor(-1) // 0xFFFFFFFF
        val action = DashboardAction.TestColor(colorWithGarbageAlpha)
        assertEquals(255, action.color.r)
        assertEquals(255, action.color.g)
        assertEquals(255, action.color.b)

        val zeroColor = RgbColor(0)
        val zeroAction = DashboardAction.TestColor(zeroColor)
        assertEquals(0, zeroAction.color.r)
        assertEquals(0, zeroAction.color.g)
        assertEquals(0, zeroAction.color.b)
        assertEquals(RgbColor.OFF, zeroAction.color)
    }

    @Test
    fun `challenge TestColor - equality between secondary constructor and RgbColor presets`() {
        assertEquals(DashboardAction.TestColor(RgbColor.RED), DashboardAction.TestColor(255, 0, 0))
        assertEquals(DashboardAction.TestColor(RgbColor.GREEN), DashboardAction.TestColor(0, 255, 0))
        assertEquals(DashboardAction.TestColor(RgbColor.BLUE), DashboardAction.TestColor(0, 0, 255))
        assertEquals(DashboardAction.TestColor(RgbColor.WARM_AMBER), DashboardAction.TestColor(255, 140, 40))
        assertEquals(DashboardAction.TestColor(RgbColor.CINEMA_WARM), DashboardAction.TestColor(210, 115, 45))
        assertEquals(DashboardAction.TestColor(RgbColor.GAMING_CYAN), DashboardAction.TestColor(0, 229, 255))
        assertEquals(DashboardAction.TestColor(RgbColor.BLACK), DashboardAction.TestColor(0, 0, 0))
        assertEquals(DashboardAction.TestColor(RgbColor.WHITE), DashboardAction.TestColor(255, 255, 255))
    }

    // =========================================================================
    // SECTION 4: BRIGHTNESS BOUNDS & CLAMPING STRESS
    // =========================================================================

    @Test
    fun `challenge ChangeBrightness - boundary and clamping invariants`() {
        val testCases = listOf(
            -100 to 10,
            0 to 10,
            9 to 10,
            10 to 10,
            25 to 25,
            50 to 50,
            75 to 75,
            100 to 100,
            101 to 100,
            200 to 100,
            Int.MIN_VALUE to 10,
            Int.MAX_VALUE to 100
        )

        for ((input, expectedClamped) in testCases) {
            val action = DashboardAction.ChangeBrightness(input)
            assertEquals("Action must hold verbatim input percent", input, action.percent)
            val clamped = action.percent.coerceIn(10, 100)
            assertEquals("Clamping must enforce 10..100 range", expectedClamped, clamped)
        }
    }

    // =========================================================================
    // SECTION 5: COMPLETE ENUM AND SETTINGS COVERAGE
    // =========================================================================

    @Test
    fun `challenge SelectProfile - all AmbilightProfile entries are distinct and accepted`() {
        val profiles = VividEdgeColorEngine.AmbilightProfile.entries
        assertEquals(3, profiles.size)

        val actions = profiles.map { DashboardAction.SelectProfile(it) }
        assertEquals(3, actions.distinct().size)

        for (action in actions) {
            assertTrue(action.profile in profiles)
        }
    }

    @Test
    fun `challenge SelectChannelOrder - all ChannelOrder entries are distinct and accepted`() {
        val orders = BleDobProtocol.ChannelOrder.entries
        assertEquals(6, orders.size)

        val actions = orders.map { DashboardAction.SelectChannelOrder(it) }
        assertEquals(6, actions.distinct().size)

        for (action in actions) {
            assertTrue(action.order in orders)
        }
    }

    @Test
    fun `challenge SelectProtocolVariant - all ProtocolVariant entries are distinct and accepted`() {
        val variants = BleDobProtocol.ProtocolVariant.entries
        assertEquals(3, variants.size)

        val actions = variants.map { DashboardAction.SelectProtocolVariant(it) }
        assertEquals(3, actions.distinct().size)

        for (action in actions) {
            assertTrue(action.variant in variants)
        }
    }

    // =========================================================================
    // SECTION 6: TYPEALIAS INTEROPERABILITY
    // =========================================================================

    @Test
    fun `challenge typealiases - polymorphic assignment and equality`() {
        val p: UpdateProfile = DashboardAction.SelectProfile(VividEdgeColorEngine.AmbilightProfile.DYNAMIC)
        val b: UpdateBrightness = DashboardAction.ChangeBrightness(65)
        val c: UpdateChannelOrder = DashboardAction.SelectChannelOrder(BleDobProtocol.ChannelOrder.GRB)
        val v: UpdateVariant = DashboardAction.SelectProtocolVariant(BleDobProtocol.ProtocolVariant.DUAL_HYBRID)

        val pAction: DashboardAction = p
        val bAction: DashboardAction = b
        val cAction: DashboardAction = c
        val vAction: DashboardAction = v

        assertEquals(VividEdgeColorEngine.AmbilightProfile.DYNAMIC, (pAction as DashboardAction.SelectProfile).profile)
        assertEquals(65, (bAction as DashboardAction.ChangeBrightness).percent)
        assertEquals(BleDobProtocol.ChannelOrder.GRB, (cAction as DashboardAction.SelectChannelOrder).order)
        assertEquals(BleDobProtocol.ProtocolVariant.DUAL_HYBRID, (vAction as DashboardAction.SelectProtocolVariant).variant)
    }

    // =========================================================================
    // SECTION 7: EXHAUSTIVE DISPATCH SIMULATION & BACKWARD COMPATIBILITY
    // =========================================================================

    @Test
    fun `challenge dispatch - backward compatible callback adapter receives exact mapped events`() {
        var startCount = 0
        var stopCount = 0
        var minimizeCount = 0
        var connectCount = 0
        var scanCount = 0
        var savedMac: String? = null
        var powerOnCount = 0
        var powerOffCount = 0
        var testRgb: Triple<Int, Int, Int>? = null
        var selectedProfile: VividEdgeColorEngine.AmbilightProfile? = null
        var selectedOrder: BleDobProtocol.ChannelOrder? = null
        var selectedVariant: BleDobProtocol.ProtocolVariant? = null
        var brightnessVal = -1
        var toggleStandbyCount = 0
        var toggleDrmCount = 0

        // Mimics the exact adapter in MainActivity.kt lines 678-696:
        val dispatch: (DashboardAction) -> Unit = { action ->
            when (action) {
                DashboardAction.StartAmbilight -> startCount++
                DashboardAction.StopAmbilight -> stopCount++
                DashboardAction.MinimizeToBackground -> minimizeCount++
                DashboardAction.ConnectBle -> connectCount++
                DashboardAction.ScanBle -> scanCount++
                is DashboardAction.SaveMacAddress -> savedMac = action.mac
                DashboardAction.PowerOn -> powerOnCount++
                DashboardAction.PowerOff -> powerOffCount++
                is DashboardAction.TestColor -> testRgb = Triple(action.color.r, action.color.g, action.color.b)
                is DashboardAction.SelectProfile -> selectedProfile = action.profile
                is DashboardAction.SelectChannelOrder -> selectedOrder = action.order
                is DashboardAction.SelectProtocolVariant -> selectedVariant = action.variant
                is DashboardAction.ChangeBrightness -> brightnessVal = action.percent
                DashboardAction.ToggleStandbySync -> toggleStandbyCount++
                DashboardAction.ToggleDrmFallback -> toggleDrmCount++
            }
        }

        dispatch(DashboardAction.StartAmbilight)
        assertEquals(1, startCount)

        dispatch(DashboardAction.StopAmbilight)
        assertEquals(1, stopCount)

        dispatch(DashboardAction.MinimizeToBackground)
        assertEquals(1, minimizeCount)

        dispatch(DashboardAction.ConnectBle)
        assertEquals(1, connectCount)

        dispatch(DashboardAction.ScanBle)
        assertEquals(1, scanCount)

        dispatch(DashboardAction.SaveMacAddress("12:34:56:78:9A:BC"))
        assertEquals("12:34:56:78:9A:BC", savedMac)

        dispatch(DashboardAction.PowerOn)
        assertEquals(1, powerOnCount)

        dispatch(DashboardAction.PowerOff)
        assertEquals(1, powerOffCount)

        dispatch(DashboardAction.TestColor(255, 140, 40))
        assertEquals(Triple(255, 140, 40), testRgb)

        dispatch(DashboardAction.SelectProfile(VividEdgeColorEngine.AmbilightProfile.CINEMA))
        assertEquals(VividEdgeColorEngine.AmbilightProfile.CINEMA, selectedProfile)

        dispatch(DashboardAction.SelectChannelOrder(BleDobProtocol.ChannelOrder.BGR))
        assertEquals(BleDobProtocol.ChannelOrder.BGR, selectedOrder)

        dispatch(DashboardAction.SelectProtocolVariant(BleDobProtocol.ProtocolVariant.BLEDOM_04))
        assertEquals(BleDobProtocol.ProtocolVariant.BLEDOM_04, selectedVariant)

        dispatch(DashboardAction.ChangeBrightness(85))
        assertEquals(85, brightnessVal)

        dispatch(DashboardAction.ToggleStandbySync)
        assertEquals(1, toggleStandbyCount)

        dispatch(DashboardAction.ToggleDrmFallback)
        assertEquals(1, toggleDrmCount)
    }

    // =========================================================================
    // SECTION 8: MULTI-THREADED CONCURRENCY & MASS DISPATCH STRESS HARNESS
    // =========================================================================

    @Test
    fun `challenge concurrency - 20000 concurrent action dispatches across 8 threads cause zero race conditions`() {
        val totalDispatches = 20_000
        val threadCount = 8
        val executor = Executors.newFixedThreadPool(threadCount)
        val counts = ConcurrentHashMap<String, AtomicInteger>()

        val actions = listOf<DashboardAction>(
            DashboardAction.StartAmbilight,
            DashboardAction.StopAmbilight,
            DashboardAction.MinimizeToBackground,
            DashboardAction.ConnectBle,
            DashboardAction.ScanBle,
            DashboardAction.SaveMacAddress("AA:BB:CC:DD:EE:FF"),
            DashboardAction.PowerOn,
            DashboardAction.PowerOff,
            DashboardAction.TestColor(RgbColor.WARM_AMBER),
            DashboardAction.SelectProfile(VividEdgeColorEngine.AmbilightProfile.DYNAMIC),
            DashboardAction.SelectChannelOrder(BleDobProtocol.ChannelOrder.RGB),
            DashboardAction.SelectProtocolVariant(BleDobProtocol.ProtocolVariant.BLEDOB_07),
            DashboardAction.ChangeBrightness(100),
            DashboardAction.ToggleStandbySync,
            DashboardAction.ToggleDrmFallback
        )

        for (action in actions) {
            counts[action::class.java.simpleName] = AtomicInteger(0)
        }

        val completedDispatches = AtomicInteger(0)
        val errors = AtomicInteger(0)

        for (i in 0 until totalDispatches) {
            val action = actions[Random.nextInt(actions.size)]
            executor.submit {
                try {
                    // Exhaustive when dispatch inside thread
                    when (action) {
                        DashboardAction.StartAmbilight -> counts["StartAmbilight"]!!.incrementAndGet()
                        DashboardAction.StopAmbilight -> counts["StopAmbilight"]!!.incrementAndGet()
                        DashboardAction.MinimizeToBackground -> counts["MinimizeToBackground"]!!.incrementAndGet()
                        DashboardAction.ConnectBle -> counts["ConnectBle"]!!.incrementAndGet()
                        DashboardAction.ScanBle -> counts["ScanBle"]!!.incrementAndGet()
                        is DashboardAction.SaveMacAddress -> counts["SaveMacAddress"]!!.incrementAndGet()
                        DashboardAction.PowerOn -> counts["PowerOn"]!!.incrementAndGet()
                        DashboardAction.PowerOff -> counts["PowerOff"]!!.incrementAndGet()
                        is DashboardAction.TestColor -> counts["TestColor"]!!.incrementAndGet()
                        is DashboardAction.SelectProfile -> counts["SelectProfile"]!!.incrementAndGet()
                        is DashboardAction.SelectChannelOrder -> counts["SelectChannelOrder"]!!.incrementAndGet()
                        is DashboardAction.SelectProtocolVariant -> counts["SelectProtocolVariant"]!!.incrementAndGet()
                        is DashboardAction.ChangeBrightness -> counts["ChangeBrightness"]!!.incrementAndGet()
                        DashboardAction.ToggleStandbySync -> counts["ToggleStandbySync"]!!.incrementAndGet()
                        DashboardAction.ToggleDrmFallback -> counts["ToggleDrmFallback"]!!.incrementAndGet()
                    }
                    completedDispatches.incrementAndGet()
                } catch (t: Throwable) {
                    errors.incrementAndGet()
                }
            }
        }

        executor.shutdown()
        val finished = executor.awaitTermination(10, TimeUnit.SECONDS)
        assertTrue("Executor must terminate cleanly within timeout", finished)
        assertEquals("Zero errors during concurrent dispatch", 0, errors.get())
        assertEquals("All dispatches must be counted", totalDispatches, completedDispatches.get())

        val totalSumFromMap = counts.values.sumOf { it.get() }
        assertEquals("Sum of all action counts must match total dispatches", totalDispatches, totalSumFromMap)

        // Verify every action type was dispatched at least once
        for ((name, counter) in counts) {
            assertTrue("Action $name should have been dispatched at least once, got ${counter.get()}", counter.get() > 0)
        }
    }
}

