package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WarehouseSupplierReplacementGuardsIT : WarehouseSupplierReplacementFixture() {
    @Test fun `unknown replacement cost stays unknown and cannot bypass a configured value policy`() {
        val case = replacement(cost = null)
        replacementPolicy(case)
        val result = receiveReplacement(case)
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(409)
        assertThat(mapper.readTree(result.contentAsString).path("code").asString()).isEqualTo("COST_BASIS_REQUIRED")
        fixture(case.token).transaction {
            assertThat(scalar("SELECT (cost_total_minor IS NULL)::text FROM inventory_document_line WHERE document_id='${case.receipt}'")).isEqualTo("true")
            assertThat(scalar("SELECT count(*) FROM inventory_repair_replacement_receipt")).isEqualTo("0")
        }
    }

    @Test fun `approved vendor replacement preserves customer title and declared cost with exact decision replay`() {
        val case = replacement("150001")
        val checker = replacementPolicy(case)
        assertThat(receiveReplacement(case).status).isEqualTo(409)
        val approval = replacementApproval(case)
        val result = replacementDecision(approval, checker.first)
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
        assertThat(replacementDecision(approval, checker.first).contentAsString).isEqualTo(result.contentAsString)
        val asset = replacementAsset(case)
        fixture(case.token).transaction {
            assertThat(scalar("SELECT concat_ws('|',legal_owner,status) FROM inventory_serialized_asset WHERE id='$asset'"))
                .isEqualTo("CUSTOMER|QUARANTINE")
            assertThat(scalar("SELECT concat_ws('|',cost_total_minor,cost_basis_quantity_base,currency) FROM inventory_document_line WHERE document_id='${case.receipt}'"))
                .isEqualTo("150001|1|IDR")
            assertThat(scalar("SELECT count(*) FROM inventory_repair_replacement_receipt")).isEqualTo("1")
        }
    }

    @Test fun `competing vendor drafts receive only one replacement without leaking a second physical identity`() {
        val first = replacement()
        val second = replacement(first.repair, first.outbound, "VENDOR-NEW-2", "vendor-new-2")
        val before = fixture(first.token).transaction { scalar("SELECT count(*) FROM inventory_serialized_asset").toInt() }
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val results = Executors.newFixedThreadPool(2).use { pool ->
            val futures = listOf(first, second).mapIndexed { index, case ->
                pool.submit<org.springframework.mock.web.MockHttpServletResponse> {
                    ready.countDown()
                    check(start.await(20, TimeUnit.SECONDS))
                    receiveReplacement(case, key = "competing-replacement-$index")
                }
            }
            check(ready.await(20, TimeUnit.SECONDS))
            start.countDown()
            futures.map { it.get(30, TimeUnit.SECONDS) }
        }
        assertThat(results.map { it.status }).withFailMessage(results.joinToString("\n") { it.contentAsString }).containsExactlyInAnyOrder(200, 409)
        fixture(first.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_repair_replacement_receipt")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_serialized_asset").toInt()).isEqualTo(before + 1)
        }
    }

    @Test fun `returned old device makes pending replacement source stale without admitting the new serial`() {
        val case = replacement()
        receiveRepair(case.repair, case.outbound)
        val result = receiveReplacement(case)
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(409)
        assertThat(mapper.readTree(result.contentAsString).path("code").asString()).isEqualTo("STALE_REVISION")
        fixture(case.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_repair_replacement_receipt")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_serialized_asset WHERE canonical_serial='VENDOR-NEW-1'")).isEqualTo("0")
        }
    }

    @Test fun `approval records durable stale when original repair source changes after request`() {
        val case = replacement()
        val checker = replacementPolicy(case)
        val approval = replacementApproval(case)
        receiveRepair(case.repair, case.outbound)
        val result = replacementDecision(approval, checker.first)
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(409)
        assertThat(mapper.readTree(result.contentAsString).path("status").asString()).isEqualTo("STALE")
        assertThat(replacementDecision(approval, checker.first).contentAsString).isEqualTo(result.contentAsString)
        fixture(case.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_repair_replacement_receipt")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_approval_decision WHERE approval_id='$approval'")).isEqualTo("0")
        }
    }

    @Test fun `rejected replacement approval preserves source and permits a fresh receipt request`() {
        val case = replacement()
        val checker = replacementPolicy(case)
        val approval = replacementApproval(case)
        val rejected = replacementDecision(approval, checker.first, action = "REJECT")
        assertThat(rejected.status).withFailMessage(rejected.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(rejected.contentAsString).path("status").asString()).isEqualTo("REWORK_REQUIRED")
        val rework = request("POST", "/api/v1/warehouse/approvals/rework", case.token,
            """{"requestId":"$approval","expectedRevision":1}""", "replacement-rework")
        assertThat(rework.status).withFailMessage(rework.contentAsString).isEqualTo(409)
        val next = replacement(case.repair, case.outbound, "VENDOR-NEW-2", "replacement-revised")
        val approved = replacementDecision(replacementApproval(next, "replacement-revised-approval"), checker.first, "replacement-revised-decision")
        assertThat(approved.status).withFailMessage(approved.contentAsString).isEqualTo(200)
        fixture(case.token).transaction {
            assertThat(scalar("SELECT concat_ws('|',state,revision,approval_disposition) FROM inventory_document WHERE id='${case.receipt}'"))
                .isEqualTo("DRAFT|1|REWORK_REQUIRED")
            assertThat(scalar("SELECT count(*) FROM inventory_repair_replacement_receipt")).isEqualTo("1")
        }
    }

    @Test fun `current receipt scope is required to replay a committed replacement receive`() {
        val case = replacement()
        val actor = receiver(case, setOf("inventory.receipt.view", "inventory.receipt.manage"))
        val received = receiveReplacement(case, actor.first)
        assertThat(received.status).withFailMessage(received.contentAsString).isEqualTo(200)
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${actor.second}/${case.repair.returned.quarantine}", case.token,
            """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)
        assertThat(receiveReplacement(case, actor.first).status).isEqualTo(404)
        fixture(case.token).transaction { assertThat(scalar("SELECT count(*) FROM inventory_repair_replacement_receipt")).isEqualTo("1") }
    }

    @Test fun `customer replacement cannot become ISP stock through an unposted asset and balance rewrite`() {
        val case = replacement()
        assertThat(receiveReplacement(case).status).isEqualTo(200)
        val asset = replacementAsset(case)
        fixture(case.token).transaction {
            jdbc { connection ->
                val point = connection.setSavepoint()
                val rejection = runCatching {
                    connection.createStatement().use { statement ->
                        statement.execute("UPDATE inventory_balance_projection SET legal_owner='ISP',revision=revision+1 WHERE stock_identity_id='$asset' AND quantity_base>0")
                        statement.execute("UPDATE inventory_serialized_asset SET legal_owner='ISP',revision=revision+1 WHERE id='$asset'")
                        statement.execute("SET CONSTRAINTS ALL IMMEDIATE")
                    }
                }.exceptionOrNull()
                connection.rollback(point)
                assertThat(rejection).isInstanceOf(SQLException::class.java)
                assertThat((rejection as SQLException).sqlState).isEqualTo("23514")
            }
        }
    }
}
