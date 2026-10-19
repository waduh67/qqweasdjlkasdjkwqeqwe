package com.karuhun.ftth.fieldservice

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart

class TechnicianKpiIT: FieldHttpFixture() {
    @Test fun `team and own KPI share report credits client caps and immutable original activation`() {
        val s = actors()
        fun client(name: String) = ok("POST","/api/v1/b2b/clients",s.admin,mapOf("name" to name,"address" to "Kantor",
            "contact" to "PIC","technicianId" to s.ne.id,"target" to 1),201).path("id").asString()
        val first = client("Client A"); client("Client B")
        fun visit() {
            val result = mvc.perform(multipart("/api/v1/b2b/clients/$first/visits").file(MockMultipartFile("photos","proof.png","image/png",png))
                .param("notes","Periksa koneksi").header("Authorization","Bearer ${s.ne.token}").header("Idempotency-Key",java.util.UUID.randomUUID())).andReturn().response
            assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(201)
        }
        visit(); doReturn(today.plusDays(3)).`when`(clock).today(); visit(); doReturn(today).`when`(clock).today()
        assertThat(report(s.ne).status).isEqualTo(201)
        assertThat(report(s.ne,revision=1,notes="Revisi").status).isEqualTo(201)
        assertThat(report(s.ne,slot="EVENING").status).isEqualTo(201)
        val own = ok("GET","/api/v1/field/kpi?month=2026-10",s.ne)
        assertThat(own.path("workingDays").asInt()).isEqualTo(22)
        assertThat(own.path("rows")).hasSize(1)
        val row = own.path("rows").single(); val metrics = row.path("metrics")
        assertThat(metrics[0].path("actual").asInt()).isEqualTo(1)
        assertThat(metrics[1].path("actual").asInt()).isEqualTo(2)
        assertThat(metrics[2].path("percentage").asDouble()).isEqualTo(50.0)
        assertThat(metrics[3].path("actual").asInt()).isEqualTo(2)
        assertThat(metrics[3].path("percentage").asDouble()).isEqualTo(100.0)
        val filtered = ok("GET","/api/v1/field/kpi?month=2026-10&technicianId=${s.ne.id}",s.manager)
        assertThat(filtered.path("rows").single()).isEqualTo(row)
        assertThat(filtered.path("average")).isEqualTo(row.path("percentage"))
        assertThat(ok("GET","/api/v1/field/kpi?month=2026-10&team=FO",s.owner).path("rows")).hasSize(1)
        assertThat(request("GET","/api/v1/field/kpi?month=2026-10&technicianId=${s.other.id}",s.ne.token).status).isEqualTo(404)
        listOf("", "invalid", "2026-11", "1999-12").forEach {
            assertThat(request("GET","/api/v1/field/kpi?month=$it",s.ne.token).status).isEqualTo(400)
        }
        assertThat(ok("GET","/api/v1/b2b/visits?month=2027-01",s.ne).path("totalElements").asInt()).isZero()
        assertThat(request("GET","/api/v1/b2b/reports?month=2027-01",s.ne.token).status).isEqualTo(400)
        ok("PUT","/api/v1/b2b/clients/$first",s.admin,mapOf("name" to "Nama berikutnya","address" to "Kantor",
            "contact" to "PIC","technicianId" to s.other.id,"target" to 4,"active" to false,"expectedRevision" to 0))
        doReturn(today.plusMonths(1)).`when`(clock).today()
        assertThat(ok("GET","/api/v1/field/kpi?month=2026-10",s.ne).path("rows").single().path("metrics")[3].path("actual").asInt()).isEqualTo(2)
        assertThat(ok("GET","/api/v1/field/kpi?month=2026-11",s.other).path("rows").single().path("metrics")[3].path("actual").asInt()).isZero()
    }
    @Test fun `technician granted team permission stays scoped and revoked grants survive provisioning`() {
        val s = actors()
        val role = ok("GET","/api/roles",s.owner).single { it.path("name").asString()=="Teknisi NE" }
        val catalog = ok("GET","/api/permissions",s.owner)
        val all = catalog.single { it.path("code").asString()=="kpi.team.view" }.path("id").asString()
        val own = catalog.single { it.path("code").asString()=="kpi.technician.view" }.path("id").asString()
        val permissionIds = role.path("permissionIds").asSequence().map { it.asString() }.toSet()
        ok("PUT","/api/roles/${role.path("id").asString()}",s.owner,mapOf("name" to "Teknisi NE","permissionIds" to permissionIds+all))
        assertThat(ok("GET","/api/v1/field/kpi?month=2026-10",s.ne).path("rows")).hasSize(1)
        ok("PUT","/api/roles/${role.path("id").asString()}",s.owner,mapOf("name" to "Teknisi NE","permissionIds" to permissionIds+all-own))
        val tenantId = java.util.UUID.fromString(ok("GET","/api/me",s.owner).path("tenantId").asString())
        com.karuhun.ftth.common.tenant.TenantContext.runAs(tenantId) {
            org.springframework.transaction.support.TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager::class.java)).execute {
                context.getBean(com.karuhun.ftth.iam.application.service.AdminProvisioner::class.java).ensureOperationalRoles(tenantId)
            }
        }
        assertThat(request("GET","/api/v1/field/kpi?month=2026-10",s.ne.token).status).isEqualTo(403)
    }
}
