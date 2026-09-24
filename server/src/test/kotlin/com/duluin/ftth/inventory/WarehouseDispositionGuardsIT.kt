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

class WarehouseDispositionGuardsIT : WarehouseDispositionFixture() {
    @Test fun `receipt currency cannot be converted implicitly to the disposition policy currency`() {
        receiptCurrency = "USD"
        val case = dispositionCase()
        val document = disposition(case)
        val approval = request("POST", "/api/v1/warehouse/approvals/request", case.token,
            """{"sourceDocumentId":"$document","sourceRevision":0}""", "different-currency-approval")
        assertThat(approval.status).withFailMessage(approval.contentAsString).isEqualTo(409)
        assertThat(mapper.readTree(approval.contentAsString).path("code").asString()).isEqualTo("CURRENCY_MISMATCH")
        fixture(case.token).transaction {
            assertThat(scalar("SELECT currency FROM inventory_document_line WHERE document_id='$document'")).isEqualTo("USD")
            assertThat(scalar("SELECT count(*) FROM inventory_disposition_effect")).isEqualTo("0")
        }
    }

    @Test fun `pending approval cannot admit a physical disposition effect through direct SQL`() {
        val case = dispositionCase()
        val document = disposition(case)
        val approval = dispositionApproval(case, document)
        fixture(case.token).transaction {
            assertThat(scalar("SELECT current_user")).isEqualTo("warehouse_app")
            jdbc { connection ->
                val point = connection.setSavepoint()
                val failure = runCatching {
                    connection.createStatement().use {
                        it.execute("""INSERT INTO inventory_disposition_effect(tenant_id,request_id,approval_id,posting_operation_id,
                            return_operation_id,return_id,source_return_revision,return_revision)
                            VALUES(current_setting('app.tenant_id')::uuid,'$document','$approval',gen_random_uuid(),gen_random_uuid(),
                                '${case.returnId}',1,2)""")
                        it.execute("SET CONSTRAINTS ALL IMMEDIATE")
                    }
                }.exceptionOrNull()
                connection.rollback(point)
                assertThat(failure).isInstanceOf(SQLException::class.java)
                assertThat((failure as SQLException).sqlState).isEqualTo("23514")
            }
            assertThat(scalar("SELECT count(*) FROM inventory_disposition_effect")).isEqualTo("0")
        }
    }

    @Test fun `unknown receipt cost stays unknown and blocks threshold approval without inventing zero`() {
        receiptCost = null
        val case = dispositionCase()
        val document = disposition(case)
        val approval = request("POST", "/api/v1/warehouse/approvals/request", case.token,
            """{"sourceDocumentId":"$document","sourceRevision":0}""", "unknown-cost-approval")
        assertThat(approval.status).withFailMessage(approval.contentAsString).isEqualTo(409)
        assertThat(mapper.readTree(approval.contentAsString).path("code").asString()).isEqualTo("COST_BASIS_REQUIRED")
        fixture(case.token).transaction {
            assertThat(scalar("SELECT (cost_total_minor IS NULL AND cost_basis_quantity_base IS NULL AND currency IS NULL)::text FROM inventory_document_line WHERE document_id='$document'"))
                .isEqualTo("true")
            assertThat(scalar("SELECT count(*) FROM inventory_disposition_effect")).isEqualTo("0")
        }
    }

    @Test fun `writeoff cannot overdraw or silently resize the measured returned segment`() {
        val case = dispositionCase()
        for (quantity in listOf("-1", "0", "17499", "17501", "9223372036854775808")) {
            val result = request("POST", "/api/v1/warehouse/dispositions", case.token,
                mapper.writeValueAsString(case.input.copy(quantityBase = quantity)), "invalid-$quantity")
            assertThat(result.status).withFailMessage(result.contentAsString)
                .isEqualTo(if (quantity in listOf("17499", "17501")) 409 else 400)
        }
        fixture(case.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_disposition_request")).isEqualTo("0")
            assertThat(scalar("SELECT quantity_base FROM inventory_balance_projection WHERE stock_identity_id='${case.identity}' AND quantity_base>0"))
                .isEqualTo("17500")
        }
    }

    @Test fun `changed inspection makes pending approval durably stale without a physical effect`() {
        val case = dispositionCase()
        val approval = dispositionApproval(case, disposition(case))
        val changed = request("POST", "/api/v1/warehouse/returns/${case.returnId}/inspect", case.token,
            """{"expectedRevision":1,"measuredQuantityBase":"17500","condition":"QUARANTINE","destinationLocationId":"${case.residual.input.targetLocationId}","evidenceReference":"further-inspection-required","resetConfirmed":false}""", "changed-inspection")
        assertThat(changed.status).withFailMessage(changed.contentAsString).isEqualTo(200)
        val result = dispositionDecision(case, approval)
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(409)
        assertThat(mapper.readTree(result.contentAsString).path("status").asString()).isEqualTo("STALE")
        assertThat(dispositionDecision(case, approval).contentAsString).isEqualTo(result.contentAsString)
        fixture(case.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_disposition_effect")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_approval_decision WHERE approval_id='$approval'")).isEqualTo("0")
        }
    }

    @Test fun `rejected disposition preserves source and requires a fresh request with its own approval`() {
        val case = dispositionCase()
        val document = disposition(case)
        val approval = dispositionApproval(case, document)
        val rejected = dispositionDecision(case, approval, action = "REJECT")
        assertThat(rejected.status).withFailMessage(rejected.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(rejected.contentAsString).path("status").asString()).isEqualTo("REWORK_REQUIRED")
        val rework = request("POST", "/api/v1/warehouse/approvals/rework", case.token,
            """{"requestId":"$approval","expectedRevision":1}""", "rework-disposition")
        assertThat(rework.status).withFailMessage(rework.contentAsString).isEqualTo(409)
        val next = disposition(case, "revised-disposition", case.input.copy(evidenceReference = "corrected-independent-assessment"))
        val nextApproval = dispositionApproval(case, next, "revised-approval")
        val approved = dispositionDecision(case, nextApproval, "revised-decision")
        assertThat(approved.status).withFailMessage(approved.contentAsString).isEqualTo(200)
        fixture(case.token).transaction {
            assertThat(scalar("SELECT concat_ws('|',state,revision,approval_disposition) FROM inventory_document WHERE id='$document'"))
                .isEqualTo("DRAFT|1|REWORK_REQUIRED")
            assertThat(scalar("SELECT count(*) FROM inventory_disposition_effect")).isEqualTo("1")
        }
    }

    @Test fun `competing independently approved drafts can dispose the same remnant only once`() {
        val case = dispositionCase()
        val approvals = (1..2).map { dispositionApproval(case, disposition(case, "competing-$it"), "competing-approval-$it") }
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val results = Executors.newFixedThreadPool(2).use { pool ->
            val futures = approvals.mapIndexed { index, approval ->
                pool.submit<org.springframework.mock.web.MockHttpServletResponse> {
                    ready.countDown()
                    check(start.await(20, TimeUnit.SECONDS))
                    dispositionDecision(case, approval, "competing-decision-$index")
                }
            }
            check(ready.await(20, TimeUnit.SECONDS))
            start.countDown()
            futures.map { it.get(40, TimeUnit.SECONDS) }
        }
        assertThat(results.map { it.status }).withFailMessage(results.joinToString("\n") { it.contentAsString })
            .containsExactlyInAnyOrder(200, 409)
        assertThat(mapper.readTree(results.single { it.status == 409 }.contentAsString).path("status").asString()).isEqualTo("STALE")
        fixture(case.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_disposition_effect")).isEqualTo("1")
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE stock_identity_id='${case.identity}' AND status='DISPOSED'"))
                .isEqualTo("17500")
        }
    }

    @Test fun `requester delegation cannot turn a configured checker into an independent approver`() {
        val case = dispositionCase()
        val maker = mapper.readTree(request("GET", "/api/me", case.token).contentAsString).path("id").asString()
        val policy = request("PUT", "/api/v1/warehouse/settings/policy", case.token,
            """{"expectedRevision":1,"currency":"IDR","expiryHours":24,"warehouseIds":["${case.residual.input.targetLocationId}","${case.sink}"],"rules":[{"operation":"SCRAP","tiers":[{"minimumMinor":"1","userIds":["$maker","${case.checker.second}"],"roleIds":[]}]}]}""")
        assertThat(policy.status).withFailMessage(policy.contentAsString).isEqualTo(200)
        val approval = dispositionApproval(case, disposition(case))
        val grant = request("POST", "/api/v1/warehouse/settings/delegations", case.token,
            """{"expectedRevision":0,"approverId":"$maker","delegateId":"${case.checker.second}","sourceRoleId":null,"locationId":"${case.residual.input.targetLocationId}","operation":"SCRAP","validUntil":"${Instant.now().plusSeconds(3600)}"}""")
        assertThat(grant.status).withFailMessage(grant.contentAsString).isEqualTo(200)
        assertThat(dispositionDecision(case, approval).status).isEqualTo(403)
        fixture(case.token).transaction { assertThat(scalar("SELECT count(*) FROM inventory_disposition_effect")).isEqualTo("0") }
    }

    @Test fun `current destination scope is required to replay an approved decision`() {
        val case = dispositionCase()
        val approval = dispositionApproval(case, disposition(case))
        val result = dispositionDecision(case, approval)
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${case.checker.second}/${case.sink}", case.token,
            """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)
        assertThat(dispositionDecision(case, approval).status).isEqualTo(404)
        fixture(case.token).transaction { assertThat(scalar("SELECT count(*) FROM inventory_disposition_effect")).isEqualTo("1") }
    }

    @Test fun `disposition list removes hidden destinations before pagination`() {
        val case = dispositionCase()
        val visible = disposition(case)
        val hidden = create("locations", case.token, """{"code":"HIDDEN_DISPOSAL","name":"Different disposal location","kind":"DISPOSED"}""")
            .path("id").asString()
        disposition(case, "hidden-disposition", case.input.copy(destinationLocationId = UUID.fromString(hidden)))
        val viewer = user(case.token, setOf("inventory.custody.view"))
        val principal = mapper.readTree(request("GET", "/api/users/${viewer.second}", case.token).contentAsString)
        assertThat(request("PUT", "/api/users/${viewer.second}/access", case.token, mapper.writeValueAsString(mapOf(
            "roleIds" to principal.path("roleIds").asSequence().map { it.asString() }.toList(), "areaIds" to listOf(area(case.token))))).status).isEqualTo(200)
        for (location in listOf(case.residual.input.targetLocationId.toString(), case.sink)) {
            assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${viewer.second}/$location", case.token,
                """{"expectedRevision":0,"active":true}""").status).isEqualTo(200)
        }
        val page = request("GET", "/api/v1/warehouse/dispositions?page=0&size=1", viewer.first)
        assertThat(page.status).withFailMessage(page.contentAsString).isEqualTo(200)
        val body = mapper.readTree(page.contentAsString)
        assertThat(body.path("totalElements").asLong()).isEqualTo(1)
        assertThat(body.path("items").single().path("id").asString()).isEqualTo(visible)
    }

    @Test fun `disposed measured segment cannot be rewritten and rebuild preserves the exact applied ledger`() {
        val case = dispositionCase()
        val result = dispositionDecision(case, dispositionApproval(case, disposition(case)))
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
        val scoped = fixture(case.token)
        val before = scoped.transaction { scalar("SELECT count(*) FROM inventory_movement") }
        scoped.transaction {
            assertThat(scalar("SELECT current_user")).isEqualTo("warehouse_app")
            jdbc { connection ->
                val point = connection.setSavepoint()
                val failure = runCatching {
                    connection.createStatement().use {
                        it.execute("UPDATE inventory_balance_projection SET quantity_base=17499,revision=revision+1 WHERE stock_identity_id='${case.identity}' AND quantity_base>0")
                        it.execute("SET CONSTRAINTS ALL IMMEDIATE")
                    }
                }.exceptionOrNull()
                connection.rollback(point)
                assertThat(failure).isInstanceOf(SQLException::class.java)
                assertThat((failure as SQLException).sqlState).isEqualTo("23514")
            }
            sql("DELETE FROM inventory_balance_projection WHERE stock_identity_id='${case.identity}'")
            context.getBean(WarehousePosting::class.java).rebuild(0)
        }
        scoped.transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_movement")).isEqualTo(before)
            assertThat(scalar("SELECT concat_ws('|',quantity_base,status,condition,legal_owner) FROM inventory_balance_projection WHERE stock_identity_id='${case.identity}' AND quantity_base>0"))
                .isEqualTo("17500|DISPOSED|SCRAP|ISP")
        }
    }
}
