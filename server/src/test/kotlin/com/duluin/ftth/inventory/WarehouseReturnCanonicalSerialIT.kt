package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Locale

class WarehouseReturnCanonicalSerialIT : WarehouseCustomerRmaFixture() {
    @Test fun `mixed receipt spelling survives repair and customer RMA without changing source history or replay bytes`() {
        val case = prepareRma(serials = listOf("Mixed-Rma-One", "Mixed-Rma-Two"))
        val physical = fixture(case.repair.token)
        val raw = physical.transaction { scalar("SELECT serial_number FROM inventory_serialized_asset WHERE id='${case.repair.asset}'") }
        assertThat(raw).startsWith("Mixed-Rma-")
        assertThat(case.repair.serial).isEqualTo(raw.uppercase(Locale.ROOT))
        val outbound = dispatchRma(case)
        val path = "/api/v1/warehouse/rma-handovers/${outbound.path("id").asString()}/acknowledge"
        val observed = "  ${case.repair.serial.lowercase(Locale.ROOT)}  "
        val body = case.ack.replace(case.repair.serial, observed)
        assertThat(request("POST", path, case.receipt.receiver.first, body.replace(observed, "OTHER-DEVICE"), "canonical-rma-ack").status).isEqualTo(409)
        val accepted = request("POST", path, case.receipt.receiver.first, body, "canonical-rma-ack")
        assertThat(accepted.status).withFailMessage(accepted.contentAsString).isEqualTo(200)
        assertThat(request("POST", path, case.receipt.receiver.first, body, "canonical-rma-ack").contentAsString).isEqualTo(accepted.contentAsString)
        // Physical equality does not make altered bytes the same idempotent command.
        assertThat(request("POST", path, case.receipt.receiver.first, case.ack, "canonical-rma-ack").status).isEqualTo(409)
        val permit = request("POST", "/api/work-orders/${case.work}/assets/authorize", case.receipt.receiver.first,
            case.authorization, "canonical-rma-authorize")
        assertThat(permit.status).withFailMessage(permit.contentAsString).isEqualTo(200)
        val authorization = mapper.readTree(permit.contentAsString).path("authorizationId").asString()
        val installed = request("POST", "/api/customers/${case.customer}/assets/install", case.receipt.receiver.first,
            """{"authorizationId":"$authorization","expectedRevision":0,"topology":null}""", "canonical-rma-install")
        assertThat(installed.status).withFailMessage(installed.contentAsString).isEqualTo(201)
        assertThat(mapper.readTree(installed.contentAsString).path("assetId").asString()).isEqualTo(case.repair.asset.toString())
        physical.transaction {
            assertThat(scalar("SELECT serial_number FROM inventory_serialized_asset WHERE id='${case.repair.asset}'")).isEqualTo(raw)
            assertThat(scalar("SELECT body::jsonb->'source'->>'serial' FROM inventory_return_case WHERE id='${case.repair.returned.id}'")).isEqualTo(raw)
            assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment WHERE asset_id='${case.repair.asset}' AND ended_at IS NULL AND customer_id='${case.customer}' AND legal_owner='CUSTOMER'")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_balance_projection WHERE stock_identity_id='${case.repair.asset}' AND quantity_base>0 AND legal_owner='ISP'")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_rma_receipt WHERE handover_id='${outbound.path("id").asString()}'")).isEqualTo("1")
        }
    }
}
