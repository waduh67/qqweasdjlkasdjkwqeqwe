package com.duluin.ftth.inventory

import com.duluin.ftth.iam.application.port.inbound.OnboardTenantCommand
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantUseCase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import tools.jackson.databind.JsonNode
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ReferenceWorkIntakeIT : WarehouseMasterHttpFixture() {
    @org.springframework.beans.factory.annotation.Autowired private lateinit var onboarding: OnboardTenantUseCase
    private data class Setup(val slug: String, val owner: String, val admin: String, val technician: String, val technicianId: String, val area: String)

    private fun ok(method: String, path: String, token: String?, body: Any? = null, status: Int = 200,
        key: String = UUID.randomUUID().toString()): JsonNode {
        val response = request(method, path, token, body?.let(mapper::writeValueAsString), key)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(status)
        return mapper.readTree(response.contentAsString)
    }
    private fun setup(): Setup {
        val slug = "intake-${UUID.randomUUID().toString().take(8)}"
        onboarding.onboard(OnboardTenantCommand(slug, "ISP", "owner@$slug.test", "Owner", "secret12345"))
        val owner = login(slug, "owner@$slug.test")
        val area = ok("POST", "/api/areas", owner, mapOf("code" to "MAIN", "name" to "Main"), 201).path("id").asString()
        val roles = ok("GET", "/api/roles", owner)
        fun member(name: String): Pair<String, String> {
            val role = roles.single { it.path("name").asString() == name }.path("id").asString()
            val email = "member-${UUID.randomUUID().toString().take(8)}@$slug.test"
            val id = ok("POST", "/api/users", owner, mapOf("name" to name, "email" to email, "password" to "secret12345",
                "roleIds" to listOf(role)), 201).path("id").asString()
            ok("PUT", "/api/users/$id/access", owner, mapOf("roleIds" to listOf(role), "areaIds" to listOf(area)))
            return login(slug, email) to id
        }
        val (admin, _) = member("Admin")
        val (technician, technicianId) = member("Teknisi FO")
        return Setup(slug, owner, admin, technician, technicianId, area)
    }
    private fun psb(s: Setup): JsonNode {
        val plan = ok("POST", "/api/catalog/plans", s.owner, mapOf("name" to "Paket ${UUID.randomUUID()}", "price" to 150000,
            "downMbps" to 20, "upMbps" to 10, "serviceTypes" to listOf("PPPOE")), 201).path("id").asString()
        return ok("POST", "/api/onboarding/psb", s.owner, mapOf("name" to "Budi", "address" to "Jl. Uji",
            "location" to mapOf("longitude" to 106.8, "latitude" to -6.2), "planId" to plan, "areaId" to s.area), 201)
    }
    private fun type(s: Setup, kind: String): String = ok("POST", "/api/v2/work-orders/types", s.owner, mapOf(
        "expectedRevision" to 0, "name" to "Kunjungan $kind", "workType" to kind, "materialRequired" to false,
        "photoSlots" to listOf("Bukti"), "active" to true), 201).path("id").asString()
    private fun dispatch(s: Setup, type: String) = mapOf("typeId" to type, "technicianId" to s.technicianId, "areaId" to s.area)
    private fun complete(s: Setup, id: String) {
        val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")
        val upload = mvc.perform(multipart("/api/v2/work-orders/$id/evidence")
            .file(MockMultipartFile("file", "proof.png", "image/png", png)).param("expectedRevision", "0").param("slot", "Bukti")
            .header("Authorization", "Bearer ${s.technician}").header("Idempotency-Key", UUID.randomUUID().toString()))
            .andReturn().response
        assertThat(upload.status).withFailMessage(upload.contentAsString).isEqualTo(201)
        ok("POST", "/api/v2/work-orders/$id/complete", s.technician, mapOf("expectedRevision" to 1, "notes" to "Selesai", "materials" to emptyList<Any>()))
    }

    @Test fun `PSB source keeps its identity through scoped dispatch retry and installation completion`() {
        val s = setup()
        val psb = psb(s)
        val id = psb.path("workOrderId").asString()
        val subscription = psb.path("subscriptionId").asString()
        val intake = ok("GET", "/api/v2/work-orders/intake/$id", s.admin)
        assertThat(intake.path("sourceId").asString()).isEqualTo(subscription)
        assertThat(intake.path("areaId").asString()).isEqualTo(s.area)
        assertThat(ok("GET", "/api/v2/work-orders/intake", s.admin).path("items")).hasSize(1)
        assertThat(request("GET", "/api/v2/work-orders/intake", s.technician).status).isEqualTo(403)
        assertThat(request("GET", "/api/v2/work-orders/$id", s.technician).status).isEqualTo(404)
        assertThat(fixture(s.owner).transaction { scalar("SELECT status FROM subscription WHERE id='$subscription'") }).isEqualTo("PENDING")
        val body = dispatch(s, type(s, "PSB"))
        val key = UUID.randomUUID().toString()
        val assigned = ok("POST", "/api/v2/work-orders/intake/$id/dispatch", s.admin, body, key = key)
        assertThat(assigned.path("id").asString()).isEqualTo(id)
        assertThat(assigned.path("customerId").asString()).isEqualTo(psb.path("customerId").asString())
        assertThat(ok("POST", "/api/v2/work-orders/intake/$id/dispatch", s.admin, body, key = key)).isEqualTo(assigned)
        ok("POST", "/api/v2/work-orders/intake/$id/dispatch", s.admin, body + ("scheduledAt" to "2026-10-10T01:00:00Z"), 409, key)
        ok("POST", "/api/v2/work-orders/intake/$id/dispatch", s.admin, body, 409)
        assertThat(ok("GET", "/api/v2/work-orders/intake", s.admin).path("totalElements").asLong()).isZero()
        assertThat(ok("GET", "/api/v2/work-orders/$id", s.technician).path("workOrder").path("technicianId").asString()).isEqualTo(s.technicianId)
        complete(s, id)
        fixture(s.owner).transaction {
            assertThat(scalar("SELECT status FROM subscription WHERE id='$subscription'")).isEqualTo("ACTIVE")
            assertThat(scalar("SELECT status FROM subscriber_access WHERE id='${psb.path("accessId").asString()}'")).isEqualTo("ACTIVE")
            assertThat(scalar("SELECT count(*) FROM work_order_reference_command WHERE resource_id='$id' AND action='DISPATCH'")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM fulfillment_checkpoint WHERE work_order_id='$id' AND state='APPLIED'")).isEqualTo("1")
        }
    }

    @Test fun `competing dispatches create one assignment and invalid source kind or scope leaves intake pending`() {
        val s = setup()
        val id = psb(s).path("workOrderId").asString()
        val types = ok("GET", "/api/v2/work-orders/types", s.owner)
        val repair = types.single { it.path("workType").asString() == "REPAIR" }.path("id").asString()
        ok("POST", "/api/v2/work-orders/intake/$id/dispatch", s.admin, dispatch(s, repair), 400)
        val psbType = types.single { it.path("workType").asString() == "PSB" }.path("id").asString()
        val otherArea = ok("POST", "/api/areas", s.owner, mapOf("code" to "OTHER", "name" to "Other"), 201).path("id").asString()
        ok("POST", "/api/v2/work-orders/intake/$id/dispatch", s.admin, dispatch(s, psbType) + ("areaId" to otherArea), 404)
        val other = setup()
        assertThat(request("GET", "/api/v2/work-orders/intake/$id", other.owner).status).isEqualTo(404)
        ok("POST", "/api/v2/work-orders/intake/$id/dispatch", other.owner, dispatch(other, psbType), 404)
        val body = mapper.writeValueAsString(dispatch(s, psbType))
        Executors.newFixedThreadPool(2).use { pool ->
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val results = List(2) { pool.submit<org.springframework.mock.web.MockHttpServletResponse> {
                ready.countDown()
                check(start.await(20, TimeUnit.SECONDS))
                request("POST", "/api/v2/work-orders/intake/$id/dispatch", s.admin, body)
            } }
            check(ready.await(20, TimeUnit.SECONDS))
            start.countDown()
            val responses = results.map { it.get(30, TimeUnit.SECONDS) }
            assertThat(responses.map { it.status }).withFailMessage(responses.joinToString("\n") { it.contentAsString }).containsExactlyInAnyOrder(200, 409)
        }
        assertThat(fixture(s.owner).transaction { scalar("SELECT count(*) FROM work_order_assignee WHERE work_order_id='$id'") }).isEqualTo("1")
    }

    @Test fun `pending source cannot be assigned through raw SQL or changed to another subscription`() {
        val s = setup()
        val id = psb(s).path("workOrderId").asString()
        val other = psb(s).path("subscriptionId").asString()
        val f = fixture(s.owner)
        assertThatThrownBy { f.transaction { sql("UPDATE work_order SET status='ASSIGNED' WHERE id='$id'") } }
            .hasMessageContaining("intake must be dispatched")
        assertThatThrownBy { f.transaction { sql("UPDATE work_order SET subscription_id='$other' WHERE id='$id'") } }
            .hasMessageContaining("intake source identity is immutable")
        assertThatThrownBy { f.transaction { sql("UPDATE work_order_reference_intake SET source_id='$other' WHERE work_order_id='$id'") } }
            .hasMessageContaining("permission denied")
    }

    @Test fun `helpdesk escalation inherits customer area and retains its ticket binding after completion`() {
        val s = setup()
        val customer = ok("POST", "/api/customers", s.owner, mapOf("name" to "Sari", "address" to "Jl. Uji", "areaId" to s.area,
            "location" to mapOf("longitude" to 106.8, "latitude" to -6.2)), 201).path("id").asString()
        val identifier = "portal-${UUID.randomUUID().toString().take(8)}"
        ok("POST", "/api/portal-admin/customers/$customer/credential", s.owner, mapOf("login" to identifier, "password" to "portal12345"))
        val portal = ok("POST", "/api/portal/auth/login", null, mapOf("identifier" to identifier, "password" to "portal12345", "tenant" to s.slug))
            .path("tokens").path("accessToken").asString()
        val ticket = ok("POST", "/api/portal/me/tickets", portal, mapOf("category" to "KONEKSI_PUTUS",
            "subject" to "LOS merah", "description" to "Periksa sambungan"), 201).path("ticket").path("id").asString()
        val id = ok("POST", "/api/helpdesk/tickets/$ticket/escalate", s.owner, mapOf("priority" to "HIGH")).path("ticket").path("workOrderId").asString()
        val intake = ok("GET", "/api/v2/work-orders/intake/$id", s.admin)
        assertThat(intake.path("areaId").asString()).isEqualTo(s.area)
        assertThat(intake.path("sourceId").asString()).isEqualTo(ticket)
        ok("POST", "/api/v2/work-orders/intake/$id/dispatch", s.admin, dispatch(s, type(s, "REPAIR")))
        complete(s, id)
        val f = fixture(s.owner)
        assertThat(f.transaction { scalar("SELECT work_order_id::text FROM helpdesk_ticket WHERE id='$ticket'") }).isEqualTo(id)
        assertThatThrownBy { f.transaction { sql("UPDATE helpdesk_ticket SET work_order_id=NULL,work_order_code=NULL WHERE id='$ticket'") } }
            .hasMessageContaining("helpdesk intake must retain its ticket link")
        assertThatThrownBy { f.transaction { sql("UPDATE helpdesk_ticket SET work_order_code='OTHER' WHERE id='$ticket'") } }
            .hasMessageContaining("helpdesk intake must retain its ticket link")
        assertThatThrownBy { f.transaction { sql("DELETE FROM helpdesk_ticket WHERE id='$ticket'") } }
            .hasMessageContaining("helpdesk intake must retain its ticket link")
        ok("POST", "/api/helpdesk/tickets/$ticket/status", s.owner, mapOf("status" to "RESOLVED"))
    }
}
