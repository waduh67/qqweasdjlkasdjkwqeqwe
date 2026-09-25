package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class WarehouseCustomerRmaDeploymentIT : WarehouseCustomerRmaFixture() {
    @ParameterizedTest
    @ValueSource(strings = ["", "ROUTER"])
    fun `a repaired serialized device without an ONU category returns without inventing an ONU episode`(category: String) {
        val case = prepareRma(serials = listOf("Generic-Mixed-01", "Generic-Mixed-02"))
        val stock = case.receipt.stock
        val changed = request("PUT", "/api/v1/warehouse/skus/${stock.onu}", stock.token, mapper.writeValueAsString(mapOf(
            "expectedRevision" to 1, "code" to "ONU", "name" to "Serialized customer device", "category" to category.ifEmpty { null },
            "tracking" to "SERIAL", "baseUnit" to "EA", "inspectionRequired" to false, "allowedOwnershipModes" to listOf("LOAN", "SALE"))))
        assertThat(changed.status).withFailMessage(changed.contentAsString).isEqualTo(200)
        val returned = installRma(case)
        fixture(stock.token).transaction {
            assertThat(scalar("SELECT source::jsonb->>'createsOnu' FROM inventory_deployment_execution WHERE authorization_id='${returned.authorization}'"))
                .isEqualTo("false")
            assertThat(scalar("SELECT concat_ws('|',purpose,legal_owner,ownership_mode) FROM inventory_asset_assignment WHERE id='${returned.operation}'"))
                .isEqualTo("RETURN_CUSTOMER_RMA|CUSTOMER|SALE")
            assertThat(scalar("SELECT count(*) FROM onu WHERE asset_id='${case.repair.asset}' AND retired_at IS NULL")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM onu WHERE asset_id='${case.repair.asset}' AND retired_at IS NOT NULL")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE operation_id='${returned.operation}'")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_balance_projection WHERE stock_identity_id='${case.repair.asset}' AND quantity_base>0 AND status='AVAILABLE'"))
                .isEqualTo("0")
        }
    }

    @Test fun `acknowledged inspected sold RMA installs again only for its original customer with title unchanged`() {
        val case = prepareRma()
        val outbound = dispatchRma(case)
        receiveRma(case, outbound)
        val permit = request("POST", "/api/work-orders/${case.work}/assets/authorize", case.receipt.receiver.first,
            case.authorization, "rma-authorization")
        assertThat(permit.status).withFailMessage(permit.contentAsString).isEqualTo(200)
        val permitBody = mapper.readTree(permit.contentAsString)
        val authorization = permitBody.path("authorizationId").asString()
        val operation = permitBody.path("operationId").asString()
        val command = """{"authorizationId":"$authorization","expectedRevision":0,"topology":null}"""
        val installed = request("POST", "/api/customers/${case.customer}/assets/install", case.receipt.receiver.first, command, "rma-install")
        assertThat(installed.status).withFailMessage(installed.contentAsString).isEqualTo(201)
        assertThat(request("POST", "/api/customers/${case.customer}/assets/install", case.receipt.receiver.first, command, "rma-install").contentAsString)
            .isEqualTo(installed.contentAsString)
        fixture(case.repair.token).transaction {
            assertThat(scalar("SELECT concat_ws('|',purpose,legal_owner,ownership_mode,issue_line_id IS NULL) FROM inventory_asset_assignment WHERE id='$operation'"))
                .isEqualTo("RETURN_CUSTOMER_RMA|CUSTOMER|SALE|t")
            assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment WHERE asset_id='${case.repair.asset}'")).isEqualTo("2")
            assertThat(scalar("SELECT count(*) FROM onu WHERE asset_id='${case.repair.asset}' AND retired_at IS NULL")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM onu WHERE asset_id='${case.repair.asset}' AND retired_at IS NOT NULL")).isEqualTo("1")
            assertThat(scalar("SELECT concat_ws('|',status,legal_owner,custody_owner_id) FROM inventory_serialized_asset WHERE id='${case.repair.asset}'"))
                .isEqualTo("CUSTOMER_INSTALLED|CUSTOMER|${case.customer}")
            assertThat(scalar("SELECT count(*) FROM inventory_balance_projection WHERE stock_identity_id='${case.repair.asset}' AND quantity_base>0 AND status='AVAILABLE' AND legal_owner='ISP'"))
                .isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE operation_id='$operation'")).isEqualTo("1")
        }
    }
}
