package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarehouseCustomerRmaDeploymentIT : WarehouseCustomerRmaFixture() {
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
