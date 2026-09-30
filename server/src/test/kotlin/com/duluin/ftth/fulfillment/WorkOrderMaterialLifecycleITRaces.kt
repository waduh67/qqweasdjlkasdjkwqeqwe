package com.duluin.ftth.fulfillment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WorkOrderMaterialLifecycleITRaces : MaterialLifecycleFixture() {
    @Test fun `return and physical use cannot spend the same acknowledged stock`() {
        val case = usageCase()
        val target = create("locations", case.receipt.stock.token,
            """{"code":"RETURN_RACE","name":"Return race quarantine","kind":"QUARANTINE"}""").path("id").asString()
        val source = case.input.lines.single()
        val body = mapper.writeValueAsString(mapOf("workOrderRevision" to case.input.workOrderRevision,
            "receiptId" to source.receiptId, "issueLineId" to source.issueLineId, "stockIdentityId" to source.stockIdentityId,
            "quantityBase" to "100000", "baseUnit" to "MM", "targetLocationId" to target,
            "reason" to "Return versus use", "evidenceReference" to "signed-return"))

        val results = race({ use(case).status }, {
            request("POST", "/api/work-orders/${case.receipt.workOrder}/materials/return", case.receipt.receiver.first, body, "race-return").status
        })

        assertThat(results).containsExactlyInAnyOrder(200, 409)
        fixture(case.receipt.stock.token).transaction {
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status IN ('ISSUED','CONSUMED','IN_TRANSIT')")).isEqualTo("100000")
        }
    }

    @Test fun `cancel and dispatch retain every physically dispatched unit`() {
        val case = issuedSetup()
        val picked = action(case.stock.token, case.workOrder, "pick", pickBody(case))
        val input = transitionBody(case, picked)

        val results = race({ request("POST", "/api/work-orders/${case.workOrder}/cancel", case.stock.token,
            """{"reason":"Concurrent dispatch cancellation"}""").status }, { issueRequest(case, "dispatch", input).status })

        assertThat(results[0]).isIn(200, 409)
        assertThat(results[1]).isEqualTo(200)
        fixture(case.stock.token).transaction {
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='IN_TRANSIT'")).isEqualTo("100000")
            assertThat(scalar("SELECT sum(reserved_unpicked_base+reserved_picked_base) FROM inventory_reservation")).isEqualTo("0")
        }
    }

    @Test fun `cancel and reserve cannot leave an encumbrance on a cancelled job`() {
        val stock = setupReceipt()
        receiveStock(stock, "1000000")
        val workOrder = workOrder(stock.token)
        putPlan(stock.token, workOrder, plan(stock.token, workOrder, "[${line(stock.cable)}]"))
        action(stock.token, workOrder, "submit-request", command(stock.token, workOrder, 1))
        val input = command(stock.token, workOrder, 1)

        val results = race({ request("POST", "/api/work-orders/$workOrder/cancel", stock.token,
            """{"reason":"Concurrent reservation cancellation"}""").status }, {
            request("POST", "/api/work-orders/$workOrder/materials/reserve", stock.token, input, "race-reserve").status
        })

        assertThat(results[0]).isEqualTo(200)
        assertThat(results[1]).isIn(200, 409)
        fixture(stock.token).transaction {
            assertThat(scalar("SELECT coalesce(sum(reserved_unpicked_base+reserved_picked_base),0) FROM inventory_reservation")).isEqualTo("0")
        }
    }

    @Test fun `cancel and pick serialize without releasing picked stock`() {
        val case = issuedSetup()
        val input = pickBody(case)

        val results = race(
            { request("POST", "/api/work-orders/${case.workOrder}/cancel", case.stock.token, """{"reason":"Concurrent cancel"}""").status },
            { issueRequest(case, "pick", input).status })

        assertThat(results).containsExactlyInAnyOrder(200, 409)
        fixture(case.stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE kind='ISSUE'")).isEqualTo("0")
            assertThat(scalar("SELECT coalesce(sum(reserved_picked_base),0) FROM inventory_reservation")).isEqualTo(if (results[0] == 200) "0" else "100000")
        }
    }

    @Test fun `reassignment racing use never changes custody to the new technician`() {
        val case = usageCase()
        val replacement = technician(case.receipt.stock.token)

        val results = race(
            { use(case).status },
            { request("POST", "/api/work-orders/${case.receipt.workOrder}/assign", case.receipt.stock.token,
                """{"technicianIds":["${replacement.second}"]}""").status })

        assertThat(results[1]).isEqualTo(200)
        assertThat(results[0]).isIn(200, 403)
        assertThat(use(case).status).isEqualTo(403)
        fixture(case.receipt.stock.token).transaction {
            assertThat(scalar("SELECT coalesce(sum(quantity_base),0) FROM inventory_balance_projection WHERE custody_owner_id='${replacement.second}'")).isEqualTo("0")
        }
    }

    @Test fun `return dispatch and material closure preserve the outstanding obligation`() {
        val case = residualCase()
        val before = mapper.readTree(settlement(case).contentAsString).path("revision").asLong()

        val results = race(
            { dispatchResidual(case).status },
            { request("POST", "/api/work-orders/${case.usage.receipt.workOrder}/materials/settlement", case.usage.receipt.stock.token,
                """{"expectedRevision":$before,"workOrderRevision":${case.input.workOrderRevision},"reason":"Concurrent close"}""", "race-close").status })

        assertThat(results).containsExactly(200, 409)
        assertThat(mapper.readTree(settlement(case).contentAsString).path("outstandingBase").asString()).isEqualTo("17500")
    }

    @Test fun `concurrent receiver acknowledgements post custody once`() {
        val case = residualCase()
        val id = dispatchedResidual(case)

        val results = race({ acknowledgeResidual(case, id).status }, { acknowledgeResidual(case, id, key = "other-ack").status })

        assertThat(results).containsExactlyInAnyOrder(200, 409)
        fixture(case.usage.receipt.stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_material_residual_ack")).isEqualTo("1")
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='QUARANTINE'")).isEqualTo("17500")
        }
    }

    @Test fun `revocation racing dispatch replay denies every later body read`() {
        val case = residualCase()
        dispatchedResidual(case)
        val receipt = case.usage.receipt

        val results = race({ dispatchResidual(case).status }, {
            request("PUT", "/api/v1/warehouse/settings/scopes/${receipt.receiver.second}/${receipt.field}", receipt.stock.token,
                """{"expectedRevision":1,"active":false}""").status
        })

        assertThat(results[0]).isIn(200, 403, 404)
        assertThat(results[1]).isEqualTo(200)
        assertThat(dispatchResidual(case).status).isIn(403, 404)
    }

    private fun race(first: () -> Int, second: () -> Int): List<Int> {
        val gate = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { executor ->
            val tasks = listOf(first, second).map { action -> executor.submit<Int> { check(gate.await(10, TimeUnit.SECONDS)); action() } }
            gate.countDown()
            return tasks.map { it.get(45, TimeUnit.SECONDS) }
        }
    }
}
