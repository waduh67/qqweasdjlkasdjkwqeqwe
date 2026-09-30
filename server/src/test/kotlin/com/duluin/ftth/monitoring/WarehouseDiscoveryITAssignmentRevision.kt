package com.duluin.ftth.monitoring

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.cpe.CpeApi
import com.duluin.ftth.cpe.application.port.outbound.AcsDevice
import com.duluin.ftth.cpe.application.service.CpeSyncService
import com.duluin.ftth.customer.CustomerAssetOwnershipFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class WarehouseDiscoveryITAssignmentRevision : CustomerAssetOwnershipFixture() {
    @Test
    fun `actual assignment revision invalidates exposure until a newly bound ACS readback`() {
        val case = ownershipCase("LOAN", listOf("REVISION-${java.util.UUID.randomUUID()}".uppercase(), "SPARE-${java.util.UUID.randomUUID()}".uppercase()))
        val stock = fixture(case.installation.receipt.stock.token)
        val serial = requireNotNull(case.installation.receipt.input.lines.single().serial)
        val time = Instant.now()
        val snapshot = AcsDevice("REVISION-${case.installation.operation}", serial, null, null, "Vendor", "Model",
            null, "192.0.2.10", time, "private", observedFieldsAt = time)
        val sync = context.getBean(CpeSyncService::class.java)
        TenantContext.runAs(stock.tenant) { sync.sync(listOf(snapshot)) }
        stock.transaction { assertThat(context.getBean(CpeApi::class.java).findDevicesForCustomer(case.installation.customer)).hasSize(1) }
        assertThat(accept(case).status).isEqualTo(200)
        stock.transaction {
            assertThat(scalar("SELECT revision FROM inventory_asset_assignment WHERE id='${case.installation.operation}'")).isEqualTo("1")
            assertThat(context.getBean(CpeApi::class.java).findDevicesForCustomer(case.installation.customer)).isEmpty()
        }
        val fresh = Instant.now()
        TenantContext.runAs(stock.tenant) { sync.sync(listOf(snapshot.copy(lastInformAt = fresh, observedFieldsAt = fresh))) }
        stock.transaction {
            assertThat(context.getBean(CpeApi::class.java).findDevicesForCustomer(case.installation.customer)).hasSize(1)
            assertThat(scalar("SELECT count(*) FROM cpe_device")).isEqualTo("1")
            assertThat(scalar("SELECT string_agg(assignment_revision::text,',' ORDER BY assignment_revision) FROM cpe_episode_snapshot")).isEqualTo("0,1")
        }
    }
}
