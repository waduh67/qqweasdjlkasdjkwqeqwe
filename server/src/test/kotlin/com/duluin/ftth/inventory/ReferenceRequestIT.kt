package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.duluin.ftth.inventory.application.service.referenceTimestamp
import com.duluin.ftth.inventory.adapter.outbound.persistence.PostingSql

class ReferenceRequestIT : WarehouseMasterHttpFixture() {
    @Test fun `snapshot timestamp and JDBC projection agree at rounding boundaries`() {
        val fixture = fixture(tenant())
        fixture.transaction {
            jdbc { connection ->
                val sql = PostingSql(connection, fixture.tenant)
                for (nanos in listOf(835998499, 835998500, 835998501, 835999500, 999999500)) {
                    val stamp = referenceTimestamp(java.time.Instant.ofEpochSecond(1791468590, nanos.toLong()))
                    assertThat(sql.value("SELECT ?::timestamptz IS NOT DISTINCT FROM ?::timestamptz", stamp, stamp.toString()))
                        .describedAs("JSON and JDBC timestamp at nanos=%s", nanos).isEqualTo("t")
                }
            }
        }
    }

    private data class Setup(val owner: String, val warehouse: String, val sku: String, val manager: String,
        val admin: String, val technician: String, val technicianId: String, val other: String)

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
            val scope = request("PUT", "/api/v1/warehouse/settings/scopes/$id/$warehouse", owner,
                """{"expectedRevision":0,"active":true}""")
            assertThat(scope.status).withFailMessage(scope.contentAsString).isEqualTo(200)
            return login(slug, email) to id
        }
        val (admin, _) = member("Admin")
        val (manager, _) = member("Manager")
        val (technician, technicianId) = member("Teknisi NE")
        val (other, _) = member("Teknisi FO")
        ok("POST", "/workflow/drain", owner, """{"expectedEpoch":0}""")
        val review = ok("GET", "/workflow/review", owner)
        ok("POST", "/workflow/activate", owner,
            """{"expectedEpoch":1,"reviewHash":"${review.path("reviewHash").asString()}","reason":"Alur gudang"}""")
        return Setup(owner, warehouse, sku, manager, admin, technician, technicianId, other)
    }

    private fun submit(setup: Setup, token: String = setup.technician, warehouse: Boolean = false,
        key: String = UUID.randomUUID().toString()): JsonNode = ok("POST", "/requests", token, mapper.writeValueAsString(mapOf(
        "kind" to if (warehouse) "PROCUREMENT" else "RESTOCK", "reason" to "Persediaan kerja",
        "warehouseId" to if (warehouse) setup.warehouse else null,
        "lines" to listOf(mapOf("skuId" to setup.sku, "baseUnit" to "EA", "requestedBase" to "9")))), key, 201)

    @Test fun `technician restock quantity review manager approval and retry never move stock`() {
        val setup = setup()
        val before = fixture(setup.owner).transaction { counts() }
        val key = UUID.randomUUID().toString()
        val submitted = submit(setup, key = key)
        assertThat(submit(setup, key = key)).isEqualTo(submitted)
        assertThat(submitted.path("technicianId").asString()).isEqualTo(setup.technicianId)
        assertThat(submitted.path("requiresManagerApproval").asBoolean()).isTrue()
        val id = submitted.path("id").asString()
        val body = """{"expectedRevision":0,"lines":[{"lineId":"${submitted.path("lines")[0].path("id").asString()}","approvedBase":"6"}],"notes":"Kurangi sesuai kebutuhan"}"""
        assertThat(request("POST", "/api/v2/warehouse/requests/$id/review", setup.technician, body).status).isEqualTo(403)
        assertThat(request("POST", "/api/v2/warehouse/requests/$id/review", setup.manager, body).status).isEqualTo(403)
        val reviewed = ok("POST", "/requests/$id/review", setup.admin, body)
        assertThat(reviewed.path("state").asString()).isEqualTo("MANAGER_REVIEW")
        assertThat(reviewed.path("lines")[0].path("requestedBase").asString()).isEqualTo("9")
        assertThat(reviewed.path("lines")[0].path("approvedBase").asString()).isEqualTo("6")
        val decision = """{"expectedRevision":1,"approved":true}"""
        assertThat(request("POST", "/api/v2/warehouse/requests/$id/decision", setup.admin, decision).status).isEqualTo(403)
        val decisionKey = UUID.randomUUID().toString()
        val approved = ok("POST", "/requests/$id/decision", setup.manager, decision, decisionKey)
        assertThat(approved.path("state").asString()).isEqualTo("APPROVED")
        assertThat(ok("POST", "/requests/$id/decision", setup.manager, decision, decisionKey)).isEqualTo(approved)
        assertThat(request("POST", "/api/v2/warehouse/requests/$id/decision", setup.manager,
            decision.replace("true", "false"), decisionKey).status).isEqualTo(409)
        assertThat(fixture(setup.owner).transaction { counts() }).isEqualTo(before)
        val detail = ok("GET", "/requests/$id", setup.technician)
        assertThat(detail.path("timeline")).hasSize(3)
        assertThat(detail.path("timeline").asSequence().map { it.path("actorName").asString() }.toList()).containsExactly("Teknisi NE", "Admin", "Manager")
    }

    @Test fun `request stock preview includes scoped zero warehouses and destination holdings without granting general stock access`() {
        val setup = setup()
        val empty = create("locations", setup.owner, """{"code":"EMPTY","name":"Gudang kosong","kind":"WAREHOUSE","issueEligible":true}""").path("id").asString()
        val hidden = create("locations", setup.owner, """{"code":"HIDDEN","name":"Gudang tersembunyi","kind":"WAREHOUSE","issueEligible":true}""").path("id").asString()
        val grant = request("PUT", "/api/v1/warehouse/settings/scopes/${setup.technicianId}/$empty", setup.owner,
            """{"expectedRevision":0,"active":true}""")
        assertThat(grant.status).withFailMessage(grant.contentAsString).isEqualTo(200)
        ok("POST", "/receipts", setup.admin, """{"warehouseId":"${setup.warehouse}","lines":[{"skuId":"${setup.sku}","quantityBase":"12"}]}""", status = 201)
        ok("POST", "/receipts", setup.owner, """{"warehouseId":"$hidden","lines":[{"skuId":"${setup.sku}","quantityBase":"99"}]}""", status = 201)
        val view = approved(setup, "RESTOCK", quantity = "9")
        val id = view.path("id").asString()
        val identity = stock(setup).path("positions").single { it.path("locationId").asString() == setup.warehouse }.path("stockIdentityId").asString()
        ok("POST", "/requests/$id/handovers", setup.admin, handoverBody(setup, view, identity, "3"))
        val path = "/requests/stock-preview?skuId=${setup.sku}"
        val own = ok("GET", "$path&size=1", setup.technician)
        assertThat(own.path("totalWarehouseBase").asString()).isEqualTo("9")
        assertThat(own.path("technicianId").asString()).isEqualTo(setup.technicianId)
        assertThat(own.path("technicianQuantityBase").asString()).isEqualTo("3")
        assertThat(own.path("warehouses").path("totalElements").asLong()).isEqualTo(2)
        val second = ok("GET", "$path&size=1&page=1", setup.technician)
        assertThat(second.path("warehouses").path("items")[0].path("quantityBase").asString()).isEqualTo("0")
        assertThat(second.path("warehouses").path("items")[0].path("warehouseId").asString()).isEqualTo(empty)
        assertThat(request("GET", "/api/v2/warehouse/stock/${setup.sku}", setup.technician).status).isEqualTo(403)
        assertThat(request("GET", "/api/v2/warehouse$path&technicianId=${UUID.randomUUID()}", setup.technician).status).isEqualTo(403)
        assertThat(request("GET", "/api/v2/warehouse$path&warehouseId=${setup.warehouse}", setup.technician).status).isEqualTo(403)
        assertThat(request("GET", "/api/v2/warehouse$path&requestId=$id", setup.other).status).isEqualTo(404)
        val reviewed = ok("GET", "$path&requestId=$id", setup.admin)
        assertThat(reviewed.path("technicianName").asString()).isEqualTo("Teknisi NE")
        assertThat(reviewed.path("technicianQuantityBase").asString()).isEqualTo("3")
        val destination = ok("GET", "$path&warehouseId=${setup.warehouse}", setup.admin)
        assertThat(destination.path("totalWarehouseBase").asString()).isEqualTo("9")
        assertThat(destination.path("technicianId").isNull).isTrue()
        assertThat(destination.path("warehouses").path("totalElements").asLong()).isEqualTo(1)
        assertThat(request("GET", "/api/v2/warehouse$path&warehouseId=$hidden", setup.admin).status).isEqualTo(404)
        val revoke = request("PUT", "/api/v1/warehouse/settings/scopes/${setup.technicianId}/${setup.warehouse}", setup.owner,
            """{"expectedRevision":1,"active":false}""")
        assertThat(revoke.status).withFailMessage(revoke.contentAsString).isEqualTo(200)
        assertThat(ok("GET", path, setup.technician).path("totalWarehouseBase").asString()).isEqualTo("0")
        val foreign = this.setup()
        assertThat(request("GET", "/api/v2/warehouse$path", foreign.technician).status).isEqualTo(404)
    }

    @Test fun `admin rejection retains its original permission during replay and requires a reason`() {
        val setup = setup()
        val submitted = submit(setup)
        val id = submitted.path("id").asString()
        assertThat(request("POST", "/api/v2/warehouse/requests/$id/decision", setup.admin,
            """{"expectedRevision":0,"approved":false}""").status).isEqualTo(400)
        val body = """{"expectedRevision":0,"approved":false,"reason":"Masih tersedia di lapangan"}"""
        val key = UUID.randomUUID().toString()
        val rejected = ok("POST", "/requests/$id/decision", setup.admin, body, key)
        assertThat(rejected.path("state").asString()).isEqualTo("REJECTED")
        assertThat(ok("POST", "/requests/$id/decision", setup.admin, body, key)).isEqualTo(rejected)
        assertThat(ok("GET", "/requests/$id", setup.admin).path("timeline")).hasSize(2)
    }

    @Test fun `owner settings affect new requests while submitted decisions retain original policy`() {
        val setup = setup()
        val settings = ok("GET", "/settings", setup.owner)
        assertThat(settings.path("overdueDays").asInt()).isEqualTo(3)
        assertThat(request("GET", "/api/v2/warehouse/settings", setup.admin).status).isEqualTo(403)
        val old = submit(setup)
        val input = """{"expectedRevision":0,"requireManagerApproval":false,"overdueDays":5}"""
        assertThat(request("PUT", "/api/v2/warehouse/settings", setup.manager, input).status).isEqualTo(403)
        val key = UUID.randomUUID().toString()
        val changed = ok("PUT", "/settings", setup.owner, input, key)
        assertThat(ok("PUT", "/settings", setup.owner, input, key)).isEqualTo(changed)
        assertThat(request("PUT", "/api/v2/warehouse/settings", setup.owner, input).status).isEqualTo(409)
        val fresh = submit(setup, token = setup.admin, warehouse = true)
        assertThat(fresh.path("policyRevision").asLong()).isEqualTo(1)
        fun review(view: JsonNode): JsonNode = ok("POST", "/requests/${view.path("id").asString()}/review", setup.admin,
            """{"expectedRevision":0,"lines":[{"lineId":"${view.path("lines")[0].path("id").asString()}","approvedBase":"9"}]}""")
        assertThat(review(old).path("state").asString()).isEqualTo("MANAGER_REVIEW")
        assertThat(review(fresh).path("state").asString()).isEqualTo("APPROVED")
        assertThat(request("PUT", "/api/v2/warehouse/settings", setup.owner, input.replace("5", "366")).status).isEqualTo(400)
    }

    @Test fun `technicians see only own requests and warehouse scopes filter before pagination`() {
        val setup = setup()
        val own = submit(setup)
        submit(setup, token = setup.other)
        submit(setup, token = setup.admin, warehouse = true)
        val hiddenWarehouse = create("locations", setup.owner, """{"code":"HIDDEN","name":"Gudang lain","kind":"WAREHOUSE","issueEligible":true}""").path("id").asString()
        ok("POST", "/requests", setup.owner, """{"kind":"PROCUREMENT","reason":"Tersembunyi","warehouseId":"$hiddenWarehouse","lines":[{"skuId":"${setup.sku}","baseUnit":"EA","requestedBase":"1"}]}""", status = 201)
        assertThat(ok("GET", "/requests?size=1", setup.technician).path("totalElements").asLong()).isEqualTo(1)
        assertThat(ok("GET", "/requests?size=1", setup.technician).path("items")[0].path("id")).isEqualTo(own.path("id"))
        assertThat(request("GET", "/api/v2/warehouse/requests/${own.path("id").asString()}", setup.other).status).isEqualTo(404)
        assertThat(ok("GET", "/requests?size=1", setup.admin).path("totalElements").asLong()).isEqualTo(3)
        assertThat(ok("GET", "/requests?page=2&size=1", setup.admin).path("items")).hasSize(1)
        assertThat(ok("GET", "/requests?page=3&size=1", setup.admin).path("items").isEmpty).isTrue()
        assertThat(ok("GET", "/requests?search=Tersembunyi", setup.admin).path("totalElements").asLong()).isZero()
        assertThat(request("POST", "/api/v2/warehouse/requests", setup.technician,
            """{"kind":"PROCUREMENT","reason":"Tidak boleh","warehouseId":"${setup.warehouse}","lines":[]}""").status).isEqualTo(403)
    }

    @Test fun `proposed procurement maps the same unit and review rejects zero or excessive approval`() {
        val setup = setup()
        val submitted = ok("POST", "/requests", setup.technician,
            """{"kind":"PROCUREMENT","reason":"Barang baru","lines":[{"proposedName":"Konektor baru","baseUnit":"EA","requestedBase":"9"}]}""", status = 201)
        val id = submitted.path("id").asString()
        val line = submitted.path("lines")[0].path("id").asString()
        for (quantity in listOf("0", "10")) {
            assertThat(request("POST", "/api/v2/warehouse/requests/$id/review", setup.admin,
                """{"expectedRevision":0,"lines":[{"lineId":"$line","approvedBase":"$quantity","skuId":"${setup.sku}"}]}""").status).isEqualTo(400)
        }
        assertThat(request("POST", "/api/v2/warehouse/requests/$id/review", setup.admin,
            """{"expectedRevision":0,"lines":[{"lineId":"$line","approvedBase":"9"}]}""").status).isEqualTo(400)
        val wrong = ok("POST", "/skus", setup.owner, """{"code":"CABLE","name":"Kabel","tracking":"LOT","baseUnit":"MM"}""", status = 201).path("id").asString()
        assertThat(request("POST", "/api/v2/warehouse/requests/$id/review", setup.admin,
            """{"expectedRevision":0,"lines":[{"lineId":"$line","approvedBase":"9","skuId":"$wrong"}]}""").status).isEqualTo(400)
        val reviewed = ok("POST", "/requests/$id/review", setup.admin,
            """{"expectedRevision":0,"lines":[{"lineId":"$line","approvedBase":"9","skuId":"${setup.sku}"}]}""")
        assertThat(reviewed.path("lines")[0].path("name").asString()).isEqualTo("Konektor")
        assertThat(reviewed.path("lines")[0].path("proposedName").asString()).isEqualTo("Konektor baru")
    }

    @Test fun `database rejects changed projections absent commands and immutable command rewrites`() {
        val setup = setup()
        val submitted = submit(setup)
        val id = submitted.path("id").asString()
        assertThatThrownBy { fixture(setup.owner).transaction {
            sql("UPDATE inventory_reference_request SET revision=revision+1,snapshot=jsonb_set(snapshot,'{revision}','1') WHERE tenant_id='$tenant' AND id='$id'")
        } }.hasMessageContaining("reference request projection differs from command history")
        assertThatThrownBy { fixture(setup.owner).transaction {
            sql("UPDATE inventory_reference_command SET notes='Changed' WHERE tenant_id='$tenant' AND resource_id='$id'")
        } }.hasMessageContaining("append-only")
        assertThat(ok("GET", "/requests/$id", setup.owner).path("request").path("revision").asLong()).isZero()
    }

    private fun approved(setup: Setup, kind: String, sku: String = setup.sku, unit: String = "EA", quantity: String = "9", warehouse: Boolean = false): JsonNode {
        val submitted = ok("POST", "/requests", if (warehouse) setup.admin else setup.technician, mapper.writeValueAsString(mapOf(
            "kind" to kind, "reason" to "Persediaan lapangan", "warehouseId" to if (warehouse) setup.warehouse else null,
            "lines" to listOf(mapOf("skuId" to sku, "baseUnit" to unit, "requestedBase" to quantity)))), status = 201)
        val id = submitted.path("id").asString()
        ok("POST", "/requests/$id/review", setup.admin,
            """{"expectedRevision":0,"lines":[{"lineId":"${submitted.path("lines")[0].path("id").asString()}","approvedBase":"$quantity"}]}""")
        return ok("POST", "/requests/$id/decision", setup.manager, """{"expectedRevision":1,"approved":true}""")
    }

    private fun receiveBody(setup: Setup, view: JsonNode, quantity: String) = mapper.writeValueAsString(mapOf(
        "expectedRevision" to view.path("revision").asLong(), "lineId" to view.path("lines")[0].path("id").asString(),
        "warehouseId" to setup.warehouse, "quantityBase" to quantity, "notes" to "Pembelian diterima"))

    private fun handoverBody(setup: Setup, view: JsonNode, identity: String, quantity: String) = mapper.writeValueAsString(mapOf(
        "expectedRevision" to view.path("revision").asLong(), "lineId" to view.path("lines")[0].path("id").asString(),
        "warehouseId" to setup.warehouse, "lines" to listOf(mapOf("stockIdentityId" to identity, "quantityBase" to quantity)), "notes" to "Material diserahkan"))

    private fun stock(setup: Setup, sku: String = setup.sku) = ok("GET", "/stock/$sku", setup.owner)

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

    @Test fun `concurrent receipt retries post once and competing revisions preserve approved limit`() {
        val setup = setup()
        var view = approved(setup, "PROCUREMENT")
        val id = view.path("id").asString()
        val body = receiveBody(setup, view, "4")
        val key = UUID.randomUUID().toString()
        val retry = race(List(2) { { request("POST", "/api/v2/warehouse/requests/$id/receipts", setup.admin, body, key) } })
        assertThat(retry.map { it.status }).withFailMessage(retry.joinToString("\n") { it.contentAsString }).containsOnly(200)
        assertThat(mapper.readTree(retry[0].contentAsString)).isEqualTo(mapper.readTree(retry[1].contentAsString))
        view = mapper.readTree(retry[0].contentAsString)
        val remaining = receiveBody(setup, view, "5")
        val competing = race(List(2) { { request("POST", "/api/v2/warehouse/requests/$id/receipts", setup.admin, remaining) } })
        assertThat(competing.map { it.status }).withFailMessage(competing.joinToString("\n") { it.contentAsString }).containsExactlyInAnyOrder(200, 409)
        assertThat(ok("GET", "/requests/$id", setup.owner).path("request").path("lines")[0].path("receivedBase").asString()).isEqualTo("9")
        assertThat(stock(setup).path("positions").sumOf { it.path("quantityBase").asString().toLong() }).isEqualTo(9)
        fixture(setup.owner).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_reference_command WHERE tenant_id='$tenant' AND resource_id='$id' AND action='RECEIVE'")).isEqualTo("2")
        }
    }

    @Test fun `competing restock handovers cannot spend the same stock twice and revoked scope prevents replay`() {
        val setup = setup()
        ok("POST", "/receipts", setup.admin, """{"warehouseId":"${setup.warehouse}","lines":[{"skuId":"${setup.sku}","quantityBase":"9"}]}""", status = 201)
        val identity = stock(setup).path("positions").single().path("stockIdentityId").asString()
        val views = List(2) { approved(setup, "RESTOCK", quantity = "6") }
        val keys = List(2) { UUID.randomUUID().toString() }
        val results = race(views.mapIndexed { index, view -> { request("POST", "/api/v2/warehouse/requests/${view.path("id").asString()}/handovers",
            setup.admin, handoverBody(setup, view, identity, "6"), keys[index]) } })
        assertThat(results.map { it.status }).withFailMessage(results.joinToString("\n") { it.contentAsString }).containsExactlyInAnyOrder(200, 409)
        val positions = stock(setup).path("positions")
        assertThat(positions.single { it.path("holderKind").asString() == "WAREHOUSE" }.path("quantityBase").asString()).isEqualTo("3")
        assertThat(positions.single { it.path("holderKind").asString() == "TECHNICIAN" }.path("quantityBase").asString()).isEqualTo("6")
        val winner = results.indexOfFirst { it.status == 200 }
        val adminId = mapper.readTree(request("GET", "/api/me", setup.admin).contentAsString).path("id").asString()
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/$adminId/${setup.warehouse}", setup.owner,
            """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)
        val view = views[winner]
        assertThat(request("POST", "/api/v2/warehouse/requests/${view.path("id").asString()}/handovers",
            setup.admin, handoverBody(setup, view, identity, "6"), keys[winner]).status).isEqualTo(404)
        assertThat(ok("GET", "/requests/${views[1 - winner].path("id").asString()}", setup.owner).path("request").path("state").asString()).isEqualTo("APPROVED")
    }

    @Test fun `partial procurement receipts and immediate technician handover conserve counters and replay`() {
        val setup = setup()
        var view = approved(setup, "PROCUREMENT")
        val id = view.path("id").asString()
        val first = receiveBody(setup, view, "4")
        assertThat(request("POST", "/api/v2/warehouse/requests/$id/receipts", setup.manager, first).status).isEqualTo(403)
        val receiptKey = UUID.randomUUID().toString()
        view = ok("POST", "/requests/$id/receipts", setup.admin, first, receiptKey)
        assertThat(ok("POST", "/requests/$id/receipts", setup.admin, first, receiptKey)).isEqualTo(view)
        assertThat(view.path("state").asString()).isEqualTo("PARTIALLY_RECEIVED")
        assertThat(view.path("lines")[0].path("receivedBase").asString()).isEqualTo("4")
        assertThat(view.path("lines")[0].path("fulfilledBase").asString()).isEqualTo("0")
        var positions = stock(setup).path("positions")
        val identity = positions.single().path("stockIdentityId").asString()
        val before = fixture(setup.owner).transaction { counts() }
        assertThat(request("POST", "/api/v2/warehouse/requests/$id/handovers", setup.admin, handoverBody(setup, view, identity, "5")).status).isEqualTo(400)
        assertThat(request("POST", "/api/v2/warehouse/requests/$id/receipts", setup.admin, receiveBody(setup, view, "6")).status).isEqualTo(400)
        assertThat(fixture(setup.owner).transaction { counts() }).isEqualTo(before)
        val handover = handoverBody(setup, view, identity, "3")
        val handoverKey = UUID.randomUUID().toString()
        view = ok("POST", "/requests/$id/handovers", setup.admin, handover, handoverKey)
        assertThat(ok("POST", "/requests/$id/handovers", setup.admin, handover, handoverKey)).isEqualTo(view)
        assertThat(view.path("state").asString()).isEqualTo("PARTIALLY_FULFILLED")
        positions = stock(setup).path("positions")
        assertThat(positions.single { it.path("holderKind").asString() == "TECHNICIAN" }.path("quantityBase").asString()).isEqualTo("3")
        assertThat(positions.single { it.path("holderKind").asString() == "WAREHOUSE" }.path("quantityBase").asString()).isEqualTo("1")
        view = ok("POST", "/requests/$id/receipts", setup.admin, receiveBody(setup, view, "5"))
        view = ok("POST", "/requests/$id/handovers", setup.admin, handoverBody(setup, view, identity, "1"))
        val lastIdentity = stock(setup).path("positions").single { it.path("holderKind").asString() == "WAREHOUSE" }.path("stockIdentityId").asString()
        view = ok("POST", "/requests/$id/handovers", setup.admin, handoverBody(setup, view, lastIdentity, "5"))
        assertThat(view.path("state").asString()).isEqualTo("FULFILLED")
        assertThat(view.path("lines")[0].path("receivedBase").asString()).isEqualTo("9")
        assertThat(view.path("lines")[0].path("fulfilledBase").asString()).isEqualTo("9")
        assertThat(stock(setup).path("positions").all { it.path("holderId").asString() == setup.technicianId }).isTrue()
        assertThat(ok("GET", "/requests/$id", setup.technician).path("timeline")).hasSize(8)
        assertThat(request("POST", "/api/v2/warehouse/requests/$id/handovers", setup.admin, handoverBody(setup, view, lastIdentity, "1")).status).isEqualTo(400)
    }

    @Test fun `warehouse procurement receives directly to destination and fulfills without technician action`() {
        val setup = setup()
        var view = approved(setup, "PROCUREMENT", warehouse = true)
        val id = view.path("id").asString()
        val other = create("locations", setup.owner, """{"code":"OTHER","name":"Lain","kind":"WAREHOUSE","issueEligible":true}""").path("id").asString()
        assertThat(request("POST", "/api/v2/warehouse/requests/$id/receipts", setup.owner, receiveBody(setup, view, "2").replace(setup.warehouse, other)).status).isEqualTo(400)
        view = ok("POST", "/requests/$id/receipts", setup.admin, receiveBody(setup, view, "2"))
        assertThat(view.path("state").asString()).isEqualTo("PARTIALLY_RECEIVED")
        assertThat(view.path("lines")[0].path("fulfilledBase").asString()).isEqualTo("2")
        view = ok("POST", "/requests/$id/receipts", setup.admin, receiveBody(setup, view, "7"))
        assertThat(view.path("state").asString()).isEqualTo("FULFILLED")
        assertThat(stock(setup).path("positions").all { it.path("holderKind").asString() == "WAREHOUSE" }).isTrue()
        assertThat(stock(setup).path("positions").asSequence().sumOf { it.path("quantityBase").asString().toLong() }).isEqualTo(9)
    }

    @Test fun `technician material directory contains only own issued stock with scoped catalog and workflow reads`() {
        val setup = setup()
        val submitted = approved(setup, "RESTOCK", quantity = "3")
        ok("POST", "/receipts", setup.admin, """{"warehouseId":"${setup.warehouse}","lines":[{"skuId":"${setup.sku}","quantityBase":"7"}]}""", status = 201)
        val identity = stock(setup, setup.sku).path("positions").single().path("stockIdentityId").asString()
        ok("POST", "/requests/${submitted.path("id").asString()}/handovers", setup.admin, handoverBody(setup, submitted, identity, "3"))
        val own = ok("GET", "/my-materials?size=1", setup.technician)
        assertThat(own.path("totalElements").asLong()).isEqualTo(1)
        val position = own.path("items").single()
        assertThat(position.path("holderId").asString()).isEqualTo(setup.technicianId)
        assertThat(position.path("quantityBase").asString()).isEqualTo("3")
        assertThat(position.path("status").asString()).isEqualTo("ISSUED")
        assertThat(ok("GET", "/my-materials", setup.other).path("totalElements").asLong()).isZero()
        assertThat(ok("GET", "/my-materials?page=1&size=1", setup.technician).path("items")).isEmpty()
        assertThat(ok("GET", "/my-materials?search=Konektor", setup.technician).path("items")).hasSize(1)
        assertThat(ok("GET", "/my-materials?search=absent", setup.technician).path("items")).isEmpty()
        assertThat(ok("GET", "/skus/${setup.sku}", setup.technician).path("id").asString()).isEqualTo(setup.sku)
        assertThat(ok("GET", "/locations", setup.technician).path("items").single().path("id").asString()).isEqualTo(setup.warehouse)
        assertThat(request("GET", "/api/v2/warehouse/suppliers", setup.technician).status).isEqualTo(403)
        assertThat(request("GET", "/api/v2/warehouse/stock/${setup.sku}", setup.technician).status).isEqualTo(403)
        assertThat(request("GET", "/api/v2/warehouse/my-materials?size=0", setup.technician).status).isEqualTo(400)
        val workflow = ok("GET", "/workflow", setup.technician)
        assertThat(workflow.path("snapshot").path("workflow").asString()).isEqualTo("REFERENCE")
        assertThat(workflow.path("owner").asBoolean()).isFalse()
        assertThat(ok("GET", "/workflow", setup.owner).path("owner").asBoolean()).isTrue()
        assertThat(request("GET", "/api/v2/warehouse/workflow/review", setup.technician).status).isEqualTo(403)
    }

    @Test fun `restock handover cuts cable with retained remnant and serial ownership changes immediately`() {
        val setup = setup()
        val cable = ok("POST", "/skus", setup.owner, """{"code":"CABLE","name":"Kabel","tracking":"LOT","baseUnit":"MM"}""", status = 201).path("id").asString()
        val onu = ok("POST", "/skus", setup.owner, """{"code":"ONU","name":"ONU","tracking":"SERIAL","baseUnit":"EA"}""", status = 201).path("id").asString()
        ok("POST", "/receipts", setup.admin, """{"warehouseId":"${setup.warehouse}","lines":[{"skuId":"$cable","quantityBase":"10000"},{"skuId":"$onu","quantityBase":"1","serials":[{"serial":"ONU-REFERENCE","mac":"02:11:22:33:44:55"}]}]}""", status = 201)
        var view = approved(setup, "RESTOCK", cable, "MM", "3000")
        val cableId = view.path("id").asString()
        val original = stock(setup, cable).path("positions").single().path("stockIdentityId").asString()
        assertThat(request("POST", "/api/v2/warehouse/requests/$cableId/receipts", setup.admin, receiveBody(setup, view, "1")).status).isEqualTo(400)
        view = ok("POST", "/requests/$cableId/handovers", setup.admin, handoverBody(setup, view, original, "3000"))
        assertThat(view.path("state").asString()).isEqualTo("FULFILLED")
        val cableStock = stock(setup, cable).path("positions")
        assertThat(cableStock).hasSize(2)
        assertThat(cableStock.single { it.path("holderKind").asString() == "TECHNICIAN" }.path("quantityBase").asString()).isEqualTo("3000")
        assertThat(cableStock.single { it.path("holderKind").asString() == "WAREHOUSE" }.path("quantityBase").asString()).isEqualTo("7000")
        assertThat(cableStock.none { it.path("stockIdentityId").asString() == original }).isTrue()
        view = approved(setup, "RESTOCK", onu, quantity = "1")
        val onuId = view.path("id").asString()
        val identity = stock(setup, onu).path("positions").single().path("stockIdentityId").asString()
        ok("POST", "/requests/$onuId/handovers", setup.admin, handoverBody(setup, view, identity, "1"))
        val owned = stock(setup, onu).path("positions").single()
        assertThat(owned.path("holderId").asString()).isEqualTo(setup.technicianId)
        assertThat(owned.path("status").asString()).isEqualTo("ISSUED")
        assertThat(owned.path("serial").asString()).isEqualTo("ONU-REFERENCE")
        assertThat(fixture(setup.owner).transaction { scalar("SELECT custody_owner_id FROM inventory_serialized_asset WHERE tenant_id='$tenant' AND id='$identity'") }).isEqualTo(setup.technicianId)
    }
}
