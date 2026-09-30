package com.duluin.ftth.monitoring

import com.duluin.ftth.InMemoryAcsGateway
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.cpe.application.port.outbound.AcsDevice
import com.duluin.ftth.cpe.application.service.CpeSyncScheduler
import com.duluin.ftth.cpe.application.service.CpeSyncService
import com.duluin.ftth.cpe.domain.model.WifiNetwork
import com.duluin.ftth.customer.CustomerDeploymentFixture
import com.duluin.ftth.customer.LegacyOnuTestFixture
import com.duluin.ftth.tenancy.TenantApi
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import java.util.UUID

class WarehouseDiscoveryITReviewCurrentOwner : CustomerDeploymentFixture() {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `CPE-1 ownership conflict after admission blocks cached live and operation access`(suspendOther: Boolean) {
        val serial = "REVIEW-${UUID.randomUUID()}".uppercase()
        val installation = installation(serials = listOf(serial, "$serial-X"))
        assertThat(consume(installation).status).isEqualTo(201)
        val stock = fixture(installation.receipt.stock.token)
        val now = Instant.now()
        val device = AcsDevice("ACS-$serial", serial, null, null, "Vendor", "B-model", null, "192.0.2.2", now,
            "B-private", observedFieldsAt = now)
        TenantContext.runAs(stock.tenant) { context.getBean(CpeSyncService::class.java).sync(listOf(device)) }
        val id = stock.transaction { scalar("SELECT id FROM cpe_device") }
        assertThat(mapper.readTree(request("GET", "/api/cpe/devices?customerId=${installation.customer}", installation.receipt.stock.token).contentAsString).size()).isEqualTo(1)
        val other = tenant()
        val customer = request("POST", "/api/customers", other,
            """{"code":"OTHER","name":"Other","address":"Test","location":{"longitude":106.99,"latitude":-6.24}}""")
        assertThat(customer.status).isEqualTo(201)
        LegacyOnuTestFixture.stage(mapper.readTree(customer.contentAsString).path("id").asString(), serial)
        if (suspendOther) context.getBean(TenantApi::class.java).suspend(fixture(other).tenant)
        val acs = context.getBean(InMemoryAcsGateway::class.java)
        acs.reset()
        try {
            val fresh = Instant.now()
            acs.seedDevice(device.copy(model = "A-model", lastInformAt = fresh, observedFieldsAt = fresh))
            acs.seedWifi(device.genieacsId, listOf(WifiNetwork("WLAN.1", "A-private", "A-password", null, true, fresh)))
            context.getBean(CpeSyncScheduler::class.java).syncAll()
            val cached = request("GET", "/api/cpe/devices?customerId=${installation.customer}", installation.receipt.stock.token)
            val live = request("GET", "/api/cpe/devices/$id/live", installation.receipt.stock.token)
            val reboot = request("POST", "/api/cpe/devices/$id/reboot", installation.receipt.stock.token)
            val refresh = request("POST", "/api/cpe/devices/$id/refresh", installation.receipt.stock.token)
            assertThat(mapper.readTree(cached.contentAsString).size()).isZero()
            assertThat(listOf(live.status, reboot.status, refresh.status)).containsExactly(404, 404, 404)
            assertThat(live.contentAsString).doesNotContain("A-private", "A-password")
            assertThat(acs.rebootCalls).isEmpty()
        } finally { acs.reset() }
    }
}
