package com.duluin.ftth.inventory

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.inventory.application.service.WarehouseDraftExpiryService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class WarehouseDraftExpiryDocumentIT : WarehouseTransferFixture() {
    @ParameterizedTest @ValueSource(strings = ["transfers", "counts"])
    fun `accepted saves renew once but due sources reject dispatch or start and retain replay`(family: String) {
        val stock = transferStock()
        val token = stock.setup.token
        val database = fixture(token)
        val body = input(family, stock)
        val clock = WarehouseDraftClockFixture(database)
        clock.policy(3)
        val created = request("POST", "/api/v1/warehouse/$family", token, body, "created")
        assertThat(created.status).withFailMessage(created.contentAsString).isEqualTo(201)
        val id = mapper.readTree(created.contentAsString).path("id").asString()
        val old = clock.activity(id)
        val update = """{"expectedRevision":0,"draft":$body}"""
        val saved = request("PUT", "/api/v1/warehouse/$family/$id", token, update, "saved")
        assertThat(saved.status).withFailMessage(saved.contentAsString).isEqualTo(200)
        val current = clock.activity(id)
        assertThat(current).isNotEqualTo(old)
        clock.awaitDocument(id)
        val detail = request("GET", "/api/v1/warehouse/$family/$id", token)
        assertThat(detail.status).withFailMessage(detail.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(detail.contentAsString).path("state").asString()).isEqualTo("EXPIRED")
        val action = if (family == "transfers") "dispatch" else "start"
        val rejected = request("POST", "/api/v1/warehouse/$family/$id/$action", token, """{"expectedRevision":1}""")
        assertThat(rejected.status).withFailMessage(rejected.contentAsString).isEqualTo(409)
        assertThat(mapper.readTree(rejected.contentAsString).path("code").asString()).isEqualTo("DRAFT_EXPIRED")
        assertThat(request("PUT", "/api/v1/warehouse/$family/$id", token, update, "saved").contentAsString).isEqualTo(saved.contentAsString)
        assertThat(request("POST", "/api/v1/warehouse/$family", token, body, "created").contentAsString).isEqualTo(created.contentAsString)
        assertThat(clock.activity(id)).isEqualTo(current)
        TenantContext.runAs(database.tenant) { assertThat(context.getBean(WarehouseDraftExpiryService::class.java).expireOne()).isTrue() }
        database.transaction {
            assertThat(scalar("SELECT state||':'||revision FROM inventory_document WHERE id='$id'")).isEqualTo("DRAFT:1")
            assertThat(scalar("SELECT count(*) FROM inventory_document_draft_expiry WHERE document_id='$id'")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE document_id='$id'")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_count_round WHERE document_id='$id'")).isEqualTo("0")
        }
        balances(stock, "100000", "0", "0")
    }

    @ParameterizedTest @ValueSource(strings = ["transfers", "counts"])
    fun `started physical workflows are not expired when their former draft deadline passes`(family: String) {
        val stock = transferStock()
        val token = stock.setup.token
        val database = fixture(token)
        val body = input(family, stock)
        val clock = WarehouseDraftClockFixture(database)
        clock.policy(3)
        val created = request("POST", "/api/v1/warehouse/$family", token, body)
        assertThat(created.status).withFailMessage(created.contentAsString).isEqualTo(201)
        val id = mapper.readTree(created.contentAsString).path("id").asString()
        val action = if (family == "transfers") "dispatch" else "start"
        val started = request("POST", "/api/v1/warehouse/$family/$id/$action", token, """{"expectedRevision":0}""")
        assertThat(started.status).withFailMessage(started.contentAsString).isEqualTo(200)
        clock.awaitDocument(id)
        val current = request("GET", "/api/v1/warehouse/$family/$id", token)
        assertThat(current.status).withFailMessage(current.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(current.contentAsString).path("state").asString()).isEqualTo(if (family == "transfers") "DISPATCHED" else "COUNTING")
        assertThat(mapper.readTree(current.contentAsString).has("draftExpiry")).isFalse()
        TenantContext.runAs(database.tenant) { assertThat(context.getBean(WarehouseDraftExpiryService::class.java).expireOne()).isFalse() }
    }

    private fun input(family: String, stock: TransferStock): String {
        if (family == "transfers") return transferBody(stock)
        val counter = approver(stock.setup.token, listOf(stock.setup.bin), setOf("inventory.count.view", "inventory.count.manage"))
        val balance = fixture(stock.setup.token).transaction { scalar("SELECT id FROM inventory_balance_projection WHERE stock_identity_id='${stock.identity}' AND quantity_base>0") }
        return """{"locationId":"${stock.setup.bin}","partialLocation":true,"reason":"Blind count plan",
            "entries":[{"balanceId":"$balance","counterId":"${counter.second}"}]}"""
    }
}
