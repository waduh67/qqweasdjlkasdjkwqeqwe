package com.duluin.ftth.customer

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.Connection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

abstract class CustomerAssetTitleRaceCases : CustomerAssetTitleGuardCases() {
    @Test
    fun `two approved corrections competing for one source revision commit one transfer`() {
        val first = pendingCorrection()
        val case = first.case
        val alternate = request("POST", "/api/v1/warehouse/asset-title-corrections", case.ownership.installation.receipt.stock.token,
            """{"assignmentId":"${case.ownership.installation.operation}","sourceHandoverId":"${case.handover}","expectedAssignmentRevision":1,"expectedTitleRevision":1,"targetOwner":"ISP","reason":"Second independent request","evidenceId":"${case.ownership.signature}"}""", "second-request")
        assertThat(alternate.status).withFailMessage(alternate.contentAsString).isEqualTo(201)
        val document = mapper.readTree(alternate.contentAsString).path("documentId").asString()
        val submitted = request("POST", "/api/v1/warehouse/approvals/request", case.ownership.installation.receipt.stock.token,
            """{"sourceDocumentId":"$document","sourceRevision":0}""", "second-approval")
        assertThat(submitted.status).isEqualTo(201)
        val second = PendingCorrection(case, document, mapper.readTree(submitted.contentAsString).path("requestId").asString())
        val gate = CountDownLatch(1)

        val statuses = Executors.newFixedThreadPool(2).use { executor ->
            val futures = listOf(first, second).map { pending -> executor.submit<Int> {
                check(gate.await(20, TimeUnit.SECONDS))
                request("POST", "/api/v1/warehouse/approvals/decide", case.checker.first,
                    """{"requestId":"${pending.approval}","expectedRevision":0,"decision":"APPROVE"}""", "decide-${pending.approval}").status
            } }
            gate.countDown()
            futures.map { it.get(40, TimeUnit.SECONDS) }
        }

        assertThat(statuses).containsExactlyInAnyOrder(200, 409)
        assertThat(title(case.ownership)).isEqualTo("SALE|ISP|ISP|CUSTOMER_INSTALLED|2|1")
        fixture(case.ownership.installation.receipt.stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_asset_title_transfer")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_approval WHERE status='STALE'")).isEqualTo("1")
        }
    }

    @Test
    fun `loan cessation racing approved title relinquishment leaves no ISP recovery due`() {
        val pending = pendingCorrection("LOAN")
        val case = pending.case.ownership
        val gate = CountDownLatch(1)

        val statuses = Executors.newFixedThreadPool(2).use { executor ->
            val correction = executor.submit<Int> { check(gate.await(20, TimeUnit.SECONDS)); decideCorrection(pending).status }
            val cessation = executor.submit<Int> {
                check(gate.await(20, TimeUnit.SECONDS))
                request("PUT", "/api/customers/${case.installation.customer}/status", case.installation.receipt.stock.token,
                    """{"status":"TERMINATED"}""").status
            }
            gate.countDown()
            listOf(correction.get(40, TimeUnit.SECONDS), cessation.get(40, TimeUnit.SECONDS))
        }

        assertThat(statuses).containsExactly(200, 200)
        val report = mapper.readTree(ownershipReport(case).contentAsString).single()
        assertThat(report.path("ownershipMode").asString()).isEqualTo("LOAN")
        assertThat(report.path("legalOwner").asString()).isEqualTo("CUSTOMER")
        assertThat(report.path("serviceCeased").asBoolean()).isTrue()
        assertThat(report.path("recoveryDue").asBoolean()).isFalse()
    }

    @Test
    fun `handover waits on assignment closure boundary and cannot partially commit`() {
        val case = ownershipCase("SALE")
        val stock = fixture(case.installation.receipt.stock.token)
        Executors.newSingleThreadExecutor().use { executor ->
            lateinit var acceptance: java.util.concurrent.Future<Int>

            assertThrows<Exception> { stock.transaction {
                sql("SELECT id FROM inventory_asset_assignment WHERE id='${case.installation.operation}' FOR UPDATE")
                val holder = scalar("SELECT pg_backend_pid()").toInt()
                acceptance = executor.submit<Int> { accept(case).status }
                jdbc { awaitTitleWait(it, holder) }
                sql("UPDATE inventory_asset_assignment SET ended_at=clock_timestamp(),revision=revision+1 WHERE id='${case.installation.operation}'")
            } }

            assertThat(acceptance.get(40, TimeUnit.SECONDS)).isEqualTo(200)
        }
        assertThat(title(case)).isEqualTo("SALE|CUSTOMER|CUSTOMER|CUSTOMER_INSTALLED|1|1")
    }

    @Test
    fun `replay waiting on current-authority fence cannot reveal a revoked actors response`() {
        val case = ownershipCase("SALE")
        assertThat(accept(case).status).isEqualTo(200)
        val stock = fixture(case.installation.receipt.stock.token)
        Executors.newSingleThreadExecutor().use { executor ->
            lateinit var replay: java.util.concurrent.Future<Int>

            stock.transaction {
                sql("SELECT epoch FROM iam_authorization_epoch WHERE tenant_id='$tenant' FOR UPDATE")
                val holder = scalar("SELECT pg_backend_pid()").toInt()
                replay = executor.submit<Int> { accept(case).status }
                jdbc { awaitTitleWait(it, holder) }
                sql("UPDATE app_user SET status='DISABLED' WHERE id='${case.installation.receipt.receiver.second}'")
                sql("UPDATE iam_authorization_epoch SET epoch=epoch+1,revision=revision+1 WHERE tenant_id='$tenant'")
            }

            assertThat(replay.get(40, TimeUnit.SECONDS)).isIn(401, 403)
        }
        assertThat(title(case)).isEqualTo("SALE|CUSTOMER|CUSTOMER|CUSTOMER_INSTALLED|1|1")
    }

    private fun awaitTitleWait(connection: Connection, holder: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        connection.prepareStatement("SELECT EXISTS(SELECT FROM pg_stat_activity WHERE ?=ANY(pg_blocking_pids(pid)))").use { query ->
            query.setInt(1, holder)
            while (System.nanoTime() < deadline) {
                if (query.executeQuery().use { rows -> check(rows.next()); rows.getBoolean(1) }) return
                Thread.onSpinWait()
            }
        }
        error("Title operation did not wait on the database owner fence")
    }
}
