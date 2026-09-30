package com.duluin.ftth.customer

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class CustomerAssetTitleApprovalMatrixIT : CustomerAssetTitleGuardCases() {
    override fun installationCustomer(stock: Setup): CustomerIdentity {
        val actor = user(stock.token, setOf("inventory.approval.view", "inventory.approval.decide"))
        return CustomerIdentity(UUID.fromString(actor.second), actor)
    }

    @Test
    fun `owning customer with valid approval permissions is excluded while independent approver succeeds`() {
        val case = correctionCase()
        val customer = requireNotNull(case.ownership.installation.receipt.customerActor)
        val admin = case.ownership.installation.receipt.stock.token
        assertThat(customer.second).isEqualTo(case.ownership.installation.customer.toString())
        grantTitleApprover(case, customer.second)
        configureTiers(case, listOf(listOf(customer.second, case.checker.second)))
        val pending = submitCorrection(case)
        val before = effectState(case)

        val denied = decide(pending, customer.first, 0, "customer-self")

        assertThat(denied.status).withFailMessage(denied.contentAsString).isEqualTo(403)
        assertThat(mapper.readTree(denied.contentAsString).path("code").asString()).isEqualTo("FORBIDDEN")
        assertThat(effectState(case)).isEqualTo(before)
        fixture(admin).transaction {
            assertThat(scalar("SELECT jsonb_exists(independence_snapshot,'${customer.second}')::text FROM inventory_approval WHERE id='${pending.approval}'")).isEqualTo("true")
        }
        val approved = decide(pending, case.checker.first, 0, "independent-customer-control")
        assertThat(approved.status).withFailMessage(approved.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(approved.contentAsString).path("status").asString()).isEqualTo("APPROVED")
        assertThat(title(case.ownership)).isEqualTo("SALE|ISP|ISP|CUSTOMER_INSTALLED|2|1")
    }

    @Test
    fun `all three configured tiers must approve despite thresholds above the neutral title value`() {
        val case = correctionCase()
        val admin = case.ownership.installation.receipt.stock.token
        val second = user(admin, setOf("inventory.approval.view", "inventory.approval.decide"))
        val third = user(admin, setOf("inventory.approval.view", "inventory.approval.decide"))
        grantTitleApprover(case, second.second)
        grantTitleApprover(case, third.second)
        configureTiers(case, listOf(listOf(case.checker.second), listOf(second.second), listOf(third.second)))
        val pending = submitCorrection(case)
        val before = effectState(case)
        fixture(admin).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_approval_requirement WHERE approval_id='${pending.approval}'")).isEqualTo("3")
        }

        val outOfOrder = decide(pending, third.first, 0, "third-before-first")

        assertThat(outOfOrder.status).isEqualTo(403)
        assertThat(effectState(case)).isEqualTo(before)
        val first = decide(pending, case.checker.first, 0, "tier-one")
        assertThat(first.status).withFailMessage(first.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(first.contentAsString).path("status").asString()).isEqualTo("PENDING")
        assertThat(effectState(case)).isEqualTo(before)
        val repeatedActor = decide(pending, case.checker.first, 1, "first-cannot-be-second")
        assertThat(repeatedActor.status).isEqualTo(403)
        assertThat(effectState(case)).isEqualTo(before)
        val middle = decide(pending, second.first, 1, "tier-two")
        assertThat(middle.status).withFailMessage(middle.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(middle.contentAsString).path("status").asString()).isEqualTo("PENDING")
        assertThat(effectState(case)).isEqualTo(before)
        val final = decide(pending, third.first, 2, "tier-three")
        assertThat(final.status).withFailMessage(final.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(final.contentAsString).path("status").asString()).isEqualTo("APPROVED")
        assertThat(title(case.ownership)).isEqualTo("SALE|ISP|ISP|CUSTOMER_INSTALLED|2|1")
        assertThat(decide(pending, third.first, 2, "tier-three").contentAsString).isEqualTo(final.contentAsString)
        fixture(admin).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_approval_decision WHERE approval_id='${pending.approval}'")).isEqualTo("3")
            assertThat(scalar("SELECT count(*) FROM inventory_asset_title_transfer")).isEqualTo("1")
        }
    }

    private fun configureTiers(case: CorrectionCase, users: List<List<String>>) {
        val admin = case.ownership.installation.receipt.stock.token
        val location = fixture(admin).transaction {
            scalar("SELECT location_id FROM inventory_serialized_asset WHERE id='${case.ownership.installation.receipt.input.lines.single().stockIdentityId}'")
        }
        val thresholds = listOf("1", "1000000", "1000000000")
        val response = request("PUT", "/api/v1/warehouse/settings/policy", admin, mapper.writeValueAsString(mapOf(
            "expectedRevision" to 1, "currency" to "IDR", "expiryHours" to 24, "warehouseIds" to listOf(location),
            "rules" to listOf(mapOf("operation" to "TITLE_REACQUISITION", "tiers" to users.mapIndexed { index, userIds ->
                mapOf("minimumMinor" to thresholds[index], "userIds" to userIds, "roleIds" to emptyList<String>())
            })))))
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
    }

    private fun decide(pending: PendingCorrection, actor: String, revision: Int, key: String) = request("POST",
        "/api/v1/warehouse/approvals/decide", actor,
        """{"requestId":"${pending.approval}","expectedRevision":$revision,"decision":"APPROVE"}""", key)

    private fun effectState(case: CorrectionCase): String = fixture(case.ownership.installation.receipt.stock.token).transaction {
        scalar("""SELECT concat_ws('|',(SELECT count(*) FROM inventory_movement),(SELECT count(*) FROM inventory_movement_leg),
            (SELECT count(*) FROM inventory_asset_title_transfer),(SELECT count(*) FROM inventory_asset_recovery_obligation),
            (SELECT count(*) FROM inventory_asset_recovery_transition),
            (SELECT md5(to_jsonb(assignment)::text) FROM inventory_asset_assignment assignment WHERE id='${case.ownership.installation.operation}'))""")
    }
}
