package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.util.UUID
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseCountStore

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

    private fun measured(fixture: CountFixture, quantity: String): String {
        val id = draftCount(fixture).path("id").asString()
        action(id, fixture.setup.token, "start", """{"expectedRevision":0}""")
        action(id, fixture.counter.first, "observe", """{"expectedRevision":1,"balanceId":"${fixture.balance}",
            "quantityBase":"$quantity","reason":"Measured discrepancy","documentReference":"SHEET-2"}""")
        return id
    }

    private fun approval(fixture: CountFixture, id: String): Pair<String, String> {
        val reviewer = approver(fixture.setup.token, listOf(fixture.setup.bin))
        configure(fixture.setup.token, policyBody(listOf(fixture.setup.bin), listOf(reviewer.second), "COUNT_VARIANCE", "1"))
        val result = request("POST", "/api/v1/warehouse/approvals/request", fixture.setup.token,
            """{"sourceDocumentId":"$id","sourceRevision":3}""")
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(201)
        return reviewer.first to mapper.readTree(result.contentAsString).path("requestId").asString()
    }

    @Test fun `independent approval posts one linked negative count adjustment and replays unchanged`() {
        val fixture = stock()
        val id = measured(fixture, "80")
        action(id, fixture.setup.token, "submit", """{"expectedRevision":2}""")
        val (reviewer, approval) = approval(fixture, id)
        val body = """{"requestId":"$approval","expectedRevision":0,"decision":"APPROVE"}"""
        val response = request("POST", "/api/v1/warehouse/approvals/decide", reviewer, body, "approve-count")
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        assertThat(request("POST", "/api/v1/warehouse/approvals/decide", reviewer, body, "approve-count").contentAsString)
            .isEqualTo(response.contentAsString)
        fixture(fixture.setup.token).transaction {
            assertThat(scalar("SELECT quantity_base FROM inventory_balance_projection WHERE id='${fixture.balance}'")).isEqualTo("80")
            assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE document_id='$id' AND kind='COUNT_VARIANCE'")).isEqualTo("1")
            assertThat(scalar("SELECT state FROM inventory_document WHERE id='$id'")).isEqualTo("POSTED")
            assertThat(scalar("SELECT count(*) FROM inventory_cycle_count WHERE document_id='$id'")).isEqualTo("1")
        }
    }

    private fun moveTen(fixture: CountFixture) {
        val destination = UUID.fromString(create("locations", fixture.setup.token,
            """{"code":"OTHER","name":"Other warehouse","kind":"WAREHOUSE","issueEligible":true}""").path("id").asString())
        val database = fixture(fixture.setup.token)
        database.transaction {
            val position = context.getBean(WarehouseCountStore::class.java).position(UUID.fromString(fixture.balance))
            val document = UUID.randomUUID()
            val line = UUID.randomUUID()
            sql("""INSERT INTO inventory_document(id,tenant_id,code,kind,actor_id,cutover_epoch,authority_epoch)
                VALUES ('$document','$tenant','$document','TRANSFER','$actor',0,0)""")
            sql("""INSERT INTO inventory_document_line(id,tenant_id,document_id,line_number,document_revision,sku_id,stock_identity_id,lot_id,
                base_unit,tracking,quantity_base,location_id,custodian_id,custodian_kind,condition,legal_owner)
                SELECT '$line',tenant_id,'$document',1,0,sku_id,stock_identity_id,lot_id,base_unit,'BULK',10,location_id,
                    custody_owner_id,custody_owner_kind,condition,legal_owner FROM inventory_balance_projection WHERE id='${fixture.balance}'""")
            post(WarehousePost(document, 0, "DISPATCHED", operation("TRANSFER"), MovementKind.TRANSFER, "Concurrent stock movement",
                listOf(PostingLeg(LegDirection.OUT, position.dimension, StockQuantity.each("10"), line, InventoryStatus.AVAILABLE),
                    PostingLeg(LegDirection.IN, position.dimension.copy(locationId = destination, custodianId = destination),
                        StockQuantity.each("10"), line, InventoryStatus.AVAILABLE))))
        }
    }

    @Test fun `movement before final approval returns COUNT_STALE without changing stock or count evidence`() {
        val fixture = stock()
        val id = measured(fixture, "80")
        action(id, fixture.setup.token, "submit", """{"expectedRevision":2}""")
        val (reviewer, approval) = approval(fixture, id)
        moveTen(fixture)
        val response = request("POST", "/api/v1/warehouse/approvals/decide", reviewer,
            """{"requestId":"$approval","expectedRevision":0,"decision":"APPROVE"}""")
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(409)
        assertThat(mapper.readTree(response.contentAsString).path("code").asString()).isEqualTo("COUNT_STALE")
        fixture(fixture.setup.token).transaction {
            assertThat(scalar("SELECT quantity_base FROM inventory_balance_projection WHERE id='${fixture.balance}'")).isEqualTo("90")
            assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE document_id='$id'")).isEqualTo("0")
            assertThat(scalar("SELECT state FROM inventory_document WHERE id='$id'")).isEqualTo("RECOUNT_REQUIRED")
        }
    }

    @Test fun `movement before submission requires an append-only recount`() {
        val fixture = stock()
        val id = measured(fixture, "100")
        moveTen(fixture)
        val response = request("POST", "/api/v1/warehouse/counts/$id/submit", fixture.setup.token, """{"expectedRevision":2}""")
        assertThat(response.status).isEqualTo(409)
        assertThat(mapper.readTree(response.contentAsString).path("code").asString()).isEqualTo("COUNT_STALE")
        action(id, fixture.setup.token, "recount", """{"expectedRevision":3}""")
        action(id, fixture.counter.first, "observe", """{"expectedRevision":4,"balanceId":"${fixture.balance}",
            "quantityBase":"90","reason":"Recounted after movement","documentReference":"SHEET-3"}""")
        action(id, fixture.setup.token, "submit", """{"expectedRevision":5}""")
        fixture(fixture.setup.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_cycle_count WHERE document_id='$id'")).isEqualTo("2")
            assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE document_id='$id'")).isEqualTo("0")
        }
    }

    @Test fun `approver review exposes frozen book and measured quantities only after submission`() {
        val fixture = stock()
        val id = measured(fixture, "80")
        assertThat(request("GET", "/api/v1/warehouse/counts/$id/review", fixture.setup.token).status).isEqualTo(409)
        assertThat(request("GET", "/api/v1/warehouse/counts/$id/review", fixture.counter.first).status).isEqualTo(403)
        action(id, fixture.setup.token, "submit", """{"expectedRevision":2}""")
        val (reviewer, _) = approval(fixture, id)
        val reviewed = request("GET", "/api/v1/warehouse/counts/$id/review", reviewer)
        assertThat(reviewed.status).withFailMessage(reviewed.contentAsString).isEqualTo(200)
        val comparison = mapper.readTree(reviewed.contentAsString).path("observations")[0]
        assertThat(comparison.path("bookQuantityBase").asString()).isEqualTo("100")
        assertThat(comparison.path("quantityBase").asString()).isEqualTo("80")
    }

    @Test fun `positive count recovers only previously recorded missing units without minting identity`() {
        val fixture = stock()
        val first = measured(fixture, "80")
        action(first, fixture.setup.token, "submit", """{"expectedRevision":2}""")
        val (reviewer, approval) = approval(fixture, first)
        assertThat(request("POST", "/api/v1/warehouse/approvals/decide", reviewer,
            """{"requestId":"$approval","expectedRevision":0,"decision":"APPROVE"}""").status).isEqualTo(200)
        val second = measured(fixture, "100")
        action(second, fixture.setup.token, "submit", """{"expectedRevision":2}""")
        val pending = request("POST", "/api/v1/warehouse/approvals/request", fixture.setup.token,
            """{"sourceDocumentId":"$second","sourceRevision":3}""")
        assertThat(pending.status).withFailMessage(pending.contentAsString).isEqualTo(201)
        val requestId = mapper.readTree(pending.contentAsString).path("requestId").asString()
        val result = request("POST", "/api/v1/warehouse/approvals/decide", reviewer,
            """{"requestId":"$requestId","expectedRevision":0,"decision":"APPROVE"}""")
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
        fixture(fixture.setup.token).transaction {
            assertThat(scalar("SELECT quantity_base FROM inventory_balance_projection WHERE id='${fixture.balance}'")).isEqualTo("100")
            assertThat(scalar("SELECT count(*) FROM inventory_segment")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE kind='COUNT_VARIANCE'")).isEqualTo("2")
        }
    }

    @Test fun `unknown positive variance is actionable and cannot invent stock`() {
        val fixture = stock()
        val id = measured(fixture, "120")
        action(id, fixture.setup.token, "submit", """{"expectedRevision":2}""")
        val (reviewer, approval) = approval(fixture, id)
        val result = request("POST", "/api/v1/warehouse/approvals/decide", reviewer,
            """{"requestId":"$approval","expectedRevision":0,"decision":"APPROVE"}""")
        assertThat(result.status).isEqualTo(409)
        assertThat(result.contentAsString).contains("previously recorded")
        fixture(fixture.setup.token).transaction {
            assertThat(scalar("SELECT quantity_base FROM inventory_balance_projection WHERE id='${fixture.balance}'")).isEqualTo("100")
            assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE document_id='$id'")).isEqualTo("0")
        }
    }

    @Test fun `requester cannot approve own count even with administrative permissions`() {
        val fixture = stock()
        val id = measured(fixture, "80")
        action(id, fixture.setup.token, "submit", """{"expectedRevision":2}""")
        val (_, approval) = approval(fixture, id)
        val result = request("POST", "/api/v1/warehouse/approvals/decide", fixture.setup.token,
            """{"requestId":"$approval","expectedRevision":0,"decision":"APPROVE"}""")
        assertThat(result.status).isEqualTo(403)
        fixture(fixture.setup.token).transaction { assertThat(scalar("SELECT count(*) FROM inventory_approval_decision")).isEqualTo("0") }
    }

    @Test fun `revoked warehouse scope blocks original observation replay and history`() {
        val fixture = stock()
        val id = draftCount(fixture).path("id").asString()
        action(id, fixture.setup.token, "start", """{"expectedRevision":0}""")
        val body = """{"expectedRevision":1,"balanceId":"${fixture.balance}","quantityBase":"100","reason":"Measured","documentReference":"SHEET"}"""
        assertThat(request("POST", "/api/v1/warehouse/counts/$id/observe", fixture.counter.first, body, "own-observation").status).isEqualTo(200)
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${fixture.counter.second}/${fixture.setup.bin}", fixture.setup.token,
            """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)
        assertThat(request("POST", "/api/v1/warehouse/counts/$id/observe", fixture.counter.first, body, "own-observation").status).isIn(403, 404)
        assertThat(request("GET", "/api/v1/warehouse/counts/$id/history", fixture.counter.first).status).isIn(403, 404)
    }

    @Test fun `app role cannot append a count fact without its bound observation command`() {
        val fixture = stock()
        val id = draftCount(fixture).path("id").asString()
        action(id, fixture.setup.token, "start", """{"expectedRevision":0}""")
        assertThatThrownBy {
            fixture(fixture.setup.token).transaction {
                val store = context.getBean(WarehouseCountStore::class.java)
                val input = WarehouseCountObservation(1, UUID.fromString(fixture.balance), "100", "Forged command", "NO-COMMAND")
                store.observe(store.get(UUID.fromString(id)), input, store.position(input.balanceId), UUID.fromString(fixture.counter.second), "forged", "a".repeat(64))
            }
        }.hasStackTraceContaining("count observation command")
    }

    @Test fun `app role cannot change or delete immutable measured facts`() {
        val fixture = stock()
        val id = measured(fixture, "100")
        for (statement in listOf("UPDATE inventory_cycle_count SET observed_quantity_base=99,revision=revision+1 WHERE document_id='$id'",
            "DELETE FROM inventory_cycle_count WHERE document_id='$id'")) {
            assertThatThrownBy { fixture(fixture.setup.token).transaction { sql(statement) } }.hasStackTraceContaining("count observations are immutable")
        }
        fixture(fixture.setup.token).transaction {
            assertThat(scalar("SELECT observed_quantity_base FROM inventory_cycle_count WHERE document_id='$id'")).isEqualTo("100")
        }
    }

    @Test fun `barrier concurrent final decisions produce one adjustment and one original replay`() {
        val fixture = stock()
        val id = measured(fixture, "80")
        action(id, fixture.setup.token, "submit", """{"expectedRevision":2}""")
        val (reviewer, approval) = approval(fixture, id)
        val gate = java.util.concurrent.CountDownLatch(1)
        java.util.concurrent.Executors.newFixedThreadPool(2).use { pool ->
            val calls = (1..2).map { pool.submit<String> {
                check(gate.await(10, java.util.concurrent.TimeUnit.SECONDS))
                val response = request("POST", "/api/v1/warehouse/approvals/decide", reviewer,
                    """{"requestId":"$approval","expectedRevision":0,"decision":"APPROVE"}""", "concurrent-approval")
                assertThat(response.status).isEqualTo(200)
                response.contentAsString
            } }
            gate.countDown()
            assertThat(calls[0].get(30, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(calls[1].get(30, java.util.concurrent.TimeUnit.SECONDS))
        }
        fixture(fixture.setup.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE document_id='$id'")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_approval_decision WHERE approval_id='$approval'")).isEqualTo("1")
        }
    }
}
