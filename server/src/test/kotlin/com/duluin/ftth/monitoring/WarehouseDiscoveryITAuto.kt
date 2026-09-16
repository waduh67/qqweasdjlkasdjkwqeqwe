package com.duluin.ftth.monitoring

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.contract.OnuOperationalStatus
import com.duluin.ftth.contract.OnuReading
import com.duluin.ftth.customer.CustomerDeploymentFixture
import com.duluin.ftth.monitoring.application.service.AutoProvisionSweeper
import com.duluin.ftth.monitoring.application.service.MetricIngestionService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant

class WarehouseDiscoveryITAuto : CustomerDeploymentFixture() {
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `actual automatic sweep requires a minted issued authorization despite HIGH policy`(authorized: Boolean) {
        val installation = if (authorized) installation() else null
        val receipt = installation?.receipt ?: receiptCase(serial = true, installation = true).also { received(it) }
        val stock = fixture(receipt.stock.token)
        val customer = stock.transaction { scalar("SELECT customer_id FROM work_order WHERE id='${receipt.workOrder}'") }
        val serial = requireNotNull(receipt.input.lines.single().serial)
        val areaId = area(receipt.stock.token)
        fun createNode(path: String, body: String): String {
            val result = request("POST", path, receipt.stock.token, body)
            assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(201)
            return mapper.readTree(result.contentAsString).path("id").asString()
        }
        val site = createNode("/api/sites", """{"code":"AUTO-SITE","name":"Auto site","areaId":"$areaId","location":{"longitude":106.99,"latitude":-6.24}}""")
        val olt = createNode("/api/olts", """{"siteId":"$site","code":"AUTO-OLT","name":"Auto OLT","vendor":"ZTE","managementIp":"127.0.0.1","snmpCommunity":"owned-test"}""")
        val pon = createNode("/api/olts/$olt/pon-ports", """{"label":"1/1/1"}""")
        val odc = createNode("/api/odcs", """{"code":"AUTO-ODC","name":"Auto ODC","ponPortId":"$pon","splitterRatio":"1:8","capacity":64,"areaId":"$areaId","location":{"longitude":106.99,"latitude":-6.24}}""")
        createNode("/api/odps", """{"code":"AUTO-ODP","name":"Auto ODP","odcId":"$odc","splitterRatio":"1:8","capacity":8,"areaId":"$areaId","location":{"longitude":106.99,"latitude":-6.24}}""")
        stock.transaction {
            sql("UPDATE customer SET location=ST_SetSRID(ST_MakePoint(106.99,-6.24),4326),location_status='LOCATED' WHERE id='$customer'")
        }
        assertThat(request("PUT", "/api/monitoring/auto-provision-policy", receipt.stock.token, """{"enabled":true}""").status).isEqualTo(200)
        stock.transaction {
            context.getBean(MetricIngestionService::class.java).ingestReadings(tenant,
                listOf(OnuReading(serial, "AUTO-OLT", "1/1/1", OnuOperationalStatus.ONLINE, -20.0, null, null, null, Instant.now())))
        }
        val waiting = mapper.readTree(request("GET", "/api/monitoring/discovered-onus", receipt.stock.token).contentAsString)
        assertThat(waiting[0].path("suggestion").path("confidence").asString()).isEqualTo("HIGH")
        repeat(2) { TenantContext.runAs(stock.tenant) { context.getBean(AutoProvisionSweeper::class.java).sweep(stock.tenant) } }
        stock.transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_serialized_asset")).isEqualTo("2")
            assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment")).isEqualTo(if (authorized) "1" else "0")
            assertThat(scalar("SELECT state FROM discovered_onu WHERE serial_number='$serial'")).isEqualTo(if (authorized) "PROVISIONED" else "DISCOVERED")
        }
    }
}
