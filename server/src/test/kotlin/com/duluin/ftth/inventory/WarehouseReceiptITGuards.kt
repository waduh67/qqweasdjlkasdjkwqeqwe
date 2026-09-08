package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WarehouseReceiptITGuards : WarehouseReceiptHttpFixture() {
    @Test fun `strict intake decoder rejects scalar coercion authority fields malformed quantity and package rounding`() {
        val setup = setupReceipt()
        val valid = draftBody(setup, """{"skuId":"${setup.cable}","quantityBase":"1000","lotCode":"R1"}""")
        val invalid = listOf("{", valid + "{}", valid.replace("\"1000\"", "1000"), valid.replace("\"1000\"", "true"),
            valid.replace("\"1000\"", "\"1.5\""), valid.replace("\"1000\"", "\"-1\""), valid.replace("\"1000\"", "\"9223372036854775808\""),
            valid.replace("\"1000\"", "\"1e3\""), valid.replace("\"1000\"", "\"0\""), valid.replace("\"DELIVERY-1\"", "123"),
            valid.replace("\"quantityBase\":\"1000\"", "\"quantityBase\":\"1000\",\"quantityBase\":\"1000\"")) +
            listOf("tenantId", "actorId", "state", "authorityEpoch", "canonicalSerial", "payloadHash", "acceptedBase", "totalBase").map { valid.dropLast(1) + ",\"$it\":\"bad\"}" }
        for (body in invalid) assertThat(request("POST", "/api/v1/warehouse/receipts", setup.token, body).status).describedAs(body).isEqualTo(400)
        val ratio = draftBody(setup, """{"skuId":"${setup.cable}","quantityBase":"1000","lotCode":"R1","conversion":{"numerator":"1000","denominator":"3","packageQuantity":"1"}}""")
        assertThat(request("POST", "/api/v1/warehouse/receipts", setup.token, ratio).status).isEqualTo(400)
        fixture(setup.token).transaction { assertThat(scalar("SELECT count(*) FROM inventory_document")).isEqualTo("0") }
    }

    @Test fun `draft update preserves source cost ratio and stable scoped pages without stock`() {
        val setup = setupReceipt()
        val body = draftBody(setup, """{"skuId":"${setup.cable}","quantityBase":"1000","lotCode":"R1","conversion":{"numerator":"2000","denominator":"2","packageQuantity":"1"},"cost":{"totalMinor":"19","currency":"IDR"}}""")
        val first = request("POST", "/api/v1/warehouse/receipts", setup.token, body)
        val id = mapper.readTree(first.contentAsString).path("id").asString()
        val update = body.replace("DELIVERY-1", "DELIVERY-2").dropLast(1) + ",\"expectedRevision\":0}"
        assertThat(request("PUT", "/api/v1/warehouse/receipts/$id", setup.token, update).status).isEqualTo(200)
        assertThat(request("PUT", "/api/v1/warehouse/receipts/$id", setup.token, update).status).isEqualTo(409)
        val detail = mapper.readTree(request("GET", "/api/v1/warehouse/receipts/$id", setup.token).contentAsString)
        assertThat(detail.path("revision").asLong()).isEqualTo(1)
        assertThat(detail.path("lines")[0].path("conversion").path("numerator").asString()).isEqualTo("2000")
        assertThat(detail.path("lines")[0].path("cost").path("costBasisQuantityBase").asString()).isEqualTo("1000")
        assertThat(mapper.readTree(request("GET", "/api/v1/warehouse/receipts?size=1&status=DRAFT&skuId=${setup.cable}", setup.token).contentAsString).path("totalElements").asLong()).isEqualTo(1)
        for (query in listOf("page=-1", "size=0", "size=101", "sort=tenant_id", "direction=invalid", "status=draft"))
            assertThat(request("GET", "/api/v1/warehouse/receipts?$query", setup.token).status).isEqualTo(400)
        assertThat(request("GET", "/api/v1/warehouse/receipts/$id", tenant()).status).isEqualTo(404)
    }

    @Test fun `normalized serial or MAC conflicts never add a second stock identity`() {
        val setup = setupReceipt()
        val original = draft(setup, """{"skuId":"${setup.onu}","quantityBase":"1","serials":[{"serial":" ab-1 ","mac":"aa-bb-cc-dd-ee-ff"}]}""")
        transition(setup, original.path("id").asString(), "receive", """{"expectedRevision":0}""")
        for (serial in listOf("""{"serial":"AB-1"}""", """{"serial":"OTHER","mac":"AABB.CCDD.EEFF"}""")) {
            val duplicate = draft(setup, """{"skuId":"${setup.onu}","quantityBase":"1","serials":[$serial]}""")
            assertThat(request("POST", "/api/v1/warehouse/receipts/${duplicate.path("id").asString()}/receive", setup.token, """{"expectedRevision":0}""").status).isEqualTo(409)
        }
        for (line in listOf("""{"skuId":"${setup.onu}","quantityBase":"2","serials":[{"serial":"same"},{"serial":" SAME "}]}""",
            """{"skuId":"${setup.onu}","quantityBase":"2","serials":[{"serial":"ONE"}]}"""))
            assertThat(request("POST", "/api/v1/warehouse/receipts", setup.token, draftBody(setup, line)).status).isIn(400, 409)
        fixture(setup.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_segment")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_movement")).isEqualTo("1")
        }
    }

    @Test fun `concurrent canonical serial receipts have one winner and duplicate key has one original response`() {
        val setup = setupReceipt()
        val ids = listOf(" race-serial ", "RACE-SERIAL").map { serial -> draft(setup,
            """{"skuId":"${setup.onu}","quantityBase":"1","serials":[{"serial":"$serial"}]}""").path("id").asString() }
        val barrier = CyclicBarrier(2)
        Executors.newFixedThreadPool(2).use { pool ->
            val responses = ids.map { id -> pool.submit<Int> {
                barrier.await(10, TimeUnit.SECONDS)
                request("POST", "/api/v1/warehouse/receipts/$id/receive", setup.token, """{"expectedRevision":0}""").status
            } }.map { it.get(30, TimeUnit.SECONDS) }
            assertThat(responses.sorted()).containsExactly(200, 409)
        }
        fixture(setup.token).transaction { assertThat(scalar("SELECT count(*) FROM inventory_movement")).isEqualTo("1") }
        val duplicate = draft(setup, """{"skuId":"${setup.onu}","quantityBase":"1","serials":[{"serial":"SAME-KEY"}]}""").path("id").asString()
        Executors.newFixedThreadPool(2).use { pool ->
            val responses = (1..2).map { pool.submit<org.springframework.mock.web.MockHttpServletResponse> {
                barrier.await(10, TimeUnit.SECONDS)
                request("POST", "/api/v1/warehouse/receipts/$duplicate/receive", setup.token, """{"expectedRevision":0}""", "same-receive-key")
            } }.map { it.get(30, TimeUnit.SECONDS) }
            assertThat(responses.map { it.status }).containsExactly(200, 200)
            assertThat(responses[0].contentAsString).isEqualTo(responses[1].contentAsString)
        }
        fixture(setup.token).transaction { assertThat(scalar("SELECT count(*) FROM inventory_movement")).isEqualTo("2") }
    }

    @Test fun `current permissions scopes and revisions precede replay and all stock effects`() {
        val setup = setupReceipt()
        val draft = draft(setup, """{"skuId":"${setup.cable}","quantityBase":"1000","lotCode":"R1"}""")
        val id = draft.path("id").asString()
        val path = "/api/v1/warehouse/receipts/$id/receive"
        for (body in listOf("{}", """{"expectedRevision":"0"}""", """{"expectedRevision":0.0}""", """{"expectedRevision":0e0}""", """{"expectedRevision":null}"""))
            assertThat(request("POST", path, setup.token, body).status).describedAs(body).isEqualTo(400)
        assertThat(mvc.perform(post(path).header("Authorization", "Bearer ${setup.token}").contentType("application/json").content("""{"expectedRevision":0}""")).andReturn().response.status).isEqualTo(400)
        assertThat(request("POST", path, null, """{"expectedRevision":0}""").status).isEqualTo(401)
        val (viewer, _) = user(setup.token, setOf("inventory.receipt.view"))
        assertThat(request("POST", path, viewer, """{"expectedRevision":0}""").status).isEqualTo(403)
        assertThat(request("GET", "/api/v1/warehouse/receipts/$id", viewer).status).isEqualTo(404)
        transition(setup, id, "receive", """{"expectedRevision":0}""", "original")
        assertThat(request("POST", path, setup.token, """{"expectedRevision":1}""", "original").status).isEqualTo(409)
        val (disabler, _) = user(setup.token, setOf("iam.user.update"))
        val actor = mapper.readTree(request("GET", "/api/me", setup.token).contentAsString).path("id").asString()
        assertThat(request("POST", "/api/users/$actor/disable", disabler).status).isEqualTo(200)
        val replay = request("POST", path, setup.token, """{"expectedRevision":0}""", "original")
        assertThat(replay.status).isEqualTo(403)
        assertThat(replay.contentAsString).doesNotContain("operationId")
    }

    @Test fun `missing source foreign supplier and referenced master mutation are rejected`() {
        val setup = setupReceipt()
        val valid = draftBody(setup, """{"skuId":"${setup.cable}","quantityBase":"1000","lotCode":"R1"}""")
        assertThat(request("POST", "/api/v1/warehouse/receipts", setup.token, valid.replace("DELIVERY-1", "")).status).isEqualTo(400)
        assertThat(request("POST", "/api/v1/warehouse/receipts", setup.token, valid.replace(setup.supplier, UUID.randomUUID().toString())).status).isEqualTo(404)
        assertThat(request("POST", "/api/v1/warehouse/receipts", setup.token, valid.replace(setup.inspection, setup.bin)).status).isEqualTo(400)
        val created = request("POST", "/api/v1/warehouse/receipts", setup.token, valid)
        assertThat(created.status).isEqualTo(201)
        for ((kind, id) in listOf("suppliers" to setup.supplier, "locations" to setup.inspection, "skus" to setup.cable))
            assertThat(request("POST", "/api/v1/warehouse/$kind/$id/archive", setup.token, """{"expectedRevision":0}""").status).isEqualTo(409)
    }
}
