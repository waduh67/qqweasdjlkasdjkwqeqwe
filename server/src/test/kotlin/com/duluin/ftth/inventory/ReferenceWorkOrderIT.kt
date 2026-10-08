package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.fulfillment.FulfillmentCoordinator
import com.duluin.ftth.fulfillment.FulfillmentEffectType
import com.duluin.ftth.fulfillment.FulfillmentRequest
import com.duluin.ftth.fulfillment.FulfillmentSource
import com.duluin.ftth.fulfillment.FulfillmentSqlPhase
import com.duluin.ftth.fulfillment.FulfillmentSqlProbe
import com.duluin.ftth.fulfillment.FulfillmentState
import com.duluin.ftth.fulfillment.decodeFulfillmentRequest

class ReferenceWorkOrderIT : WarehouseMasterHttpFixture() {
    private data class Setup(val owner: String, val tech: String, val techId: String, val second: String,
        val secondId: String, val admin: String, val manager: String, val type: String)
    private fun ok(method: String, path: String, token: String, body: String? = null, key: String = UUID.randomUUID().toString(),
        status: Int = 200): JsonNode {
        val response = request(method, "/api/v2/work-orders$path", token, body, key)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(status)
        return mapper.readTree(response.contentAsString)
    }
    private fun setup(): Setup {
        val owner = tenant()
        fun member(name: String): Pair<String, String> {
            val role = mapper.readTree(request("GET", "/api/roles", owner).contentAsString).single { it.path("name").asString() == name }.path("id").asString()
            val slug = mapper.readTree(request("GET", "/api/me", owner).contentAsString).path("email").asString().substringAfter('@').substringBefore(".test")
            val email = "wo${UUID.randomUUID().toString().take(8)}@$slug.test"
            val created = request("POST", "/api/users", owner, mapper.writeValueAsString(mapOf("name" to name, "email" to email,
                "password" to "secret12345", "roleIds" to listOf(role))))
            assertThat(created.status).withFailMessage(created.contentAsString).isEqualTo(201)
            val id = mapper.readTree(created.contentAsString).path("id").asString()
            val grant = request("PUT", "/api/users/$id/access", owner, mapper.writeValueAsString(mapOf("roleIds" to listOf(role), "areaIds" to listOf(area(owner)))))
            assertThat(grant.status).withFailMessage(grant.contentAsString).isEqualTo(200)
            return login(slug, email) to id
        }
        val (tech, techId) = member("Teknisi NE")
        val (second, secondId) = member("Teknisi FO")
        val (admin, _) = member("Admin")
        val (manager, _) = member("Manager")
        fun wh(path: String, body: String? = null) = request(if (body == null) "GET" else "POST", "/api/v2/warehouse$path", owner, body).also {
            assertThat(it.status).withFailMessage(it.contentAsString).isEqualTo(200)
        }
        wh("/workflow/drain", """{"expectedEpoch":0}""")
        val review = mapper.readTree(wh("/workflow/review").contentAsString)
        wh("/workflow/activate", """{"expectedEpoch":1,"reviewHash":"${review.path("reviewHash").asString()}","reason":"WO sederhana"}""")
        val type = ok("GET", "/types", owner).single { it.path("name").asString() == "Pasang Baru" }.path("id").asString()
        return Setup(owner, tech, techId, second, secondId, admin, manager, type)
    }
    private fun body(setup: Setup, technician: String = setup.techId) = """{"typeId":"${setup.type}","title":"Pasang pelanggan","technicianId":"$technician","areaId":"${area(setup.owner)}","description":"Datang sesuai jadwal"}"""
    private fun create(setup: Setup) = ok("POST", "", setup.admin, body(setup), status = 201)
    private val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")
    private fun upload(id: String, token: String, revision: Long, slot: String = "Bukti Pasang", bytes: ByteArray = png,
        key: String = UUID.randomUUID().toString(), contentType: String = "image/png") = mvc.perform(
        multipart("/api/v2/work-orders/$id/evidence").file(MockMultipartFile("file", "proof.png", contentType, bytes))
            .param("expectedRevision", revision.toString()).param("slot", slot)
            .header("Authorization", "Bearer $token").header("Idempotency-Key", key)).andReturn().response
    private fun warehouseOk(method: String, path: String, s: Setup, token: String = s.owner, body: String? = null, status: Int = 200): JsonNode {
        val response = request(method, "/api/v2/warehouse$path", token, body)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(status)
        return mapper.readTree(response.contentAsString)
    }
    private fun issue(s: Setup, unit: String = "EA", tracking: String = "BULK", quantity: String = "9"): Pair<String, String> {
        val warehouse = create("locations", s.owner, """{"code":"WH-${UUID.randomUUID().toString().uppercase()}","name":"Gudang","kind":"WAREHOUSE","issueEligible":true}""").path("id").asString()
        val sku = create("skus", s.owner, """{"code":"SKU-${UUID.randomUUID().toString().uppercase()}","name":"Material kerja","tracking":"$tracking","baseUnit":"$unit"}""").path("id").asString()
        warehouseOk("POST", "/receipts", s, body = mapper.writeValueAsString(mapOf("warehouseId" to warehouse,
            "lines" to listOf(mapOf("skuId" to sku, "quantityBase" to quantity, "serials" to if (tracking == "SERIAL")
                listOf(mapOf("serial" to "ONT-${UUID.randomUUID()}", "mac" to "02:AA:BB:CC:DD:01")) else emptyList<Map<String, String>>())))), status = 201)
        val identity = warehouseOk("GET", "/stock/$sku", s).path("positions").single().path("stockIdentityId").asString()
        var view = warehouseOk("POST", "/requests", s, s.tech, mapper.writeValueAsString(mapOf("kind" to "RESTOCK", "reason" to "Persediaan kerja",
            "lines" to listOf(mapOf("skuId" to sku, "baseUnit" to unit, "requestedBase" to quantity)))), 201)
        val id = view.path("id").asString()
        val line = view.path("lines")[0].path("id").asString()
        view = warehouseOk("POST", "/requests/$id/review", s, body = """{"expectedRevision":0,"lines":[{"lineId":"$line","approvedBase":"$quantity"}]}""")
        if (view.path("state").asString() == "MANAGER_REVIEW") view = warehouseOk("POST", "/requests/$id/decision", s,
            body = """{"expectedRevision":${view.path("revision").asLong()},"approved":true}""")
        warehouseOk("POST", "/requests/$id/handovers", s, body = mapper.writeValueAsString(mapOf("expectedRevision" to view.path("revision").asLong(),
            "lineId" to line, "warehouseId" to warehouse, "lines" to listOf(mapOf("stockIdentityId" to identity, "quantityBase" to quantity)))))
        return sku to warehouseOk("GET", "/stock/$sku", s).path("positions").single { it.path("holderKind").asString() == "TECHNICIAN" }.path("stockIdentityId").asString()
    }
    private fun completeBody(revision: Long, materials: List<Pair<String, String>> = emptyList(), notes: String = "Selesai di lokasi") =
        mapper.writeValueAsString(mapOf("expectedRevision" to revision, "notes" to notes,
            "materials" to materials.map { (identity, quantity) -> mapOf("stockIdentityId" to identity, "quantityBase" to quantity) }))
    private fun completePhotos(id: String, token: String, revision: Long = 0) {
        val first = upload(id, token, revision)
        assertThat(first.status).withFailMessage(first.contentAsString).isEqualTo(201)
        val second = upload(id, token, revision + 1, slot = "Bukti Kedatangan")
        assertThat(second.status).withFailMessage(second.contentAsString).isEqualTo(201)
    }
    private fun race(actions: List<() -> org.springframework.mock.web.MockHttpServletResponse>) =
        Executors.newFixedThreadPool(actions.size).use { pool ->
            val ready = CountDownLatch(actions.size)
            val start = CountDownLatch(1)
            val pending = actions.map { action -> pool.submit<org.springframework.mock.web.MockHttpServletResponse> {
                ready.countDown()
                check(start.await(20, TimeUnit.SECONDS))
                action()
            } }
            check(ready.await(20, TimeUnit.SECONDS))
            start.countDown()
            pending.map { it.get(30, TimeUnit.SECONDS) }
        }

    @Test fun `concurrent completions replay once and competing orders cannot overspend stock`() {
        val s = setup()
        val material = issue(s)
        val id = create(s).path("id").asString()
        completePhotos(id, s.tech)
        val body = completeBody(2, listOf(material.second to "3"))
        val key = UUID.randomUUID().toString()
        val replay = race(List(2) { { request("POST", "/api/v2/work-orders/$id/complete", s.tech, body, key) } })
        assertThat(replay.map { it.status }).withFailMessage(replay.joinToString("\n") { it.contentAsString }).containsOnly(200)
        assertThat(mapper.readTree(replay[0].contentAsString)).isEqualTo(mapper.readTree(replay[1].contentAsString))
        val ids = List(2) { create(s).path("id").asString().also { completePhotos(it, s.tech) } }
        val competing = race(ids.map { target -> { request("POST", "/api/v2/work-orders/$target/complete", s.tech,
            completeBody(2, listOf(material.second to "4"))) } })
        assertThat(competing.map { it.status }).withFailMessage(competing.joinToString("\n") { it.contentAsString }).containsExactlyInAnyOrder(200, 409)
        assertThat(warehouseOk("GET", "/stock/${material.first}", s).path("positions").single().path("quantityBase").asString()).isEqualTo("2")
        assertThat(fixture(s.owner).transaction { scalar("SELECT count(*) FROM inventory_movement WHERE operation_namespace='warehouse.reference.consume'") }).isEqualTo("2")
        val unresolved = ids[competing.indexOfFirst { it.status == 409 }]
        assertThat(ok("GET", "/$unresolved", s.owner).path("completion").isNull).isTrue()
    }
    @Test fun `deferred completion failure rolls back consumption and same key remains retryable`() {
        val s = setup()
        val material = issue(s, "MM", "LOT", "10000")
        val id = create(s).path("id").asString()
        completePhotos(id, s.tech)
        val key = UUID.randomUUID().toString()
        val body = completeBody(2, listOf(material.second to "3500"), "\n Selesai di lokasi \n")
        val f = fixture(s.owner)
        try { assertThatThrownBy { f.transaction {
            val saved = request("POST", "/api/v2/work-orders/$id/complete", s.tech, body, key)
            assertThat(saved.status).withFailMessage(saved.contentAsString).isEqualTo(200)
            sql("UPDATE work_order SET proof_of_work_hash=repeat('a',64) WHERE id='$id'")
            org.springframework.security.core.context.SecurityContextHolder.getContext().authentication =
                com.duluin.ftth.common.infrastructure.security.JwtAuthenticationConverter().convert(
                    context.getBean(org.springframework.security.oauth2.jwt.JwtDecoder::class.java).decode(s.tech))
        } }.hasMessageContaining("reference completion differs")
        } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext() }
        assertThat(warehouseOk("GET", "/stock/${material.first}", s).path("positions").single().path("quantityBase").asString()).isEqualTo("10000")
        assertThat(ok("GET", "/$id", s.owner).path("completion").isNull).isTrue()
        assertThat(f.transaction { scalar("SELECT count(*) FROM inventory_movement WHERE operation_namespace='warehouse.reference.consume'") }).isEqualTo("0")
        assertThat(f.transaction { scalar("SELECT count(*) FROM fulfillment_approval_snapshot WHERE work_order_id='$id'") }).isEqualTo("0")
        ok("POST", "/$id/complete", s.tech, body, key)
        assertThat(warehouseOk("GET", "/stock/${material.first}", s).path("positions").single().path("quantityBase").asString()).isEqualTo("6500")
        assertThat(f.transaction { scalar("SELECT resolution_note FROM work_order WHERE id='$id'") }).isEqualTo("Selesai di lokasi")
    }

    @Test fun `optional material completion requires all current photos and preserves no approval`() {
        val s = setup()
        val type = ok("GET", "/types", s.owner).single { it.path("name").asString() == "Maintenance" }.path("id").asString()
        val id = ok("POST", "", s.admin, body(s).replace(s.type, type), status = 201).path("id").asString()
        assertThat(request("POST", "/api/v2/work-orders/$id/complete", s.tech, completeBody(0)).status).isEqualTo(400)
        assertThat(upload(id, s.tech, 0, slot = "Bukti").status).isEqualTo(201)
        val key = UUID.randomUUID().toString()
        val saved = ok("POST", "/$id/complete", s.tech, completeBody(1), key)
        assertThat(saved.path("state").asString()).isEqualTo("COMPLETED")
        assertThat(ok("POST", "/$id/complete", s.tech, completeBody(1), key)).isEqualTo(saved)
        assertThat(request("POST", "/api/v2/work-orders/$id/complete", s.tech, completeBody(1, notes = "Berubah"), key).status).isEqualTo(409)
        val completion = ok("GET", "/$id", s.owner).path("completion")
        assertThat(completion.path("materials")).isEmpty()
        assertThat(completion.path("documentId").isNull).isTrue()
        assertThat(completion.path("photos")).hasSize(1)
        assertThat(fixture(s.owner).transaction { scalar("SELECT count(*) FROM work_order WHERE id='$id' AND status='DONE' AND completed_by='${s.techId}' AND approval_status IS NULL AND approved_by IS NULL") }).isEqualTo("1")
        assertReferenceFulfilled(s, id)
        assertThat(fixture(s.owner).transaction { scalar("SELECT count(*) FROM fulfillment_reference_material_receipt WHERE work_order_id='$id' AND document_id IS NULL") }).isEqualTo("1")
        assertThatThrownBy { fixture(s.owner).transaction { sql("UPDATE work_order SET proof_of_work_hash=repeat('a',64) WHERE id='$id'") } }
            .hasMessageContaining("reference completion differs")
        assertThatThrownBy { fixture(s.owner).transaction { sql("UPDATE work_order_reference_completion SET revision=revision+1 WHERE work_order_id='$id'") } }
            .hasMessageContaining("permission denied")
    }
    @Test fun `required materials consume own bulk cable and serial once and freeze visible history`() {
        val s = setup()
        val bulk = issue(s)
        val cable = issue(s, "MM", "LOT", "10000")
        val serial = issue(s, tracking = "SERIAL", quantity = "1")
        val id = create(s).path("id").asString()
        completePhotos(id, s.tech)
        assertThat(request("POST", "/api/v2/work-orders/$id/complete", s.tech, completeBody(2)).status).isEqualTo(400)
        assertThat(request("POST", "/api/v2/work-orders/$id/complete", s.admin, completeBody(2, listOf(bulk.second to "3"))).status).isEqualTo(403)
        assertThat(request("POST", "/api/v2/work-orders/$id/complete", s.second, completeBody(2, listOf(bulk.second to "3"))).status).isEqualTo(404)
        assertThat(request("POST", "/api/v2/work-orders/$id/complete", s.tech, completeBody(2, listOf(bulk.second to "10"))).status).isEqualTo(409)
        val body = completeBody(2, listOf(serial.second to "1", bulk.second to "3", cable.second to "3500"))
        val key = UUID.randomUUID().toString()
        val saved = ok("POST", "/$id/complete", s.tech, body, key)
        assertThat(ok("POST", "/$id/complete", s.tech, body, key)).isEqualTo(saved)
        val detail = ok("GET", "/$id", s.owner)
        assertThat(detail.path("completion").path("photos")).hasSize(2)
        val materials = detail.path("completion").path("materials")
        assertThat(materials).hasSize(3)
        assertThat(materials.single { it.path("tracking").asString() == "SERIAL" }.path("serial").asString()).startsWith("ONT-")
        assertThat(warehouseOk("GET", "/stock/${bulk.first}", s).path("positions").single().path("quantityBase").asString()).isEqualTo("6")
        assertThat(warehouseOk("GET", "/stock/${cable.first}", s).path("positions").single().path("quantityBase").asString()).isEqualTo("6500")
        assertThat(warehouseOk("GET", "/stock/${serial.first}", s).path("positions")).isEmpty()
        assertThat(fixture(s.owner).transaction { scalar("SELECT count(*) FROM inventory_movement WHERE operation_namespace='warehouse.reference.consume' AND document_id='${detail.path("completion").path("documentId").asString()}'") }).isEqualTo("1")
        assertReferenceFulfilled(s, id)
        assertThat(fixture(s.owner).transaction { scalar("SELECT count(*) FROM fulfillment_reference_material_receipt WHERE work_order_id='$id' AND document_id='${detail.path("completion").path("documentId").asString()}'") }).isEqualTo("1")
        val second = create(s).path("id").asString()
        completePhotos(second, s.tech)
        assertThat(request("POST", "/api/v2/work-orders/$second/complete", s.tech, completeBody(2, listOf(serial.second to "1"))).status).isEqualTo(409)
    }
    private fun assertReferenceFulfilled(s: Setup, id: String) {
        fixture(s.owner).transaction {
            assertThat(scalar("SELECT state||':'||coalesce(outcome,'') FROM fulfillment_checkpoint WHERE work_order_id='$id'"))
                .startsWith("APPLIED:")
            assertThat(scalar("SELECT count(*) FROM fulfillment_approval_snapshot WHERE work_order_id='$id' AND source='REFERENCE_WORK_ORDER' AND approved_by IS NULL AND plan_id IS NULL AND usage_id IS NULL AND fulfillment_actor_id='${s.techId}'")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM fulfillment_effect_progress p JOIN fulfillment_checkpoint c ON c.tenant_id=p.tenant_id AND c.id=p.fulfillment_id WHERE c.work_order_id='$id' AND p.status='COMPLETED'")).isEqualTo("2")
            assertThat(scalar("SELECT count(*) FROM workorder_fulfillment_result WHERE work_order_id='$id' AND source='REFERENCE_WORK_ORDER' AND result='APPLIED'")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_material_settlement s JOIN fulfillment_approval_snapshot a ON a.tenant_id=s.tenant_id AND a.id=s.id WHERE a.work_order_id='$id'")).isEqualTo("0")
        }
    }

    @Test fun `linked completion activates service and fulfills order once after delivery failure`() {
        val s = setup()
        fun post(path: String, input: String, status: Int = 201): JsonNode {
            val response = request("POST", path, s.owner, input)
            assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(status)
            return mapper.readTree(response.contentAsString)
        }
        val plan = post("/api/catalog/plans", """{"name":"Reference service","price":150000,"downMbps":20,"upMbps":10,"serviceTypes":["PPPOE"]}""").path("id").asString()
        val customer = post("/api/customers", """{"code":"REFERENCE","name":"Pelanggan","address":"Lokasi","planId":"$plan","location":{"longitude":106.9,"latitude":-6.2}}""")
        val customerId = customer.path("id").asString()
        val subscription = customer.path("subscription").path("id").asString()
        val nas = post("/api/bng/nas", """{"name":"Reference NAS","vendor":"MIKROTIK"}""").path("id").asString()
        val access = post("/api/bng/access", """{"subscriptionId":"$subscription","planId":"$plan","nasId":"$nas"}""").path("id").asString()
        val material = issue(s)
        var order = post("/api/orders", """{"customerId":"$customerId","lines":[{"catalogItemId":"${material.first}","description":"Pemasangan","quantity":1}],
            "serviceAddress":{"address":"Lokasi","city":"Kota","postalCode":"10000"},
            "operation":{"namespace":"test.reference.order","key":"create","payloadHash":"create"}}""")
        val orderId = order.path("id").asString()
        for (action in listOf("SUBMIT", "ACCEPT", "SCHEDULE", "START_FULFILLING")) {
            order = post("/api/orders/$orderId/$action", """{"expectedRevision":${order.path("revision").asLong()},
                "appointment":{"startsAt":"2030-01-01T10:00:00Z","endsAt":"2030-01-01T11:00:00Z"},
                "operation":{"namespace":"test.reference.order","key":"$action","payloadHash":"$action"}}""", 200)
        }
        val input = body(s).dropLast(1) + ",\"customerId\":\"$customerId\",\"subscriptionId\":\"$subscription\",\"orderId\":\"$orderId\"}"
        val id = ok("POST", "", s.admin, input, status = 201).path("id").asString()
        completePhotos(id, s.tech)
        val key = UUID.randomUUID().toString()
        val completion = completeBody(2, listOf(material.second to "2"))
        FulfillmentSqlProbe(context, FulfillmentSqlPhase.COMPLETED_EFFECT) { error("Delivery unavailable") }.use {
            val result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v2/work-orders/$id/complete")
                .header("Authorization", "Bearer ${s.tech}").header("Idempotency-Key", key)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(completion)).andReturn()
            assertThat(result.response.status).withFailMessage(result.resolvedException?.stackTraceToString() ?: result.response.contentAsString).isEqualTo(200)
        }
        val frozen = fixture(s.owner).transaction { scalar("SELECT request_payload FROM fulfillment_approval_snapshot WHERE work_order_id='$id'").decodeFulfillmentRequest() }
        assertThat(frozen.approved).isFalse()
        val retry = TenantContext.runAs(frozen.tenantId) { context.getBean(FulfillmentCoordinator::class.java).process(frozen) }
        assertThat(retry.state).withFailMessage(retry.toString()).isEqualTo(FulfillmentState.APPLIED)
        ok("POST", "/$id/complete", s.tech, completion, key)
        assertThat(TenantContext.runAs(frozen.tenantId) { context.getBean(FulfillmentCoordinator::class.java).process(frozen) }.replayed).isTrue()
        assertThat(mapper.readTree(request("GET", "/api/orders/$orderId", s.owner).contentAsString).path("status").asString()).isEqualTo("FULFILLED")
        assertThat(mapper.readTree(request("GET", "/api/bng/access/$access", s.owner).contentAsString).path("status").asString()).isEqualTo("ACTIVE")
        fixture(s.owner).transaction {
            assertThat(scalar("SELECT status FROM subscription WHERE id='$subscription'")).isEqualTo("ACTIVE")
            assertThat(scalar("SELECT count(*) FROM customer_fulfillment_receipt WHERE namespace='workorder.fulfillment.complete' AND actor_id='${s.techId}'")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM order_operation WHERE namespace='workorder.fulfillment.complete'")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE operation_namespace='warehouse.reference.consume'")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM fulfillment_effect_progress p JOIN fulfillment_checkpoint c ON c.id=p.fulfillment_id AND c.tenant_id=p.tenant_id WHERE c.work_order_id='$id' AND p.status='COMPLETED'")).isEqualTo("5")
        }
    }

    @Test fun `reference coordinator refuses a handoff without completed assignment proof`() {
        val s = setup()
        val id = UUID.fromString(create(s).path("id").asString())
        val tenant = fixture(s.owner).tenant
        val forged = FulfillmentRequest(tenant, "workorder.fulfillment.complete", "forged", "a".repeat(64),
            FulfillmentSource.REFERENCE_WORK_ORDER, id, null, id, "PSB", false,
            setOf(FulfillmentEffectType.INVENTORY, FulfillmentEffectType.WORK_ORDER), approvalActorId = UUID.fromString(s.techId))
        assertThatThrownBy { TenantContext.runAs(tenant) { context.getBean(FulfillmentCoordinator::class.java).accept(forged) } }
            .hasStackTraceContaining("legacy fulfillment requires explicit snapshot reconciliation")
        fixture(s.owner).transaction {
            assertThat(scalar("SELECT count(*) FROM fulfillment_checkpoint WHERE work_order_id='$id'")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM fulfillment_outbox")).isEqualTo("0")
        }
        assertThat(ok("GET", "/$id", s.tech).path("workOrder").path("state").asString()).isEqualTo("PENDING")
    }
    @Test fun `completion isolates reassignment evidence and storage tampering leaves stock unchanged`() {
        val s = setup()
        val material = issue(s)
        val id = create(s).path("id").asString()
        completePhotos(id, s.tech)
        ok("POST", "/$id/assignment", s.admin, """{"expectedRevision":2,"technicianId":"${s.secondId}"}""")
        assertThat(request("POST", "/api/v2/work-orders/$id/complete", s.second, completeBody(3, listOf(material.second to "1"))).status).isEqualTo(400)
        completePhotos(id, s.second, 3)
        assertThat(request("POST", "/api/v2/work-orders/$id/complete", s.second, completeBody(5, listOf(material.second to "1"))).status).isEqualTo(409)
        ok("POST", "/$id/assignment", s.admin, """{"expectedRevision":5,"technicianId":"${s.techId}"}""")
        completePhotos(id, s.tech, 6)
        val objectKey = fixture(s.owner).transaction { scalar("SELECT object_key FROM work_order_reference_photo WHERE work_order_id='$id' AND assignment_generation=2 ORDER BY work_order_revision DESC LIMIT 1") }
        context.getBean(com.duluin.ftth.common.storage.ObjectStorage::class.java).put(objectKey, "image/png", png + byteArrayOf(1))
        assertThat(request("POST", "/api/v2/work-orders/$id/complete", s.tech, completeBody(8, listOf(material.second to "1"))).status).isEqualTo(409)
        assertThat(warehouseOk("GET", "/stock/${material.first}", s).path("positions").single().path("quantityBase").asString()).isEqualTo("9")
        assertThat(ok("GET", "/$id", s.owner).path("completion").isNull).isTrue()
    }

    @Test fun `defaults preserve customized inactive types and only owner may change types`() {
        val s = setup()
        val defaults = ok("GET", "/types", s.owner)
        assertThat(defaults).hasSize(2)
        val defaultSlots = defaults.single { it.path("name").asString() == "Maintenance" }.path("photoSlots").iterator().asSequence().map { it.asString() }.toList()
        assertThat(defaultSlots).containsExactly("Bukti")
        val input = """{"expectedRevision":0,"name":"Pasang Baru","workType":"PSB","materialRequired":false,"photoSlots":["Foto khusus"],"active":false}"""
        assertThat(request("PUT", "/api/v2/work-orders/types/${s.type}", s.admin, input).status).isEqualTo(403)
        assertThat(request("PUT", "/api/v2/work-orders/types/${s.type}", s.manager, input).status).isEqualTo(403)
        ok("PUT", "/types/${s.type}", s.owner, input)
        repeat(2) {
            val type = ok("GET", "/types", s.owner).single { it.path("id").asString() == s.type }
            assertThat(type.path("active").asBoolean()).isFalse()
            val slots = type.path("photoSlots").iterator().asSequence().map { it.asString() }.toList()
            assertThat(slots).containsExactly("Foto khusus")
        }
        assertThat(request("POST", "/api/v2/work-orders", s.admin, body(s)).status).isEqualTo(400)
    }
    @Test fun `new work order snapshots rules rejects HTML and mixed roles and exact retries create once`() {
        val s = setup()
        val key = UUID.randomUUID().toString()
        val created = ok("POST", "", s.admin, body(s), key, 201)
        assertThat(ok("POST", "", s.admin, body(s), key, 201)).isEqualTo(created)
        assertThat(request("POST", "/api/v2/work-orders", s.admin, body(s).replace("Datang sesuai jadwal", "<script>alert(1)</script>")).status).isEqualTo(400)
        val ownerId = mapper.readTree(request("GET", "/api/me", s.owner).contentAsString).path("id").asString()
        assertThat(request("POST", "/api/v2/work-orders", s.admin, body(s, ownerId)).status).isEqualTo(400)
        val mixedRoleIds = mapper.readTree(request("GET", "/api/roles", s.owner).contentAsString)
            .filter { it.path("name").asString() in setOf("Admin", "Teknisi FO") }.map { it.path("id").asString() }
        assertThat(request("PUT", "/api/users/${s.secondId}/access", s.owner, mapper.writeValueAsString(
            mapOf("roleIds" to mixedRoleIds, "areaIds" to listOf(area(s.owner))))).status).isEqualTo(200)
        assertThat(request("POST", "/api/v2/work-orders", s.admin, body(s, s.secondId)).status).isEqualTo(400)
        assertThat(request("POST", "/api/v2/work-orders", s.manager, body(s)).status).isEqualTo(403)
        ok("PUT", "/types/${s.type}", s.owner, """{"expectedRevision":0,"name":"Pasang Baru","workType":"REPAIR","materialRequired":false,"photoSlots":["Baru"],"active":false}""")
        val detail = ok("GET", "/${created.path("id").asString()}", s.tech)
        assertThat(detail.path("workOrder").path("type")).isEqualTo(created.path("type"))
        assertThat(detail.path("timeline")).hasSize(1)
        assertThat(request("DELETE", "/api/v2/work-orders/types/${s.type}?expectedRevision=1", s.owner).status).isEqualTo(400)
    }
    @Test fun `current technician activity blocked reason and reassignment reset ownership and old retry`() {
        val s = setup()
        val view = create(s)
        val id = view.path("id").asString()
        val key = UUID.randomUUID().toString()
        val progress = """{"expectedRevision":0,"state":"BLOCKED","notes":"Pelanggan belum di rumah"}"""
        assertThat(request("POST", "/api/v2/work-orders/$id/progress", s.second, progress).status).isEqualTo(404)
        assertThat(request("POST", "/api/v2/work-orders/$id/progress", s.tech, progress.replace("Pelanggan belum di rumah", " ")).status).isEqualTo(400)
        val blocked = ok("POST", "/$id/progress", s.tech, progress, key)
        assertThat(ok("POST", "/$id/progress", s.tech, progress, key)).isEqualTo(blocked)
        assertThat(blocked.path("lastActivityAt").asString()).isNotEqualTo(view.path("lastActivityAt").asString())
        val assigned = ok("POST", "/$id/assignment", s.admin, """{"expectedRevision":1,"technicianId":"${s.secondId}"}""")
        assertThat(assigned.path("state").asString()).isEqualTo("PENDING")
        assertThat(assigned.path("assignmentGeneration").asLong()).isEqualTo(1)
        assertThat(assigned.path("blockedReason").isNull).isTrue()
        assertThat(request("POST", "/api/v2/work-orders/$id/progress", s.tech, progress, key).status).isEqualTo(404)
        assertThat(ok("GET", "", s.tech).path("totalElements").asLong()).isZero()
        assertThat(ok("GET", "", s.second).path("totalElements").asLong()).isEqualTo(1)
        assertThat(ok("GET", "/$id", s.second).path("timeline")).hasSize(3)
    }
    @Test fun `scope filtering precedes pagination and revocation hides records and retries`() {
        val s = setup()
        create(s); create(s)
        assertThat(ok("GET", "?size=1", s.tech).path("totalElements").asLong()).isEqualTo(2)
        assertThat(ok("GET", "?page=2&size=1", s.tech).path("items")).isEmpty()
        val role = mapper.readTree(request("GET", "/api/roles", s.owner).contentAsString).single { it.path("name").asString() == "Teknisi NE" }.path("id").asString()
        val revoked = request("PUT", "/api/users/${s.techId}/access", s.owner, """{"roleIds":["$role"],"areaIds":[]}""")
        assertThat(revoked.status).isEqualTo(200)
        assertThat(ok("GET", "", s.tech).path("totalElements").asLong()).isZero()
    }
    @Test fun `named photos are verified immutable and exact retry stores once`() {
        val s = setup()
        val id = create(s).path("id").asString()
        val key = UUID.randomUUID().toString()
        val saved = upload(id, s.tech, 0, key = key)
        assertThat(saved.status).withFailMessage(saved.contentAsString).isEqualTo(201)
        val replay = upload(id, s.tech, 0, key = key)
        assertThat(replay.status).isEqualTo(saved.status)
        assertThat(mapper.readTree(replay.contentAsString)).isEqualTo(mapper.readTree(saved.contentAsString))
        assertThat(upload(id, s.tech, 0, bytes = png + byteArrayOf(1), key = key).status).isEqualTo(409)
        val photos = ok("GET", "/$id/evidence", s.tech)
        assertThat(photos).hasSize(1)
        val photoId = photos.single().path("id").asString()
        assertThat(photos.single().path("current").asBoolean()).isTrue()
        val content = request("GET", "/api/v2/work-orders/$id/evidence/$photoId/content", s.tech)
        assertThat(content.status).isEqualTo(200)
        assertThat(content.contentAsByteArray).isEqualTo(png)
        assertThat(content.getHeader("X-Content-Type-Options")).isEqualTo("nosniff")
        assertThat(ok("GET", "/$id", s.owner).path("timeline")).hasSize(2)
        assertThatThrownBy { fixture(s.owner).transaction {
            sql("UPDATE wo_evidence SET sha256=repeat('a',64) WHERE id='$photoId'")
        } }.hasMessageContaining("photo identity is immutable")
        assertThatThrownBy { fixture(s.owner).transaction {
            sql("UPDATE work_order_reference_photo SET slot='Palsu' WHERE evidence_id='$photoId'")
        } }.hasMessageContaining("permission denied")
        val storage = context.getBean(com.duluin.ftth.common.storage.ObjectStorage::class.java)
        val objectKey = fixture(s.owner).transaction { scalar("SELECT object_key FROM work_order_reference_photo WHERE evidence_id='$photoId'") }
        storage.put(objectKey, "image/png", png + byteArrayOf(9))
        assertThat(request("GET", "/api/v2/work-orders/$id/evidence/$photoId/content", s.tech).status).isEqualTo(409)
    }
    @Test fun `photos enforce slots revisions scope and current assignment generation`() {
        val s = setup()
        val id = create(s).path("id").asString()
        assertThat(upload(id, s.second, 0).status).isEqualTo(404)
        assertThat(upload(id, s.admin, 0).status).isEqualTo(403)
        assertThat(upload(id, s.tech, 0, slot = "Tidak ada").status).isEqualTo(400)
        assertThat(upload(id, s.tech, 0, contentType = "image/svg+xml").status).isEqualTo(400)
        assertThat(upload(id, s.tech, 0, bytes = "invalid".toByteArray()).status).isEqualTo(400)
        val key = UUID.randomUUID().toString()
        assertThat(upload(id, s.tech, 0, key = key).status).isEqualTo(201)
        assertThat(upload(id, s.tech, 0, slot = "Bukti Kedatangan").status).isEqualTo(409)
        assertThat(upload(id, s.tech, 1).status).isEqualTo(201)
        assertThat(ok("GET", "/$id/evidence", s.tech).count { it.path("current").asBoolean() }).isEqualTo(1)
        ok("POST", "/$id/assignment", s.admin, """{"expectedRevision":2,"technicianId":"${s.secondId}"}""")
        assertThat(upload(id, s.tech, 0, key = key).status).isEqualTo(404)
        assertThat(ok("GET", "/$id/evidence", s.second).none { it.path("current").asBoolean() }).isTrue()
        assertThat(upload(id, s.second, 3).status).isEqualTo(201)
        val current = ok("GET", "/$id/evidence", s.second).single { it.path("current").asBoolean() }
        assertThat(current.path("uploadedBy").asString()).isEqualTo(s.secondId)
        assertThat(current.path("assignmentGeneration").asLong()).isEqualTo(1)
        val neighbor = tenant()
        assertThat(request("GET", "/api/v2/work-orders/$id/evidence", neighbor).status).isEqualTo(404)
    }
    @Test fun `concurrent photo retries store one object and competing revisions preserve first upload`() {
        val s = setup()
        val id = create(s).path("id").asString()
        val key = UUID.randomUUID().toString()
        val replay = race(List(2) { { upload(id, s.tech, 0, key = key) } })
        assertThat(replay.map { it.status }).withFailMessage(replay.joinToString("\n") { it.contentAsString }).containsOnly(201)
        assertThat(mapper.readTree(replay[0].contentAsString)).isEqualTo(mapper.readTree(replay[1].contentAsString))
        val competing = race(List(2) { { upload(id, s.tech, 1, slot = "Bukti Kedatangan") } })
        assertThat(competing.map { it.status }).containsExactlyInAnyOrder(201, 409)
        assertThat(ok("GET", "/$id/evidence", s.owner)).hasSize(2)
        assertThat(ok("GET", "/$id", s.owner).path("workOrder").path("revision").asLong()).isEqualTo(2)
        val f = fixture(s.owner)
        val storage = context.getBean(com.duluin.ftth.common.storage.ObjectStorage::class.java)
        assertThat(storage.list(f.tenant.toString(), "${f.tenant}/wo/$id/evidence/").objects).hasSize(2)
    }
    @Test fun `failed commit removes uploaded object and leaves revision retryable`() {
        val s = setup()
        val id = create(s).path("id").asString()
        val f = fixture(s.owner)
        val storage = context.getBean(com.duluin.ftth.common.storage.ObjectStorage::class.java)
        val prefix = "${f.tenant}/wo/$id/evidence/"
        val key = UUID.randomUUID().toString()
        assertThatThrownBy { f.transaction {
            val saved = upload(id, s.tech, 0, key = key)
            assertThat(saved.status).withFailMessage(saved.contentAsString).isEqualTo(201)
            assertThat(storage.list(tenant.toString(), prefix).objects).hasSize(1)
            sql("UPDATE work_order SET title='Commit must fail' WHERE id='$id'")
        } }.hasMessageContaining("work order projection differs from source")
        assertThat(storage.list(f.tenant.toString(), prefix).objects).isEmpty()
        assertThat(ok("GET", "/$id/evidence", s.owner)).isEmpty()
        assertThat(ok("GET", "/$id", s.owner).path("workOrder").path("revision").asLong()).isZero()
        val retried = upload(id, s.tech, 0, key = key)
        assertThat(retried.status).withFailMessage(retried.contentAsString).isEqualTo(201)
        assertThat(storage.list(f.tenant.toString(), prefix).objects).hasSize(1)
    }
    @Test fun `database refuses projection forgery and command mutation`() {
        val s = setup()
        val id = create(s).path("id").asString()
        assertThatThrownBy { fixture(s.owner).transaction {
            sql("""UPDATE work_order_reference SET snapshot=jsonb_set(snapshot,'{title}','"Palsu"'::jsonb),revision=revision+1 WHERE id='$id'""")
        } }.hasMessageContaining("work order projection differs from source")
        assertThatThrownBy { fixture(s.owner).transaction {
            sql("UPDATE work_order_reference_command SET notes='Palsu' WHERE resource_id='$id'")
        } }.hasMessageContaining("append-only")
        assertThatThrownBy { fixture(s.owner).transaction {
            sql("UPDATE work_order SET title='Palsu' WHERE id='$id'")
        } }.hasMessageContaining("work order projection differs from source")
        assertThatThrownBy { fixture(s.owner).transaction {
            sql("UPDATE work_order_assignee SET technician_id='${s.secondId}' WHERE work_order_id='$id'")
        } }.hasMessageContaining("work order projection differs from source")
        assertThat(ok("GET", "/$id", s.owner).path("workOrder").path("title").asString()).isEqualTo("Pasang pelanggan")
    }
}
