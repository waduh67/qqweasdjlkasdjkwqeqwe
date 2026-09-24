package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.port.outbound.WarehousePosting
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WarehouseAssetLossGuardsIT : WarehouseAssetLossFixture() {
    @Test fun `real recovery makes pending lost loan approval stale and preserves the recovered device`() {
        val case = lossCase()
        val document = lossRequest(case)
        val approval = lossApproval(case, document)
        val old = case.old
        val receipt = old.installation.receipt
        val work = workOrder(case.token, "DISMANTLE", old.installation.customer.toString())
        assign(case.token, work, receipt.receiver.second)
        val evidence = removalEvidence(receipt.receiver.first, work)
        val removed = request("POST", "/api/customers/${old.installation.customer}/assets/remove", receipt.receiver.first,
            """{"assignmentId":"${old.installation.operation}","expectedRevision":1,"expectedTitleRevision":0,"workOrderId":"$work","evidenceId":"$evidence"}""", "recover-before-loss")
        assertThat(removed.status).withFailMessage(removed.contentAsString).isEqualTo(200)
        val decision = lossDecision(case, approval)
        assertThat(decision.status).withFailMessage(decision.contentAsString).isEqualTo(409)
        assertThat(mapper.readTree(decision.contentAsString).path("status").asString()).isEqualTo("STALE")
        assertThat(lossDecision(case, approval).contentAsString).isEqualTo(decision.contentAsString)
        fixture(case.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_asset_loss_effect")).isEqualTo("0")
            assertThat(scalar("SELECT status FROM inventory_serialized_asset WHERE id='${case.asset}'")).isEqualTo("QUARANTINE")
            assertThat(scalar("SELECT count(*) FROM inventory_approval_decision WHERE approval_id='$approval'")).isEqualTo("0")
        }
    }

    @Test fun `pending approval cannot forge a loss effect or silently close its loan assignment`() {
        val case = lossCase()
        val document = lossRequest(case)
        val approval = lossApproval(case, document)
        fixture(case.token).transaction {
            assertThat(scalar("SELECT current_user")).isEqualTo("warehouse_app")
            jdbc { connection ->
                for (statement in listOf(
                    """INSERT INTO inventory_asset_loss_effect(tenant_id,request_id,approval_id,posting_operation_id,assignment_id,closed_at)
                        VALUES(current_setting('app.tenant_id')::uuid,'$document','$approval',gen_random_uuid(),'${case.input.assignmentId}',clock_timestamp())""",
                    "UPDATE inventory_asset_assignment SET ended_at=clock_timestamp(),revision=revision+1 WHERE id='${case.input.assignmentId}'")) {
                    val point = connection.setSavepoint()
                    val failure = runCatching { connection.createStatement().use { it.execute(statement); it.execute("SET CONSTRAINTS ALL IMMEDIATE") } }.exceptionOrNull()
                    connection.rollback(point)
                    assertThat(failure).isInstanceOf(SQLException::class.java)
                    assertThat((failure as SQLException).sqlState).isEqualTo("23514")
                }
            }
            assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment WHERE id='${case.input.assignmentId}' AND ended_at IS NULL")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_asset_loss_effect")).isEqualTo("0")
        }
    }

    @Test fun `unknown original loan cost cannot be treated as zero for loss approval`() {
        lossCost = null
        val case = lossCase()
        val document = lossRequest(case)
        val result = request("POST", "/api/v1/warehouse/approvals/request", case.token,
            """{"sourceDocumentId":"$document","sourceRevision":0}""", "unknown-loan-cost")
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(409)
        assertThat(mapper.readTree(result.contentAsString).path("code").asString()).isEqualTo("COST_BASIS_REQUIRED")
        fixture(case.token).transaction { assertThat(scalar("SELECT count(*) FROM inventory_asset_loss_effect")).isEqualTo("0") }
    }

    @Test fun `two independently approved loss requests retire a customer loan only once`() {
        val case = lossCase()
        val approvals = (1..2).map { lossApproval(case, lossRequest(case, "race-request-$it"), "race-approval-$it") }
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val results = Executors.newFixedThreadPool(2).use { pool ->
            val futures = approvals.mapIndexed { index, approval -> pool.submit<org.springframework.mock.web.MockHttpServletResponse> {
                ready.countDown(); check(start.await(20, TimeUnit.SECONDS))
                lossDecision(case, approval, "race-loss-$index")
            } }
            check(ready.await(20, TimeUnit.SECONDS)); start.countDown()
            futures.map { it.get(40, TimeUnit.SECONDS) }
        }
        assertThat(results.map { it.status }).withFailMessage(results.joinToString("\n") { it.contentAsString }).containsExactlyInAnyOrder(200, 409)
        assertThat(mapper.readTree(results.single { it.status == 409 }.contentAsString).path("status").asString()).isEqualTo("STALE")
        fixture(case.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_asset_loss_effect")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM customer_asset_loss")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE kind='LOSS'")).isEqualTo("1")
        }
    }

    @Test fun `rejected loan loss requires a fresh immutable request and fresh approval`() {
        val case = lossCase()
        val document = lossRequest(case)
        val approval = lossApproval(case, document)
        val rejected = lossDecision(case, approval, action = "REJECT")
        assertThat(rejected.status).withFailMessage(rejected.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(rejected.contentAsString).path("status").asString()).isEqualTo("REWORK_REQUIRED")
        assertThat(request("POST", "/api/v1/warehouse/approvals/rework", case.token,
            """{"requestId":"$approval","expectedRevision":1}""", "loss-rework").status).isEqualTo(409)
        val next = lossRequest(case, "fresh-loss-request", case.input.copy(reason = "Corrected witnessed loss assessment"))
        val result = lossDecision(case, lossApproval(case, next, "fresh-loss-approval"), "fresh-loss-decision")
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
        fixture(case.token).transaction {
            assertThat(scalar("SELECT concat_ws('|',state,revision,approval_disposition) FROM inventory_document WHERE id='$document'"))
                .isEqualTo("DRAFT|1|REWORK_REQUIRED")
            assertThat(scalar("SELECT count(*) FROM inventory_asset_loss_effect")).isEqualTo("1")
        }
    }

    @Test fun `requester delegation cannot authorize their own lost loan`() {
        val case = lossCase()
        val maker = mapper.readTree(request("GET", "/api/me", case.token).contentAsString).path("id").asString()
        assertThat(request("PUT", "/api/v1/warehouse/settings/policy", case.token,
            """{"expectedRevision":1,"currency":"IDR","expiryHours":24,"warehouseIds":["${case.source}","${case.sink}"],"rules":[{"operation":"LOSS","tiers":[{"minimumMinor":"1","userIds":["$maker","${case.checker.second}"],"roleIds":[]}]}]}""").status).isEqualTo(200)
        val approval = lossApproval(case, lossRequest(case))
        val grant = request("POST", "/api/v1/warehouse/settings/delegations", case.token,
            """{"expectedRevision":0,"approverId":"$maker","delegateId":"${case.checker.second}","sourceRoleId":null,"locationId":"${case.source}","operation":"LOSS","validUntil":"${Instant.now().plusSeconds(3600)}"}""")
        assertThat(grant.status).withFailMessage(grant.contentAsString).isEqualTo(200)
        assertThat(lossDecision(case, approval).status).isEqualTo(403)
        fixture(case.token).transaction { assertThat(scalar("SELECT count(*) FROM inventory_asset_loss_effect")).isEqualTo("0") }
    }

    @Test fun `revoked destination scope denies replay of an already committed loan loss`() {
        val case = lossCase()
        val approval = lossApproval(case, lossRequest(case))
        val result = lossDecision(case, approval)
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${case.checker.second}/${case.sink}", case.token,
            """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)
        assertThat(lossDecision(case, approval).status).isEqualTo(404)
        fixture(case.token).transaction { assertThat(scalar("SELECT count(*) FROM inventory_asset_loss_effect")).isEqualTo("1") }
    }

    @Test fun `loss list applies location scopes before pagination and omits private source details`() {
        val case = lossCase()
        val visible = lossRequest(case)
        val hidden = create("locations", case.token, """{"code":"HIDDEN_LOANS","name":"Another loan loss location","kind":"LOST"}""").path("id").asString()
        lossRequest(case, "hidden-loss", case.input.copy(destinationLocationId = UUID.fromString(hidden)))
        val viewer = user(case.token, setOf("inventory.custody.view"))
        grantLossLocations(case.token, viewer, listOf(case.source, case.sink))
        val page = request("GET", "/api/v1/warehouse/asset-losses?page=0&size=1", viewer.first)
        assertThat(page.status).withFailMessage(page.contentAsString).isEqualTo(200)
        val body = mapper.readTree(page.contentAsString)
        assertThat(body.path("totalElements").asLong()).isEqualTo(1)
        assertThat(body.path("items").single().path("id").asString()).isEqualTo(visible)
        assertThat(page.contentAsString).doesNotContain("customerId", "cost", "storage_key", "receiverLabel", "authorityEpoch")
    }

    @Test fun `lost loan ledger rebuild preserves the original deployment and dated telemetry`() {
        val case = lossCase()
        val history = populateTelemetry(case.old)
        val result = lossDecision(case, lossApproval(case, lossRequest(case)))
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
        val scoped = fixture(case.token)
        val before = scoped.transaction { scalar("SELECT count(*) FROM inventory_movement") }
        scoped.transaction {
            sql("DELETE FROM inventory_balance_projection WHERE stock_identity_id='${case.asset}'")
            context.getBean(WarehousePosting::class.java).rebuild(0)
        }
        assertThat(telemetryFingerprint(case.old)).isEqualTo(history)
        scoped.transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_movement")).isEqualTo(before)
            assertThat(scalar("SELECT concat_ws('|',quantity_base,status,legal_owner) FROM inventory_balance_projection WHERE stock_identity_id='${case.asset}' AND quantity_base>0"))
                .isEqualTo("1|LOST|ISP")
        }
    }

    @Test fun `pending replacement authorization is retired when its old loan is independently declared lost`() {
        val replacement = replacementCase()
        val authorized = authorizeReplacement(replacement)
        assertThat(authorized.status).withFailMessage(authorized.contentAsString).isEqualTo(200)
        val authorization = mapper.readTree(authorized.contentAsString).path("authorizationId").asString()
        val case = lossCase(replacement.old)
        val result = lossDecision(case, lossApproval(case, lossRequest(case)))
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
        val attempted = request("POST", "/api/customers/${case.old.installation.customer}/assets/replace", replacement.replacement.receiver.first,
            """{"authorizationId":"$authorization","expectedRevision":0,"expectedAssignmentRevision":1,"expectedTitleRevision":0,"evidenceId":"${replacement.evidence}","topology":null}""", "replace-lost-loan")
        assertThat(attempted.status).withFailMessage(attempted.contentAsString).isEqualTo(409)
        fixture(case.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_asset_loss_permit_retirement WHERE authorization_id='$authorization'")).isEqualTo("1")
            assertThat(scalar("SELECT consumed::text FROM inventory_deployment_authorization WHERE id='$authorization'")).isEqualTo("false")
            assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment WHERE asset_id='${replacement.replacement.input.lines.single().stockIdentityId}'")).isEqualTo("0")
        }
    }
}
