package com.karuhun.ftth.fieldservice

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import java.util.UUID

class WorkOrderKpiIT : FieldHttpFixture() {
    @Test fun `completed FO work keeps frozen category and completion attribution after master changes`() {
        val s = actors()
        ok("POST", "/api/v2/warehouse/workflow/drain", s.owner, mapOf("expectedEpoch" to 0))
        val review = ok("GET", "/api/v2/warehouse/workflow/review", s.owner)
        ok("POST", "/api/v2/warehouse/workflow/activate", s.owner, mapOf("expectedEpoch" to 1,
            "reviewHash" to review.path("reviewHash").asString(), "reason" to "KPI integration"))
        val typeInput = mapOf("name" to "Project jaringan", "workType" to "PREVENTIVE", "kpiCategory" to "PROJECT",
            "materialRequired" to false, "photoSlots" to listOf("Hasil"))
        val type = ok("POST", "/api/v2/work-orders/types", s.owner, typeInput, 201)
        val typeId = type.path("id").asString()
        val initial = ok("POST", "/api/v2/work-orders", s.admin, mapOf("typeId" to typeId,
            "title" to "Project FO", "technicianId" to s.fo.id, "areaId" to area(s.owner.token)), 201)
        val id = initial.path("id").asString()
        ok("PUT", "/api/v2/work-orders/types/$typeId", s.owner, typeInput + mapOf("expectedRevision" to 0, "kpiCategory" to "NONE"))
        val updated = ok("PUT", "/api/v2/work-orders/$id", s.admin, mapOf("expectedRevision" to 0,
            "title" to "Project FO terbaru", "description" to "Catatan admin", "priority" to "NORMAL",
            "customerId" to null, "scheduledAt" to null, "areaId" to area(s.owner.token)))
        assertThat(updated.path("lastActivityAt")).isEqualTo(initial.path("lastActivityAt"))
        assertThat(updated.path("type").path("kpiCategory").asString()).isEqualTo("PROJECT")
        val photo = mvc.perform(multipart("/api/v2/work-orders/$id/evidence")
            .file(MockMultipartFile("file", "proof.png", "image/png", png)).param("slot", "Hasil")
            .param("expectedRevision", "1").header("Authorization", "Bearer " + s.fo.token)
            .header("Idempotency-Key", UUID.randomUUID().toString())).andReturn().response
        assertThat(photo.status).withFailMessage(photo.contentAsString).isEqualTo(201)
        val key = UUID.randomUUID().toString()
        val body = mapOf("expectedRevision" to 2, "notes" to "Pekerjaan selesai", "materials" to emptyList<String>())
        val completed = ok("POST", "/api/v2/work-orders/$id/complete", s.fo, body, key = key)
        assertThat(ok("POST", "/api/v2/work-orders/$id/complete", s.fo, body, key = key)).isEqualTo(completed)
        val own = ok("GET", "/api/v1/field/kpi?month=2026-10", s.fo).path("rows").single()
        assertThat(own.path("metrics")[3].path("actual").asInt()).isEqualTo(3)
        assertThat(own.path("completed")).hasSize(1)
        assertThat(own.path("completed").single().path("category").asString()).isEqualTo("PROJECT")
        val team = ok("GET", "/api/v1/field/kpi?month=2026-10&technicianId=" + s.fo.id, s.manager)
        assertThat(team.path("rows").single()).isEqualTo(own)
        assertThat(ok("GET", "/api/v1/field/kpi?month=2026-10", s.ne).path("rows").single().path("completed")).isEmpty()
    }
}
