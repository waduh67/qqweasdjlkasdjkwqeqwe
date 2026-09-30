package com.duluin.ftth.customer

import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehousePolicyPersistence
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.reset
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.Instant
import java.util.UUID

abstract class CustomerAssetTitleGuardCases : CustomerAssetTitleCorrectionCases() {
    @MockitoSpyBean lateinit var approvalClock: WarehousePolicyPersistence

    @ParameterizedTest
    @ValueSource(strings = ["CUSTODIAN", "DELEGATE"])
    fun `custodian and delegated custodian authority cannot approve their own title transfer`(actor: String) {
        val case = correctionCase()
        val admin = case.ownership.installation.receipt.stock.token
        val custodian = case.ownership.installation.receipt.receiver
        val location = fixture(admin).transaction {
            scalar("SELECT location_id FROM inventory_serialized_asset WHERE id='${case.ownership.installation.receipt.input.lines.single().stockIdentityId}'")
        }
        grantTitleApprover(case, custodian.second)
        val independent = if (actor == "DELEGATE") user(admin, setOf("inventory.approval.view", "inventory.approval.decide")) else case.checker
        if (actor == "DELEGATE") grantTitleApprover(case, independent.second)
        val configured = request("PUT", "/api/v1/warehouse/settings/policy", admin,
            """{"expectedRevision":1,"currency":"IDR","expiryHours":24,"warehouseIds":["$location"],"rules":[{"operation":"TITLE_REACQUISITION","tiers":[{"minimumMinor":"1","userIds":["${custodian.second}","${independent.second}"],"roleIds":[]}]}]}""")
        assertThat(configured.status).withFailMessage(configured.contentAsString).isEqualTo(200)
        if (actor == "DELEGATE") {
            val delegation = request("POST", "/api/v1/warehouse/settings/delegations", admin,
                """{"expectedRevision":0,"approverId":"${custodian.second}","delegateId":"${case.checker.second}","sourceRoleId":null,"locationId":"$location","operation":"TITLE_REACQUISITION","validUntil":"${Instant.now().plusSeconds(3600)}"}""")
            assertThat(delegation.status).withFailMessage(delegation.contentAsString).isEqualTo(200)
        }
        val pending = submitCorrection(case)

        val decision = decideCorrection(pending, actor = if (actor == "CUSTODIAN") custodian.first else case.checker.first)

        assertThat(decision.status).isIn(403, 404)
        assertThat(title(case.ownership)).isEqualTo("SALE|CUSTOMER|CUSTOMER|CUSTOMER_INSTALLED|1|1")
    }

    protected fun grantTitleApprover(case: CorrectionCase, userId: String) {
        val admin = case.ownership.installation.receipt.stock.token
        val adminRoles = mapper.readTree(request("GET", "/api/me", admin).contentAsString).path("roleIds").asSequence().map { it.asString() }.toList()
        val principal = mapper.readTree(request("GET", "/api/users/$userId", admin).contentAsString)
        val roles = (adminRoles + principal.path("roleIds").asSequence().map { it.asString() }).distinct()
        assertThat(request("PUT", "/api/users/$userId/access", admin, mapper.writeValueAsString(mapOf("roleIds" to roles, "areaIds" to listOf(area(admin))))).status).isEqualTo(200)
        val location = fixture(admin).transaction {
            scalar("SELECT location_id FROM inventory_serialized_asset WHERE id='${case.ownership.installation.receipt.input.lines.single().stockIdentityId}'")
        }
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/$userId/$location", admin,
            """{"expectedRevision":0,"active":true}""").status).isEqualTo(200)
    }

    @Test
    fun `approved correction creates neither stock availability nor commercial rows`() {
        val pending = pendingCorrection()

        val decision = decideCorrection(pending)

        assertThat(decision.status).withFailMessage(decision.contentAsString).isEqualTo(200)
        fixture(pending.case.ownership.installation.receipt.stock.token).transaction {
            assertThat(scalar("SELECT (SELECT count(*) FROM invoice)+(SELECT count(*) FROM payment)+(SELECT count(*) FROM refund)")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_serialized_asset")).isEqualTo("2")
            assertThat(scalar("SELECT count(*) FROM inventory_balance_projection WHERE stock_identity_id='${pending.case.ownership.installation.receipt.input.lines.single().stockIdentityId}' AND quantity_base>0 AND status='AVAILABLE'")).isEqualTo("0")
        }
    }

    @Test
    fun `requester cannot approve their own title correction`() {
        val pending = pendingCorrection()

        val decision = decideCorrection(pending, actor = pending.case.ownership.installation.receipt.stock.token)

        assertThat(decision.status).isIn(403, 404)
        assertThat(title(pending.case.ownership)).isEqualTo("SALE|CUSTOMER|CUSTOMER|CUSTOMER_INSTALLED|1|1")
        fixture(pending.case.ownership.installation.receipt.stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_asset_title_transfer")).isEqualTo("0")
        }
    }

    @Test
    fun `rejected correction retains customer title without a transfer`() {
        val pending = pendingCorrection()

        val decision = decideCorrection(pending, "REJECT")

        assertThat(decision.status).withFailMessage(decision.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(decision.contentAsString).path("status").asString()).isEqualTo("REWORK_REQUIRED")
        fixture(pending.case.ownership.installation.receipt.stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_asset_title_transfer")).isEqualTo("0")
        }
    }

    @Test
    fun `expired correction returns an exact no-effect replay without transferring title`() {
        val case = correctionCase()
        val requested = correction(case)
        assertThat(requested.status).isEqualTo(201)
        val document = mapper.readTree(requested.contentAsString).path("documentId").asString()
        doReturn(Instant.now().minusSeconds(48 * 3600)).`when`(approvalClock).now()
        val submitted = try {
            request("POST", "/api/v1/warehouse/approvals/request", case.ownership.installation.receipt.stock.token,
                """{"sourceDocumentId":"$document","sourceRevision":0}""", "expired-request")
        } finally { reset(approvalClock) }
        assertThat(submitted.status).withFailMessage(submitted.contentAsString).isEqualTo(201)
        val pending = PendingCorrection(case, document, mapper.readTree(submitted.contentAsString).path("requestId").asString())

        val decision = decideCorrection(pending)

        assertThat(decision.status).isEqualTo(409)
        assertThat(mapper.readTree(decision.contentAsString).path("status").asString()).isEqualTo("EXPIRED")
        assertThat(decideCorrection(pending).contentAsString).isEqualTo(decision.contentAsString)
        assertThat(title(case.ownership)).isEqualTo("SALE|CUSTOMER|CUSTOMER|CUSTOMER_INSTALLED|1|1")
    }

    @Test
    fun `correction request and decision replay remain exact after execution`() {
        val pending = pendingCorrection()
        val requestBody = correction(pending.case).contentAsString
        val first = decideCorrection(pending)
        assertThat(first.status).withFailMessage(first.contentAsString).isEqualTo(200)

        val replay = decideCorrection(pending)

        assertThat(replay.contentAsString).isEqualTo(first.contentAsString)
        assertThat(correction(pending.case).contentAsString).isEqualTo(requestBody)
        assertThat(correction(pending.case, "different-key").status).isEqualTo(409)
        assertThat(title(pending.case.ownership)).isEqualTo("SALE|ISP|ISP|CUSTOMER_INSTALLED|2|1")
    }

    @ParameterizedTest
    @ValueSource(strings = ["MODE", "OWNER", "TRANSFER_DELETE", "REQUEST_EVIDENCE", "REQUEST_SOURCE_REVISION", "RECOVERY_DELETE",
        "EXTRA_HEADER", "EXTRA_OPERATION", "MISSING_LEG", "EXTRA_LEG", "SUBSTITUTED_LEG", "HANDOVER_DELETE", "ORIGIN_DELETE"])
    fun `app role cannot alter an approved transfer graph`(mutation: String) {
        val pending = pendingCorrection()
        val decision = decideCorrection(pending)
        assertThat(decision.status).withFailMessage(decision.contentAsString).isEqualTo(200)
        val stock = fixture(pending.case.ownership.installation.receipt.stock.token)

        assertThrows<Exception> { stock.transaction {
            when (mutation) {
                "MODE" -> sql("UPDATE inventory_asset_assignment SET ownership_mode='LOAN',revision=revision+1 WHERE id='${pending.case.ownership.installation.operation}'")
                "OWNER" -> sql("UPDATE inventory_asset_assignment SET legal_owner='CUSTOMER',revision=revision+1 WHERE id='${pending.case.ownership.installation.operation}'")
                "TRANSFER_DELETE" -> sql("DELETE FROM inventory_asset_title_transfer WHERE request_id='${pending.document}'")
                "REQUEST_EVIDENCE" -> sql("UPDATE inventory_asset_title_request SET evidence_digest=repeat('0',64) WHERE id='${pending.document}'")
                "REQUEST_SOURCE_REVISION" -> sql("UPDATE inventory_asset_title_request SET source_assignment_revision=source_assignment_revision+1 WHERE id='${pending.document}'")
                "RECOVERY_DELETE" -> sql("DELETE FROM inventory_asset_recovery_transition WHERE assignment_id='${pending.case.ownership.installation.operation}'")
                "MISSING_LEG" -> sql("DELETE FROM inventory_movement_leg WHERE movement_id=(SELECT posting_id FROM inventory_asset_title_transfer WHERE request_id='${pending.document}')")
                "EXTRA_LEG" -> sql("""INSERT INTO inventory_movement_leg SELECT (jsonb_populate_record(NULL::inventory_movement_leg,
                    to_jsonb(original)||jsonb_build_object('id','${UUID.randomUUID()}'))).* FROM inventory_movement_leg original
                    WHERE movement_id=(SELECT posting_id FROM inventory_asset_title_transfer WHERE request_id='${pending.document}') AND direction='IN'""")
                "SUBSTITUTED_LEG" -> sql("UPDATE inventory_movement_leg SET stock_identity_id='${UUID.randomUUID()}' WHERE movement_id=(SELECT posting_id FROM inventory_asset_title_transfer WHERE request_id='${pending.document}')")
                "HANDOVER_DELETE" -> sql("DELETE FROM inventory_asset_handover WHERE id='${pending.case.handover}'")
                "ORIGIN_DELETE" -> sql("DELETE FROM inventory_asset_acceptance_origin WHERE handover_id='${pending.case.handover}'")
                "EXTRA_HEADER" -> sql("""INSERT INTO inventory_movement SELECT (jsonb_populate_record(NULL::inventory_movement,
                    to_jsonb(original)||jsonb_build_object('id','${UUID.randomUUID()}','operation_key','extra-title-header'))).*
                    FROM inventory_movement original WHERE document_id='${pending.document}'""")
                "EXTRA_OPERATION" -> sql("""INSERT INTO inventory_operation SELECT (jsonb_populate_record(NULL::inventory_operation,
                    to_jsonb(original)||jsonb_build_object('id','${UUID.randomUUID()}','operation_key','extra-title-operation'))).*
                    FROM inventory_operation original WHERE document_id='${pending.document}'""")
                else -> error("Unknown mutation")
            }
        } }

        assertThat(title(pending.case.ownership)).isEqualTo("SALE|ISP|ISP|CUSTOMER_INSTALLED|2|1")
    }

    @ParameterizedTest
    @ValueSource(strings = ["NORMAL", "CLEARED", "MISMATCHED", "RESTORED"])
    fun `approved title validators own their tenant assertion`(scope: String) {
        val pending = pendingCorrection()
        assertThat(decideCorrection(pending).status).isEqualTo(200)
        val stock = fixture(pending.case.ownership.installation.receipt.stock.token)
        val validate = { stock.transaction {
            val transfer = scalar("SELECT id FROM inventory_asset_title_transfer WHERE request_id='${pending.document}'")
            sql("SELECT set_config('app.tenant_id','${when (scope) { "CLEARED" -> ""; "NORMAL" -> tenant.toString(); else -> UUID.randomUUID().toString() }}',true)")
            if (scope == "RESTORED") sql("SELECT set_config('app.tenant_id','$tenant',true)")
            sql("SELECT warehouse_assert_title_transfer('$tenant','$transfer')")
        } }

        if (scope in setOf("NORMAL", "RESTORED")) validate() else assertThrows<Exception> { validate() }
    }

    @Test
    fun `ceased sale remains unreclaimable until independently approved ISP reacquisition`() {
        val pending = pendingCorrection()
        val case = pending.case.ownership
        assertThat(request("PUT", "/api/customers/${case.installation.customer}/status", case.installation.receipt.stock.token,
            """{"status":"TERMINATED"}""").status).isEqualTo(200)
        val before = mapper.readTree(ownershipReport(case).contentAsString).single()
        assertThat(before.path("recoveryRequired").asBoolean()).isFalse()

        val decision = decideCorrection(pending)

        assertThat(decision.status).withFailMessage(decision.contentAsString).isEqualTo(200)
        val after = mapper.readTree(ownershipReport(case).contentAsString).single()
        assertThat(after.path("legalOwner").asString()).isEqualTo("ISP")
        assertThat(after.path("recoveryDue").asBoolean()).isTrue()
        assertThat(after.path("positionStatus").asString()).isEqualTo("CUSTOMER_INSTALLED")
    }
}
