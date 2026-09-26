package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.SQLException
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WarehouseCountDraftIT : WarehouseTransferFixture() {
    private val root = "/api/v1/warehouse/counts"
    private val permissions = setOf("inventory.count.view", "inventory.count.manage")

    @ParameterizedTest @ValueSource(strings = ["100000", "80"])
    fun `saved blind draft replaces scope and assignments then completes its actual edited round`(observed: String) {
        val stock = transferStock()
        val admin = stock.setup.token
        val firstCounter = approver(admin, listOf(stock.setup.bin), permissions)
        val oldBody = body(stock.setup.bin, balance(stock), firstCounter.second, "Original count")
        val original = request("POST", root, admin, oldBody, "original-count")
        assertThat(original.status).withFailMessage(original.contentAsString).isEqualTo(201)
        val id = mapper.readTree(original.contentAsString).path("id").asString()
        val second = incoming(stock, stock.destination, bulk = observed == "80")
        val newCounter = approver(admin, listOf(stock.destination), permissions)
        val editBody = """{"expectedRevision":0,"draft":${body(stock.destination, second, newCounter.second, "Revised count scope")}}"""
        val before = physical(admin)
        val edited = request("PUT", "$root/$id", admin, editBody, "edited-count")
        assertThat(edited.status).withFailMessage(edited.contentAsString).isEqualTo(200)
        val view = mapper.readTree(edited.contentAsString)
        assertThat(view.path("revision").asLong()).isEqualTo(1)
        assertThat(view.path("state").asString()).isEqualTo("DRAFT")
        assertThat(view.path("locationId").asString()).isEqualTo(stock.destination)
        assertThat(view.path("roundRevision").isNull).isTrue()
        assertThat(view.path("entries").single().path("balanceId").asString()).isEqualTo(second)
        assertThat(view.path("entries").single().path("counterId").asString()).isEqualTo(newCounter.second)
        assertThat(edited.contentAsString).doesNotContain("quantityBase", "bookQuantity", "capacity", "100000")
        assertThat(physical(admin)).isEqualTo(before)
        val form = request("GET", "$root/$id/draft", admin)
        assertThat(form.status).withFailMessage(form.contentAsString).isEqualTo(200)
        val data = mapper.readTree(form.contentAsString)
        assertThat(data.path("details").path("references").path("reason").asString()).isEqualTo("Revised count scope")
        assertThat(data.path("positions").single().path("id").asString()).isEqualTo(second)
        assertThat(data.path("eligibleAssignedCounterIds").single().asString()).isEqualTo(newCounter.second)
        assertThat(form.contentAsString).doesNotContain("quantityBase", "bookQuantity", "available", "reserved", "capacity", "100000")
        assertThat(request("GET", "$root/$id", firstCounter.first).status).isEqualTo(404)
        assertThat(request("GET", "$root/$id/draft", newCounter.first).status).isEqualTo(403)
        assertThat(request("PUT", "$root/$id", admin, editBody).status).isEqualTo(409)
        assertThat(request("PUT", "$root/$id", admin, editBody.replace("Revised count scope", "Other"), "edited-count").status).isEqualTo(409)
        assertThat(action(id, admin, "start", """{"expectedRevision":1}""").path("roundRevision").asLong()).isEqualTo(2)
        val replay = request("PUT", "$root/$id", admin, editBody, "edited-count")
        assertThat(replay.status).isEqualTo(200)
        assertThat(replay.contentAsString).isEqualTo(edited.contentAsString)
        assertThat(request("POST", root, admin, oldBody, "original-count").contentAsString).isEqualTo(original.contentAsString)
        assertThat(request("PUT", "$root/$id", admin, editBody.replace("expectedRevision\":0", "expectedRevision\":2")).status).isEqualTo(409)
        action(id, newCounter.first, "observe", """{"expectedRevision":2,"balanceId":"$second","quantityBase":"$observed",
            "reason":"Measured physical reel","documentReference":"COUNT-EDITED"}""")
        val submitted = action(id, admin, "submit", """{"expectedRevision":3}""")
        if (observed == "100000") {
            assertThat(submitted.path("state").asString()).isEqualTo("POSTED")
            assertThat(physical(admin)).isEqualTo(before)
        } else {
            assertThat(submitted.path("state").asString()).isEqualTo("SUBMITTED")
            assertThat(physical(admin)).isEqualTo(before)
            val reviewer = approver(admin, listOf(stock.destination))
            configure(admin, policyBody(listOf(stock.destination), listOf(reviewer.second), "COUNT_VARIANCE", "1"))
            val proposal = request("POST", "/api/v1/warehouse/approvals/request", admin,
                """{"sourceDocumentId":"$id","sourceRevision":4}""")
            assertThat(proposal.status).withFailMessage(proposal.contentAsString).isEqualTo(201)
            val approval = mapper.readTree(proposal.contentAsString).path("requestId").asString()
            val decision = """{"requestId":"$approval","expectedRevision":0,"decision":"APPROVE"}"""
            val accepted = request("POST", "/api/v1/warehouse/approvals/decide", reviewer.first, decision, "edited-count-approval")
            assertThat(accepted.status).withFailMessage(accepted.contentAsString).isEqualTo(200)
            assertThat(request("POST", "/api/v1/warehouse/approvals/decide", reviewer.first, decision, "edited-count-approval").contentAsString)
                .isEqualTo(accepted.contentAsString)
        }
        fixture(admin).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_count_round WHERE document_id='$id'")).isEqualTo("1")
            assertThat(scalar("SELECT round_revision FROM inventory_count_result WHERE id='$id'")).isEqualTo("2")
            assertThat(scalar("SELECT state FROM inventory_document WHERE id='$id'")).isEqualTo("POSTED")
            assertThat(scalar("SELECT quantity_base FROM inventory_balance_projection WHERE id='$second'")).isEqualTo(observed)
            assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE document_id='$id' AND kind='COUNT_VARIANCE'"))
                .isEqualTo(if (observed == "100000") "0" else "1")
        }
    }

    @Test fun `new draft receipt rejects a forged canonical hash while the otherwise identical complete command is valid`() {
        val stock = transferStock()
        val admin = stock.setup.token
        val original = create("counts", admin, body(stock.setup.bin, balance(stock), stock.receiver, "Hash-bound draft")).path("id").asString()
        val id = java.util.UUID.randomUUID()
        val line = java.util.UUID.randomUUID()
        val operation = java.util.UUID.randomUUID()
        val before = physical(admin)
        fun clone(hash: String) = fixture(admin).transaction {
            sql("""INSERT INTO inventory_document SELECT (jsonb_populate_record(NULL::inventory_document,
                to_jsonb(source)||jsonb_build_object('id','$id','code','COUNT-$id'))).* FROM inventory_document source WHERE id='$original'""")
            sql("""INSERT INTO inventory_count_scope SELECT (jsonb_populate_record(NULL::inventory_count_scope,
                to_jsonb(source)||jsonb_build_object('id','$id'))).* FROM inventory_count_scope source WHERE id='$original'""")
            sql("""INSERT INTO inventory_document_line SELECT (jsonb_populate_record(NULL::inventory_document_line,
                to_jsonb(source)||jsonb_build_object('id','$line','document_id','$id'))).* FROM inventory_document_line source WHERE document_id='$original'""")
            sql("""INSERT INTO inventory_count_entry SELECT (jsonb_populate_record(NULL::inventory_count_entry,
                to_jsonb(source)||jsonb_build_object('id','$line','document_id','$id'))).* FROM inventory_count_entry source WHERE document_id='$original'""")
            sql("""INSERT INTO inventory_operation SELECT (jsonb_populate_record(NULL::inventory_operation,
                to_jsonb(source)||jsonb_build_object('id','$operation','document_id','$id','resource_id','$id',
                    'resource_scope','count:$id','operation_key','cloned-$id','payload_hash',$hash,
                    'original_body',(source.original_body::jsonb||jsonb_build_object('id','$id'))::text))).*
                FROM inventory_operation source WHERE document_id='$original'""")
            sql("""INSERT INTO inventory_command_identity SELECT (jsonb_populate_record(NULL::inventory_command_identity,
                to_jsonb(source)||jsonb_build_object('id','$operation'))).* FROM inventory_command_identity source
                JOIN inventory_operation receipt ON receipt.id=source.id AND receipt.tenant_id=source.tenant_id WHERE receipt.document_id='$original'""")
        }
        assertThatThrownBy { clone("repeat('0',64)") }.satisfies(java.util.function.Consumer<Throwable> { failure ->
            assertThat(generateSequence(failure) { it.cause }.filterIsInstance<SQLException>().map { it.sqlState }.toList()).contains("23514")
        })
        clone("source.payload_hash")
        assertThat(request("GET", "$root/$id", admin).status).isEqualTo(200)
        assertThat(physical(admin)).isEqualTo(before)
    }

    @Test fun `draft commands recheck original and current locations before returning immutable receipts`() {
        val stock = transferStock()
        val admin = stock.setup.token
        val maker = approver(admin, listOf(stock.setup.bin, stock.destination), permissions)
        val second = incoming(stock, stock.destination)
        val first = body(stock.setup.bin, balance(stock), maker.second, "First location")
        val created = request("POST", root, maker.first, first, "scoped-create")
        assertThat(created.status).withFailMessage(created.contentAsString).isEqualTo(201)
        val id = mapper.readTree(created.contentAsString).path("id").asString()
        val changed = """{"expectedRevision":0,"draft":${body(stock.destination, second, maker.second, "Second location")}}"""
        val edited = request("PUT", "$root/$id", maker.first, changed, "scoped-edit")
        assertThat(edited.status).withFailMessage(edited.contentAsString).isEqualTo(200)
        val scope = "/api/v1/warehouse/settings/scopes/${maker.second}/${stock.setup.bin}"
        assertThat(request("PUT", scope, admin, """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)
        assertThat(request("POST", root, maker.first, first, "scoped-create").status).isEqualTo(404)
        assertThat(request("PUT", "$root/$id", maker.first, changed, "scoped-edit").contentAsString).isEqualTo(edited.contentAsString)
        assertThat(request("PUT", scope, admin, """{"expectedRevision":2,"active":true}""").status).isEqualTo(200)
        assertThat(request("POST", root, maker.first, first, "scoped-create").contentAsString).isEqualTo(created.contentAsString)
        val back = """{"expectedRevision":1,"draft":$first}"""
        assertThat(request("PUT", "$root/$id", maker.first, back).status).isEqualTo(200)
        val secondScope = "/api/v1/warehouse/settings/scopes/${maker.second}/${stock.destination}"
        assertThat(request("PUT", secondScope, admin, """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)
        assertThat(request("PUT", "$root/$id", maker.first, changed, "scoped-edit").status).isEqualTo(404)
    }

    @Test fun `only original requester edits and revoked counters are invalid in both picker and commands`() {
        val stock = transferStock()
        val admin = stock.setup.token
        val counter = approver(admin, listOf(stock.setup.bin), permissions)
        val original = body(stock.setup.bin, balance(stock), counter.second, "Counter scope")
        val id = create("counts", admin, original).path("id").asString()
        val update = """{"expectedRevision":0,"draft":$original}"""
        val before = physical(admin)
        assertThat(request("PUT", "$root/$id", counter.first, update).status).isEqualTo(403)
        assertThat(request("PUT", "$root/$id", tenant(), update).status).isEqualTo(404)
        assertThat(request("PUT", "$root/$id", null, update).status).isEqualTo(401)
        val scope = "/api/v1/warehouse/settings/scopes/${counter.second}/${stock.setup.bin}"
        assertThat(request("PUT", scope, admin, """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)
        val form = request("GET", "$root/$id/draft", admin)
        assertThat(form.status).isEqualTo(200)
        assertThat(mapper.readTree(form.contentAsString).path("eligibleAssignedCounterIds").size()).isZero()
        assertThat(request("PUT", "$root/$id", admin, update).status).isEqualTo(403)
        assertThat(request("POST", root, admin, original).status).isEqualTo(403)
        val invalid = listOf(update.replace("\"partialLocation\":true", "\"partialLocation\":false"),
            update.replace("\"entries\":[", "\"bookQuantityBase\":\"100000\",\"entries\":["),
            """{"expectedRevision":9223372036854775807,"draft":$original}""")
        for (input in invalid) assertThat(request("PUT", "$root/$id", admin, input).status).isEqualTo(400)
        assertThat(physical(admin)).isEqualTo(before)
        assertThat(mapper.readTree(request("GET", "$root/$id", admin).contentAsString).path("revision").asLong()).isZero()
    }

    @Test fun `raw edits require the complete draft command and counting freezes scope lines assignments and observations`() {
        val stock = transferStock()
        val admin = stock.setup.token
        val id = create("counts", admin, body(stock.setup.bin, balance(stock), stock.receiver, "Guarded count")).path("id").asString()
        val fixture = fixture(admin)
        fun reject(command: String) {
            assertThatThrownBy { fixture.transaction { sql(command) } }.satisfies(java.util.function.Consumer<Throwable> { failure ->
                assertThat(generateSequence(failure) { it.cause }.filterIsInstance<SQLException>().map { it.sqlState }.toList()).contains("23514")
            })
        }
        for (sql in listOf("UPDATE inventory_document SET reason='forged' WHERE id='$id'",
            "UPDATE inventory_document SET reason='forged',revision=revision+1 WHERE id='$id'",
            "UPDATE inventory_document SET actor_id='${java.util.UUID.randomUUID()}',revision=revision+1 WHERE id='$id'",
            "UPDATE inventory_count_scope SET location_id='${stock.destination}' WHERE id='$id'",
            "DELETE FROM inventory_count_entry WHERE document_id='$id'",
            "UPDATE inventory_document_line SET quantity_base=1,revision=revision+1 WHERE document_id='$id'")) reject(sql)
        action(id, admin, "start", """{"expectedRevision":0}""")
        for (sql in listOf("UPDATE inventory_count_scope SET location_id='${stock.destination}' WHERE id='$id'",
            "DELETE FROM inventory_count_entry WHERE document_id='$id'",
            "DELETE FROM inventory_document_line WHERE document_id='$id'",
            "DELETE FROM inventory_count_round WHERE document_id='$id'")) reject(sql)
        action(id, admin, "observe", """{"expectedRevision":1,"balanceId":"${balance(stock)}","quantityBase":"100000",
            "reason":"Physical measurement","documentReference":"IMMUTABLE"}""")
        reject("UPDATE inventory_cycle_count SET observed_quantity_base=1 WHERE document_id='$id'")
    }

    @ParameterizedTest @ValueSource(strings = ["start", "update"])
    fun `concurrent draft edits and starts admit one revision without losing the winning plan`(other: String) {
        val stock = transferStock()
        val admin = stock.setup.token
        val draft = body(stock.setup.bin, balance(stock), stock.receiver, "Original")
        val id = create("counts", admin, draft).path("id").asString()
        val before = physical(admin)
        val workers = Executors.newFixedThreadPool(2)
        val barrier = CyclicBarrier(2)
        try {
            val editing = workers.submit(Callable {
                barrier.await(20, TimeUnit.SECONDS)
                request("PUT", "$root/$id", admin, """{"expectedRevision":0,"draft":${draft.replace("Original", "Edited")}}""", "racing-edit")
            })
            val competing = workers.submit(Callable {
                barrier.await(20, TimeUnit.SECONDS)
                if (other == "start") request("POST", "$root/$id/start", admin, """{"expectedRevision":0}""", "racing-start")
                else request("PUT", "$root/$id", admin, """{"expectedRevision":0,"draft":${draft.replace("Original", "Competing edit")}}""", "competing-edit")
            })
            val results = listOf(editing.get(60, TimeUnit.SECONDS), competing.get(60, TimeUnit.SECONDS))
            assertThat(results.map { it.status }.sorted()).containsExactly(200, 409)
            val winner = mapper.readTree(results.single { it.status == 200 }.contentAsString)
            assertThat(mapper.readTree(request("GET", "$root/$id", admin).contentAsString)).isEqualTo(winner)
            assertThat(winner.path("revision").asLong()).isEqualTo(1)
            assertThat(physical(admin)).isEqualTo(before)
        } finally { workers.shutdownNow() }
    }

    private fun body(location: String, balance: String, counter: String, reason: String) =
        """{"locationId":"$location","partialLocation":true,"reason":"$reason","entries":[{"balanceId":"$balance","counterId":"$counter"}]}"""
    private fun balance(stock: TransferStock) = fixture(stock.setup.token).transaction {
        scalar("SELECT id FROM inventory_balance_projection WHERE stock_identity_id='${stock.identity}' AND location_id='${stock.setup.bin}'")
    }
    private fun incoming(stock: TransferStock, location: String, bulk: Boolean = false): String {
        val sku = if (bulk) create("skus", stock.setup.token,
            """{"code":"PARTS","name":"Counted parts","tracking":"BULK","baseUnit":"EA","inspectionRequired":false}""").path("id").asString()
            else stock.setup.cable
        val quantity = if (bulk) "100" else "100000"
        val unit = if (bulk) "EA" else "MM"
        val receipt = draft(stock.setup, """{"skuId":"$sku","quantityBase":"$quantity","lotCode":"SECOND","cost":{"totalMinor":"500000","currency":"IDR"}}""")
        val id = receipt.path("id").asString()
        transition(stock.setup, id, "receive", """{"expectedRevision":0}""")
        val line = mapper.readTree(request("GET", "/api/v1/warehouse/receipts/$id", stock.setup.token).contentAsString).path("lines").single()
        val identity = line.path("pieces").single().path("stockIdentityId").asString()
        transition(stock.setup, id, "putaway", """{"expectedRevision":1,"destinationLocationId":"${stock.setup.bin}","lines":[{
            "lineId":"${line.path("id").asString()}","stockIdentityId":"$identity","quantityBase":"$quantity","baseUnit":"$unit"}]}""")
        val moving = stock.copy(identity = identity, destination = location)
        val transfer = create("transfers", stock.setup.token, transferBody(moving)
            .replace("\"quantityBase\":\"100000\"", "\"quantityBase\":\"$quantity\"").replace("\"baseUnit\":\"MM\"", "\"baseUnit\":\"$unit\""))
        val transferId = transfer.path("id").asString()
        transferAction(moving, transferId, "dispatch", """{"expectedRevision":0}""")
        transferAction(moving, transferId, "receive", receiveBody(transfer.path("lines").single().path("id").asString(), 1, quantity)
            .replace("\"baseUnit\":\"MM\"", "\"baseUnit\":\"$unit\""))
        return fixture(stock.setup.token).transaction { scalar("SELECT id FROM inventory_balance_projection WHERE stock_identity_id='$identity' AND location_id='$location'") }
    }
    private fun action(id: String, token: String, action: String, body: String): tools.jackson.databind.JsonNode {
        val result = request("POST", "$root/$id/$action", token, body)
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
        return mapper.readTree(result.contentAsString)
    }
    private fun physical(token: String) = fixture(token).transaction {
        scalar("SELECT json_build_array((SELECT count(*) FROM inventory_movement),(SELECT count(*) FROM inventory_movement_leg),(SELECT sum(quantity_base) FROM inventory_balance_projection),(SELECT count(*) FROM inventory_reservation))::text")
    }
}
