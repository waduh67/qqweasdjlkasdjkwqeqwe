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
        fun race(actions: List<() -> org.springframework.mock.web.MockHttpServletResponse>) =
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
