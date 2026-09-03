package com.duluin.ftth.collector.adapter.hsgq

import com.duluin.ftth.contract.OltManagementTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class HsgqProvisioningModelTest {
    @Test
    fun `default certification registry contains no certified fingerprint`() {
        val fingerprint = HsgqFirmwareFingerprint("HSGQ-E04I", "V1.2.3", OltManagementTransport.HTTPS_API)

        assertNull(HsgqCertificationRegistry().find(fingerprint))
    }

    @Test
    fun `state hash changes with management or service state`() {
        val baseline = HsgqDeviceState(
            model = "HSGQ-E04I",
            firmware = "V1.2.3",
            managementVlanId = 100,
            managementInterface = "MGMT0",
            subscriberBindings = emptySet(),
            taggedUplinks = emptySet(),
        )

        assertNotEquals(baseline.sha256(), baseline.copy(managementVlanId = 101).sha256())
        assertNotEquals(
            baseline.sha256(),
            baseline.copy(subscriberBindings = setOf(HsgqSubscriberVlanBinding(3901, "EPON1/1:ONU1"))).sha256(),
        )
        assertEquals(baseline.sha256(), baseline.copy().sha256())
    }
}
