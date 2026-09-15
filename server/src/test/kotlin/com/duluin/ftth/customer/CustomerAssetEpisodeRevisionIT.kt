package com.duluin.ftth.customer

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.SQLException

class CustomerAssetEpisodeRevisionIT : CustomerAssetEpisodeRevisionCases() {
    @ParameterizedTest
    @ValueSource(strings = ["NORMAL", "RESTORED", "SELECTIVE"])
    fun `active episode increment without an event rejects at the application role boundary`(timing: String) {
        val old = ownershipCase()
        val stock = fixture(old.installation.receipt.stock.token)

        assertThrows<Exception> {
            stock.transaction {
                sql("UPDATE onu SET episode_revision=episode_revision+1 WHERE id='${old.installation.operation}'")
                when (timing) {
                    "RESTORED" -> { sql("SET LOCAL app.tenant_id=''"); sql("SET LOCAL app.tenant_id='$tenant'") }
                    "SELECTIVE" -> sql("SET CONSTRAINTS warehouse_asset_episode_final IMMEDIATE")
                    "NORMAL" -> Unit
                    else -> error("Unknown timing")
                }
            }
        }

        stock.transaction { assertThat(scalar("SELECT episode_revision FROM onu WHERE id='${old.installation.operation}'")).isEqualTo("0") }
    }

    @Test
    fun `dismantle response equals persisted retirement after an attempted unowned increment`() {
        val old = ownershipCase()
        assertThat(accept(old).status).isEqualTo(200)
        val receipt = old.installation.receipt
        val stock = fixture(receipt.stock.token)
        try {
            stock.transaction { sql("UPDATE onu SET episode_revision=episode_revision+1 WHERE id='${old.installation.operation}'") }
        } catch (failure: Exception) {
            val databaseFailure = generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<SQLException>().firstOrNull()
            assertThat(databaseFailure?.sqlState).isEqualTo("23514")
        }
        val order = workOrder(receipt.stock.token, "DISMANTLE", old.installation.customer.toString())
        assign(receipt.stock.token, order, receipt.receiver.second)
        val evidence = removalEvidence(receipt.receiver.first, order)

        val removed = request("POST", "/api/customers/${old.installation.customer}/assets/remove", receipt.receiver.first,
            """{"assignmentId":"${old.installation.operation}","expectedRevision":1,"expectedTitleRevision":0,
                "workOrderId":"$order","evidenceId":"$evidence"}""", "revision-removal")

        assertThat(removed.status).withFailMessage(removed.contentAsString).isEqualTo(200)
        val responseRevision = mapper.readTree(removed.contentAsString).path("retired").path("episodeRevision").asLong()
        stock.transaction {
            assertThat(responseRevision).isEqualTo(scalar("SELECT episode_revision FROM onu WHERE id='${old.installation.operation}'").toLong())
            assertThat(scalar("SELECT response::jsonb->>'episodeRevision' FROM customer_asset_retirement WHERE episode_id='${old.installation.operation}'").toLong())
                .isEqualTo(responseRevision)
        }
    }

    @Test
    fun `topology relocation advances the authoritative episode event and returns its revision`() {
        val case = replacementCase()
        val odp = topology(case.old)
        val receipt = case.replacement
        val revision = summary(receipt.stock.token, receipt.workOrder).path("revisions").path("workOrderRevision").asLong()

        val moved = request("POST", "/api/customers/${case.old.installation.customer}/assets/${case.old.installation.operation}/relocate", receipt.receiver.first,
            """{"workOrderId":"${receipt.workOrder}","expectedWorkOrderRevision":$revision,"expectedRevision":0,
                "topology":{"odpId":"$odp","portNumber":1,"installRxPowerDbm":null}}""", "episode-relocation")

        assertThat(moved.status).withFailMessage(moved.contentAsString).isEqualTo(200)
        val response = mapper.readTree(moved.contentAsString)
        assertThat(response.path("episodeRevision").asLong()).isEqualTo(1)
        fixture(receipt.stock.token).transaction {
            assertThat(scalar("SELECT episode_revision FROM onu WHERE id='${case.old.installation.operation}'")).isEqualTo("1")
            assertThat(scalar("SELECT topology_revision FROM onu WHERE id='${case.old.installation.operation}'")).isEqualTo(response.path("revision").asString())
        }
    }
}
