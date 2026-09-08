package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarehouseReceiptITReceive : WarehouseReceiptHttpFixture() {
    @Test fun `receive creates verified origins once and only quarantined physical balances`() {
        val setup = setupReceipt()
        val serials = (1..10).joinToString(",") { """{"serial":" onu-$it "}""" }
        val draft = draft(setup, """{"skuId":"${setup.cable}","quantityBase":"1000000","lotCode":"R1","cost":{"totalMinor":"500000","currency":"IDR"}},
            {"skuId":"${setup.onu}","quantityBase":"10","serials":[$serials]}""")
        val id = draft.path("id").asString()
        val received = transition(setup, id, "receive", """{"expectedRevision":0}""", "receive-key")
        assertThat(received.path("state").asString()).isEqualTo("RECEIVED_IN_INSPECTION")
        assertThat(transition(setup, id, "receive", """{"expectedRevision":0}""", "receive-key")).isEqualTo(received)
        assertThat(request("POST", "/api/v1/warehouse/receipts/$id/receive", setup.token, """{"expectedRevision":0}""").status).isEqualTo(409)
        fixture(setup.token).transaction {
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE base_unit='MM'")).isEqualTo("1000000")
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE base_unit='EA'")).isEqualTo("10")
            assertThat(scalar("SELECT count(*) FROM inventory_balance_projection WHERE status='AVAILABLE' OR condition<>'QUARANTINE'")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_identity_claim WHERE state='ADMITTED'")).isEqualTo("10")
            assertThat(scalar("SELECT count(*) FROM inventory_movement")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_movement_leg WHERE status='RECEIPT_SOURCE'")).isEqualTo("11")
            assertThat(scalar("SELECT cost_total_minor::text||'/'||cost_basis_quantity_base::text FROM inventory_lot")).isEqualTo("500000/1000000")
        }
    }
}
