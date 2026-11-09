package com.lotus.ambilighttv.model

import com.lotus.ambilighttv.ble.BleDobProtocol
import com.lotus.ambilighttv.engine.VividEdgeColorEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardActionTest {

    @Test
    fun `all 15 action types can be instantiated and maintain distinct identities`() {
        val actions = listOf<DashboardAction>(
            DashboardAction.StartAmbilight,
            DashboardAction.StopAmbilight,
            DashboardAction.MinimizeToBackground,
            DashboardAction.ConnectBle,
            DashboardAction.ScanBle,
            DashboardAction.SaveMacAddress("AA:BB:CC:DD:EE:FF"),
            DashboardAction.PowerOn,
            DashboardAction.PowerOff,
            DashboardAction.TestColor(RgbColor.RED),
            DashboardAction.SelectProfile(VividEdgeColorEngine.AmbilightProfile.DYNAMIC),
            DashboardAction.SelectChannelOrder(BleDobProtocol.ChannelOrder.RGB),
            DashboardAction.SelectProtocolVariant(BleDobProtocol.ProtocolVariant.BLEDOB_07),
            DashboardAction.ChangeBrightness(80),
            DashboardAction.ToggleStandbySync,
            DashboardAction.ToggleDrmFallback
        )

        assertEquals("Expected exactly 15 distinct action intents", 15, actions.size)
        // Verify every action is non-null and distinct in class type or content
        val types = actions.map { it::class }.distinct()
        assertEquals("Expected 15 distinct classes for 15 actions", 15, types.size)
    }

    @Test
    fun `SaveMacAddress secondary constructor formats BleMacAddress correctly`() {
        val bleMac = BleMacAddress.parse("aa:bb:cc:dd:ee:ff")
        val action = DashboardAction.SaveMacAddress(bleMac)

        assertEquals("AA:BB:CC:DD:EE:FF", action.mac)
    }

    @Test
    fun `SaveMacAddress primary constructor preserves raw string`() {
        val action = DashboardAction.SaveMacAddress("11:22:33:44:55:66")
        assertEquals("11:22:33:44:55:66", action.mac)

        val copy = action.copy(mac = "AA:BB:CC:DD:EE:FF")
        assertEquals("AA:BB:CC:DD:EE:FF", copy.mac)
        assertNotEquals(action, copy)
    }

    @Test
    fun `TestColor primary constructor stores packed RgbColor`() {
        val color = RgbColor.fromRgb(255, 140, 40)
        val action = DashboardAction.TestColor(color)

        assertEquals(255, action.color.r)
        assertEquals(140, action.color.g)
        assertEquals(40, action.color.b)
        assertEquals(RgbColor.WARM_AMBER, action.color)
    }

    @Test
    fun `TestColor secondary constructor converts integer triplet to RgbColor`() {
        val action = DashboardAction.TestColor(0, 255, 0)

        assertEquals(RgbColor.GREEN, action.color)
        assertEquals(0, action.color.r)
        assertEquals(255, action.color.g)
        assertEquals(0, action.color.b)
    }

    @Test
    fun `SelectProfile holds AmbilightProfile instances`() {
        val cinemaAction = DashboardAction.SelectProfile(VividEdgeColorEngine.AmbilightProfile.CINEMA)
        val gamingAction = DashboardAction.SelectProfile(VividEdgeColorEngine.AmbilightProfile.GAMING)

        assertEquals(VividEdgeColorEngine.AmbilightProfile.CINEMA, cinemaAction.profile)
        assertEquals(VividEdgeColorEngine.AmbilightProfile.GAMING, gamingAction.profile)
        assertNotEquals(cinemaAction, gamingAction)
    }

    @Test
    fun `SelectChannelOrder holds ChannelOrder instances`() {
        val rgbAction = DashboardAction.SelectChannelOrder(BleDobProtocol.ChannelOrder.RGB)
        val grbAction = DashboardAction.SelectChannelOrder(BleDobProtocol.ChannelOrder.GRB)

        assertEquals(BleDobProtocol.ChannelOrder.RGB, rgbAction.order)
        assertEquals(BleDobProtocol.ChannelOrder.GRB, grbAction.order)
        assertNotEquals(rgbAction, grbAction)
    }

    @Test
    fun `SelectProtocolVariant holds ProtocolVariant instances`() {
        val v07Action = DashboardAction.SelectProtocolVariant(BleDobProtocol.ProtocolVariant.BLEDOB_07)
        val v04Action = DashboardAction.SelectProtocolVariant(BleDobProtocol.ProtocolVariant.BLEDOM_04)
        val hybridAction = DashboardAction.SelectProtocolVariant(BleDobProtocol.ProtocolVariant.DUAL_HYBRID)

        assertEquals(BleDobProtocol.ProtocolVariant.BLEDOB_07, v07Action.variant)
        assertEquals(BleDobProtocol.ProtocolVariant.BLEDOM_04, v04Action.variant)
        assertEquals(BleDobProtocol.ProtocolVariant.DUAL_HYBRID, hybridAction.variant)
    }

    @Test
    fun `ChangeBrightness stores brightness percentage value`() {
        val action = DashboardAction.ChangeBrightness(75)
        assertEquals(75, action.percent)

        val clampedAction = DashboardAction.ChangeBrightness(action.percent.coerceIn(10, 100))
        assertEquals(75, clampedAction.percent)
    }

    @Test
    fun `typealiases resolve to equivalent DashboardAction subtypes`() {
        val updateProfile: UpdateProfile = DashboardAction.SelectProfile(VividEdgeColorEngine.AmbilightProfile.CINEMA)
        val updateBrightness: UpdateBrightness = DashboardAction.ChangeBrightness(50)
        val updateChannelOrder: UpdateChannelOrder = DashboardAction.SelectChannelOrder(BleDobProtocol.ChannelOrder.BGR)
        val updateVariant: UpdateVariant = DashboardAction.SelectProtocolVariant(BleDobProtocol.ProtocolVariant.BLEDOB_07)

        assertNotNull(updateProfile)
        assertNotNull(updateBrightness)
        assertNotNull(updateChannelOrder)
        assertNotNull(updateVariant)
    }

    @Test
    fun `exhaustive sealed when expression covers all 15 branches`() {
        val testActions = listOf(
            DashboardAction.StartAmbilight,
            DashboardAction.StopAmbilight,
            DashboardAction.MinimizeToBackground,
            DashboardAction.ConnectBle,
            DashboardAction.ScanBle,
            DashboardAction.SaveMacAddress("AA:BB:CC:DD:EE:FF"),
            DashboardAction.PowerOn,
            DashboardAction.PowerOff,
            DashboardAction.TestColor(RgbColor.BLUE),
            DashboardAction.SelectProfile(VividEdgeColorEngine.AmbilightProfile.DYNAMIC),
            DashboardAction.SelectChannelOrder(BleDobProtocol.ChannelOrder.RGB),
            DashboardAction.SelectProtocolVariant(BleDobProtocol.ProtocolVariant.BLEDOB_07),
            DashboardAction.ChangeBrightness(100),
            DashboardAction.ToggleStandbySync,
            DashboardAction.ToggleDrmFallback
        )

        var handledCount = 0
        for (action in testActions) {
            when (action) {
                DashboardAction.StartAmbilight -> handledCount++
                DashboardAction.StopAmbilight -> handledCount++
                DashboardAction.MinimizeToBackground -> handledCount++
                DashboardAction.ConnectBle -> handledCount++
                DashboardAction.ScanBle -> handledCount++
                is DashboardAction.SaveMacAddress -> {
                    assertTrue(action.mac.isNotEmpty())
                    handledCount++
                }
                DashboardAction.PowerOn -> handledCount++
                DashboardAction.PowerOff -> handledCount++
                is DashboardAction.TestColor -> {
                    assertEquals(RgbColor.BLUE, action.color)
                    handledCount++
                }
                is DashboardAction.SelectProfile -> handledCount++
                is DashboardAction.SelectChannelOrder -> handledCount++
                is DashboardAction.SelectProtocolVariant -> handledCount++
                is DashboardAction.ChangeBrightness -> handledCount++
                DashboardAction.ToggleStandbySync -> handledCount++
                DashboardAction.ToggleDrmFallback -> handledCount++
            }
        }

        assertEquals("All 15 actions should be dispatched via sealed when without exception", 15, handledCount)
    }
}
