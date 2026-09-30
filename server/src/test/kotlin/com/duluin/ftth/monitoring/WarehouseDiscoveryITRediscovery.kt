package com.duluin.ftth.monitoring

import com.duluin.ftth.contract.OnuOperationalStatus
import com.duluin.ftth.contract.OnuReading
import com.duluin.ftth.customer.CustomerAssetReplacementFixture
import com.duluin.ftth.monitoring.application.service.MetricIngestionService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class WarehouseDiscoveryITRediscovery : CustomerAssetReplacementFixture() {
    override fun consume(installation: Installation, key: String): org.springframework.mock.web.MockHttpServletResponse {
        val stock = fixture(installation.receipt.stock.token)
        val serial = requireNotNull(installation.receipt.input.lines.single().serial)
        stock.transaction {
            context.getBean(MetricIngestionService::class.java).ingestReadings(tenant,
                listOf(OnuReading(serial, "OLT-X", null, OnuOperationalStatus.ONLINE, -20.0, null, null, null, Instant.now())))
        }
        return super.consume(installation, key)
    }

    @Test
    fun `recovered device gets a new actionable discovery without rewriting its old resolved observation`() {
        val old = ownershipCase("LOAN")
        assertThat(accept(old).status).isEqualTo(200)
        val receipt = old.installation.receipt
        val stock = fixture(receipt.stock.token)
        val serial = requireNotNull(receipt.input.lines.single().serial)
        val prior = stock.transaction { scalar("SELECT id FROM discovered_onu WHERE serial_number='$serial' AND state='PROVISIONED'") }
        val order = workOrder(receipt.stock.token, "DISMANTLE", old.installation.customer.toString())
        assign(receipt.stock.token, order, receipt.receiver.second)
        val evidence = removalEvidence(receipt.receiver.first, order)
        val removed = request("POST", "/api/customers/${old.installation.customer}/assets/remove", receipt.receiver.first,
            """{"assignmentId":"${old.installation.operation}","expectedRevision":1,"expectedTitleRevision":0,
                "workOrderId":"$order","evidenceId":"$evidence"}""", "discovery-recovery")
        assertThat(removed.status).withFailMessage(removed.contentAsString).isEqualTo(200)
        stock.transaction {
            val result = context.getBean(MetricIngestionService::class.java).ingestReadings(tenant,
                listOf(OnuReading(serial, "OLT-X", null, OnuOperationalStatus.ONLINE, -21.0, null, null, null, Instant.now())))
            assertThat(result.accepted).isZero()
        }
        stock.transaction {
            assertThat(scalar("SELECT count(*) FROM discovered_onu WHERE serial_number='$serial' AND state='DISCOVERED' AND id<>'$prior'")).isEqualTo("1")
            assertThat(scalar("SELECT state FROM discovered_onu WHERE id='$prior'")).isEqualTo("PROVISIONED")
            assertThat(scalar("SELECT status FROM inventory_serialized_asset WHERE id='${receipt.input.lines.single().stockIdentityId}'")).isEqualTo("QUARANTINE")
            assertThat(scalar("SELECT count(*) FROM inventory_balance_projection WHERE stock_identity_id='${receipt.input.lines.single().stockIdentityId}' AND status='AVAILABLE' AND quantity_base>0")).isEqualTo("0")
        }
    }
}
