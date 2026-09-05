package com.duluin.ftth.collector.adapter.junos

import com.duluin.ftth.contract.ProvisioningPayload
import com.duluin.ftth.contract.ProvisioningPayloadValues
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JunosConfigurationTest {
    @Test
    fun `transport VLAN edit is merge-only and models switching IRB and filter state`() {
        val payload = ProvisioningPayload(
            ProvisioningPayloadValues(
                tenantId = "tenant-1",
                intentId = "intent-1",
                vlanId = "110",
                trunkPorts = "ge-0/0/0",
                accessPorts = "ge-0/0/1",
                vlanInterface = "irb.110",
                firewallChain = "ftth-110",
            ),
        )

        val change = JunosConfiguration.build(JunosOperation.TRANSPORT_VLAN, payload)

        assertEquals(JunosEditOperation.MERGE, change.defaultOperation)
        assertTrue("<vlans>" in change.configuration)
        assertTrue("<interfaces>" in change.configuration)
        assertTrue("<firewall>" in change.configuration)
        assertTrue("irb.110" in change.configuration)
        assertFalse("replace" in change.configuration)
    }

    @Test
    fun `MX PPPoE edit models subscriber termination separately`() {
        val payload = ProvisioningPayload(
            ProvisioningPayloadValues(
                tenantId = "tenant-1",
                intentId = "intent-2",
                vlanId = "210",
                pppoeInterface = "demux0.210",
                pppoeServiceName = "fiber",
                poolName = "pool-210",
                poolRanges = "10.210.0.2-10.210.0.254",
            ),
        )

        val change = JunosConfiguration.build(JunosOperation.PPPOE_TERMINATION, payload)

        assertEquals(JunosEditOperation.MERGE, change.defaultOperation)
        assertTrue("<dynamic-profiles>" in change.configuration)
        assertTrue("<subscribers>" in change.configuration)
        assertTrue("<name>demux0</name>" in change.configuration)
        assertTrue("<unit><name>210</name>" in change.configuration)
        assertFalse("replace" in change.configuration)
    }
}
