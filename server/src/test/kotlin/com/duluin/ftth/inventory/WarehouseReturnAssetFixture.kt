package com.duluin.ftth.inventory

import com.duluin.ftth.customer.CustomerAssetReplacementFixture
import org.assertj.core.api.Assertions.assertThat

abstract class WarehouseReturnAssetFixture : CustomerAssetReplacementFixture() {
    protected data class RecoveredReturn(val old: OwnershipCase, val id: String, val quarantine: String, val revision: Long)

    protected fun recoveredReturn(mode: String = "LOAN", release: Boolean = false, serials: List<String> = listOf("RECEIVE-1", "RECEIVE-2")): RecoveredReturn {
        val old = ownershipCase(mode, serials)
        assertThat(accept(old).status).isEqualTo(200)
        val receipt = old.installation.receipt
        val admin = receipt.stock.token
        val order = workOrder(admin, "DISMANTLE", old.installation.customer.toString())
        assign(admin, order, receipt.receiver.second)
        val evidence = removalEvidence(receipt.receiver.first, order)
        val removed = request("POST", "/api/customers/${old.installation.customer}/assets/remove", receipt.receiver.first,
            """{"assignmentId":"${old.installation.operation}","expectedRevision":1,"expectedTitleRevision":${if (mode == "SALE") 1 else 0},"workOrderId":"$order","evidenceId":"$evidence"}""", "recover-remove")
        assertThat(removed.status).withFailMessage(removed.contentAsString).isEqualTo(200)
        val source = mapper.readTree(removed.contentAsString).path("operationId").asString()
        val quarantine = create("locations", admin,
            """{"code":"RECOVERED_ASSET","name":"Recovered asset inspection","kind":"QUARANTINE"}""").path("id").asString()
        val received = request("POST", "/api/v1/warehouse/returns", admin,
            """{"origin":"ASSET_REMOVAL","sourceDocumentId":"$source","quarantineLocationId":"$quarantine","evidenceReference":"witnessed-device-return"}""", "recover-receive")
        assertThat(received.status).withFailMessage(received.contentAsString).isEqualTo(201)
        val view = mapper.readTree(received.contentAsString)
        val id = view.path("id").asString()
        val destination = if (release) receipt.stock.bin else quarantine
        val serial = receipt.input.lines.single().serial
        val result = request("POST", "/api/v1/warehouse/returns/$id/inspect", admin,
            """{"expectedRevision":${view.path("revision").asLong()},"measuredQuantityBase":"1","condition":"${if (release) "SERVICEABLE" else "DAMAGED"}","destinationLocationId":"$destination","evidenceReference":"measured-device-condition","observedSerial":"$serial","resetConfirmed":$release${if (release) ",\"resetEvidenceReference\":\"factory-reset-and-erasure\"" else ""}}""", "recover-inspect")
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
        return RecoveredReturn(old, id, quarantine, mapper.readTree(result.contentAsString).path("revision").asLong())
    }
}
