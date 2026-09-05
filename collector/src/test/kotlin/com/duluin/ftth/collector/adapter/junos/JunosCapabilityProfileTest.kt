package com.duluin.ftth.collector.adapter.junos

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JunosCapabilityProfileTest {
    @Test
    fun `EX profile is exact transport-only and provisional`() {
        val identity = JunosDeviceIdentity("EX4300-48P", "21.4R3-S5.4")

        val profile = JunosCapabilityProfiles.find(identity)

        assertEquals(JunosPlatformFamily.EX, profile?.family)
        assertEquals(setOf(JunosOperation.TRANSPORT_VLAN), profile?.operations)
        assertEquals(true, profile?.provisional)
    }

    @Test
    fun `MX profile explicitly advertises PPPoE and remains provisional`() {
        val identity = JunosDeviceIdentity("MX204", "23.4R2-S2.1")

        val profile = JunosCapabilityProfiles.find(identity)

        assertEquals(JunosPlatformFamily.MX, profile?.family)
        assertEquals(
            setOf(JunosOperation.TRANSPORT_VLAN, JunosOperation.PPPOE_TERMINATION),
            profile?.operations,
        )
        assertEquals(true, profile?.provisional)
    }

    @Test
    fun `unknown model or firmware has no profile`() {
        val unknownModel = JunosDeviceIdentity("EX9999", "21.4R3-S5.4")
        val unknownFirmware = JunosDeviceIdentity("EX4300-48P", "21.4R3-S5.5")

        val modelProfile = JunosCapabilityProfiles.find(unknownModel)
        val firmwareProfile = JunosCapabilityProfiles.find(unknownFirmware)

        assertNull(modelProfile)
        assertNull(firmwareProfile)
    }
}
