package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class WarehouseSupplierReplacementIT : WarehouseRepairFixture() {
    @ParameterizedTest @ValueSource(strings = ["LOAN", "SALE"])
    fun `actual supplier replacement receives a distinct verified asset with vendor provenance and preserves owner`(mode: String) {
        val setup = repairSetup(mode)
        val old = setup.returned.old.installation.receipt
        val outbound = dispatchRepair(setup)
        val expectedOwner = if (mode == "SALE") "CUSTOMER" else "ISP"
        val serial = "VENDOR-REPLACEMENT-$mode"
        val input = """{"expectedRevision":${outbound.path("revision").asLong()},"externalReference":"VENDOR-EXCHANGE-$mode","sourceLocationId":"${old.stock.source}","inspectionLocationId":"${setup.returned.quarantine}","skuId":"${old.stock.onu}","serial":"$serial","evidenceReference":"vendor-exchange-delivery-slip"}"""
        val path = "${setup.path}/replacement-receipts"
        val requested = request("POST", path, setup.token, input, "replacement-intake")
        assertThat(requested.status).withFailMessage(requested.contentAsString).isEqualTo(201)
        assertThat(request("POST", path, setup.token, input, "replacement-intake").contentAsString).isEqualTo(requested.contentAsString)
        val receiptId = mapper.readTree(requested.contentAsString).path("receiptId").asString()
        val result = request("POST", "/api/v1/warehouse/receipts/$receiptId/receive", setup.token,
            """{"expectedRevision":0}""", "replacement-receive")
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
        val detail = request("GET", "/api/v1/warehouse/receipts/$receiptId", setup.token)
        assertThat(detail.status).withFailMessage(detail.contentAsString).isEqualTo(200)
        val receipt = mapper.readTree(detail.contentAsString)
        assertThat(receipt.path("supplierId").asString()).isEqualTo(setup.vendor)
        assertThat(receipt.path("externalReference").asString()).isEqualTo("VENDOR-EXCHANGE-$mode")
        val line = receipt.path("lines").single()
        assertThat(line.path("serial").asString()).isEqualTo(serial)
        val piece = line.path("pieces").single()
        val replacement = piece.path("stockIdentityId").asString()
        assertThat(replacement).isNotEqualTo(setup.asset.toString())
        assertThat(piece.path("legalOwner").asString()).isEqualTo(expectedOwner)
        assertThat(piece.path("status").asString()).isEqualTo("QUARANTINE")
        val linked = request("GET", path, setup.token)
        assertThat(linked.status).withFailMessage(linked.contentAsString).isEqualTo(200)
        val link = mapper.readTree(linked.contentAsString).single()
        assertThat(link.path("receiptId").asString()).isEqualTo(receiptId)
        assertThat(link.path("replacementAssetId").asString()).isEqualTo(replacement)
        assertThat(link.path("originalAssetId").asString()).isEqualTo(setup.asset.toString())
        assertThat(link.path("legalOwner").asString()).isEqualTo(expectedOwner)
        fixture(setup.token).transaction {
            assertThat(scalar("SELECT concat_ws('|',custody_owner_kind,legal_owner,status) FROM inventory_serialized_asset WHERE id='${setup.asset}'"))
                .isEqualTo("REPAIR|$expectedOwner|QUARANTINE")
            assertThat(scalar("SELECT origin_document_line_id::text FROM inventory_serialized_asset WHERE id='$replacement'"))
                .isEqualTo(line.path("id").asString())
            assertThat(scalar("SELECT count(*) FROM inventory_balance_projection WHERE stock_identity_id='$replacement' AND status='AVAILABLE' AND quantity_base>0"))
                .isEqualTo("0")
        }
    }
}
