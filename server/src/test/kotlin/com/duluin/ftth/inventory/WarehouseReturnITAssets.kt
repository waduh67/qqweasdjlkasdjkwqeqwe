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
        val discovered = request("GET", "/api/v1/warehouse/returns/sources?origin=ASSET_REMOVAL&serial=${receipt.input.lines.single().serial}", admin)
        assertThat(discovered.status).withFailMessage(discovered.contentAsString).isEqualTo(200)
        val candidate = mapper.readTree(discovered.contentAsString).path("items").single()
        assertThat(candidate.path("sourceDocumentId").asString()).isEqualTo(source)
        assertThat(candidate.path("stockIdentityId").asString()).isEqualTo(asset.toString())
        assertThat(candidate.path("quantityBase").asString()).isEqualTo("1")
        assertThat(candidate.path("legalOwner").asString()).isEqualTo(if (mode == "SALE") "CUSTOMER" else "ISP")
        assertThat(candidate.path("quarantineLocationId").isNull).isTrue()
        // Giving the remover warehouse intake permission must not make their own recovery eligible.
        val manager = user(admin, setOf("inventory.return.manage"))
        val managerRoleIds = mapper.readTree(request("GET", "/api/users/${manager.second}", admin).contentAsString).path("roleIds").asSequence().map { it.asString() }.toList()
        val removerRoleIds = mapper.readTree(request("GET", "/api/users/${receipt.receiver.second}", admin).contentAsString).path("roleIds").asSequence().map { it.asString() }.toList()
        assertThat(request("PUT", "/api/users/${receipt.receiver.second}/access", admin, mapper.writeValueAsString(mapOf(
            "roleIds" to (managerRoleIds+removerRoleIds).distinct(), "areaIds" to listOf(area(admin))))).status).isEqualTo(200)
        val self = request("GET", "/api/v1/warehouse/returns/sources?origin=ASSET_REMOVAL", receipt.receiver.first)
        assertThat(self.status).isEqualTo(200)
        assertThat(mapper.readTree(self.contentAsString).path("totalElements").asLong()).isZero()
        val quarantine = create("locations", admin,
            """{"code":"ASSET_RETURN","name":"Device inspection","kind":"QUARANTINE"}""").path("id").asString()
        val body = """{"origin":"ASSET_REMOVAL","sourceDocumentId":"$source","quarantineLocationId":"$quarantine","evidenceReference":"received-original-device"}"""
        val received = request("POST", "/api/v1/warehouse/returns", admin, body, "asset-return-intake")
        assertThat(received.status).withFailMessage(received.contentAsString).isEqualTo(201)
        val result = mapper.readTree(received.contentAsString)
        val remainingSources = request("GET", "/api/v1/warehouse/returns/sources?origin=ASSET_REMOVAL", admin)
        assertThat(remainingSources.status).isEqualTo(200)
        assertThat(mapper.readTree(remainingSources.contentAsString).path("totalElements").asLong()).isZero()
        assertThat(result.path("stockIdentityId").asString()).isEqualTo(asset.toString())
        assertThat(result.path("legalOwner").asString()).isEqualTo(if (mode == "SALE") "CUSTOMER" else "ISP")
        val id = result.path("id").asString()
        val detail = request("GET", "/api/v1/warehouse/returns/$id/details", admin)
        assertThat(detail.status).withFailMessage(detail.contentAsString).isEqualTo(200)
        val references = mapper.readTree(detail.contentAsString).path("references")
        assertThat(references.path("workOrderId").asString()).isEqualTo(order)
        val original = references.path("assetOrigin")
        assertThat(original.path("assignmentId").asString()).isEqualTo(old.installation.operation.toString())
        assertThat(original.path("customerId").asString()).isEqualTo(old.installation.customer.toString())
        assertThat(original.path("workOrderId").asString()).isEqualTo(receipt.workOrder)
        assertThat(original.path("workOrderId")).isNotEqualTo(references.path("workOrderId"))
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
