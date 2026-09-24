package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WarehouseReturnTitleGuardsIT : WarehouseReturnTitleFixture() {
    @Test fun `changed return inspection makes pending title approval stale without transferring ownership`() {
        val setup = titleSetup()
        val pending = pendingTitle(setup)
        val repair = setup.repair
        val inspected = request("POST", "${repair.path}/inspect", setup.token,
            """{"expectedRevision":${setup.returned.revision},"measuredQuantityBase":"1","condition":"QUARANTINE","destinationLocationId":"${setup.returned.quarantine}","evidenceReference":"reinspection-after-title-request","observedSerial":"${repair.serial}","resetConfirmed":false}""", "changed-title-source")
        assertThat(inspected.status).withFailMessage(inspected.contentAsString).isEqualTo(200)
        titleStatus(decideTitle(setup, pending), "STALE")
        fixture(setup.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_return_title_effect")).isEqualTo("0")
            assertThat(scalar("SELECT concat_ws('|',legal_owner,status) FROM inventory_serialized_asset WHERE id='${repair.asset}'"))
                .isEqualTo("CUSTOMER|QUARANTINE")
        }
    }

    @Test fun `competing independent approvals transfer one quarantined physical device only once`() {
        val setup = titleSetup()
        val pending = List(2) { pendingTitle(setup, "competing-title-$it") }
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val responses = Executors.newFixedThreadPool(2).use { pool ->
            val futures = pending.mapIndexed { index, title ->
                pool.submit<org.springframework.mock.web.MockHttpServletResponse> {
                    ready.countDown()
                    check(start.await(20, TimeUnit.SECONDS))
                    decideTitle(setup, title, "competing-title-decision-$index")
                }
            }
            check(ready.await(20, TimeUnit.SECONDS))
            start.countDown()
            futures.map { it.get(30, TimeUnit.SECONDS) }
        }
        assertThat(responses.map { it.status }).withFailMessage(responses.joinToString("\n") { it.contentAsString }).containsOnly(200)
        assertThat(responses.map { mapper.readTree(it.contentAsString).path("status").asString() }).containsExactlyInAnyOrder("APPROVED", "STALE")
        fixture(setup.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_return_title_effect WHERE return_id='${setup.returned.id}'")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_operation WHERE namespace='warehouse.return.reacquire' AND document_id='${setup.returned.id}'")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE kind='TITLE_CORRECTION'")).isEqualTo("1")
        }
    }

    @Test fun `app role cannot mark title posted without approval and posted title links remain append only`() {
        val setup = titleSetup()
        val pending = pendingTitle(setup)
        rejectedMutation(setup, "UPDATE inventory_document SET state='POSTED',revision=1 WHERE id='${pending.document}'")
        titleStatus(decideTitle(setup, pending), "APPROVED")
        rejectedMutation(setup, "DELETE FROM inventory_return_title_effect WHERE request_id='${pending.document}'")
        rejectedMutation(setup, "UPDATE inventory_return_title_effect SET return_revision=return_revision+1 WHERE request_id='${pending.document}'")
        fixture(setup.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_return_title_effect WHERE request_id='${pending.document}'")).isEqualTo("1")
        }
    }

    @Test fun `rejected title request has no effect and customer can submit a new request`() {
        val setup = titleSetup()
        val rejected = pendingTitle(setup)
        titleStatus(decideTitle(setup, rejected, value = "REJECT"), "REWORK_REQUIRED")
        fixture(setup.token).transaction {
            assertThat(scalar("SELECT concat_ws('|',state,revision,approval_disposition) FROM inventory_document WHERE id='${rejected.document}'"))
                .isEqualTo("DRAFT|1|REWORK_REQUIRED")
            assertThat(scalar("SELECT count(*) FROM inventory_return_title_effect")).isEqualTo("0")
        }
        val revised = pendingTitle(setup, "revised-title")
        titleStatus(decideTitle(setup, revised, "revised-title-decision"), "APPROVED")
    }

    @Test fun `approved quarantine title survives vendor repair and reset inspection with historical customer title intact`() {
        val setup = titleSetup()
        val pending = pendingTitle(setup)
        titleStatus(decideTitle(setup, pending), "APPROVED")
        val repair = setup.repair.copy(returned = setup.returned.copy(revision = setup.returned.revision + 1))
        val outbound = dispatchRepair(repair)
        val inbound = receiveRepair(repair, outbound)
        assertThat(inspectRepair(repair, inbound).path("state").asString()).isEqualTo("ACCEPTED")
        fixture(setup.token).transaction {
            assertThat(scalar("SELECT concat_ws('|',legal_owner,state) FROM inventory_repair_case WHERE return_document_id='${setup.returned.id}'"))
                .isEqualTo("ISP|CLOSED")
            assertThat(scalar("SELECT concat_ws('|',legal_owner,status) FROM inventory_serialized_asset WHERE id='${repair.asset}'"))
                .isEqualTo("ISP|AVAILABLE")
            assertThat(scalar("SELECT legal_owner FROM inventory_asset_assignment WHERE id='${setup.returned.old.installation.operation}'"))
                .isEqualTo("CUSTOMER")
        }
    }

    private fun rejectedMutation(setup: TitleSetup, mutation: String) = fixture(setup.token).transaction {
        assertThat(scalar("SELECT current_user")).isEqualTo("warehouse_app")
        jdbc { connection ->
            val point = connection.setSavepoint()
            val rejection = runCatching {
                connection.createStatement().use { statement ->
                    statement.execute(mutation)
                    statement.execute("SET CONSTRAINTS ALL IMMEDIATE")
                }
            }.exceptionOrNull()
            connection.rollback(point)
            assertThat(rejection).isInstanceOf(SQLException::class.java)
            assertThat((rejection as SQLException).sqlState).isEqualTo("23514")
        }
    }
}
