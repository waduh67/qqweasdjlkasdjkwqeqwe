package com.duluin.ftth.customer

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.springframework.mock.web.MockHttpServletResponse

abstract class CustomerAssetEpisodeRevisionCases : CustomerAssetReplacementFixture() {
    protected data class RevisionCase(val replacement: ReplacementCase, val odp: UUID)

    protected fun move(case: RevisionCase, port: Int, revision: Long) = request("POST",
        "/api/customers/${case.replacement.old.installation.customer}/assets/${case.replacement.old.installation.operation}/relocate",
        case.replacement.replacement.receiver.first,
        """{"workOrderId":"${case.replacement.replacement.workOrder}","expectedWorkOrderRevision":${summary(case.replacement.replacement.stock.token, case.replacement.replacement.workOrder).path("revisions").path("workOrderRevision").asLong()},
            "expectedRevision":$revision,"topology":{"odpId":"${case.odp}","portNumber":$port,"installRxPowerDbm":null}}""", "revision-move-$revision")

    protected fun revisionCase(): RevisionCase {
        val replacement = replacementCase()
        val case = RevisionCase(replacement, topology(replacement.old))
        val moved = move(case, 1, 0)
        assertThat(moved.status).withFailMessage(moved.contentAsString).isEqualTo(200)
        return case
    }

    @Test
    fun `swap after relocation returns old revision two and new opening revision zero`() {
        val case = revisionCase()
        val replacement = case.replacement
        val telemetry = populateTelemetry(replacement.old)
        val authorized = authorizeReplacement(replacement)
        assertThat(authorized.status).isEqualTo(200)
        val authorization = mapper.readTree(authorized.contentAsString).path("authorizationId").asString()

        val swapped = request("POST", "/api/customers/${replacement.old.installation.customer}/assets/replace", replacement.replacement.receiver.first,
            """{"authorizationId":"$authorization","expectedRevision":0,"expectedAssignmentRevision":1,"expectedTitleRevision":0,
                "evidenceId":"${replacement.evidence}","topology":null}""", "relocated-swap")

        assertThat(swapped.status).withFailMessage(swapped.contentAsString).isEqualTo(201)
        val response = mapper.readTree(swapped.contentAsString)
        assertThat(response.path("retired").path("episodeRevision").asLong()).isEqualTo(2)
        assertThat(response.path("replacement").path("episodeRevision").asLong()).isZero()
        assertThat(telemetryFingerprint(replacement.old)).isEqualTo(telemetry)
        fixture(replacement.replacement.stock.token).transaction {
            assertThat(scalar("SELECT episode_revision FROM onu WHERE id='${replacement.old.installation.operation}'")).isEqualTo("2")
            assertThat(scalar("SELECT episode_revision FROM onu WHERE id='${response.path("replacement").path("onuId").asString()}'")).isEqualTo("0")
        }
    }

    @Test
    fun `concurrent topology and retirement return exactly the committed event revision`() {
        val case = revisionCase()
        val old = case.replacement.old.installation
        val receipt = case.replacement.replacement
        val order = workOrder(receipt.stock.token, "DISMANTLE", old.customer.toString())
        assign(receipt.stock.token, order, receipt.receiver.second)
        val evidence = removalEvidence(receipt.receiver.first, order)
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val topology = pool.submit<MockHttpServletResponse> { barrier.await(15, TimeUnit.SECONDS); move(case, 2, 1) }
            val retirement = pool.submit<MockHttpServletResponse> {
                barrier.await(15, TimeUnit.SECONDS)
                request("POST", "/api/customers/${old.customer}/assets/remove", receipt.receiver.first,
                    """{"assignmentId":"${old.operation}","expectedRevision":1,"expectedTitleRevision":0,"workOrderId":"$order","evidenceId":"$evidence"}""", "concurrent-revision")
            }

            val removed = retirement.get(45, TimeUnit.SECONDS)
            val moved = topology.get(45, TimeUnit.SECONDS)

            assertThat(removed.status).withFailMessage(removed.contentAsString).isEqualTo(200)
            assertThat(moved.status).isIn(200, 409)
            val revision = mapper.readTree(removed.contentAsString).path("retired").path("episodeRevision").asLong()
            assertThat(revision).isEqualTo(if (moved.status == 200) 3L else 2L)
            fixture(receipt.stock.token).transaction {
                assertThat(scalar("SELECT episode_revision FROM onu WHERE id='${old.operation}'").toLong()).isEqualTo(revision)
                assertThat(scalar("SELECT max(revision) FROM customer_onu_episode_event WHERE onu_id='${old.operation}'").toLong()).isEqualTo(revision)
                assertThat(scalar("SELECT response::jsonb->>'episodeRevision' FROM customer_asset_retirement WHERE episode_id='${old.operation}'").toLong()).isEqualTo(revision)
            }
        } finally { pool.shutdownNow() }
    }

    @ParameterizedTest
    @ValueSource(strings = ["INCREMENT", "DECREMENT", "SKIP", "EVENT_WITHOUT_UPDATE", "HISTORY_WITHOUT_UPDATE", "DUPLICATE_EVENT",
        "WRONG_ASSIGNMENT", "WRONG_ASSET", "WRONG_CUSTOMER", "WRONG_ONU", "WRONG_TENANT", "DELETE_EVENT", "SUBSTITUTE_EVENT", "DELETE_TOPOLOGY"])
    fun `episode event graph rejects direct corruption`(mutation: String) {
        val case = revisionCase()
        val old = case.replacement.old.installation
        val stock = fixture(case.replacement.replacement.stock.token)

        assertThrows<Exception> {
            stock.transaction {
                when (mutation) {
                    "INCREMENT" -> sql("UPDATE onu SET episode_revision=episode_revision+1 WHERE id='${old.operation}'")
                    "DECREMENT" -> sql("UPDATE onu SET episode_revision=episode_revision-1 WHERE id='${old.operation}'")
                    "SKIP" -> sql("UPDATE onu SET episode_revision=episode_revision+2 WHERE id='${old.operation}'")
                    "HISTORY_WITHOUT_UPDATE" -> sql("""INSERT INTO onu_topology_history SELECT tenant_id,onu_id,assignment_id,revision+1,effective_at,snapshot
                        FROM onu_topology_history WHERE onu_id='${old.operation}' AND revision=1""")
                    "DELETE_EVENT" -> sql("DELETE FROM customer_onu_episode_event WHERE onu_id='${old.operation}' AND revision=1")
                    "SUBSTITUTE_EVENT" -> sql("UPDATE customer_onu_episode_event SET customer_id='${UUID.randomUUID()}' WHERE onu_id='${old.operation}' AND revision=1")
                    "DELETE_TOPOLOGY" -> sql("DELETE FROM onu_topology_history WHERE onu_id='${old.operation}' AND revision=1")
                    else -> {
                        val change = when (mutation) {
                            "EVENT_WITHOUT_UPDATE" -> "'revision',2,'source_revision',1,'topology_revision',2"
                            "DUPLICATE_EVENT" -> "'revision',1"
                            "WRONG_ASSIGNMENT" -> "'assignment_id','${UUID.randomUUID()}'"
                            "WRONG_ASSET" -> "'asset_id','${UUID.randomUUID()}'"
                            "WRONG_CUSTOMER" -> "'customer_id','${UUID.randomUUID()}'"
                            "WRONG_ONU" -> "'onu_id','${UUID.randomUUID()}'"
                            "WRONG_TENANT" -> "'tenant_id','${UUID.randomUUID()}'"
                            else -> error("Unknown mutation")
                        }
                        sql("""INSERT INTO customer_onu_episode_event SELECT (jsonb_populate_record(NULL::customer_onu_episode_event,
                            to_jsonb(event)||jsonb_build_object($change,'created_xid',pg_current_xact_id()::text))).*
                            FROM customer_onu_episode_event event WHERE onu_id='${old.operation}' AND revision=1""")
                    }
                }
            }
        }

        stock.transaction { assertThat(scalar("SELECT episode_revision FROM onu WHERE id='${old.operation}'")).isEqualTo("1") }
    }

    @ParameterizedTest
    @ValueSource(strings = ["NORMAL", "CLEARED", "MISMATCHED", "RESTORED", "SELECTIVE"])
    fun `authoritative topology event final validation owns its tenant scope`(timing: String) {
        val case = revisionCase()
        val stock = fixture(case.replacement.replacement.stock.token)
        val change = {
            stock.transaction {
                sql("UPDATE onu SET odp_port_number=2 WHERE id='${case.replacement.old.installation.operation}'")
                when (timing) {
                    "CLEARED" -> sql("SET LOCAL app.tenant_id=''")
                    "MISMATCHED" -> sql("SET LOCAL app.tenant_id='${UUID.randomUUID()}'")
                    "RESTORED" -> { sql("SET LOCAL app.tenant_id=''"); sql("SET LOCAL app.tenant_id='$tenant'") }
                    "NORMAL", "SELECTIVE" -> Unit
                    else -> error("Unknown timing")
                }
                sql(if (timing == "SELECTIVE") "SET CONSTRAINTS warehouse_onu_episode_final IMMEDIATE" else "SET CONSTRAINTS ALL IMMEDIATE")
            }
        }

        if (timing in setOf("CLEARED", "MISMATCHED")) assertThrows<Exception> { change() } else change()

        stock.transaction {
            assertThat(scalar("SELECT episode_revision FROM onu WHERE id='${case.replacement.old.installation.operation}'"))
                .isEqualTo(if (timing in setOf("CLEARED", "MISMATCHED")) "1" else "2")
        }
    }

    @Test
    fun `two topology events then dismantle return the third persisted episode revision`() {
        val case = revisionCase()
        val old = case.replacement.old
        val receipt = case.replacement.replacement
        val telemetry = populateTelemetry(old)
        assertThat(move(case, 2, 1).status).isEqualTo(200)
        val order = workOrder(receipt.stock.token, "DISMANTLE", old.installation.customer.toString())
        assign(receipt.stock.token, order, receipt.receiver.second)
        val evidence = removalEvidence(receipt.receiver.first, order)

        val removed = request("POST", "/api/customers/${old.installation.customer}/assets/remove", receipt.receiver.first,
            """{"assignmentId":"${old.installation.operation}","expectedRevision":1,"expectedTitleRevision":0,
                "workOrderId":"$order","evidenceId":"$evidence"}""", "third-revision")

        assertThat(removed.status).withFailMessage(removed.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(removed.contentAsString).path("retired").path("episodeRevision").asLong()).isEqualTo(3)
        assertThat(telemetryFingerprint(old)).isEqualTo(telemetry)
        fixture(receipt.stock.token).transaction {
            assertThat(scalar("SELECT episode_revision FROM onu WHERE id='${old.installation.operation}'")).isEqualTo("3")
            assertThat(scalar("SELECT response::jsonb->>'episodeRevision' FROM customer_asset_retirement WHERE episode_id='${old.installation.operation}'")).isEqualTo("3")
            assertThat(scalar("SELECT string_agg(kind||':'||revision,',' ORDER BY revision) FROM customer_onu_episode_event WHERE onu_id='${old.installation.operation}'"))
                .isEqualTo("OPENED:0,TOPOLOGY:1,TOPOLOGY:2,RETIRED:3")
        }
    }
}
