package com.duluin.ftth.customer

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

abstract class CustomerAssetReplacementRaceCases : CustomerAssetReplacementCommandCases() {
    private fun race(first: () -> Int, second: () -> Int): List<Int> {
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf(first, second).map { action -> pool.submit<Int> {
                ready.countDown(); check(start.await(15, TimeUnit.SECONDS)); action()
            } }
            check(ready.await(15, TimeUnit.SECONDS))
            start.countDown()
            return futures.map { it.get(45, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `customer deletion racing replacement cannot destroy either history`() {
        val case = replacementCase()
        val authorized = authorizeReplacement(case)
        assertThat(authorized.status).isEqualTo(200)
        val authorization = mapper.readTree(authorized.contentAsString).path("authorizationId").asString()

        val outcomes = race({
            request("DELETE", "/api/customers/${case.old.installation.customer}", case.replacement.stock.token).status
        }, {
            request("POST", "/api/customers/${case.old.installation.customer}/assets/replace", case.replacement.receiver.first,
                """{"authorizationId":"$authorization","expectedRevision":0,"expectedAssignmentRevision":1,"expectedTitleRevision":0,
                    "evidenceId":"${case.evidence}","topology":null}""", "delete-race-swap").status
        })

        assertThat(outcomes).containsExactly(409, 201)
        fixture(case.replacement.stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM customer_asset_installation WHERE customer_id='${case.old.installation.customer}'")).isEqualTo("2")
            assertThat(scalar("SELECT count(*) FROM customer_asset_retirement WHERE episode_id='${case.old.installation.operation}'")).isEqualTo("1")
        }
    }

    @Test
    fun `topology relocation racing dismantle cannot restock or resurrect the retired ONU`() {
        val case = replacementCase()
        val receipt = case.replacement
        val odp = topology(case.old)
        val workRevision = summary(receipt.stock.token, receipt.workOrder).path("revisions").path("workOrderRevision").asLong()
        val dismantle = workOrder(receipt.stock.token, "DISMANTLE", case.old.installation.customer.toString())
        assign(receipt.stock.token, dismantle, receipt.receiver.second)
        val evidence = removalEvidence(receipt.receiver.first, dismantle)

        val outcomes = race({
            request("POST", "/api/customers/${case.old.installation.customer}/assets/${case.old.installation.operation}/relocate", receipt.receiver.first,
                """{"workOrderId":"${receipt.workOrder}","expectedWorkOrderRevision":$workRevision,"expectedRevision":0,
                    "topology":{"odpId":"$odp","portNumber":1,"installRxPowerDbm":null}}""", "relocate-race").status
        }, {
            request("POST", "/api/customers/${case.old.installation.customer}/assets/remove", receipt.receiver.first,
                """{"assignmentId":"${case.old.installation.operation}","expectedRevision":1,"expectedTitleRevision":0,
                    "workOrderId":"$dismantle","evidenceId":"$evidence"}""", "relocate-race-remove").status
        })

        assertThat(outcomes[0]).isIn(200, 409)
        assertThat(outcomes[1]).isEqualTo(200)
        fixture(receipt.stock.token).transaction {
            assertThat(scalar("SELECT status FROM inventory_serialized_asset WHERE id='${case.old.installation.receipt.input.lines.single().stockIdentityId}'")).isEqualTo("QUARANTINE")
            assertThat(scalar("SELECT count(*) FROM onu WHERE assignment_id='${case.old.installation.operation}' AND retired_at IS NOT NULL AND odp_id IS NULL")).isEqualTo("1")
        }
    }

    @Test
    fun `reassignment racing swap retains a single physical outcome and denies every later old actor command`() {
        val case = replacementCase()
        val receipt = case.replacement
        val replacementTechnician = technician(receipt.stock.token, setOf("customer.onu.assign"))
        val authorized = authorizeReplacement(case)
        assertThat(authorized.status).isEqualTo(200)
        val authorization = mapper.readTree(authorized.contentAsString).path("authorizationId").asString()
        val body = """{"authorizationId":"$authorization","expectedRevision":0,"expectedAssignmentRevision":1,"expectedTitleRevision":0,
            "evidenceId":"${case.evidence}","topology":null}"""
        val path = "/api/customers/${case.old.installation.customer}/assets/replace"

        val outcomes = race({
            request("POST", "/api/work-orders/${receipt.workOrder}/assign", receipt.stock.token,
                """{"technicianIds":["${replacementTechnician.second}"]}""").status
        }, { request("POST", path, receipt.receiver.first, body, "reassignment-race").status })

        assertThat(outcomes[0]).isEqualTo(200)
        assertThat(outcomes[1]).isIn(201, 403, 404, 409)
        assertThat(request("POST", path, receipt.receiver.first, body, "reassignment-race").status).isIn(403, 404, 409)
        fixture(receipt.stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment WHERE customer_id='${case.old.installation.customer}' AND ended_at IS NULL")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_asset_removal WHERE assignment_id='${case.old.installation.operation}'"))
                .isEqualTo(if (outcomes[1] == 201) "1" else "0")
        }
    }
}
