package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.port.outbound.WarehousePosting
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WarehouseCompensationGuardsIT : WarehouseDispositionFixture() {
    private data class Correction(val source: DispositionCase, val original: String, val input: WarehouseCompensationInput) {
        val path get() = "/api/v1/warehouse/dispositions/$original/compensations"
    }

    private fun correction(): Correction {
        val case = dispositionCase()
        val original = disposition(case)
        val approved = dispositionDecision(case, dispositionApproval(case, original))
        assertThat(approved.status).withFailMessage(approved.contentAsString).isEqualTo(200)
        val policy = request("PUT", "/api/v1/warehouse/settings/policy", case.token,
            """{"expectedRevision":1,"currency":"IDR","expiryHours":24,"warehouseIds":["${case.residual.input.targetLocationId}","${case.sink}"],"rules":[{"operation":"SCRAP","tiers":[{"minimumMinor":"1","userIds":["${case.checker.second}"],"roleIds":[]}]},{"operation":"ADJUSTMENT","tiers":[{"minimumMinor":"1","userIds":["${case.checker.second}"],"roleIds":[]}]}]}""")
        assertThat(policy.status).withFailMessage(policy.contentAsString).isEqualTo(200)
        return Correction(case, original, WarehouseCompensationInput(1, 2, case.residual.input.targetLocationId,
            "Witnessed recovery corrects the original disposition", "recovered-physical-piece-evidence"))
    }

    private fun draft(case: Correction, key: String = "correction-request", input: WarehouseCompensationInput = case.input): String {
        val result = request("POST", case.path, case.source.token, mapper.writeValueAsString(input), key)
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(201)
        return mapper.readTree(result.contentAsString).path("id").asString()
    }

    private fun approval(case: Correction, document: String, key: String = "correction-approval") = dispositionApproval(case.source, document, key)
    private fun decide(case: Correction, approval: String, key: String = "correction-decision", action: String = "APPROVE") =
        dispositionDecision(case.source, approval, key, action = action)

    private fun close(case: Correction) {
        val source = case.source
        val settlement = mapper.readTree(settlement(source.residual).contentAsString)
        val work = source.residual.usage.receipt.workOrder
        val revision = summary(source.token, work).path("revisions").path("workOrderRevision").asLong()
        val closed = request("POST", "/api/work-orders/$work/materials/settlement", source.token,
            """{"expectedRevision":${settlement.path("revision").asLong()},"workOrderRevision":$revision,"reason":"Verified disposal settled the exact remaining material"}""", "close-before-correction")
        assertThat(closed.status).withFailMessage(closed.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(closed.contentAsString).path("materialState").asString()).isEqualTo("CLOSED")
    }

    @Test fun `closed material settlement requires its corrective workflow before compensation can be requested`() {
        val case = correction()
        close(case)
        val result = request("POST", case.path, case.source.token, mapper.writeValueAsString(case.input), "closed-correction")
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(409)
        fixture(case.source.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_compensation_request")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE kind='REVERSAL'")).isEqualTo("0")
        }
    }

    @Test fun `closing material after compensation request makes the pending approval durably stale`() {
        val case = correction()
        val approval = approval(case, draft(case))
        close(case)
        val result = decide(case, approval)
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(409)
        assertThat(mapper.readTree(result.contentAsString).path("status").asString()).isEqualTo("STALE")
        assertThat(decide(case, approval).contentAsString).isEqualTo(result.contentAsString)
        fixture(case.source.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_compensation_effect")).isEqualTo("0")
            assertThat(scalar("SELECT status FROM inventory_balance_projection WHERE stock_identity_id='${case.source.identity}' AND quantity_base>0"))
                .isEqualTo("DISPOSED")
        }
    }

    @Test fun `competing corrections of the same original movement produce one reversal`() {
        val case = correction()
        val approvals = (1..2).map { approval(case, draft(case, "correction-$it"), "correction-approval-$it") }
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val results = Executors.newFixedThreadPool(2).use { pool ->
            val futures = approvals.mapIndexed { index, id ->
                pool.submit<org.springframework.mock.web.MockHttpServletResponse> {
                    ready.countDown()
                    check(start.await(20, TimeUnit.SECONDS))
                    decide(case, id, "correction-decision-$index")
                }
            }
            check(ready.await(20, TimeUnit.SECONDS))
            start.countDown()
            futures.map { it.get(40, TimeUnit.SECONDS) }
        }
        assertThat(results.map { it.status }).withFailMessage(results.joinToString("\n") { it.contentAsString }).containsExactlyInAnyOrder(200, 409)
        assertThat(mapper.readTree(results.single { it.status == 409 }.contentAsString).path("status").asString()).isEqualTo("STALE")
        fixture(case.source.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_compensation_effect")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE kind='REVERSAL'")).isEqualTo("1")
        }
    }

    @Test fun `rejected correction cannot edit its sealed source and a fresh approved request may restore it`() {
        val case = correction()
        val rejectedId = draft(case)
        val rejectedApproval = approval(case, rejectedId)
        val rejected = decide(case, rejectedApproval, action = "REJECT")
        assertThat(rejected.status).withFailMessage(rejected.contentAsString).isEqualTo(200)
        val rework = request("POST", "/api/v1/warehouse/approvals/rework", case.source.token,
            """{"requestId":"$rejectedApproval","expectedRevision":1}""", "correction-rework")
        assertThat(rework.status).withFailMessage(rework.contentAsString).isEqualTo(409)
        val next = draft(case, "correction-revised", case.input.copy(evidenceReference = "corrected-witnessed-recovery"))
        val result = decide(case, approval(case, next, "correction-revised-approval"), "correction-revised-decision")
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
        fixture(case.source.token).transaction {
            assertThat(scalar("SELECT concat_ws('|',state,revision,approval_disposition) FROM inventory_document WHERE id='$rejectedId'"))
                .isEqualTo("DRAFT|1|REWORK_REQUIRED")
            assertThat(scalar("SELECT count(*) FROM inventory_compensation_effect")).isEqualTo("1")
        }
    }

    @Test fun `correction cannot return stock directly to an available bin`() {
        val case = correction()
        val result = request("POST", case.path, case.source.token,
            mapper.writeValueAsString(case.input.copy(destinationLocationId = UUID.fromString(case.source.residual.usage.receipt.stock.bin))), "available-correction")
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(409)
        fixture(case.source.token).transaction { assertThat(scalar("SELECT count(*) FROM inventory_compensation_request")).isEqualTo("0") }
    }

    @Test fun `revoked source scope denies replay of an applied correction`() {
        val case = correction()
        val approval = approval(case, draft(case))
        val result = decide(case, approval)
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${case.source.checker.second}/${case.source.sink}", case.source.token,
            """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)
        assertThat(decide(case, approval).status).isEqualTo(404)
        fixture(case.source.token).transaction { assertThat(scalar("SELECT count(*) FROM inventory_compensation_effect")).isEqualTo("1") }
    }

    @Test fun `a pending correction cannot insert its effect through direct SQL`() {
        val case = correction()
        val document = draft(case)
        val approval = approval(case, document)
        fixture(case.source.token).transaction {
            jdbc { connection ->
                val point = connection.setSavepoint()
                val failure = runCatching {
                    connection.createStatement().use {
                        it.execute("""INSERT INTO inventory_compensation_effect(tenant_id,request_id,approval_id,original_posting_id,
                            posting_operation_id,return_operation_id,return_id,source_return_revision,return_revision)
                            SELECT tenant_id,id,'$approval',original_posting_id,gen_random_uuid(),gen_random_uuid(),return_id,2,3
                            FROM inventory_compensation_request WHERE id='$document'""")
                        it.execute("SET CONSTRAINTS ALL IMMEDIATE")
                    }
                }.exceptionOrNull()
                connection.rollback(point)
                assertThat(failure).isInstanceOf(SQLException::class.java)
                assertThat((failure as SQLException).sqlState).isEqualTo("23514")
            }
            assertThat(scalar("SELECT count(*) FROM inventory_compensation_effect")).isEqualTo("0")
        }
    }

    @Test fun `compensated stock rebuild keeps the original disposition and exactly one restored piece`() {
        val case = correction()
        val result = decide(case, approval(case, draft(case)))
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
        val scoped = fixture(case.source.token)
        val before = scoped.transaction { scalar("SELECT count(*) FROM inventory_movement") }
        scoped.transaction {
            sql("DELETE FROM inventory_balance_projection WHERE stock_identity_id='${case.source.identity}'")
            context.getBean(WarehousePosting::class.java).rebuild(0)
        }
        scoped.transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_movement")).isEqualTo(before)
            assertThat(scalar("SELECT concat_ws('|',quantity_base,status,condition,legal_owner) FROM inventory_balance_projection WHERE stock_identity_id='${case.source.identity}' AND quantity_base>0"))
                .isEqualTo("17500|QUARANTINE|QUARANTINE|ISP")
        }
    }
}
