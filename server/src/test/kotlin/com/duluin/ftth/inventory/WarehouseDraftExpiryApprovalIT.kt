package com.duluin.ftth.inventory

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.inventory.application.service.WarehouseDraftExpiryService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WarehouseDraftExpiryApprovalIT : WarehouseApprovalHttpFixture() {
    @Test fun `source expiry is durable on late decision even when approval lifetime remains live`() {
        val case = due()
        val result = decide(case, key = "late-decision")
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(409)
        assertThat(mapper.readTree(result.contentAsString).path("code").asString()).isEqualTo("DRAFT_EXPIRED")
        assertThat(decide(case, key = "late-decision").contentAsString).isEqualTo(result.contentAsString)
        assertThat(request("POST", "/api/v1/warehouse/approvals/request", case.setup.token, case.source, case.requestKey).contentAsString).isEqualTo(case.original)
        val details = request("GET", "/api/v1/warehouse/approvals/${case.id}/details", case.checker.first)
        assertThat(details.status).withFailMessage(details.contentAsString).isEqualTo(200)
        val view = mapper.readTree(details.contentAsString)
        assertThat(view.path("currentSourceState").asString()).isEqualTo("EXPIRED")
        assertThat(view.path("document").path("state").asString()).isEqualTo("DRAFT")
        assertThat(view.path("actions").path("canRework").asBoolean()).isFalse()
        fixture(case.setup.token).transaction {
            assertThat(scalar("SELECT status||':'||revision FROM inventory_approval WHERE id='${case.id}'")).isEqualTo("EXPIRED:1")
            assertThat(scalar("SELECT (expires_at>clock_timestamp())::text FROM inventory_approval WHERE id='${case.id}'")).isEqualTo("true")
        }
        counts(case, 0, 0)
    }

    @Test fun `terminal overlay cannot commit with pending approval and competing workers produce one terminal revision`() {
        val case = due()
        val database = fixture(case.setup.token)
        val incomplete = catchThrowable { database.transaction { scalar("SELECT warehouse_expire_document_draft('$tenant','${case.document}')") } }
        assertThat(generateSequence(incomplete) { it.cause }.filterIsInstance<SQLException>().first().sqlState).isEqualTo("23514")
        database.transaction { assertThat(scalar("SELECT count(*) FROM inventory_document_draft_expiry WHERE document_id='${case.document}'")).isEqualTo("0") }
        val gate = CountDownLatch(1)
        Executors.newFixedThreadPool(3).use { pool ->
            val workers = (1..2).map { pool.submit<Boolean> {
                check(gate.await(10, TimeUnit.SECONDS))
                TenantContext.runAs(database.tenant) { context.getBean(WarehouseDraftExpiryService::class.java).expireOne() }
            } }
            val decision = pool.submit<Int> { check(gate.await(10, TimeUnit.SECONDS)); decide(case).status }
            gate.countDown()
            assertThat(workers.map { it.get(30, TimeUnit.SECONDS) }).containsExactlyInAnyOrder(true, false)
            assertThat(decision.get(30, TimeUnit.SECONDS)).isEqualTo(409)
        }
        database.transaction {
            assertThat(scalar("SELECT status||':'||revision FROM inventory_approval WHERE id='${case.id}'")).isEqualTo("EXPIRED:1")
            assertThat(scalar("SELECT count(*) FROM inventory_document_draft_expiry WHERE document_id='${case.document}'")).isEqualTo("1")
        }
        counts(case, 0, 0)
    }

    private fun due(): ApprovalCase {
        val setup = setupReceipt()
        val checker = approver(setup.token, listOf(setup.source, setup.inspection))
        configure(setup.token, policyBody(listOf(setup.inspection), listOf(checker.second)))
        val clock = WarehouseDraftClockFixture(fixture(setup.token))
        clock.policy(5)
        val document = draft(setup, costLine(setup)).path("id").asString()
        val case = submit(setup, checker, document)
        clock.awaitDocument(document)
        return case
    }
}
