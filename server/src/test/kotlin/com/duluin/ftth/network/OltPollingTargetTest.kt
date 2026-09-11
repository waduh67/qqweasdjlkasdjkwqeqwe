package com.duluin.ftth.network

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class OltPollingTargetTest {
    private val ready = OltPollingTarget(
        id = UUID.randomUUID(),
        code = "OLT-01",
        vendor = "HSGQ",
        host = "192.0.2.10",
        snmpCommunity = "secret",
    )

    @Test
    fun `strict readiness distinguishes every configuration defect`() {
        assertThat(ready.pollingReadiness()).isEqualTo(OltPollingReadiness.READY)
        assertThat(ready.copy(active = false).pollingReadiness()).isEqualTo(OltPollingReadiness.INACTIVE)
        assertThat(ready.copy(snmpEnabled = false).pollingReadiness()).isEqualTo(OltPollingReadiness.SNMP_DISABLED)
        assertThat(ready.copy(vendorSupported = false).pollingReadiness())
            .isEqualTo(OltPollingReadiness.UNSUPPORTED_VENDOR)
        assertThat(ready.pollingReadiness(adapterSupported = false))
            .isEqualTo(OltPollingReadiness.UNSUPPORTED_VENDOR)
        assertThat(ready.copy(host = " ").pollingReadiness()).isEqualTo(OltPollingReadiness.MISSING_HOST)
        assertThat(ready.copy(snmpCommunity = " ").pollingReadiness())
            .isEqualTo(OltPollingReadiness.MISSING_COMMUNITY)
    }
}
