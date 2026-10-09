package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.util.UUID

class ReferenceWarehouseIT : WarehouseMasterHttpFixture() {
    private data class Setup(val token: String, val warehouse: String, val destination: String, val cable: String, val bulk: String, val onu: String)

    private fun setup(): Setup {
        val token = tenant()
        fun master(resource: String, body: String) = create(resource, token, body).path("id").asString()
        val warehouse = master("locations", """{"code":"WH","name":"Gudang A","kind":"WAREHOUSE","issueEligible":true}""")
        val destination = master("locations", """{"code":"DEST","name":"Gudang B","kind":"WAREHOUSE","issueEligible":true}""")
        val cable = master("skus", """{"code":"CABLE","name":"Kabel","tracking":"LOT","baseUnit":"MM"}""")
        val bulk = master("skus", """{"code":"CONNECTOR","name":"Konektor","tracking":"BULK","baseUnit":"EA"}""")
        val onu = master("skus", """{"code":"ONU","name":"ONU","tracking":"SERIAL","baseUnit":"EA"}""")
        return Setup(token, warehouse, destination, cable, bulk, onu)
    }

    private fun ok(method: String, path: String, token: String, body: String? = null, key: String = UUID.randomUUID().toString(), status: Int = 200): JsonNode {
        val response = request(method, "/api/v2/warehouse$path", token, body, key)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(status)
        return mapper.readTree(response.contentAsString)
    }

    private fun activate(token: String): Pair<String, String> {
        ok("POST", "/workflow/drain", token, """{"expectedEpoch":0}""")
        val review = ok("GET", "/workflow/review", token)
        assertThat(review.path("issues").isEmpty).isTrue()
        val body = """{"expectedEpoch":1,"reviewHash":"${review.path("reviewHash").asString()}","reason":"Alur gudang sederhana"}"""
        val key = UUID.randomUUID().toString()
        val activated = ok("POST", "/workflow/activate", token, body, key)
        assertThat(activated.path("epoch").asLong()).isEqualTo(2)
        assertThat(activated.path("workflow").asString()).isEqualTo("REFERENCE")
        return body to key
    }

    private fun receipt(setup: Setup, lines: String, key: String = UUID.randomUUID().toString()) =
        ok("POST", "/receipts", setup.token, """{"warehouseId":"${setup.warehouse}","lines":[$lines],"notes":"Barang datang"}""", key, 201)

    private fun stock(setup: Setup, sku: String) = ok("GET", "/stock/$sku", setup.token)

    private fun member(setup: Setup, roleName: String, locations: List<String>): Pair<String, String> {
        val roles = mapper.readTree(request("GET", "/api/roles", setup.token).contentAsString)
        val role = roles.single { it.path("name").asString() == roleName }.path("id").asString()
        val me = mapper.readTree(request("GET", "/api/me", setup.token).contentAsString)
        val slug = me.path("email").asString().substringAfter('@').substringBefore(".test")
        val email = "member${UUID.randomUUID().toString().take(8)}@$slug.test"
        val created = request("POST", "/api/users", setup.token, mapper.writeValueAsString(mapOf("name" to roleName,
            "email" to email, "password" to "secret12345", "roleIds" to listOf(role))))
        assertThat(created.status).withFailMessage(created.contentAsString).isEqualTo(201)
        val id = mapper.readTree(created.contentAsString).path("id").asString()
        val access = request("PUT", "/api/users/$id/access", setup.token,
            mapper.writeValueAsString(mapOf("roleIds" to listOf(role), "areaIds" to listOf(area(setup.token)))))
        assertThat(access.status).withFailMessage(access.contentAsString).isEqualTo(200)
        locations.forEach { location ->
            val grant = request("PUT", "/api/v1/warehouse/settings/scopes/$id/$location", setup.token,
                """{"expectedRevision":0,"active":true}""")
            assertThat(grant.status).withFailMessage(grant.contentAsString).isEqualTo(200)
        }
        return login(slug, email) to id
    }

    @Test fun `provisioned Admin manages reference catalog while Manager reads and revoked warehouse hides history and replay`() {
        val setup = setup()
        val (admin, adminId) = member(setup, "Admin", listOf(setup.warehouse))
        val (manager, _) = member(setup, "Manager", listOf(setup.warehouse))
        activate(setup.token)
        val key = UUID.randomUUID().toString()
        val body = """{"code":"NEW-ITEM","name":"Barang baru","tracking":"BULK","baseUnit":"EA"}"""
        val created = ok("POST", "/skus", admin, body, key, 201)
        assertThat(ok("POST", "/skus", admin, body, key, 201)).isEqualTo(created)
        assertThat(ok("GET", "/skus/${created.path("id").asString()}", manager)).isEqualTo(created)
        assertThat(request("POST", "/api/v2/warehouse/skus", manager, body).status).isEqualTo(403)
        assertThat(request("POST", "/api/v2/warehouse/locations", admin,
            """{"code":"TRANSIT-X","name":"Transit","kind":"TRANSIT"}""").status).isEqualTo(400)
        val receiptBody = """{"warehouseId":"${setup.warehouse}","lines":[{"skuId":"${setup.bulk}","quantityBase":"5"}]}"""
        val receiptKey = UUID.randomUUID().toString()
        ok("POST", "/receipts", admin, receiptBody, receiptKey, 201)
        val history = ok("GET", "/stock/${setup.bulk}/history?size=1", manager)
        assertThat(history.path("totalElements").asLong()).isEqualTo(1)
        assertThat(history.path("items")[0].path("actorName").asString()).isEqualTo("Admin")
        assertThat(history.path("items")[0].path("locationName").asString()).isEqualTo("Gudang A")
        assertThat(history.path("items")[0].path("direction").asString()).isEqualTo("IN")
        assertThat(ok("GET", "/stock/${setup.bulk}/history?page=1&size=1", manager).path("items").isEmpty).isTrue()
        val revoke = request("PUT", "/api/v1/warehouse/settings/scopes/$adminId/${setup.warehouse}", setup.token,
            """{"expectedRevision":1,"active":false}""")
        assertThat(revoke.status).withFailMessage(revoke.contentAsString).isEqualTo(200)
        assertThat(ok("GET", "/stock/${setup.bulk}/history", admin).path("totalElements").asLong()).isZero()
        assertThat(request("POST", "/api/v2/warehouse/receipts", admin, receiptBody, receiptKey).status).isEqualTo(404)
        assertThat(request("GET", "/api/v2/warehouse/stock/${setup.bulk}/history?size=0", setup.token).status).isEqualTo(400)
    }

    @Test fun `scoped stock positions page and search before counting while summaries retain all warehouse stock`() {
        val setup = setup()
        val (manager, managerId) = member(setup, "Manager", listOf(setup.warehouse))
        activate(setup.token)
        val serials = (1..31).map { mapOf("serial" to "PAGE-${it.toString().padStart(3, '0')}") }
        receipt(setup, mapper.writeValueAsString(mapOf("skuId" to setup.onu, "quantityBase" to "31", "serials" to serials)))
        ok("POST", "/receipts", setup.token,
            mapper.writeValueAsString(mapOf("warehouseId" to setup.destination, "lines" to listOf(mapOf(
                "skuId" to setup.onu, "quantityBase" to "1", "serials" to listOf(mapOf("serial" to "HIDDEN-ONU")))))), status = 201)
        val summary = ok("GET", "/stock/${setup.onu}?includePositions=false", manager)
        assertThat(summary.path("positions").isEmpty).isTrue()
        assertThat(summary.path("warehouses")).hasSize(1)
        assertThat(summary.path("warehouses")[0].path("quantityBase").asString()).isEqualTo("31")
        val first = ok("GET", "/stock/${setup.onu}/positions?size=25", manager)
        val second = ok("GET", "/stock/${setup.onu}/positions?page=1&size=25", manager)
        assertThat(first.path("totalElements").asLong()).isEqualTo(31)
        assertThat(first.path("items")).hasSize(25)
        assertThat(second.path("items")).hasSize(6)
        val ids = first.path("items").toList().map { it.path("stockIdentityId").asString() } +
            second.path("items").toList().map { it.path("stockIdentityId").asString() }
        assertThat(ids.distinct()).hasSize(31)
        assertThat(ok("GET", "/stock/${setup.onu}/positions?search=PAGE-031", manager).path("totalElements").asLong()).isEqualTo(1)
        assertThat(ok("GET", "/stock/${setup.onu}/positions?search=HIDDEN", manager).path("totalElements").asLong()).isZero()
        assertThat(ok("GET", "/stock/${setup.onu}/positions?holderKind=TECHNICIAN", manager).path("totalElements").asLong()).isZero()
        assertThat(ok("GET", "/stock/${setup.onu}/positions?locationId=${setup.warehouse}&availableOnly=true", manager)
            .path("totalElements").asLong()).isEqualTo(31)
        assertThat(request("GET", "/api/v2/warehouse/stock/${setup.onu}/positions?locationId=${setup.destination}", manager).status).isEqualTo(404)
        for (query in listOf("size=0", "size=101", "page=-1", "holderKind=UNRECOGNIZED")) {
            assertThat(request("GET", "/api/v2/warehouse/stock/${setup.onu}/positions?$query", manager).status).isEqualTo(400)
        }
        val revoke = request("PUT", "/api/v1/warehouse/settings/scopes/$managerId/${setup.warehouse}", setup.token,
            """{"expectedRevision":1,"active":false}""")
        assertThat(revoke.status).withFailMessage(revoke.contentAsString).isEqualTo(200)
        assertThat(ok("GET", "/stock/${setup.onu}/positions", manager).path("totalElements").asLong()).isZero()
        assertThat(ok("GET", "/stock/${setup.onu}?includePositions=false", manager).path("warehouses").isEmpty).isTrue()
    }

    @Test fun `saved receipts reopen immutable physical lines with bounded pagination and restricted costs`() {
        val setup = setup()
        val (manager, _) = member(setup, "Manager", listOf(setup.warehouse))
        activate(setup.token)
        val serials = (1..31).map { mapOf("serial" to "DOCUMENT-${it.toString().padStart(3, '0')}") }
        val body = mapper.writeValueAsString(mapOf("warehouseId" to setup.warehouse, "reference" to "SJ-READ-001",
            "notes" to "Barang diterima", "lines" to listOf(
                mapOf("skuId" to setup.onu, "quantityBase" to "31", "serials" to serials, "cost" to mapOf("totalMinor" to "620000", "currency" to "IDR")),
                mapOf("skuId" to setup.cable, "quantityBase" to "82501", "conversion" to mapOf("numerator" to "82501", "denominator" to "1", "packageQuantity" to "1")))))
        val saved = ok("POST", "/receipts", setup.token, body, status = 201)
        val id = saved.path("id").asString()
        val detail = ok("GET", "/movements/$id", setup.token)
        assertThat(detail.path("operationId")).isEqualTo(saved.path("operationId"))
        assertThat(detail.path("reference").asString()).isEqualTo("SJ-READ-001")
        assertThat(detail.path("warehouseName").asString()).isEqualTo("Gudang A")
        assertThat(detail.path("state").asString()).isEqualTo("PUTAWAY")
        assertThat(detail.path("notes").asString()).isEqualTo("Barang diterima")
        val first = ok("GET", "/movements/$id/lines?size=25", setup.token)
        val second = ok("GET", "/movements/$id/lines?page=1&size=25", setup.token)
        assertThat(first.path("totalElements").asLong()).isEqualTo(32)
        assertThat(first.path("items")).hasSize(25)
        assertThat(second.path("items")).hasSize(7)
        val all = first.path("items").toList() + second.path("items").toList()
        assertThat(all.map { it.path("id").asString() }.distinct()).hasSize(32)
        assertThat(all.filter { it.path("tracking").asString() == "SERIAL" }.map { it.path("serial").asString() })
            .containsExactlyInAnyOrderElementsOf(serials.map { requireNotNull(it["serial"]) })
        assertThat(all.first().path("cost").path("totalMinor").asString()).isEqualTo("620000")
        val cable = all.single { it.path("skuId").asString() == setup.cable }
        assertThat(cable.path("quantityBase").asString()).isEqualTo("82501")
        assertThat(cable.path("conversion").path("numerator").asString()).isEqualTo("82501")
        assertThat(cable.path("lotCode").asString()).startsWith("RCV-")
        assertThat(ok("GET", "/movements/$id", manager).path("costVisible").asBoolean()).isFalse()
        assertThat(ok("GET", "/movements/$id/lines?size=100", manager).path("items").all { it.path("cost").isNull }).isTrue()
        ok("PUT", "/skus/${setup.onu}", setup.token,
            """{"code":"ONU","name":"Nama barang diubah","tracking":"SERIAL","baseUnit":"EA","expectedRevision":0}""")
        assertThat(ok("GET", "/movements/$id/lines?size=1", manager).path("items")[0].path("skuName").asString()).isEqualTo("ONU")
        assertThat(ok("GET", "/movements?kind=RECEIPT&search=SJ-READ", manager).path("totalElements").asLong()).isEqualTo(1)
        assertThat(ok("GET", "/movements?kind=TRANSFER", manager).path("items").isEmpty).isTrue()
        for (query in listOf("size=0", "size=101", "page=-1", "kind=ISSUE"))
            assertThat(request("GET", "/api/v2/warehouse/movements?$query", manager).status).isEqualTo(400)
        assertThat(request("GET", "/api/v2/warehouse/movements/$id/lines?size=0", manager).status).isEqualTo(400)
    }

    @Test fun `movement reads filter both transfer warehouses before paging and recheck revoked tenant scope`() {
        val setup = setup()
        val (manager, managerId) = member(setup, "Manager", listOf(setup.warehouse))
        activate(setup.token)
        val received = receipt(setup, """{"skuId":"${setup.cable}","quantityBase":"90000"},{"skuId":"${setup.onu}","quantityBase":"1","serials":[{"serial":"MOVEMENT-ONU","mac":"AA:BB:CC:DD:EF:01"}]}""")
        val receiptId = received.path("id").asString()
        val cable = stock(setup, setup.cable).path("positions").single().path("stockIdentityId").asString()
        val onu = stock(setup, setup.onu).path("positions").single().path("stockIdentityId").asString()
        val transferred = ok("POST", "/transfers", setup.token,
            """{"sourceWarehouseId":"${setup.warehouse}","warehouseId":"${setup.destination}","lines":[{"stockIdentityId":"$cable","quantityBase":"12501"},{"stockIdentityId":"$onu","quantityBase":"1"}],"notes":"Pindah gudang"}""", status = 201)
        val transferId = transferred.path("id").asString()
        assertThat(ok("GET", "/movements?size=1", manager).path("totalElements").asLong()).isEqualTo(1)
        assertThat(ok("GET", "/movements?size=1", manager).path("items")[0].path("id").asString()).isEqualTo(receiptId)
        assertThat(request("GET", "/api/v2/warehouse/movements/$transferId", manager).status).isEqualTo(404)
        assertThat(request("GET", "/api/v2/warehouse/movements/$transferId/lines", manager).status).isEqualTo(404)
        val grant = request("PUT", "/api/v1/warehouse/settings/scopes/$managerId/${setup.destination}", setup.token,
            """{"expectedRevision":0,"active":true}""")
        assertThat(grant.status).withFailMessage(grant.contentAsString).isEqualTo(200)
        val detail = ok("GET", "/movements/$transferId", manager)
        assertThat(detail.path("sourceWarehouseName").asString()).isEqualTo("Gudang A")
        assertThat(detail.path("warehouseName").asString()).isEqualTo("Gudang B")
        val lines = ok("GET", "/movements/$transferId/lines", manager).path("items")
        assertThat(lines.single { it.path("skuId").asString() == setup.cable }.path("quantityBase").asString()).isEqualTo("12501")
        val serial = lines.single { it.path("skuId").asString() == setup.onu }
        assertThat(serial.path("serial").asString()).isEqualTo("MOVEMENT-ONU")
        assertThat(serial.path("mac").asString()).isEqualTo("AA:BB:CC:DD:EF:01")
        assertThat(ok("GET", "/movements?size=1", manager).path("totalElements").asLong()).isEqualTo(2)
        val otherTenant = tenant()
        assertThat(request("GET", "/api/v2/warehouse/movements/$receiptId", otherTenant).status).isEqualTo(404)
        assertThat(request("GET", "/api/v2/warehouse/movements/$transferId/lines", otherTenant).status).isEqualTo(404)
        val revoke = request("PUT", "/api/v1/warehouse/settings/scopes/$managerId/${setup.warehouse}", setup.token,
            """{"expectedRevision":1,"active":false}""")
        assertThat(revoke.status).withFailMessage(revoke.contentAsString).isEqualTo(200)
        assertThat(ok("GET", "/movements", manager).path("totalElements").asLong()).isZero()
        assertThat(request("GET", "/api/v2/warehouse/movements/$receiptId", manager).status).isEqualTo(404)
        assertThat(request("GET", "/api/v2/warehouse/movements/$transferId/lines", manager).status).isEqualTo(404)
    }

    @Test fun `new tenant default warehouse receives immediately and every warehouse has an explicit zero balance`() {
        val setup = setup()
        activate(setup.token)
        val default = ok("GET", "/locations", setup.token).path("items").single { it.path("code").asString() == "GUDANG-UTAMA" }
        val initial = stock(setup, setup.bulk)
        assertThat(initial.path("warehouses")).hasSize(3)
        assertThat(initial.path("warehouses").all { it.path("quantityBase").asString() == "0" }).isTrue()
        ok("POST", "/receipts", setup.token,
            """{"warehouseId":"${default.path("id").asString()}","lines":[{"skuId":"${setup.bulk}","quantityBase":"9"}]}""", status = 201)
        val locations = ok("GET", "/locations?size=1", setup.token)
        assertThat(locations.path("totalElements").asLong()).isEqualTo(3)
        assertThat(ok("GET", "/locations?page=2&size=1", setup.token).path("items")).hasSize(1)
        assertThat(ok("GET", "/locations?page=3&size=1", setup.token).path("items").isEmpty).isTrue()
        val internal = fixture(setup.token).transaction { scalar("SELECT id FROM inventory_location WHERE tenant_id='$tenant' AND code='RECEIPT_SOURCE'") }
        assertThat(request("GET", "/api/v2/warehouse/locations/$internal", setup.token).status).isEqualTo(404)
        assertThat(stock(setup, setup.bulk).path("warehouses").single { it.path("warehouseName").asString() == "Gudang Utama" }
            .path("quantityBase").asString()).isEqualTo("9")
        assertThat(ok("GET", "/stock/${setup.bulk}/history", setup.token).path("items").single()
            .path("quantityBase").asString()).isEqualTo("9")
    }

    @Test fun `missing or forged canonical identity rolls back otherwise complete receipt and normal command still succeeds`() {
        val setup = setup()
        activate(setup.token)
        val tenantId = mapper.readTree(request("GET", "/api/me", setup.token).contentAsString).path("tenantId").asString()
        val marker = "reference_identity_${UUID.randomUUID().toString().replace("-", "")}"
        val before = fixture(setup.token).transaction { counts() }
        val key = UUID.randomUUID().toString()
        val body = """{"warehouseId":"${setup.warehouse}","lines":[{"skuId":"${setup.bulk}","quantityBase":"7"}]}"""
        val flyway = context.getBean(org.flywaydb.core.Flyway::class.java)
        flyway.configuration.dataSource.connection.use { owner ->
            owner.createStatement().use { sql ->
                sql.execute("SET search_path TO ${flyway.configuration.defaultSchema ?: "public"}")
                for (mutation in listOf("RETURN NULL;", "NEW.canonical_payload:='{}'; RETURN NEW;")) {
                    sql.execute("CREATE FUNCTION $marker() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN IF NEW.tenant_id=''$tenantId''::uuid THEN ${mutation.replace("'", "''")} END IF; RETURN NEW; END'")
                    sql.execute("CREATE TRIGGER $marker BEFORE INSERT ON inventory_command_identity FOR EACH ROW EXECUTE FUNCTION $marker()")
                    try {
                        val response = request("POST", "/api/v2/warehouse/receipts", setup.token, body, key)
                        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(409)
                        assertThat(fixture(setup.token).transaction { counts() }).isEqualTo(before)
                    } finally {
                        sql.execute("DROP TRIGGER $marker ON inventory_command_identity")
                        sql.execute("DROP FUNCTION $marker()")
                    }
                }
            }
        }
        ok("POST", "/receipts", setup.token, body, key, 201)
        assertThat(stock(setup, setup.bulk).path("positions").single().path("quantityBase").asString()).isEqualTo("7")
    }

    @Test fun `owner activates exact reconciled snapshot and replays while direct database transition is forbidden`() {
        val setup = setup()
        val before = fixture(setup.token).transaction { counts() }
        assertThat(request("POST", "/api/v2/warehouse/receipts", setup.token,
            """{"warehouseId":"${setup.warehouse}","lines":[{"skuId":"${setup.bulk}","quantityBase":"4"}]}""").status).isEqualTo(409)
        assertThat(fixture(setup.token).transaction { counts() }).isEqualTo(before)
        assertThatThrownBy { fixture(setup.token).transaction { sql("UPDATE inventory_tenant_cutover SET workflow_mode='REFERENCE',epoch=epoch+1,revision=revision+1 WHERE tenant_id='$tenant'") } }
            .hasMessageContaining("reference activation requires reconciled legacy transactions")
        val (body, key) = activate(setup.token)
        val replay = request("POST", "/api/v2/warehouse/workflow/activate", setup.token, body, key)
        assertThat(replay.status).withFailMessage(replay.contentAsString).isEqualTo(200)
        assertThat(request("POST", "/api/v2/warehouse/workflow/activate", setup.token, body.replace("Alur gudang sederhana", "Changed"), key).status).isEqualTo(409)
        val (other, _) = user(setup.token, setOf("warehouse.stock.manage"))
        assertThat(request("GET", "/api/v2/warehouse/workflow/review", other).status).isEqualTo(403)
        assertThat(request("POST", "/api/v2/warehouse/workflow/activate", other, body, key).status).isEqualTo(403)
    }

    @Test fun `direct receipt is immediately available with automatic lot and supplier and exact package conversion`() {
        val setup = setup()
        activate(setup.token)
        val key = UUID.randomUUID().toString()
        val lines = """{"skuId":"${setup.cable}","quantityBase":"1000000","conversion":{"numerator":"500000","denominator":"1","packageQuantity":"2"}}"""
        val result = receipt(setup, lines, key)
        assertThat(result.path("state").asString()).isEqualTo("PUTAWAY")
        val beforeReplay = fixture(setup.token).transaction { counts() }
        assertThat(receipt(setup, lines, key)).isEqualTo(result)
        assertThat(fixture(setup.token).transaction { counts() }).isEqualTo(beforeReplay)
        val available = stock(setup, setup.cable)
        assertThat(available.path("positions")).hasSize(1)
        assertThat(available.path("positions")[0].path("status").asString()).isEqualTo("AVAILABLE")
        assertThat(available.path("warehouses").first { it.path("warehouseId").asString() == setup.warehouse }.path("quantityBase").asString()).isEqualTo("1000000")
        assertThat(available.path("warehouses").first { it.path("warehouseId").asString() == setup.destination }.path("quantityBase").asString()).isEqualTo("0")
        val changed = request("POST", "/api/v2/warehouse/receipts", setup.token,
            """{"warehouseId":"${setup.warehouse}","lines":[$lines],"notes":"Changed"}""", key)
        assertThat(changed.status).isEqualTo(409)
        val fractional = request("POST", "/api/v2/warehouse/receipts", setup.token,
            """{"warehouseId":"${setup.warehouse}","lines":[{"skuId":"${setup.bulk}","quantityBase":"2","conversion":{"numerator":"3","denominator":"2","packageQuantity":"1"}}]}""")
        assertThat(fractional.status).isEqualTo(400)
        assertThat(fixture(setup.token).transaction { counts() }).isEqualTo(beforeReplay)
    }

    @Test fun `serial and MAC identity stay permanently unique across direct receipts`() {
        val setup = setup()
        activate(setup.token)
        receipt(setup, """{"skuId":"${setup.onu}","quantityBase":"1","serials":[{"serial":"SN-001","mac":"AA:BB:CC:DD:EE:01"}]}""")
        val before = fixture(setup.token).transaction { counts() }
        for (serial in listOf("""{"serial":"sn-001"}""", """{"serial":"SN-002","mac":"aa-bb-cc-dd-ee-01"}""")) {
            val denied = request("POST", "/api/v2/warehouse/receipts", setup.token,
                """{"warehouseId":"${setup.warehouse}","lines":[{"skuId":"${setup.onu}","quantityBase":"1","serials":[$serial]}]}""")
            assertThat(denied.status).withFailMessage(denied.contentAsString).isEqualTo(409)
            assertThat(fixture(setup.token).transaction { counts() }).isEqualTo(before)
        }
    }

    @Test fun `partial bulk and cable transfer conserve stock and split cable with no transit acknowledgement`() {
        val setup = setup()
        activate(setup.token)
        receipt(setup, """{"skuId":"${setup.cable}","quantityBase":"1000"},{"skuId":"${setup.bulk}","quantityBase":"10"}""")
        val cable = stock(setup, setup.cable).path("positions")[0].path("stockIdentityId").asString()
        val bulk = stock(setup, setup.bulk).path("positions")[0].path("stockIdentityId").asString()
        val body = """{"sourceWarehouseId":"${setup.warehouse}","warehouseId":"${setup.destination}","lines":[{"stockIdentityId":"$cable","quantityBase":"400"},{"stockIdentityId":"$bulk","quantityBase":"3"}]}"""
        val key = UUID.randomUUID().toString()
        val result = ok("POST", "/transfers", setup.token, body, key, 201)
        assertThat(result.path("state").asString()).isEqualTo("RECEIVED")
        val before = fixture(setup.token).transaction { counts() }
        assertThat(ok("POST", "/transfers", setup.token, body, key, 201)).isEqualTo(result)
        assertThat(fixture(setup.token).transaction { counts() }).isEqualTo(before)
        for ((sku, total, destination) in listOf(Triple(setup.cable, 1000L, "400"), Triple(setup.bulk, 10L, "3"))) {
            val positions = stock(setup, sku).path("positions")
            assertThat(positions.sumOf { it.path("quantityBase").asString().toLong() }).isEqualTo(total)
            assertThat(positions.first { it.path("locationId").asString() == setup.destination }.path("quantityBase").asString()).isEqualTo(destination)
            assertThat(positions.all { it.path("status").asString() == "AVAILABLE" }).isTrue()
        }
        val insufficient = request("POST", "/api/v2/warehouse/transfers", setup.token,
            """{"sourceWarehouseId":"${setup.warehouse}","warehouseId":"${setup.destination}","lines":[{"stockIdentityId":"$bulk","quantityBase":"8"}]}""")
        assertThat(insufficient.status).isEqualTo(409)
        assertThat(fixture(setup.token).transaction { counts() }).isEqualTo(before)
        assertThatThrownBy { fixture(setup.token).transaction { sql("UPDATE inventory_reference_post SET expected_legs='[]' WHERE tenant_id='$tenant'") } }
            .hasMessageContaining("append")
    }
}
