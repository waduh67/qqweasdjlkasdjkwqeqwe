package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.util.UUID

class ReferenceRequestIT : WarehouseMasterHttpFixture() {
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
}
