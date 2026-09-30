package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import tools.jackson.databind.JsonNode

abstract class WarehouseRepairFixture : WarehouseReturnAssetFixture() {
    protected data class RepairSetup(val returned: RecoveredReturn, val vendor: String, val location: String) {
        val token get() = returned.old.installation.receipt.stock.token
        val path get() = "/api/v1/warehouse/returns/${returned.id}"
        val serial get() = requireNotNull(returned.old.installation.receipt.input.lines.single().serial)
        val asset get() = returned.old.installation.receipt.input.lines.single().stockIdentityId
        val dispatchBody get() = """{"expectedRevision":${returned.revision},"vendorId":"$vendor","repairLocationId":"$location","vendorReference":"REPAIR-JOB","evidenceReference":"supplier-pickup","observedSerial":"$serial"}"""
        fun receiptBody(revision: Long) = """{"expectedRevision":$revision,"observedSerial":"$serial","quarantineLocationId":"${returned.quarantine}","result":"REPAIRED","vendorReference":"REPAIR-RESULT","evidenceReference":"supplier-return"}"""
    }

    protected fun repairSetup(mode: String = "SALE", serials: List<String> = listOf("RECEIVE-1", "RECEIVE-2")): RepairSetup {
        val returned = recoveredReturn(mode, serials = serials)
        val admin = returned.old.installation.receipt.stock.token
        val vendor = create("suppliers", admin, """{"code":"REPAIR_VENDOR","name":"Repair vendor"}""").path("id").asString()
        val location = create("locations", admin, """{"code":"REPAIR_LOCATION","name":"Repair custody","kind":"TRANSIT"}""").path("id").asString()
        return RepairSetup(returned, vendor, location)
    }

    protected fun dispatchRepair(setup: RepairSetup, actor: String = setup.token): JsonNode {
        val response = request("POST", "${setup.path}/repair-dispatch", actor, setup.dispatchBody, "supplier-outbound")
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        return mapper.readTree(response.contentAsString)
    }

    protected fun receiveRepair(setup: RepairSetup, outbound: JsonNode, actor: String = setup.token): JsonNode {
        val response = request("POST", "${setup.path}/repair-receive", actor, setup.receiptBody(outbound.path("revision").asLong()), "supplier-inbound")
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        return mapper.readTree(response.contentAsString)
    }

    protected fun inspectRepair(setup: RepairSetup, inbound: JsonNode): JsonNode {
        val stock = setup.returned.old.installation.receipt.stock
        val destination = if (inbound.path("legalOwner").asString() == "CUSTOMER") setup.returned.quarantine else stock.bin
        val response = request("POST", "${setup.path}/inspect", setup.token,
            """{"expectedRevision":${inbound.path("revision").asLong()},"measuredQuantityBase":"1","condition":"SERVICEABLE","destinationLocationId":"$destination","evidenceReference":"post-service-inspection","observedSerial":"${setup.serial}","resetConfirmed":true,"resetEvidenceReference":"post-service-reset"}""", "supplier-inspection")
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        return mapper.readTree(response.contentAsString)
    }
}
