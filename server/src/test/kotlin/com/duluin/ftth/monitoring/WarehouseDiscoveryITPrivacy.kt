package com.duluin.ftth.monitoring

import com.duluin.ftth.InMemoryAcsGateway
import com.duluin.ftth.cpe.application.port.outbound.AcsDevice
import com.duluin.ftth.cpe.application.service.CpeSyncScheduler
import com.duluin.ftth.customer.LegacyOnuTestFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WarehouseDiscoveryITPrivacy : WarehouseDiscoveryFixture() {
    @Autowired private lateinit var acs: InMemoryAcsGateway
    @Autowired private lateinit var scheduler: CpeSyncScheduler

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = [false, true])
    fun `global ACS serial ambiguity never creates customer snapshots in either tenant`(suspendFirst: Boolean) {
        val first = newTenantAdmin("acsfirst")
        val second = newTenantAdmin("acssecond")
        val serial = "SHARED-${uniq().uppercase()}"
        LegacyOnuTestFixture.stage(customer(first), serial)
        LegacyOnuTestFixture.stage(customer(second), serial)
        if (suspendFirst) tenants.suspend(tenantId(first))
        acs.reset()
        try {
            acs.seedDevice(AcsDevice("SHARED-ACS-$serial", serial, null, null, "Private vendor", "First customer model",
                null, "192.0.2.50", Instant.now(), "First-private"))
            scheduler.syncAll()
            assertThat(scalar(first, "SELECT count(*) FROM cpe_device WHERE serial_number='$serial'")).isEqualTo("0")
            assertThat(scalar(second, "SELECT count(*) FROM cpe_device WHERE serial_number='$serial'")).isEqualTo("0")
            assertThat(scalar(second, "SELECT count(*) FROM inventory_serialized_asset")).isEqualTo("0")
            assertThat(scalar(second, "SELECT count(*) FROM cpe_unassigned_observation WHERE reason='AMBIGUOUS_SERIAL'")).isEqualTo("1")
            scheduler.syncAll()
            assertThat(scalar(second, "SELECT count(*) FROM cpe_unassigned_observation WHERE reason='AMBIGUOUS_SERIAL'")).isEqualTo("1")
        } finally {
            acs.reset()
        }
    }

    @Autowired private lateinit var tenants: com.duluin.ftth.tenancy.TenantApi
}
