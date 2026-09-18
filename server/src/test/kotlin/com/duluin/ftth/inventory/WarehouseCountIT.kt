package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

class WarehouseCountIT : WarehousePolicyHttpFixture() {
    private data class CountFixture(val setup: Setup, val balance: String, val counter: Pair<String, String>)

    private fun stock(): CountFixture {
        val setup = setupReceipt()
        val sku = create("skus", setup.token,
            """{"code":"PARTS","name":"Parts","tracking":"BULK","baseUnit":"EA","inspectionRequired":false}""").path("id").asString()
        val receipt = draft(setup, """{"skuId":"$sku","quantityBase":"100","lotCode":"PARTS-1","cost":{"totalMinor":"1000","currency":"IDR"}}""")
        val id = receipt.path("id").asString()
        transition(setup, id, "receive", """{"expectedRevision":0}""")
        val received = mapper.readTree(request("GET", "/api/v1/warehouse/receipts/$id", setup.token).contentAsString)
        val line = received.path("lines")[0]
        val identity = line.path("pieces")[0].path("stockIdentityId").asString()
        transition(setup, id, "putaway", """{"expectedRevision":1,"destinationLocationId":"${setup.bin}","lines":[
            {"lineId":"${line.path("id").asString()}","stockIdentityId":"$identity","quantityBase":"100","baseUnit":"EA"}]}""")
        val balance = fixture(setup.token).transaction {
            requireNotNull(scalar("SELECT id FROM inventory_balance_projection WHERE location_id='${setup.bin}' AND quantity_base=100"))
        }
        val counter = approver(setup.token, listOf(setup.bin), setOf("inventory.count.view", "inventory.count.manage"))
        return CountFixture(setup, balance, counter)
    }

    private fun draftCount(fixture: CountFixture): JsonNode = create("counts", fixture.setup.token,
        """{"locationId":"${fixture.setup.bin}","partialLocation":true,"reason":"Measured stock check","entries":[
            {"balanceId":"${fixture.balance}","counterId":"${fixture.counter.second}"}]}""")

    private fun action(id: String, actor: String, action: String, body: String): JsonNode {
        val response = request("POST", "/api/v1/warehouse/counts/$id/$action", actor, body)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        return mapper.readTree(response.contentAsString)
    }

    @Test fun `assigned counter observes blind stock and unchanged count preserves every movement`() {
        val fixture = stock()
        val draft = draftCount(fixture)
        val id = draft.path("id").asString()
        val started = action(id, fixture.setup.token, "start", """{"expectedRevision":0}""")
        assertThat(started.path("state").asString()).isEqualTo("COUNTING")
        val read = request("GET", "/api/v1/warehouse/counts/$id", fixture.counter.first)
        assertThat(read.status).isEqualTo(200)
        assertThat(read.contentAsString).doesNotContain("priorQuantity", "expectedQuantity", "bookQuantity", "quantityBase\":\"100")
        val observed = action(id, fixture.counter.first, "observe", """{"expectedRevision":1,"balanceId":"${fixture.balance}",
            "quantityBase":"100","reason":"Counted by hand","documentReference":"COUNT-SHEET-1"}""")
        assertThat(observed.path("revision").asLong()).isEqualTo(2)
        val submitted = action(id, fixture.setup.token, "submit", """{"expectedRevision":2}""")
        assertThat(submitted.path("state").asString()).isEqualTo("POSTED")
        fixture(fixture.setup.token).transaction {
            assertThat(scalar("SELECT current_user")).isEqualTo("warehouse_app")
            assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE document_id='$id'")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_cycle_count WHERE document_id='$id'")).isEqualTo("1")
            assertThat(scalar("SELECT quantity_base FROM inventory_balance_projection WHERE id='${fixture.balance}'")).isEqualTo("100")
        }
    }

    @Test fun `wrong counter and foreign tenant cannot read or append count facts`() {
        val fixture = stock()
        val id = draftCount(fixture).path("id").asString()
        action(id, fixture.setup.token, "start", """{"expectedRevision":0}""")
        val other = approver(fixture.setup.token, listOf(fixture.setup.bin), setOf("inventory.count.view", "inventory.count.manage"))
        val response = request("POST", "/api/v1/warehouse/counts/$id/observe", other.first,
            """{"expectedRevision":1,"balanceId":"${fixture.balance}","quantityBase":"100","reason":"Measured","documentReference":"SHEET"}""")
        assertThat(response.status).isIn(403, 404)
        assertThat(request("GET", "/api/v1/warehouse/counts/$id", tenant()).status).isEqualTo(404)
        fixture(fixture.setup.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_cycle_count WHERE document_id='$id'")).isEqualTo("0")
        }
    }

    @Test fun `new count facts have an immutable application role guard`() {
        val token = tenant()
        fixture(token).transaction {
            assertThat(scalar("""SELECT count(*) FROM pg_trigger WHERE tgrelid='inventory_cycle_count'::regclass
                AND tgname='warehouse_count_evidence_immutable' AND NOT tgisinternal""")).isEqualTo("1")
        }
    }
}
