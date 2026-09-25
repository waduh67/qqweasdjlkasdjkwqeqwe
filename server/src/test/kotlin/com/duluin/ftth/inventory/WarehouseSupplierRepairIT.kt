package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class WarehouseSupplierRepairIT : WarehouseReturnAssetFixture() {
    @ParameterizedTest @ValueSource(strings = ["LOAN", "SALE"])
    fun `supplier repair preserves physical identity and title and requires inspection after receipt`(mode: String) {
        val returned = recoveredReturn(mode, serials = listOf("Mixed-Repair-1", "Mixed-Repair-2"))
        val receipt = returned.old.installation.receipt
        val admin = receipt.stock.token
        val asset = receipt.input.lines.single().stockIdentityId
        val serial = receipt.input.lines.single().serial
        val owner = if (mode == "SALE") "CUSTOMER" else "ISP"
        val vendor = create("suppliers", admin, """{"code":"SERVICE_VENDOR","name":"Repair vendor"}""").path("id").asString()
        val repairLocation = create("locations", admin,
            """{"code":"SERVICE_CUSTODY","name":"Supplier repair custody","kind":"TRANSIT"}""").path("id").asString()
        val dispatchBody = """{"expectedRevision":${returned.revision},"vendorId":"$vendor","repairLocationId":"$repairLocation","vendorReference":"RMA-123","evidenceReference":"supplier-collected-device","observedSerial":"$serial"}"""
        val dispatched = request("POST", "/api/v1/warehouse/returns/${returned.id}/repair-dispatch", admin, dispatchBody, "repair-dispatch")
        assertThat(dispatched.status).withFailMessage(dispatched.contentAsString).isEqualTo(200)
        val outbound = mapper.readTree(dispatched.contentAsString)
        assertThat(outbound.path("state").asString()).isEqualTo("REPAIR")
        assertThat(outbound.path("repair").path("vendorId").asString()).isEqualTo(vendor)
        assertThat(request("POST", "/api/v1/warehouse/returns/${returned.id}/repair-dispatch", admin, dispatchBody, "repair-dispatch").contentAsString)
            .isEqualTo(dispatched.contentAsString)
        fixture(admin).transaction {
            assertThat(scalar("SELECT concat_ws('|',custody_owner_kind,custody_owner_id,location_id,status,condition,legal_owner) FROM inventory_serialized_asset WHERE id='$asset'"))
                .isEqualTo("REPAIR|$vendor|$repairLocation|QUARANTINE|DAMAGED|$owner")
        }
        fun inspection(revision: Long) = """{"expectedRevision":$revision,"measuredQuantityBase":"1","condition":"SERVICEABLE","destinationLocationId":"${if (mode == "SALE") returned.quarantine else receipt.stock.bin}","evidenceReference":"post-repair-inspection","observedSerial":"$serial","resetConfirmed":true,"resetEvidenceReference":"post-repair-reset"}"""
        assertThat(request("POST", "/api/v1/warehouse/returns/${returned.id}/inspect", admin,
            inspection(outbound.path("revision").asLong()), "repair-premature-inspection").status).isEqualTo(409)
        fun incoming(observed: String) = """{"expectedRevision":${outbound.path("revision").asLong()},"observedSerial":"$observed","quarantineLocationId":"${returned.quarantine}","result":"REPAIRED","vendorReference":"RMA-123-complete","evidenceReference":"supplier-delivery-slip"}"""
        assertThat(request("POST", "/api/v1/warehouse/returns/${returned.id}/repair-receive", admin, incoming("OTHER-PHYSICAL-DEVICE"), "repair-wrong-device").status)
            .isEqualTo(409)
        val received = request("POST", "/api/v1/warehouse/returns/${returned.id}/repair-receive", admin, incoming(serial!!), "repair-receive")
        assertThat(received.status).withFailMessage(received.contentAsString).isEqualTo(200)
        val inbound = mapper.readTree(received.contentAsString)
        assertThat(inbound.path("state").asString()).isEqualTo("RECEIVED_IN_INSPECTION")
        assertThat(inbound.path("stockIdentityId").asString()).isEqualTo(asset.toString())
        assertThat(inbound.path("legalOwner").asString()).isEqualTo(owner)
        assertThat(inbound.path("repair").path("result").asString()).isEqualTo("REPAIRED")
        fixture(admin).transaction {
            assertThat(scalar("SELECT concat_ws('|',status,condition,legal_owner) FROM inventory_serialized_asset WHERE id='$asset'"))
                .isEqualTo("QUARANTINE|DAMAGED|$owner")
            assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE document_id='${returned.id}' AND kind='REPAIR'"))
                .isEqualTo("2")
        }
        assertThat(request("POST", "/api/v1/warehouse/returns/${returned.id}/repair-receive", admin, incoming(serial), "repair-receive").contentAsString)
            .isEqualTo(received.contentAsString)
        val inspected = request("POST", "/api/v1/warehouse/returns/${returned.id}/inspect", admin,
            inspection(inbound.path("revision").asLong()), "repair-inspect")
        assertThat(inspected.status).withFailMessage(inspected.contentAsString).isEqualTo(200)
        fixture(admin).transaction {
            assertThat(scalar("SELECT concat_ws('|',status,condition,legal_owner) FROM inventory_serialized_asset WHERE id='$asset'"))
                .isEqualTo("${if (mode == "SALE") "QUARANTINE" else "AVAILABLE"}|SERVICEABLE|$owner")
            assertThat(scalar("SELECT count(*) FROM inventory_balance_projection WHERE stock_identity_id='$asset' AND quantity_base>0 AND status='AVAILABLE' AND legal_owner='ISP'"))
                .isEqualTo(if (mode == "SALE") "0" else "1")
            assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment WHERE asset_id='$asset' AND ended_at IS NOT NULL")).isEqualTo("1")
        }
    }
}
