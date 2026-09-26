package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.awaitility.Awaitility.await
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WarehouseTransferDraftIT : WarehouseTransferFixture() {
    @Test fun `saved transfer draft replaces its plan without moving stock and preserves every original response`() {
        val stock = transferStock()
        val admin = stock.setup.token
        val originalInput = transferBody(stock)
        val original = request("POST", "/api/v1/warehouse/transfers", admin, originalInput, "original-draft")
        assertThat(original.status).withFailMessage(original.contentAsString).isEqualTo(201)
        val id = mapper.readTree(original.contentAsString).path("id").asString()
        val newDestination = create("locations", admin,
            """{"code":"UPDATED_DEST","name":"Updated destination","kind":"WAREHOUSE","issueEligible":true}""")
            .path("id").asString()
        val changed = originalInput.replace(stock.destination, newDestination).replace("100000", "60000")
            .replace("Warehouse replenishment", "Corrected receiving plan")
        val input = """{"expectedRevision":0,"draft":$changed}"""
        val before = physical(stock)
        val updated = request("PUT", "/api/v1/warehouse/transfers/$id", admin, input, "update-draft")
        assertThat(updated.status).withFailMessage(updated.contentAsString).isEqualTo(200)
        val view = mapper.readTree(updated.contentAsString)
        assertThat(view.path("revision").asLong()).isEqualTo(1)
        assertThat(view.path("state").asString()).isEqualTo("DRAFT")
        assertThat(view.path("destinationLocationId").asString()).isEqualTo(newDestination)
        assertThat(view.path("reason").asString()).isEqualTo("Corrected receiving plan")
        assertThat(view.path("lines").single().path("quantityBase").asString()).isEqualTo("60000")
        assertThat(view.path("lines").single().path("inTransitBase").asString()).isEqualTo("0")
        assertThat(physical(stock)).isEqualTo(before)
        assertThat(request("PUT", "/api/v1/warehouse/transfers/$id", admin, input, "update-draft").contentAsString).isEqualTo(updated.contentAsString)
        assertThat(request("POST", "/api/v1/warehouse/transfers", admin, originalInput, "original-draft").contentAsString).isEqualTo(original.contentAsString)
        assertThat(request("PUT", "/api/v1/warehouse/transfers/$id", admin, input).status).isEqualTo(409)
        assertThat(request("PUT", "/api/v1/warehouse/transfers/$id", admin, input.replace("60000", "50000"), "update-draft").status).isEqualTo(409)
        val history = mapper.readTree(request("GET", "/api/v1/warehouse/transfers/$id/history", admin).contentAsString)
        assertThat(history.asSequence().toList()).containsExactly(mapper.readTree(original.contentAsString), view)
        val details = request("GET", "/api/v1/warehouse/transfers/$id/details", admin)
        assertThat(details.status).withFailMessage(details.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(details.contentAsString).path("references").path("lines").single().path("lineId"))
            .isEqualTo(view.path("lines").single().path("id"))

        // Current document visibility must not reveal a former destination through history.
        val viewer = user(admin, setOf("inventory.transfer.view"))
        grantAreas(admin, viewer.second)
        for (location in listOf(stock.setup.bin, stock.transit, newDestination)) grantScope(admin, viewer.second, location)
        for (suffix in listOf("history", "history/page")) {
            val response = request("GET", "/api/v1/warehouse/transfers/$id/$suffix", viewer.first)
            assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
            assertThat(response.contentAsString).doesNotContain(stock.destination)
            val rows = mapper.readTree(response.contentAsString).let { if (it.isArray) it else it.path("items") }
            assertThat(rows.asSequence().toList()).containsExactly(view)
        }

        transferAction(stock, id, "dispatch", """{"expectedRevision":1}""")
        transferAction(stock, id, "receive", receiveBody(view.path("lines").single().path("id").asString(), 2, "60000"))
        fixture(admin).transaction {
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE location_id='${stock.setup.bin}'")).isEqualTo("40000")
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE location_id='$newDestination'")).isEqualTo("60000")
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection")).isEqualTo("100000")
        }
        val after = physical(stock)
        assertThat(request("PUT", "/api/v1/warehouse/transfers/$id", admin, input, "update-draft").contentAsString).isEqualTo(updated.contentAsString)
        assertThat(request("PUT", "/api/v1/warehouse/transfers/$id", admin, input.replace("expectedRevision\":0", "expectedRevision\":3")).status).isEqualTo(409)
        assertThat(physical(stock)).isEqualTo(after)
    }

    @Test fun `draft update requires sender current scopes and exact command binding while posted facts stay immutable`() {
        val stock = transferStock()
        val admin = stock.setup.token
        val id = transfer(stock).path("id").asString()
        val input = """{"expectedRevision":0,"draft":${transferBody(stock)}}"""
        val other = user(admin, setOf("inventory.transfer.view", "inventory.transfer.manage"))
        grantAreas(admin, other.second)
        for (location in listOf(stock.setup.bin, stock.transit, stock.destination)) grantScope(admin, other.second, location)
        val before = physical(stock)
        assertThat(request("PUT", "/api/v1/warehouse/transfers/$id", other.first, input).status).isEqualTo(409)
        assertThat(request("PUT", "/api/v1/warehouse/transfers/$id", tenant(), input).status).isEqualTo(404)
        assertThat(request("PUT", "/api/v1/warehouse/transfers/$id", null, input).status).isEqualTo(401)
        val hidden = create("locations", admin, """{"code":"HIDDEN","name":"Hidden","kind":"WAREHOUSE"}""").path("id").asString()
        assertThat(request("PUT", "/api/v1/warehouse/transfers/$id", other.first, input.replace(stock.destination, hidden)).status).isEqualTo(404)
        val fixture = fixture(admin)
        assertThatThrownBy { fixture.transaction { sql("UPDATE inventory_document SET reason='unrecorded edit' WHERE id='$id'") } }
            .satisfies(java.util.function.Consumer<Throwable> { failure ->
                assertThat(generateSequence(failure) { it.cause }.filterIsInstance<java.sql.SQLException>().map { it.sqlState }.toList())
                    .contains("40001")
            })
        assertThatThrownBy { fixture.transaction { sql("""UPDATE inventory_document SET transfer_source_location_id=NULL,
            transfer_destination_location_id=NULL,transfer_transit_location_id=NULL,transfer_receiver_id=NULL,
            revision=revision+1 WHERE id='$id'""") } }
            .satisfies(java.util.function.Consumer<Throwable> { failure ->
                assertThat(generateSequence(failure) { it.cause }.filterIsInstance<java.sql.SQLException>().map { it.sqlState }.toList())
                    .contains("23514")
            })
        assertThatThrownBy { fixture.transaction { sql("UPDATE inventory_document SET reason='unrecorded edit',revision=revision+1 WHERE id='$id'") } }
            .satisfies(java.util.function.Consumer<Throwable> { failure ->
                assertThat(generateSequence(failure) { it.cause }.filterIsInstance<java.sql.SQLException>().map { it.sqlState }.toList())
                    .contains("23514")
            })
        assertThatThrownBy { fixture.transaction { sql("UPDATE inventory_operation SET original_body='{}' WHERE document_id='$id'") } }
            .satisfies(java.util.function.Consumer<Throwable> { failure ->
                assertThat(generateSequence(failure) { it.cause }.filterIsInstance<java.sql.SQLException>().map { it.sqlState }.toList())
                    .contains("23514")
            })
        assertThat(physical(stock)).isEqualTo(before)
        transferAction(stock, id, "dispatch", """{"expectedRevision":0}""")
        assertThatThrownBy { fixture.transaction { sql("UPDATE inventory_document SET reason='posted edit',revision=revision+1 WHERE id='$id'") } }
            .satisfies(java.util.function.Consumer<Throwable> { failure ->
                assertThat(generateSequence(failure) { it.cause }.filterIsInstance<java.sql.SQLException>().map { it.sqlState }.toList())
                    .contains("23514")
            })
        balances(stock, "0", "100000", "0")
    }

    @Test fun `concurrent draft edit and dispatch admit one revision and never ship a stale plan`() {
        val stock = transferStock()
        val id = transfer(stock).path("id").asString()
        val barrier = java.util.concurrent.CyclicBarrier(2)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val edit = pool.submit(java.util.concurrent.Callable {
                barrier.await(20, java.util.concurrent.TimeUnit.SECONDS)
                request("PUT", "/api/v1/warehouse/transfers/$id", stock.setup.token,
                    """{"expectedRevision":0,"draft":${transferBody(stock).replace("100000", "60000")}}""", "concurrent-edit").status
            })
            val dispatch = pool.submit(java.util.concurrent.Callable {
                barrier.await(20, java.util.concurrent.TimeUnit.SECONDS)
                request("POST", "/api/v1/warehouse/transfers/$id/dispatch", stock.setup.token,
                    """{"expectedRevision":0}""", "concurrent-dispatch").status
            })
            val editStatus = edit.get(60, java.util.concurrent.TimeUnit.SECONDS)
            val dispatchStatus = dispatch.get(60, java.util.concurrent.TimeUnit.SECONDS)
            assertThat(listOf(editStatus, dispatchStatus).sorted()).containsExactly(200, 409)
            val current = mapper.readTree(request("GET", "/api/v1/warehouse/transfers/$id", stock.setup.token).contentAsString)
            assertThat(current.path("revision").asLong()).isEqualTo(1)
            if (editStatus == 200) {
                assertThat(current.path("state").asString()).isEqualTo("DRAFT")
                assertThat(current.path("lines").single().path("quantityBase").asString()).isEqualTo("60000")
                balances(stock, "100000", "0", "0")
            } else {
                assertThat(current.path("state").asString()).isEqualTo("DISPATCHED")
                assertThat(current.path("lines").single().path("quantityBase").asString()).isEqualTo("100000")
                balances(stock, "0", "100000", "0")
            }
        } finally { pool.shutdownNow() }
    }

    @Test fun `disabled old receiver remains discoverable and can be replaced before dispatch`() {
        val stock = transferStock()
        val admin = stock.setup.token
        val inactive = user(admin, setOf("inventory.transfer.view"))
        val oldDraft = transferBody(stock.copy(receiver = inactive.second))
        val original = request("POST", "/api/v1/warehouse/transfers", admin, oldDraft, "before-disable-create")
        assertThat(original.status).withFailMessage(original.contentAsString).isEqualTo(201)
        val id = mapper.readTree(original.contentAsString).path("id").asString()
        val oldEditInput = """{"expectedRevision":0,"draft":${oldDraft.replace("Warehouse replenishment", "Old plan revised")}}"""
        val oldEdit = request("PUT", "/api/v1/warehouse/transfers/$id", admin, oldEditInput, "before-disable-edit")
        assertThat(oldEdit.status).withFailMessage(oldEdit.contentAsString).isEqualTo(200)
        val before = physical(stock)
        assertThat(request("POST", "/api/users/${inactive.second}/disable", admin).status).isEqualTo(200)
        val details = request("GET", "/api/v1/warehouse/transfers/$id/details", admin)
        assertThat(details.status).withFailMessage(details.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(details.contentAsString).path("references").path("people")
            .single { it.path("id").asString() == inactive.second }.path("active").asBoolean()).isFalse()
        assertThat(mapper.readTree(request("GET", "/api/v1/warehouse/transfers", admin).contentAsString)
            .path("items").asSequence().map { it.path("transfer").path("id").asString() }.toList()).contains(id)
        assertThat(request("POST", "/api/v1/warehouse/transfers/$id/dispatch", admin, """{"expectedRevision":1}""").status).isEqualTo(409)
        assertThat(request("PUT", "/api/v1/warehouse/transfers/$id", admin,
            """{"expectedRevision":1,"draft":$oldDraft}""").status).isEqualTo(409)
        val edited = request("PUT", "/api/v1/warehouse/transfers/$id", admin,
            """{"expectedRevision":1,"draft":${transferBody(stock)}}""")
        assertThat(edited.status).withFailMessage(edited.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(edited.contentAsString).path("receiverId").asString()).isEqualTo(stock.receiver)
        assertThat(physical(stock)).isEqualTo(before)
        fun originalReplays() {
            val createReplay = request("POST", "/api/v1/warehouse/transfers", admin, oldDraft, "before-disable-create")
            val editReplay = request("PUT", "/api/v1/warehouse/transfers/$id", admin, oldEditInput, "before-disable-edit")
            assertThat(createReplay.status).isEqualTo(201)
            assertThat(createReplay.contentAsString).isEqualTo(original.contentAsString)
            assertThat(editReplay.status).isEqualTo(200)
            assertThat(editReplay.contentAsString).isEqualTo(oldEdit.contentAsString)
        }
        originalReplays()
        transferAction(stock, id, "dispatch", """{"expectedRevision":2}""")
        originalReplays()
        balances(stock, "0", "100000", "0")
    }

    @Test fun `two concurrent draft replacements retain exactly one revision and its original response`() {
        val stock = transferStock()
        val id = transfer(stock).path("id").asString()
        val before = physical(stock)
        val barrier = java.util.concurrent.CyclicBarrier(2)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val results = listOf("60000", "40000").map { amount -> workers.submit(Callable {
                barrier.await(20, TimeUnit.SECONDS)
                val body = """{"expectedRevision":0,"draft":${transferBody(stock).replace("100000", amount)}}"""
                Triple(amount, body, request("PUT", "/api/v1/warehouse/transfers/$id", stock.setup.token, body, "edit-$amount"))
            }) }.map { it.get(60, TimeUnit.SECONDS) }
            assertThat(results.map { it.third.status }.sorted()).containsExactly(200, 409)
            val winner = results.single { it.third.status == 200 }
            val current = request("GET", "/api/v1/warehouse/transfers/$id", stock.setup.token)
            assertThat(mapper.readTree(current.contentAsString)).isEqualTo(mapper.readTree(winner.third.contentAsString))
            assertThat(request("PUT", "/api/v1/warehouse/transfers/$id", stock.setup.token, winner.second,
                "edit-${winner.first}").contentAsString).isEqualTo(winner.third.contentAsString)
            assertThat(physical(stock)).isEqualTo(before)
            fixture(stock.setup.token).transaction {
                assertThat(scalar("SELECT count(*) FROM inventory_operation WHERE document_id='$id'")).isEqualTo("2")
            }
        } finally { workers.shutdownNow() }
    }

    @Test fun `details waiting behind draft replacement reauthorizes the new source before reading references`() {
        val stock = transferStock()
        val admin = stock.setup.token
        val id = transfer(stock).path("id").asString()
        val viewer = user(admin, setOf("inventory.transfer.view"))
        grantAreas(admin, viewer.second)
        for (location in listOf(stock.setup.bin, stock.destination, stock.transit)) grantScope(admin, viewer.second, location)
        val privateWarehouse = create("locations", admin,
            """{"code":"PRIVATE_WAREHOUSE","name":"Private warehouse","kind":"WAREHOUSE"}""").path("id").asString()
        val hidden = create("locations", admin,
            """{"code":"PRIVATE_SOURCE","name":"Private source","kind":"BIN","parentLocationId":"$privateWarehouse","issueEligible":true}""").path("id").asString()
        val incoming = draft(stock.setup, """{"skuId":"${stock.setup.cable}","quantityBase":"100000","lotCode":"PRIVATE_REEL"}""")
        val incomingId = incoming.path("id").asString()
        transition(stock.setup, incomingId, "receive", """{"expectedRevision":0}""")
        val line = mapper.readTree(request("GET", "/api/v1/warehouse/receipts/$incomingId", admin).contentAsString).path("lines").single()
        val identity = line.path("pieces").single().path("stockIdentityId").asString()
        transition(stock.setup, incomingId, "putaway", """{"expectedRevision":1,"destinationLocationId":"$hidden","lines":[{
            "lineId":"${line.path("id").asString()}","stockIdentityId":"$identity","baseUnit":"MM","quantityBase":"100000"}]}""")
        val input = transferBody(stock).replace(stock.setup.bin, hidden).replace(stock.identity, identity)
        val fixture = fixture(admin)
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocker = AtomicInteger()
        val workers = Executors.newFixedThreadPool(3)
        try {
            val holding = workers.submit {
                fixture.transaction {
                    blocker.set(scalar("SELECT pg_backend_pid()").toInt())
                    sql("SELECT id FROM inventory_document WHERE id='$id' FOR UPDATE")
                    held.countDown()
                    check(release.await(20, TimeUnit.SECONDS))
                }
            }
            check(held.await(20, TimeUnit.SECONDS))
            val writing = workers.submit(Callable {
                request("PUT", "/api/v1/warehouse/transfers/$id", admin, """{"expectedRevision":0,"draft":$input}""")
            })
            await().atMost(Duration.ofSeconds(10)).until {
                fixture.transaction { scalar("SELECT count(*) FROM pg_stat_activity WHERE ${blocker.get()}=ANY(pg_blocking_pids(pid))").toLong() > 0 }
            }
            val writer = fixture.transaction {
                scalar("SELECT pid FROM pg_stat_activity WHERE ${blocker.get()}=ANY(pg_blocking_pids(pid)) LIMIT 1").toInt()
            }
            val reading = workers.submit(Callable { request("GET", "/api/v1/warehouse/transfers/$id/details", viewer.first) })
            await().atMost(Duration.ofSeconds(10)).until {
                fixture.transaction { scalar("SELECT count(*) FROM pg_stat_activity WHERE $writer=ANY(pg_blocking_pids(pid))").toLong() > 0 }
            }
            assertThat(reading.isDone).isFalse()
            release.countDown()
            holding.get(10, TimeUnit.SECONDS)
            val written = writing.get(10, TimeUnit.SECONDS)
            assertThat(written.status).withFailMessage(written.contentAsString).isEqualTo(200)
            val read = reading.get(10, TimeUnit.SECONDS)
            assertThat(read.status).withFailMessage(read.contentAsString).isEqualTo(404)
            assertThat(read.contentAsString).doesNotContain(hidden, identity, "PRIVATE_REEL")
            assertThat(request("GET", "/api/v1/warehouse/transfers/$id/details", admin).status).isEqualTo(200)
        } finally { release.countDown(); workers.shutdownNow() }
    }

    private fun grantAreas(admin: String, user: String) {
        val current = mapper.readTree(request("GET", "/api/users/$user", admin).contentAsString)
        assertThat(request("PUT", "/api/users/$user/access", admin,
            mapper.writeValueAsString(mapOf("roleIds" to current.path("roleIds").asSequence().map { it.asString() }.toList(), "areaIds" to listOf(area(admin))))).status).isEqualTo(200)
    }
    private fun grantScope(admin: String, user: String, location: String) {
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/$user/$location", admin,
            """{"expectedRevision":0,"active":true}""").status).isEqualTo(200)
    }
    private fun physical(stock: TransferStock) = fixture(stock.setup.token).transaction {
        scalar("SELECT json_build_array((SELECT count(*) FROM inventory_movement),(SELECT count(*) FROM inventory_movement_leg),(SELECT sum(quantity_base) FROM inventory_balance_projection),(SELECT count(*) FROM inventory_reservation))::text")
    }
}
