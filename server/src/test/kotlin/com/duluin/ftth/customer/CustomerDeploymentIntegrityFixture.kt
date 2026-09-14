package com.duluin.ftth.customer

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

abstract class CustomerDeploymentIntegrityFixture : CustomerDeploymentRaceFixture() {
    @Test
    fun `old inventory write route returns the structured workflow conflict without writes`() {
        val install = installation()
        val asset = install.receipt.input.lines.single().stockIdentityId

        val response = request("POST", "/api/inventory/serialized/$asset/installed-onu", install.receipt.stock.token,
            """{"onuId":"${UUID.randomUUID()}","operationKey":"old-route"}""")

        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(409)
        assertThat(mapper.readTree(response.contentAsString).path("code").asString()).isEqualTo("USE_WORKORDER_ASSET_WORKFLOW")
        assertUninstalled(install)
    }

    @ParameterizedTest
    @ValueSource(strings = ["UNACKNOWLEDGED", "UNKNOWN_ASSET", "FOREIGN_WO", "QUARANTINE"])
    fun `mint rejects stock without matching serviceable acknowledged authority`(scenario: String) {
        val case = receiptCase(serial = true, installation = true)
        if (scenario != "UNACKNOWLEDGED") received(case)
        val selected = case.input.lines.single()
        if (scenario == "QUARANTINE") fixture(case.stock.token).transaction {
            sql("UPDATE inventory_serialized_asset SET condition='QUARANTINE',revision=revision+1 WHERE id='${selected.stockIdentityId}'")
            sql("UPDATE inventory_balance_projection SET condition='QUARANTINE',revision=revision+1 WHERE stock_identity_id='${selected.stockIdentityId}' AND quantity_base>0")
        }
        val revision = summary(case.stock.token, case.workOrder).path("revisions").path("workOrderRevision").asLong()
        val target = if (scenario == "FOREIGN_WO") UUID.randomUUID().toString() else case.workOrder
        val asset = if (scenario == "UNKNOWN_ASSET") UUID.randomUUID() else selected.stockIdentityId

        val response = request("POST", "/api/work-orders/$target/assets/authorize", case.receiver.first,
            """{"expectedRevision":$revision,"assetId":"$asset","issueLineId":"${selected.issueLineId}","purpose":"INSTALL"}""", "invalid-mint")

        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(409)
        fixture(case.stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_deployment_authorization")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM onu")).isEqualTo("0")
        }
    }

    @Test
    fun `new receipt revision invalidates old authorization but permits a fresh bound authorization`() {
        val install = installation()
        val case = install.receipt
        val issue = fixture(case.stock.token).transaction {
            mapper.readTree(scalar("SELECT snapshot FROM inventory_issue_snapshot WHERE id='${case.input.issueId}'"))
        }
        val remaining = issue.path("lines").single { it.path("id").asString() != case.input.lines.single().issueLineId.toString() }
        val selection = com.duluin.ftth.inventory.MaterialReceiptSelection(UUID.fromString(remaining.path("id").asString()),
            UUID.fromString(remaining.path("dimension").path("stockIdentityId").asString()), com.duluin.ftth.inventory.WarehouseBaseUnit.EA,
            "1", serial = remaining.path("serial").asString())
        val ack = acknowledge(case, case.input.copy(expectedRevision = case.input.expectedRevision + 1, lines = listOf(selection)), "second-ack")
        assertThat(ack.status).withFailMessage(ack.contentAsString).isEqualTo(200)

        val stale = consume(install)

        assertThat(stale.status).withFailMessage(stale.contentAsString).isEqualTo(409)
        assertUninstalled(install)
        val selected = case.input.lines.single()
        val revision = summary(case.stock.token, case.workOrder).path("revisions").path("workOrderRevision").asLong()
        val fresh = request("POST", "/api/work-orders/${case.workOrder}/assets/authorize", case.receiver.first,
            """{"expectedRevision":$revision,"assetId":"${selected.stockIdentityId}","issueLineId":"${selected.issueLineId}","purpose":"INSTALL"}""", "fresh-authorize")
        assertThat(fresh.status).withFailMessage(fresh.contentAsString).isEqualTo(200)
        val body = mapper.readTree(fresh.contentAsString)
        val next = install.copy(authorization = UUID.fromString(body.path("authorizationId").asString()), operation = UUID.fromString(body.path("operationId").asString()))
        val response = consume(next, "fresh-install")
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(201)
    }

    @Test
    fun `extra movement header cannot attach to a completed deployment operation`() {
        val install = installation()
        val response = consume(install)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(201)

        assertThrows<Exception> {
            fixture(install.receipt.stock.token).transaction {
                sql("""INSERT INTO inventory_movement SELECT (jsonb_populate_record(NULL::inventory_movement,
                    to_jsonb(original)||jsonb_build_object('id','${UUID.randomUUID()}','operation_key','extra-header'))).*
                    FROM inventory_movement original WHERE operation_id='${install.operation}'""")
            }
        }

        fixture(install.receipt.stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE operation_id='${install.operation}'")).isEqualTo("1")
        }
    }
}
