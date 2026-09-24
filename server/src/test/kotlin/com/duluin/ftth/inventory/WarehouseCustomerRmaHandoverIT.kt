package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarehouseCustomerRmaHandoverIT : WarehouseRepairFixture() {
    @Test
    fun `inspected sold repair moves to its assigned technician for original customer without ISP availability`() {
        val setup = repairSetup()
        val inspected = inspectRepair(setup, receiveRepair(setup, dispatchRepair(setup)))
        val receipt = setup.returned.old.installation.receipt
        val customer = setup.returned.old.installation.customer
        val work = workOrder(setup.token, "REPAIR", customer.toString())
        assign(setup.token, work, receipt.receiver.second)
        val workRevision = summary(setup.token, work).path("revisions").path("workOrderRevision").asLong()
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${receipt.receiver.second}/${setup.returned.quarantine}", setup.token,
            """{"expectedRevision":0,"active":true}""").status).isEqualTo(200)
        val body = """{"expectedRevision":${inspected.path("revision").asLong()},"workOrderId":"$work","workOrderRevision":$workRevision,"technicianId":"${receipt.receiver.second}","transitLocationId":"${receipt.transit}","technicianLocationId":"${receipt.field}","observedSerial":"${setup.serial}","evidenceReference":"signed-rma-handover"}"""
        val outbound = request("POST", "${setup.path}/rma-handover", setup.token, body, "rma-outbound")
        assertThat(outbound.status).withFailMessage(outbound.contentAsString).isEqualTo(201)
        val handover = mapper.readTree(outbound.contentAsString)
        val id = handover.path("id").asString()
        val returnDetail = request("GET", "${setup.path}/details", setup.token)
        assertThat(returnDetail.status).withFailMessage(returnDetail.contentAsString).isEqualTo(200)
        val savedCase = mapper.readTree(returnDetail.contentAsString)
        assertThat(savedCase.path("references").path("rmaHandoverId").asString()).isEqualTo(id)
        assertThat(savedCase.path("returnCase")).isEqualTo(inspected)
        assertThat(handover.path("customerId").asString()).isEqualTo(customer.toString())
        assertThat(handover.path("originalAssignmentId").asString()).isEqualTo(setup.returned.old.installation.operation.toString())
        assertThat(handover.path("state").asString()).isEqualTo("DISPATCHED")
        assertThat(request("POST", "${setup.path}/rma-handover", setup.token, body, "rma-outbound").contentAsString).isEqualTo(outbound.contentAsString)
        val ack = """{"expectedRevision":1,"observedSerial":"${setup.serial}","evidenceReference":"technician-received-rma"}"""
        assertThat(request("POST", "/api/v1/warehouse/rma-handovers/$id/acknowledge", setup.token, ack, "rma-wrong-receiver").status).isEqualTo(403)
        val received = request("POST", "/api/v1/warehouse/rma-handovers/$id/acknowledge", receipt.receiver.first, ack, "rma-ack")
        assertThat(received.status).withFailMessage(received.contentAsString).isEqualTo(200)
        val current = mapper.readTree(received.contentAsString)
        assertThat(current.path("state").asString()).isEqualTo("RECEIVED")
        assertThat(current.path("revision").asLong()).isEqualTo(2)
        assertThat(current.path("legalOwner").asString()).isEqualTo("CUSTOMER")
        assertThat(request("POST", "/api/v1/warehouse/rma-handovers/$id/acknowledge", receipt.receiver.first, ack, "rma-ack").contentAsString)
            .isEqualTo(received.contentAsString)
        fixture(setup.token).transaction {
            assertThat(scalar("SELECT concat_ws('|',custody_owner_kind,custody_owner_id,location_id,status,condition,legal_owner) FROM inventory_serialized_asset WHERE id='${setup.asset}'"))
                .isEqualTo("TECHNICIAN|${receipt.receiver.second}|${receipt.field}|ISSUED|SERVICEABLE|CUSTOMER")
            assertThat(scalar("SELECT count(*) FROM inventory_balance_projection WHERE stock_identity_id='${setup.asset}' AND quantity_base>0 AND status='AVAILABLE' AND legal_owner='ISP'"))
                .isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE document_id='$id'")).isEqualTo("2")
            assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment WHERE asset_id='${setup.asset}' AND ended_at IS NOT NULL")).isEqualTo("1")
        }
    }
}
