package com.duluin.ftth.collector.adapter.hsgq

import com.duluin.ftth.contract.OltManagementTransport
import com.duluin.ftth.contract.OltTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HsgqOltTargetContractTest {
    @Test
    fun `legacy target keeps additive management fields absent`() {
        val target = OltTarget("olt-1", "OLT-1", "HSGQ", "192.0.2.1", 1161, null)

        assertNull(target.model)
        assertNull(target.firmware)
        assertNull(target.managementTransport)
        assertNull(target.managementCredentialRef)
    }

    @Test
    fun `HSGQ target carries fingerprint transport and credential reference without a secret`() {
        val target = OltTarget(
            oltId = "olt-hsgq-1",
            oltCode = "OLT-LAB-HSGQ",
            vendor = "HSGQ",
            host = "192.0.2.10",
            snmpPort = 1161,
            snmpCommunity = null,
            model = "HSGQ-E04I",
            firmware = "V1.2.3-certified",
            managementTransport = OltManagementTransport.HTTPS_API,
            managementPort = 443,
            managementCredentialRef = "credential:hsgq-lab",
        )

        assertEquals("HSGQ-E04I", target.model)
        assertEquals("V1.2.3-certified", target.firmware)
        assertEquals(OltManagementTransport.HTTPS_API, target.managementTransport)
        assertEquals("credential:hsgq-lab", target.managementCredentialRef)
        assertEquals(false, target.toString().contains("redacted-fixture-secret"))
    }
}
