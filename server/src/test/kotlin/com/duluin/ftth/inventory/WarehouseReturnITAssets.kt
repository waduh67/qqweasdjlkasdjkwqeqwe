package com.duluin.ftth.inventory

import com.duluin.ftth.customer.CustomerAssetReplacementFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class WarehouseReturnITAssets : CustomerAssetReplacementFixture() {
    @ParameterizedTest @ValueSource(strings = ["LOAN", "SALE"])
    fun `recovered physical asset needs receipt and reset while preserving its original title`(mode: String) {
        val old = ownershipCase(mode)
        assertThat(accept(old).status).isEqualTo(200)
        val receipt = old.installation.receipt
        val admin = receipt.stock.token
        val asset = receipt.input.lines.single().stockIdentityId
        val order = workOrder(admin, "DISMANTLE", old.installation.customer.toString())
        assign(admin, order, receipt.receiver.second)
        val evidence = removalEvidence(receipt.receiver.first, order)
        val removed = request("POST", "/api/customers/${old.installation.customer}/assets/remove", receipt.receiver.first,
            """{"assignmentId":"${old.installation.operation}","expectedRevision":1,"expectedTitleRevision":${if (mode == "SALE") 1 else 0},"workOrderId":"$order","evidenceId":"$evidence"}""", "return-remove")
        assertThat(removed.status).withFailMessage(removed.contentAsString).isEqualTo(200)
        val source = mapper.readTree(removed.contentAsString).path("operationId").asString()
        val quarantine = create("locations", admin,
            """{"code":"ASSET_RETURN","name":"Device inspection","kind":"QUARANTINE"}""").path("id").asString()
        val body = """{"origin":"ASSET_REMOVAL","sourceDocumentId":"$source","quarantineLocationId":"$quarantine","evidenceReference":"received-original-device"}"""
        val received = request("POST", "/api/v1/warehouse/returns", admin, body, "asset-return-intake")
        assertThat(received.status).withFailMessage(received.contentAsString).isEqualTo(201)
        val result = mapper.readTree(received.contentAsString)
        assertThat(result.path("stockIdentityId").asString()).isEqualTo(asset.toString())
        assertThat(result.path("legalOwner").asString()).isEqualTo(if (mode == "SALE") "CUSTOMER" else "ISP")
        val id = result.path("id").asString()
        val serial = fixture(admin).transaction { scalar("SELECT serial_number FROM inventory_serialized_asset WHERE id='$asset'") }
        val destination = if (mode == "SALE") quarantine else receipt.stock.bin
        val inspection = """{"expectedRevision":${result.path("revision").asLong()},"measuredQuantityBase":"1","condition":"SERVICEABLE","destinationLocationId":"$destination","evidenceReference":"identity-and-condition-verified","observedSerial":"$serial","resetConfirmed":true,"resetEvidenceReference":"factory-reset-and-configuration-erasure"}"""
        val path = "/api/v1/warehouse/returns/$id/inspect"
        assertThat(request("POST", path, admin, inspection.replace("\"resetConfirmed\":true", "\"resetConfirmed\":false")).status).isEqualTo(409)
        assertThat(request("POST", path, admin, inspection.replace(serial, "WRONG-SERIAL")).status).isEqualTo(409)
        if (mode == "SALE") assertThat(request("POST", path, admin, inspection.replace(quarantine, receipt.stock.bin)).status).isEqualTo(409)
        val inspected = request("POST", path, admin, inspection, "asset-return-inspect")
        assertThat(inspected.status).withFailMessage(inspected.contentAsString).isEqualTo(200)
        fixture(admin).transaction {
            assertThat(scalar("SELECT concat_ws('|',status,condition,legal_owner) FROM inventory_serialized_asset WHERE id='$asset'"))
                .isEqualTo(if (mode == "SALE") "QUARANTINE|SERVICEABLE|CUSTOMER" else "AVAILABLE|SERVICEABLE|ISP")
            assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment WHERE asset_id='$asset' AND ended_at IS NOT NULL")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_balance_projection WHERE stock_identity_id='$asset' AND quantity_base>0 AND legal_owner='ISP' AND status='AVAILABLE'"))
                .isEqualTo(if (mode == "SALE") "0" else "1")
        }
        assertThat(request("POST", "/api/v1/warehouse/returns", admin, body, "asset-return-intake").contentAsString).isEqualTo(received.contentAsString)
        assertThat(request("POST", path, admin, inspection, "asset-return-inspect").contentAsString).isEqualTo(inspected.contentAsString)
    }
}
