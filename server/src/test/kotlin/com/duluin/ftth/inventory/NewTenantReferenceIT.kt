package com.duluin.ftth.inventory

import com.duluin.ftth.iam.application.port.inbound.OnboardTenantCommand
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantUseCase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

class NewTenantReferenceIT : WarehouseMasterHttpFixture() {
    @Autowired private lateinit var onboarding: OnboardTenantUseCase

    @Test
    fun `new tenant receives into its default warehouse and assigns both technician roles without activation`() {
        val slug = "new-reference-${UUID.randomUUID().toString().take(8)}"
        val tenant = onboarding.onboard(OnboardTenantCommand(slug, "New ISP", "owner@$slug.test", "Owner", "secret12345")).tenant
        val owner = login(slug, "owner@$slug.test")
        fun ok(method: String, path: String, token: String = owner, body: String? = null, status: Int = 200) =
            request(method, path, token, body).let { response ->
                assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(status)
                mapper.readTree(response.contentAsString)
            }
        val workflow = ok("GET", "/api/v2/warehouse/workflow").path("snapshot")
        assertThat(workflow.path("workflow").asString()).isEqualTo("REFERENCE")
        assertThat(workflow.path("epoch").asLong()).isEqualTo(1L)
        val roles = ok("GET", "/api/roles")
        assertThat(roles.asSequence().map { it.path("name").asString() }.toList()).containsExactlyInAnyOrder("Admin", "Manager", "Teknisi NE", "Teknisi FO")
        val area = ok("POST", "/api/areas", body = """{"code":"MAIN","name":"Main"}""", status = 201).path("id").asString()
        val warehouse = ok("GET", "/api/v2/warehouse/locations").path("items").single()
        assertThat(warehouse.path("name").asString()).isEqualTo("Gudang Utama")
        val warehouseId = warehouse.path("id").asString()
        ok("PUT", "/api/v2/warehouse/locations/$warehouseId", body = mapper.writeValueAsString(mapOf(
            "code" to warehouse.path("code").asString(), "name" to "Gudang Utama", "kind" to "WAREHOUSE",
            "areaId" to area, "issueEligible" to true, "expectedRevision" to warehouse.path("revision").asLong())))
        val sku = ok("POST", "/api/v2/warehouse/skus", body = """{"code":"CONNECTOR","name":"Konektor","tracking":"BULK","baseUnit":"EA"}""", status = 201).path("id").asString()
        ok("POST", "/api/v2/warehouse/receipts", body = """{"warehouseId":"$warehouseId","lines":[{"skuId":"$sku","quantityBase":"9"}]}""", status = 201)
        assertThat(ok("GET", "/api/v2/warehouse/stock/$sku").path("positions").single().path("quantityBase").asString()).isEqualTo("9")
        val type = ok("GET", "/api/v2/work-orders/types").single { it.path("name").asString() == "Maintenance" }.path("id").asString()
        val tasks = mutableListOf<Pair<String, String>>()
        for (name in listOf("Teknisi NE", "Teknisi FO")) {
            val role = roles.single { it.path("name").asString() == name }.path("id").asString()
            val email = "tech${UUID.randomUUID().toString().take(8)}@$slug.test"
            val user = ok("POST", "/api/users", body = mapper.writeValueAsString(mapOf(
                "name" to name, "email" to email, "password" to "secret12345", "roleIds" to listOf(role))), status = 201).path("id").asString()
            ok("PUT", "/api/users/$user/access", body = mapper.writeValueAsString(mapOf("roleIds" to listOf(role), "areaIds" to listOf(area))))
            val technician = login(slug, email)
            val work = ok("POST", "/api/v2/work-orders", body = mapper.writeValueAsString(mapOf(
                "typeId" to type, "technicianId" to user, "areaId" to area, "title" to "Periksa sambungan $name",
                "description" to "Ukur redaman dan catat hasil.")), status = 201).path("id").asString()
            assertThat(ok("GET", "/api/v2/work-orders/$work", technician).path("workOrder").path("technicianId").asString()).isEqualTo(user)
            ok("POST", "/api/v2/work-orders/$work/progress", technician, """{"expectedRevision":0,"state":"PENDING","notes":"Berangkat ke lokasi."}""")
            tasks += technician to work
        }
        assertThat(request("GET", "/api/v2/work-orders/${tasks[1].second}", tasks[0].first).status).isEqualTo(404)
        assertThat(request("POST", "/api/work-orders", owner, """{"type":"REPAIR","title":"Legacy draft"}""").status).isEqualTo(409)
        fixture(owner).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_reference_bootstrap WHERE tenant_id='${tenant.id}'")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_reference_activation")).isEqualTo("0")
            assertThat(scalar("SELECT epoch FROM inventory_tenant_cutover")).isEqualTo("1")
        }
    }
}
