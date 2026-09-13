package com.duluin.ftth.fulfillment

import com.duluin.ftth.inventory.WarehouseSchemaDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WarehouseFulfillmentITOwnerUpgrade : WarehouseFulfillmentFixture() {
    companion object {
        private val database by lazy { WarehouseSchemaDatabase("175.29") }
        @JvmStatic @DynamicPropertySource fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.url }
            registry.add("spring.flyway.url") { database.url }
            registry.add("spring.flyway.schemas") { database.schema }
            registry.add("spring.flyway.default-schema") { database.schema }
            registry.add("spring.flyway.target") { "175.29" }
        }
    }
    @AfterAll fun closeDatabase() { database.close() }

    @Test fun `upgrade rejects both admitted owner corruptions without rewriting history`() {
        val cable = usageCase()
        used(cable)
        completeJob(cable.receipt.workOrder,cable.receipt.receiver.first)
        val jobs = listOf(cable.receipt.stock.token to cable.receipt.workOrder, mismatchedOrder())
        val frozen = jobs.mapIndexed { index,(token,workOrder) ->
            val actor = UUID.fromString(mapper.readTree(request("GET","/api/me",token).contentAsString).path("id").asString())
            val fixture = fixture(token)
            fixture.transaction { FirstFulfillmentForgery(fixture,UUID.fromString(workOrder),actor).insert(missingVisit=index==0,handoff=index==1) }
        }
        val before = jobs.map { (token,_) -> integrityState(token) }
        assertThat(before).allMatch { it.startsWith("APPLIED|") }

        assertThat(database.migrate().migrationsExecuted).isEqualTo(5)

        jobs.forEachIndexed { index,(token,workOrder) ->
            val response = request("POST","/api/work-orders/$workOrder/approve",token,"{}")
            assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(409)
            assertThat(integrityState(token)).isEqualTo(before[index])
            assertThat(frozen[index].snapshot.workOrder.material.workOrderId.toString()).isEqualTo(workOrder)
        }
        assertThat(database.migrate().migrationsExecuted).isZero()
    }

    private fun integrityState(token: String) = fixture(token).transaction {
        scalar("""SELECT concat_ws('|',state,canonical_hash,
            (SELECT count(*) FROM fulfillment_approval_snapshot),(SELECT count(*) FROM inventory_material_settlement),
            (SELECT count(*) FROM inventory_movement),(SELECT count(*) FROM inventory_customer_material_fact),
            (SELECT coalesce(sum(quantity_base),0) FROM inventory_balance_projection WHERE status='CONSUMED')) FROM fulfillment_checkpoint""")
    }

    private fun mismatchedOrder(): Pair<String,String> {
        val stock = setupReceipt()
        fun post(path: String,body: String): tools.jackson.databind.JsonNode {
            val response = request("POST",path,stock.token,body)
            assertThat(response.status).withFailMessage(response.contentAsString).isIn(200,201)
            return mapper.readTree(response.contentAsString)
        }
        val customers = listOf("A","B").map { suffix -> post("/api/customers","""{"code":"OWNER-$suffix","name":"Customer $suffix","address":"Field","location":{"longitude":106.9,"latitude":-6.2}}""").path("id").asString() }
        var order = post("/api/orders","""{"customerId":"${customers[1]}","lines":[{"catalogItemId":"${stock.cable}","description":"Inspection","quantity":1}],
            "serviceAddress":{"address":"Field","city":"City","postalCode":"10000"},"operation":{"namespace":"test.order","key":"create","payloadHash":"create"}}""")
        val orderId = order.path("id").asString()
        for (transition in listOf("SUBMIT","ACCEPT","SCHEDULE","START_FULFILLING")) {
            order = post("/api/orders/$orderId/$transition","""{"expectedRevision":${order.path("revision").asLong()},
                "appointment":{"startsAt":"2030-01-01T10:00:00Z","endsAt":"2030-01-01T11:00:00Z"},
                "operation":{"namespace":"test.order","key":"$transition","payloadHash":"$transition"}}""")
        }
        val receiver = technician(stock.token)
        val workOrder = post("/api/work-orders","""{"type":"PREVENTIVE","title":"Order mismatch","customerId":"${customers[0]}","orderId":"$orderId","areaId":"${area(stock.token)}"}""").path("id").asString()
        assign(stock.token,workOrder,receiver.second)
        putPlan(stock.token,workOrder,plan(stock.token,workOrder,"[]",mode="NONE",reason="Inspection"))
        action(stock.token,workOrder,"submit-request",command(stock.token,workOrder,1))
        val revision = summary(receiver.first,workOrder).path("revisions").path("workOrderRevision").asLong()
        assertThat(request("POST","/api/work-orders/$workOrder/materials/report-use",receiver.first,
            """{"expectedRevision":0,"planRevision":1,"workOrderRevision":$revision,"materialMode":"NONE","reason":"Inspection","evidenceReference":"checked","lines":[]}""").status).isEqualTo(200)
        completeJob(workOrder,receiver.first)
        return stock.token to workOrder
    }
}
