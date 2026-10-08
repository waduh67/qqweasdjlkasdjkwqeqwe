package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ReferenceReturnIT : WarehouseMasterHttpFixture() {
    private data class Setup(val owner: String, val warehouse: String, val sku: String, val manager: String,
        val admin: String, val adminId: String, val technician: String, val technicianId: String, val other: String)

    private fun ok(method: String, path: String, token: String, body: String? = null,
        key: String = UUID.randomUUID().toString(), status: Int = 200): JsonNode {
        val response = request(method, "/api/v2/warehouse$path", token, body, key)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(status)
        return mapper.readTree(response.contentAsString)
    }

    private fun setup(): Setup {
        val owner = tenant()
        val warehouse = create("locations", owner, """{"code":"WH","name":"Gudang","kind":"WAREHOUSE","issueEligible":true}""").path("id").asString()
        val sku = create("skus", owner, """{"code":"CONNECTOR","name":"Konektor","tracking":"BULK","baseUnit":"EA"}""").path("id").asString()
        val roles = mapper.readTree(request("GET", "/api/roles", owner).contentAsString)
        val me = mapper.readTree(request("GET", "/api/me", owner).contentAsString)
        val slug = me.path("email").asString().substringAfter('@').substringBefore(".test")
        fun member(name: String): Pair<String, String> {
            val role = roles.single { it.path("name").asString() == name }.path("id").asString()
            val email = "member${UUID.randomUUID().toString().take(8)}@$slug.test"
            val response = request("POST", "/api/users", owner, mapper.writeValueAsString(mapOf("name" to name,
                "email" to email, "password" to "secret12345", "roleIds" to listOf(role))))
            assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(201)
            val id = mapper.readTree(response.contentAsString).path("id").asString()
            val access = request("PUT", "/api/users/$id/access", owner,
                mapper.writeValueAsString(mapOf("roleIds" to listOf(role), "areaIds" to listOf(area(owner)))))
            assertThat(access.status).withFailMessage(access.contentAsString).isEqualTo(200)
            val scope = request("PUT", "/api/v1/warehouse/settings/scopes/$id/$warehouse", owner, """{"expectedRevision":0,"active":true}""")
            assertThat(scope.status).withFailMessage(scope.contentAsString).isEqualTo(200)
            return login(slug, email) to id
        }
        val (admin, adminId) = member("Admin")
        val (manager, _) = member("Manager")
        val (technician, technicianId) = member("Teknisi NE")
        val (other, _) = member("Teknisi FO")
        ok("POST", "/workflow/drain", owner, """{"expectedEpoch":0}""")
        val review = ok("GET", "/workflow/review", owner)
        ok("POST", "/workflow/activate", owner,
            """{"expectedEpoch":1,"reviewHash":"${review.path("reviewHash").asString()}","reason":"Mulai alur referensi"}""")
        return Setup(owner, warehouse, sku, manager, admin, adminId, technician, technicianId, other)
    }

    private fun stock(setup: Setup, sku: String = setup.sku) = ok("GET", "/stock/$sku", setup.owner).path("positions")

    private fun issue(setup: Setup, sku: String = setup.sku, unit: String = "EA", quantity: String = "9", serials: List<Map<String, String>> = emptyList()): String {
        ok("POST", "/receipts", setup.admin, mapper.writeValueAsString(mapOf("warehouseId" to setup.warehouse,
            "lines" to listOf(mapOf("skuId" to sku, "quantityBase" to quantity, "serials" to serials)))), status = 201)
        val identity = stock(setup, sku).single { it.path("holderKind").asString() == "WAREHOUSE" }.path("stockIdentityId").asString()
        var view = ok("POST", "/requests", setup.technician, mapper.writeValueAsString(mapOf("kind" to "RESTOCK", "reason" to "Persediaan kerja",
            "lines" to listOf(mapOf("skuId" to sku, "baseUnit" to unit, "requestedBase" to quantity)))), status = 201)
        val id = view.path("id").asString()
        view = ok("POST", "/requests/$id/review", setup.admin,
            """{"expectedRevision":0,"lines":[{"lineId":"${view.path("lines")[0].path("id").asString()}","approvedBase":"$quantity"}]}""")
        view = ok("POST", "/requests/$id/decision", setup.manager, """{"expectedRevision":${view.path("revision").asLong()},"approved":true}""")
        ok("POST", "/requests/$id/handovers", setup.admin, mapper.writeValueAsString(mapOf("expectedRevision" to view.path("revision").asLong(),
            "lineId" to view.path("lines")[0].path("id").asString(), "warehouseId" to setup.warehouse,
            "lines" to listOf(mapOf("stockIdentityId" to identity, "quantityBase" to quantity)), "notes" to "Diserahkan")))
        return stock(setup, sku).single { it.path("holderKind").asString() == "TECHNICIAN" }.path("stockIdentityId").asString()
    }

    private fun submission(setup: Setup, identity: String, quantity: String = "3", sku: String = setup.sku, warehouse: String = setup.warehouse) =
        mapper.writeValueAsString(mapOf("warehouseId" to warehouse, "skuId" to sku, "reason" to "Sisa pekerjaan",
            "lines" to listOf(mapOf("stockIdentityId" to identity, "quantityBase" to quantity))))
    private fun decide(setup: Setup, id: String, received: Boolean = true, notes: String = "Diterima gudang", key: String = UUID.randomUUID().toString()) =
        ok("POST", "/returns/$id/decision", setup.admin, """{"expectedRevision":0,"received":$received,"notes":"$notes"}""", key)

    @Test fun `pending returns preserve stock and receipt posts once with immutable final decision`() {
        val setup = setup()
        val identity = issue(setup)
        val before = fixture(setup.owner).transaction { counts() }
        val body = submission(setup, identity)
        val key = UUID.randomUUID().toString()
        val submitted = ok("POST", "/returns", setup.technician, body, key, 201)
        assertThat(ok("POST", "/returns", setup.technician, body, key, 201)).isEqualTo(submitted)
        assertThat(fixture(setup.owner).transaction { counts() }).isEqualTo(before)
        assertThat(stock(setup).single().path("quantityBase").asString()).isEqualTo("9")
        val id = submitted.path("id").asString()
        assertThat(request("POST", "/api/v2/warehouse/returns/$id/decision", setup.technician, """{"expectedRevision":0,"received":true}""").status).isEqualTo(403)
        val decisionKey = UUID.randomUUID().toString()
        val received = decide(setup, id, key = decisionKey)
        assertThat(decide(setup, id, key = decisionKey)).isEqualTo(received)
        assertThat(received.path("state").asString()).isEqualTo("RECEIVED")
        assertThat(received.path("reviewerName").asString()).isEqualTo("Admin")
        val positions = stock(setup)
        assertThat(positions.single { it.path("holderKind").asString() == "TECHNICIAN" }.path("quantityBase").asString()).isEqualTo("6")
        assertThat(positions.single { it.path("holderKind").asString() == "WAREHOUSE" }.path("quantityBase").asString()).isEqualTo("3")
        assertThat(ok("GET", "/returns/$id", setup.technician).path("timeline")).hasSize(2)
        assertThat(request("POST", "/api/v2/warehouse/returns/$id/decision", setup.admin, """{"expectedRevision":1,"received":false,"notes":"Batal"}""").status).isEqualTo(400)
        assertThat(request("POST", "/api/v2/warehouse/returns/$id/decision", setup.admin, """{"expectedRevision":0,"received":false,"notes":"Batal"}""", decisionKey).status).isEqualTo(409)
        val revoke = request("PUT", "/api/v1/warehouse/settings/scopes/${setup.adminId}/${setup.warehouse}", setup.owner, """{"expectedRevision":1,"active":false}""")
        assertThat(revoke.status).withFailMessage(revoke.contentAsString).isEqualTo(200)
        assertThat(request("POST", "/api/v2/warehouse/returns/$id/decision", setup.admin, """{"expectedRevision":0,"received":true,"notes":"Diterima gudang"}""", decisionKey).status).isEqualTo(404)
    }

    @Test fun `rejection requires reason and leaves balances and final command immutable`() {
        val setup = setup()
        val identity = issue(setup)
        val submitted = ok("POST", "/returns", setup.technician, submission(setup, identity), status = 201)
        val id = submitted.path("id").asString()
        val before = fixture(setup.owner).transaction { counts() }
        assertThat(request("POST", "/api/v2/warehouse/returns/$id/decision", setup.admin, """{"expectedRevision":0,"received":false}""").status).isEqualTo(400)
        val key = UUID.randomUUID().toString()
        val rejected = decide(setup, id, false, "Masih digunakan", key)
        assertThat(decide(setup, id, false, "Masih digunakan", key)).isEqualTo(rejected)
        assertThat(rejected.path("state").asString()).isEqualTo("REJECTED")
        assertThat(fixture(setup.owner).transaction { counts() }).isEqualTo(before)
        assertThatThrownBy { fixture(setup.owner).transaction {
            sql("UPDATE inventory_reference_return SET revision=revision+1,snapshot=jsonb_set(snapshot,'{revision}','2') WHERE tenant_id='$tenant' AND id='$id'")
        } }.hasMessageContaining("violates check constraint")
        assertThatThrownBy { fixture(setup.owner).transaction {
            sql("UPDATE inventory_reference_return_command SET notes='Changed' WHERE tenant_id='$tenant' AND resource_id='$id'")
        } }.hasMessageContaining("append-only")
    }

    @Test fun `owned material and warehouse scope restrict submissions and list before pagination`() {
        val setup = setup()
        val identity = issue(setup)
        val body = submission(setup, identity)
        val own = ok("POST", "/returns", setup.technician, body, status = 201)
        val id = own.path("id").asString()
        assertThat(request("POST", "/api/v2/warehouse/returns", setup.other, body).status).isEqualTo(404)
        assertThat(request("POST", "/api/v2/warehouse/returns", setup.admin, body).status).isEqualTo(403)
        assertThat(request("GET", "/api/v2/warehouse/returns", setup.manager).status).isEqualTo(403)
        assertThat(request("GET", "/api/v2/warehouse/returns/$id", setup.other).status).isEqualTo(404)
        assertThat(ok("GET", "/returns", setup.other).path("totalElements").asLong()).isZero()
        val hidden = create("locations", setup.owner, """{"code":"HIDDEN","name":"Gudang lain","kind":"WAREHOUSE","issueEligible":true}""").path("id").asString()
        val scope = request("PUT", "/api/v1/warehouse/settings/scopes/${setup.technicianId}/$hidden", setup.owner, """{"expectedRevision":0,"active":true}""")
        assertThat(scope.status).withFailMessage(scope.contentAsString).isEqualTo(200)
        ok("POST", "/returns", setup.technician, submission(setup, identity, warehouse = hidden), status = 201)
        assertThat(ok("GET", "/returns?size=1", setup.admin).path("totalElements").asLong()).isEqualTo(1)
        assertThat(ok("GET", "/returns?size=1", setup.admin).path("items")[0].path("id")).isEqualTo(own.path("id"))
        assertThat(ok("GET", "/returns?page=1&size=1", setup.admin).path("items").isEmpty).isTrue()
        assertThat(ok("GET", "/returns?state=PENDING&search=Sisa", setup.technician).path("totalElements").asLong()).isEqualTo(2)
        for (quantity in listOf("0", "-1", "1.5", "9223372036854775808"))
            assertThat(request("POST", "/api/v2/warehouse/returns", setup.technician, submission(setup, identity, quantity)).status).isEqualTo(400)
        assertThat(request("POST", "/api/v2/warehouse/returns", setup.technician, submission(setup, identity, "10")).status).isEqualTo(409)
        val wrong = create("skus", setup.owner, """{"code":"WRONG","name":"Lain","tracking":"BULK","baseUnit":"EA"}""").path("id").asString()
        assertThat(request("POST", "/api/v2/warehouse/returns", setup.technician, submission(setup, identity, sku = wrong)).status).isEqualTo(404)
        assertThatThrownBy { fixture(setup.owner).transaction {
            sql("UPDATE inventory_reference_return SET revision=1,snapshot=jsonb_set(snapshot,'{revision}','1') WHERE tenant_id='$tenant' AND id='$id'")
        } }.hasMessageContaining("return projection differs from command history")
    }

    @Test fun `competing pending receipts spend stock once and roll back stale return`() {
        val setup = setup()
        val identity = issue(setup)
        val ids = List(2) { ok("POST", "/returns", setup.technician, submission(setup, identity, "6"), status = 201).path("id").asString() }
        val results = Executors.newFixedThreadPool(2).use { pool ->
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val pending = ids.map { id -> pool.submit<org.springframework.mock.web.MockHttpServletResponse> {
                ready.countDown()
                check(start.await(20, TimeUnit.SECONDS))
                request("POST", "/api/v2/warehouse/returns/$id/decision", setup.admin, """{"expectedRevision":0,"received":true}""")
            } }
            check(ready.await(20, TimeUnit.SECONDS))
            start.countDown()
            pending.map { it.get(30, TimeUnit.SECONDS) }
        }
        assertThat(results.map { it.status }).withFailMessage(results.joinToString("\n") { it.contentAsString }).containsExactlyInAnyOrder(200, 409)
        assertThat(stock(setup).single { it.path("holderKind").asString() == "TECHNICIAN" }.path("quantityBase").asString()).isEqualTo("3")
        assertThat(ids.map { ok("GET", "/returns/$it", setup.technician).path("request").path("state").asString() }).containsExactlyInAnyOrder("RECEIVED", "PENDING")
    }

    @Test fun `cable return cuts the owned segment and keeps the remnant with technician`() {
        val setup = setup()
        val cable = create("skus", setup.owner, """{"code":"CABLE","name":"Kabel","tracking":"LOT","baseUnit":"MM"}""").path("id").asString()
        val identity = issue(setup, cable, "MM", "10000")
        val view = ok("POST", "/returns", setup.technician, submission(setup, identity, "3000", cable), status = 201)
        decide(setup, view.path("id").asString())
        val positions = stock(setup, cable)
        assertThat(positions).hasSize(2)
        assertThat(positions.none { it.path("stockIdentityId").asString() == identity }).isTrue()
        assertThat(positions.single { it.path("holderKind").asString() == "TECHNICIAN" }.path("quantityBase").asString()).isEqualTo("7000")
        assertThat(positions.single { it.path("holderKind").asString() == "WAREHOUSE" }.path("quantityBase").asString()).isEqualTo("3000")
    }

    @Test fun `duplicate selections roll back and inactive destination blocks receipt`() {
        val setup = setup()
        val identity = issue(setup)
        val before = fixture(setup.owner).transaction { counts() }
        val duplicate = mapper.writeValueAsString(mapOf("warehouseId" to setup.warehouse, "skuId" to setup.sku, "reason" to "Sisa pekerjaan",
            "lines" to List(2) { mapOf("stockIdentityId" to identity, "quantityBase" to "3") }))
        assertThat(request("POST", "/api/v2/warehouse/returns", setup.technician, duplicate).status).isEqualTo(400)
        assertThat(fixture(setup.owner).transaction { counts() }).isEqualTo(before)
        val destination = create("locations", setup.owner, """{"code":"RETURN-ONLY","name":"Gudang retur","kind":"WAREHOUSE","issueEligible":true}""").path("id").asString()
        for (actor in listOf(setup.adminId, setup.technicianId)) {
            val scope = request("PUT", "/api/v1/warehouse/settings/scopes/$actor/$destination", setup.owner, """{"expectedRevision":0,"active":true}""")
            assertThat(scope.status).withFailMessage(scope.contentAsString).isEqualTo(200)
        }
        val submitted = ok("POST", "/returns", setup.technician, submission(setup, identity, warehouse = destination), status = 201)
        val id = submitted.path("id").asString()
        fixture(setup.owner).transaction { sql("UPDATE inventory_location SET state='ARCHIVED',revision=revision+1 WHERE tenant_id='$tenant' AND id='$destination'") }
        val beforeReceipt = fixture(setup.owner).transaction { counts() }
        assertThat(request("POST", "/api/v2/warehouse/returns/$id/decision", setup.admin, """{"expectedRevision":0,"received":true}""").status).isEqualTo(409)
        assertThat(fixture(setup.owner).transaction { counts() }).isEqualTo(beforeReceipt)
        assertThat(ok("GET", "/returns/$id", setup.technician).path("request").path("state").asString()).isEqualTo("PENDING")
    }

    @Test fun `concurrent receipt retries return one decision and one stock movement`() {
        val setup = setup()
        val identity = issue(setup)
        val submitted = ok("POST", "/returns", setup.technician, submission(setup, identity), status = 201)
        val id = submitted.path("id").asString()
        val key = UUID.randomUUID().toString()
        val before = fixture(setup.owner).transaction { counts() }
        val results = Executors.newFixedThreadPool(2).use { pool ->
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val pending = List(2) { pool.submit<org.springframework.mock.web.MockHttpServletResponse> {
                ready.countDown()
                check(start.await(20, TimeUnit.SECONDS))
                request("POST", "/api/v2/warehouse/returns/$id/decision", setup.admin, """{"expectedRevision":0,"received":true}""", key)
            } }
            check(ready.await(20, TimeUnit.SECONDS))
            start.countDown()
            pending.map { it.get(30, TimeUnit.SECONDS) }
        }
        assertThat(results.map { it.status }).withFailMessage(results.joinToString("\n") { it.contentAsString }).containsOnly(200)
        assertThat(mapper.readTree(results[0].contentAsString)).isEqualTo(mapper.readTree(results[1].contentAsString))
        val after = fixture(setup.owner).transaction { counts() }
        assertThat(mapper.readTree(after)[0].asLong() - mapper.readTree(before)[0].asLong()).isEqualTo(1)
        assertThat(ok("GET", "/returns/$id", setup.technician).path("timeline")).hasSize(2)
    }

    @Test fun `serial receipt changes custody and makes a second pending serial return stale`() {
        val setup = setup()
        val onu = create("skus", setup.owner, """{"code":"ONU","name":"ONU","tracking":"SERIAL","baseUnit":"EA"}""").path("id").asString()
        val identity = issue(setup, onu, quantity = "1", serials = listOf(mapOf("serial" to "ONU-RETURN", "mac" to "02:11:22:33:44:66")))
        val views = List(2) { ok("POST", "/returns", setup.technician, submission(setup, identity, "1", onu), status = 201) }
        assertThat(views[0].path("lines")[0].path("serial").asString()).isEqualTo("ONU-RETURN")
        decide(setup, views[0].path("id").asString())
        val before = fixture(setup.owner).transaction { counts() }
        assertThat(request("POST", "/api/v2/warehouse/returns/${views[1].path("id").asString()}/decision", setup.admin, """{"expectedRevision":0,"received":true}""").status).isEqualTo(409)
        assertThat(fixture(setup.owner).transaction { counts() }).isEqualTo(before)
        val position = stock(setup, onu).single()
        assertThat(position.path("holderId").asString()).isEqualTo(setup.warehouse)
        assertThat(position.path("status").asString()).isEqualTo("AVAILABLE")
        assertThat(fixture(setup.owner).transaction { scalar("SELECT custody_owner_id FROM inventory_serialized_asset WHERE tenant_id='$tenant' AND id='$identity'") }).isEqualTo(setup.warehouse)
    }
}
