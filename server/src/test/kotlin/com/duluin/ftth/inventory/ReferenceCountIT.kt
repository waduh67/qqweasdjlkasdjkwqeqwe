package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ReferenceCountIT : WarehouseMasterHttpFixture() {
    private data class Setup(val owner: String, val warehouse: String, val sku: String)
    private fun ok(method: String, path: String, token: String, body: String? = null,
        key: String = UUID.randomUUID().toString(), status: Int = 200): JsonNode {
        val result = request(method, "/api/v2/warehouse$path", token, body, key)
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(status)
        return mapper.readTree(result.contentAsString)
    }
    private fun setup(tracking: String = "BULK", unit: String = "EA"): Setup {
        val owner = tenant()
        val warehouse = create("locations", owner, """{"code":"WH","name":"Gudang","kind":"WAREHOUSE","issueEligible":true}""").path("id").asString()
        val sku = create("skus", owner, """{"code":"SKU","name":"Material","tracking":"$tracking","baseUnit":"$unit"}""").path("id").asString()
        ok("POST", "/workflow/drain", owner, """{"expectedEpoch":0}""")
        val review = ok("GET", "/workflow/review", owner)
        ok("POST", "/workflow/activate", owner, mapper.writeValueAsString(mapOf("expectedEpoch" to 1,
            "reviewHash" to review.path("reviewHash").asString(), "reason" to "Mulai opname")))
        return Setup(owner, warehouse, sku)
    }
    private fun receive(s: Setup, quantity: String, serials: List<Map<String, String>> = emptyList()) =
        ok("POST", "/receipts", s.owner, mapper.writeValueAsString(mapOf("warehouseId" to s.warehouse,
            "lines" to listOf(mapOf("skuId" to s.sku, "quantityBase" to quantity, "serials" to serials)))), status = 201)
    private fun snapshot(s: Setup, location: String = s.warehouse, actor: String = s.owner) =
        ok("POST", "/counts/snapshot", actor, mapper.writeValueAsString(mapOf("skuId" to s.sku, "locationId" to location)))
    private fun body(snapshot: JsonNode, quantity: String, serials: List<Map<String, String>> = emptyList()) =
        mapper.writeValueAsString(mapOf("snapshotId" to snapshot.path("id").asString(), "physicalBase" to quantity,
            "serials" to serials, "reason" to "Hasil hitung fisik"))
    private fun save(s: Setup, snapshot: JsonNode, quantity: String, serials: List<Map<String, String>> = emptyList(),
        key: String = UUID.randomUUID().toString(), actor: String = s.owner) =
        ok("POST", "/counts", actor, body(snapshot, quantity, serials), key, 201)
    private fun positions(s: Setup) = ok("GET", "/stock/" + s.sku, s.owner).path("positions")
    private fun member(s: Setup, name: String): Pair<String, String> {
        val roles = mapper.readTree(request("GET", "/api/roles", s.owner).contentAsString)
        val role = roles.single { it.path("name").asString() == name }.path("id").asString()
        val me = mapper.readTree(request("GET", "/api/me", s.owner).contentAsString)
        val slug = me.path("email").asString().substringAfter('@').substringBefore(".test")
        val email = "count" + UUID.randomUUID().toString().take(8) + "@$slug.test"
        val created = request("POST", "/api/users", s.owner, mapper.writeValueAsString(mapOf("name" to name, "email" to email,
            "password" to "secret12345", "roleIds" to listOf(role))))
        assertThat(created.status).withFailMessage(created.contentAsString).isEqualTo(201)
        val id = mapper.readTree(created.contentAsString).path("id").asString()
        val access = request("PUT", "/api/users/$id/access", s.owner, mapper.writeValueAsString(mapOf("roleIds" to listOf(role), "areaIds" to listOf(area(s.owner)))))
        assertThat(access.status).isEqualTo(200)
        val scope = request("PUT", "/api/v1/warehouse/settings/scopes/$id/" + s.warehouse, s.owner, """{"expectedRevision":0,"active":true}""")
        assertThat(scope.status).withFailMessage(scope.contentAsString).isEqualTo(200)
        return login(slug, email) to id
    }

    @Test fun `bulk count records loss recovery and trusted surplus with immutable audit`() {
        val s = setup()
        receive(s, "10")
        val loaded = snapshot(s)
        val key = UUID.randomUUID().toString()
        val loss = save(s, loaded, "7", key = key)
        assertThat(loss.path("differenceBase").asString()).isEqualTo("-3")
        assertThat(loss.path("snapshot")).isEqualTo(loaded)
        assertThat(save(s, loaded, "7", key = key)).isEqualTo(loss)
        assertThat(request("POST", "/api/v2/warehouse/counts", s.owner, body(loaded, "6"), key).status).isEqualTo(409)
        assertThat(save(s, snapshot(s), "9").path("differenceBase").asString()).isEqualTo("2")
        val surplus = save(s, snapshot(s), "12")
        assertThat(surplus.path("movementIds")).hasSize(2)
        assertThat(snapshot(s).path("bookBase").asString()).isEqualTo("12")
        assertThat(ok("GET", "/counts?size=1", s.owner).path("totalElements").asLong()).isEqualTo(3)
        val id = loss.path("id").asString()
        assertThat(ok("GET", "/counts/$id", s.owner)).isEqualTo(loss)
        assertThatThrownBy { fixture(s.owner).transaction {
            sql("UPDATE inventory_reference_count SET recorded_at=clock_timestamp() WHERE tenant_id='$tenant' AND id='$id'")
        } }.hasMessageContaining("append-only")
    }

    @Test fun `zero and unchanged counts work while malformed precision rolls back`() {
        val s = setup()
        val empty = snapshot(s)
        assertThat(save(s, empty, "0").path("movementIds").isEmpty).isTrue()
        receive(s, "4")
        val loaded = snapshot(s)
        val before = fixture(s.owner).transaction { counts() }
        for (quantity in listOf("-1", "1.5", "9223372036854775808", ""))
            assertThat(request("POST", "/api/v2/warehouse/counts", s.owner, body(loaded, quantity)).status).isEqualTo(400)
        assertThat(fixture(s.owner).transaction { counts() }).isEqualTo(before)
        assertThat(save(s, loaded, "0").path("differenceBase").asString()).isEqualTo("-4")
        assertThat(snapshot(s).path("bookBase").asString()).isEqualTo("0")
    }

    @Test fun `changed book state rejects stale snapshot and competing saves apply once`() {
        val s = setup()
        receive(s, "8")
        val stale = snapshot(s)
        receive(s, "2")
        val before = fixture(s.owner).transaction { counts() }
        assertThat(request("POST", "/api/v2/warehouse/counts", s.owner, body(stale, "8")).status).isEqualTo(409)
        assertThat(fixture(s.owner).transaction { counts() }).isEqualTo(before)
        val loaded = snapshot(s)
        val key = UUID.randomUUID().toString()
        val results = Executors.newFixedThreadPool(2).use { pool ->
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val pending = List(2) { pool.submit<org.springframework.mock.web.MockHttpServletResponse> {
                ready.countDown()
                check(start.await(20, TimeUnit.SECONDS))
                request("POST", "/api/v2/warehouse/counts", s.owner, body(loaded, "7"), key)
            } }
            check(ready.await(20, TimeUnit.SECONDS))
            start.countDown()
            pending.map { it.get(30, TimeUnit.SECONDS) }
        }
        assertThat(results.map { it.status }).withFailMessage(results.joinToString("\n") { it.contentAsString }).containsOnly(201)
        assertThat(mapper.readTree(results[0].contentAsString)).isEqualTo(mapper.readTree(results[1].contentAsString))
        assertThat(snapshot(s).path("bookBase").asString()).isEqualTo("7")
    }

    @Test fun `cable variance preserves integer millimetres through loss and recovery splits`() {
        val s = setup("LOT", "MM")
        receive(s, "10000")
        val original = snapshot(s).path("positions")[0].path("dimension").path("stockIdentityId").asString()
        save(s, snapshot(s), "7000")
        assertThat(snapshot(s).path("positions")[0].path("dimension").path("stockIdentityId").asString()).isNotEqualTo(original)
        save(s, snapshot(s), "8000")
        assertThat(snapshot(s).path("bookBase").asString()).isEqualTo("8000")
        save(s, snapshot(s), "10000")
        assertThat(snapshot(s).path("bookBase").asString()).isEqualTo("10000")
    }

    @Test fun `same total serial swap records both directions and recovers only own missing serial`() {
        val s = setup("SERIAL")
        val a = mapOf("serial" to "ONU-A", "mac" to "02:11:22:33:44:55")
        val b = mapOf("serial" to "ONU-B", "mac" to "02:11:22:33:44:66")
        receive(s, "1", listOf(a))
        val loaded = snapshot(s)
        val swap = save(s, loaded, "1", listOf(b))
        assertThat(swap.path("differenceBase").asString()).isEqualTo("0")
        assertThat(swap.path("movementIds")).hasSize(2)
        assertThat(snapshot(s).path("positions")[0].path("serial").asString()).isEqualTo("ONU-B")
        assertThat(request("POST", "/api/v2/warehouse/counts", s.owner, body(loaded, "1", listOf(a))).status).isEqualTo(409)
        val recovered = save(s, snapshot(s), "1", listOf(a))
        assertThat(recovered.path("movementIds")).hasSize(2)
        assertThat(snapshot(s).path("positions")[0].path("serial").asString()).isEqualTo("ONU-A")
        val before = fixture(s.owner).transaction { counts() }
        val current = snapshot(s)
        assertThat(request("POST", "/api/v2/warehouse/counts", s.owner, body(current, "2", listOf(a, a))).status).isEqualTo(409)
        assertThat(request("POST", "/api/v2/warehouse/counts", s.owner, body(current, "1", listOf(a + ("mac" to "02:11:22:33:44:77")))).status).isEqualTo(409)
        assertThat(fixture(s.owner).transaction { counts() }).isEqualTo(before)
        val elsewhere = create("locations", s.owner, """{"code":"ELSE","name":"Lain","kind":"WAREHOUSE"}""").path("id").asString()
        assertThat(request("POST", "/api/v2/warehouse/counts", s.owner, body(snapshot(s, elsewhere), "1", listOf(b))).status).isEqualTo(409)
    }

    @Test fun `stable Admin scope governs counts and delegated manager cannot count`() {
        val s = setup()
        val (admin, adminId) = member(s, "Admin")
        val (manager, _) = member(s, "Manager")
        val (technician, _) = member(s, "Teknisi NE")
        val (delegate, _) = user(s.owner, setOf("warehouse.count.manage"))
        val loaded = snapshot(s, actor = admin)
        assertThat(ok("GET", "/counts/locations?size=1&search=WH", admin).path("items")[0].path("id").asString()).isEqualTo(s.warehouse)
        for (actor in listOf(manager, technician, delegate)) {
            assertThat(request("POST", "/api/v2/warehouse/counts/snapshot", actor,
                mapper.writeValueAsString(mapOf("skuId" to s.sku, "locationId" to s.warehouse))).status).isEqualTo(403)
            assertThat(request("GET", "/api/v2/warehouse/counts/locations", actor).status).isEqualTo(403)
        }
        val key = UUID.randomUUID().toString()
        save(s, loaded, "2", key = key, actor = admin)
        val revoke = request("PUT", "/api/v1/warehouse/settings/scopes/$adminId/" + s.warehouse, s.owner, """{"expectedRevision":1,"active":false}""")
        assertThat(revoke.status).isEqualTo(200)
        assertThat(request("POST", "/api/v2/warehouse/counts", admin, body(loaded, "2"), key).status).isEqualTo(404)
        assertThat(ok("GET", "/counts?size=1", admin).path("totalElements").asLong()).isZero()
        assertThat(ok("GET", "/counts/locations?search=WH", admin).path("totalElements").asLong()).isZero()
    }

    @Test fun `owner counts technician stock and surplus keeps immediate technician custody`() {
        val s = setup()
        val (_, technicianId) = member(s, "Teknisi NE")
        val location = create("locations", s.owner, mapper.writeValueAsString(mapOf("code" to "TECH", "name" to "Teknisi",
            "kind" to "TECHNICIAN", "custodianId" to technicianId))).path("id").asString()
        save(s, snapshot(s, location), "5")
        assertThat(positions(s).single().path("holderKind").asString()).isEqualTo("TECHNICIAN")
        assertThat(positions(s).single().path("status").asString()).isEqualTo("ISSUED")
        save(s, snapshot(s, location), "3")
        save(s, snapshot(s, location), "5")
        assertThat(snapshot(s, location).path("bookBase").asString()).isEqualTo("5")
    }

    @Test fun `automatic technician location follows current shared warehouse scope`() {
        val s = setup()
        val (admin, _) = member(s, "Admin")
        val (technician, technicianId) = member(s, "Teknisi NE")
        receive(s, "3")
        val submitted = ok("POST", "/requests", technician, mapper.writeValueAsString(mapOf("kind" to "RESTOCK",
            "reason" to "Stok teknisi", "lines" to listOf(mapOf("skuId" to s.sku, "baseUnit" to "EA", "requestedBase" to "3")))), status = 201)
        val id = submitted.path("id").asString()
        val line = submitted.path("lines")[0].path("id").asString()
        ok("POST", "/requests/$id/review", admin, """{"expectedRevision":0,"lines":[{"lineId":"$line","approvedBase":"3"}]}""")
        ok("POST", "/requests/$id/decision", s.owner, """{"expectedRevision":1,"approved":true}""")
        val identity = snapshot(s).path("positions")[0].path("dimension").path("stockIdentityId").asString()
        ok("POST", "/requests/$id/handovers", admin, mapper.writeValueAsString(mapOf("expectedRevision" to 2,
            "lineId" to line, "warehouseId" to s.warehouse, "lines" to listOf(mapOf("stockIdentityId" to identity, "quantityBase" to "3")))))
        val location = fixture(s.owner).transaction { scalar("SELECT id FROM inventory_location WHERE tenant_id='$tenant' AND code='TECH-$technicianId' AND area_id IS NULL") }
        val loaded = snapshot(s, location, admin)
        val selected = ok("GET", "/counts/locations?search=Teknisi", admin)
        assertThat(selected.path("items").single().path("id").asString()).isEqualTo(location)
        assertThat(selected.path("items").single().path("technicianName").asString()).isEqualTo("Teknisi NE")
        val key = UUID.randomUUID().toString()
        val count = save(s, loaded, "2", key = key, actor = admin)
        assertThat(snapshot(s, location, admin).path("bookBase").asString()).isEqualTo("2")
        val revoked = request("PUT", "/api/v1/warehouse/settings/scopes/$technicianId/" + s.warehouse, s.owner,
            """{"expectedRevision":1,"active":false}""")
        assertThat(revoked.status).isEqualTo(200)
        assertThat(request("POST", "/api/v2/warehouse/counts/snapshot", admin,
            mapper.writeValueAsString(mapOf("skuId" to s.sku, "locationId" to location))).status).isEqualTo(404)
        assertThat(request("POST", "/api/v2/warehouse/counts", admin, body(loaded, "2"), key).status).isEqualTo(404)
        assertThat(request("GET", "/api/v2/warehouse/counts/" + count.path("id").asString(), admin).status).isEqualTo(404)
        assertThat(ok("GET", "/counts", admin).path("totalElements").asLong()).isZero()
        assertThat(ok("GET", "/counts/locations?search=Teknisi", admin).path("totalElements").asLong()).isZero()
    }

    @Test fun `count location directory scopes before paging and excludes hidden archived and foreign locations`() {
        val s = setup()
        val (admin, _) = member(s, "Admin")
        val (_, technicianId) = member(s, "Teknisi FO")
        create("locations", s.owner, """{"code":"BIN","name":"Rak Gudang","kind":"BIN","parentLocationId":"${s.warehouse}","areaId":"${area(s.owner)}"}""")
        create("locations", s.owner, """{"code":"HIDDEN","name":"Lokasi karantina","kind":"QUARANTINE","issueEligible":false}""")
        val elsewhere = create("locations", s.owner, """{"code":"ELSE","name":"Gudang lain","kind":"WAREHOUSE"}""")
        val technician = create("locations", s.owner, """{"code":"TECH","name":"Teknisi pribadi","kind":"TECHNICIAN","custodianId":"$technicianId"}""")
        val ownerRows = ok("GET", "/counts/locations", s.owner).path("items")
        assertThat(ownerRows.asSequence().map { it.path("id").asString() }.toList()).contains(elsewhere.path("id").asString(), technician.path("id").asString())
        assertThat(ownerRows.asSequence().map { it.path("kind").asString() }.toList()).doesNotContain("QUARANTINE")
        val first = ok("GET", "/counts/locations?size=1", admin)
        val second = ok("GET", "/counts/locations?size=1&page=1", admin)
        assertThat(first.path("totalElements").asLong()).isEqualTo(2)
        assertThat(first.path("items")[0].path("code").asString()).isEqualTo("BIN")
        assertThat(second.path("items")[0].path("id").asString()).isEqualTo(s.warehouse)
        assertThat(ok("GET", "/counts/locations?size=1&page=2", admin).path("items").isEmpty).isTrue()
        val archived = request("POST", "/api/v2/warehouse/locations/" + elsewhere.path("id").asString() + "/archive", s.owner, """{"expectedRevision":0}""")
        assertThat(archived.status).withFailMessage(archived.contentAsString).isEqualTo(200)
        assertThat(ok("GET", "/counts/locations?search=ELSE", s.owner).path("totalElements").asLong()).isZero()
        val foreign = setup()
        assertThat(ok("GET", "/counts/locations?search=" + s.warehouse, foreign.owner).path("items").isEmpty).isTrue()
        for (query in listOf("size=0", "page=-1", "size=101", "search=" + "x".repeat(201)))
            assertThat(request("GET", "/api/v2/warehouse/counts/locations?$query", s.owner).status).isEqualTo(400)
    }

    @Test fun `same snapshot cannot create a second unchanged count`() {
        val s = setup()
        val loaded = snapshot(s)
        save(s, loaded, "0")
        val before = fixture(s.owner).transaction { counts() }
        assertThat(request("POST", "/api/v2/warehouse/counts", s.owner, body(loaded, "0")).status).isEqualTo(409)
        assertThat(ok("GET", "/counts", s.owner).path("totalElements").asLong()).isEqualTo(1)
        assertThat(fixture(s.owner).transaction { counts() }).isEqualTo(before)
    }

    @Test fun `database rejects forged physical MAC and audit with no stock changes`() {
        val s = setup("SERIAL")
        val serial = mapOf("serial" to "COUNT-DB", "mac" to "02:00:11:22:33:44")
        receive(s, "1", listOf(serial))
        val loaded = snapshot(s)
        fun forge(mac: String, difference: String) = fixture(s.owner).transaction {
            val id = UUID.randomUUID()
            val input = mapper.readTree(body(loaded, "1", listOf(serial + ("mac" to mac))))
            val canonical = mapper.writeValueAsString(mapOf("id" to null, "input" to input)).replace("'", "''")
            sql("""INSERT INTO inventory_reference_count_attempt(id,tenant_id,snapshot_id,operation_key,actor_id,authority_epoch,cutover_epoch,canonical_payload,payload_hash)
                SELECT '$id',tenant_id,id,'$id',actor_id,authority_epoch,cutover_epoch,'$canonical',encode(sha256(convert_to('$canonical','UTF8')),'hex')
                FROM inventory_reference_count_snapshot WHERE tenant_id='$tenant' AND id='${loaded.path("id").asString()}'""")
            sql("""INSERT INTO inventory_reference_count(id,tenant_id,snapshot_id,operation_key,actor_id,authority_epoch,cutover_epoch,canonical_payload,payload_hash,snapshot,recorded_at)
                SELECT attempt.id,attempt.tenant_id,attempt.snapshot_id,attempt.operation_key,attempt.actor_id,attempt.authority_epoch,attempt.cutover_epoch,attempt.canonical_payload,attempt.payload_hash,
                jsonb_build_object('id',attempt.id,'snapshot',loaded.snapshot,'physicalBase','1','differenceBase','$difference',
                    'serials',attempt.canonical_payload::jsonb#>'{input,serials}','reason',attempt.canonical_payload::jsonb#>>'{input,reason}',
                    'actorId',attempt.actor_id,'actorName','Admin','recordedAt',loaded.loaded_at,'movementIds','[]'::jsonb),loaded.loaded_at
                FROM inventory_reference_count_attempt attempt JOIN inventory_reference_count_snapshot loaded ON loaded.tenant_id=attempt.tenant_id AND loaded.id=attempt.snapshot_id
                WHERE attempt.tenant_id='$tenant' AND attempt.id='$id'""")
        }
        assertThatThrownBy { forge("02:00:11:22:33:55", "0") }.hasMessageContaining("count physical serial or MAC differs from owned identity")
        assertThatThrownBy { forge(serial.getValue("mac"), "1") }.hasMessageContaining("count audit differs from immutable attempt")
        assertThat(snapshot(s).path("bookBase").asString()).isEqualTo("1")
        assertThat(ok("GET", "/counts", s.owner).path("totalElements").asLong()).isZero()
    }
}
