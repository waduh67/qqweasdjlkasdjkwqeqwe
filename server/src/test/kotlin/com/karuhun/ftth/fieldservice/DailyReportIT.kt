package com.karuhun.ftth.fieldservice

import com.karuhun.ftth.common.storage.ObjectStorage
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.util.UUID

class DailyReportIT: FieldHttpFixture() {
    @MockitoSpyBean private lateinit var storage: ObjectStorage
    @Test fun `same day revisions replay exactly preserve private photos and count one slot`() {
        val s = actors(); val key = UUID.randomUUID().toString()
        val initial = report(s.ne,key=key,files=listOf(png))
        assertThat(initial.status).withFailMessage(initial.contentAsString).isEqualTo(201)
        val first = mapper.readTree(initial.contentAsString)
        assertThat(mapper.readTree(report(s.ne,key=key,files=listOf(png)).contentAsString)).isEqualTo(first)
        assertThat(report(s.ne,key=key,notes="Berubah",files=listOf(png)).status).isEqualTo(409)
        assertThat(report(s.ne,revision=1,notes="Catatan revisi",files=listOf(png,png)).status).isEqualTo(201)
        assertThat(report(s.ne,slot="EVENING").status).isEqualTo(201)
        val id = first.path("id").asString()
        val timeline = ok("GET","/api/v1/field/reports/$id/revisions",s.ne)
        assertThat(timeline).hasSize(2)
        assertThat(timeline.first().path("photos")).hasSize(2)
        assertThat(timeline.last().path("notes").asString()).isEqualTo("Pemeriksaan jaringan")
        val photo = first.path("photos").first().path("id").asString()
        val path = "/api/v1/field/reports/$id/revisions/1/photos/$photo"
        assertThat(request("GET",path,s.admin.token).contentAsByteArray).isEqualTo(png)
        assertThat(request("GET",path,s.other.token).status).isEqualTo(404)
        assertThat(request("GET","/api/v1/field/reports/$id/revisions",s.other.token).status).isEqualTo(404)
        val totals = ok("GET","/api/v1/field/reports/completeness?date=$today",s.admin)
        assertThat(totals.path("total").asInt()).isEqualTo(3)
        assertThat(totals.path("morning").asInt()).isEqualTo(1)
        assertThat(totals.path("evening").asInt()).isEqualTo(1)
        assertThat(totals.path("incomplete").asInt()).isEqualTo(2)
        val history = ok("GET","/api/v1/field/reports?month=2026-10&size=1&page=1",s.admin)
        assertThat(history.path("totalElements").asInt()).isEqualTo(2)
        assertThat(history.path("content")).hasSize(1)
        assertThat(ok("GET","/api/v1/field/reports?month=2026-10",s.other).path("content")).isEmpty()
    }
    @Test fun `FO may report holidays are optional and ordinary direct grants cannot submit or see team`() {
        val s = actors(); val foreign = actors()
        assertThat(report(s.fo).status).isEqualTo(201)
        assertThat(report(s.fo,date=today.minusDays(1)).status).isEqualTo(400)
        assertThat(report(s.fo,revision=0).status).isEqualTo(409)
        assertThat(report(s.fo,revision=1,files=listOf("fake".toByteArray())).status).isEqualTo(400)
        listOf(s.admin,s.manager,s.owner).forEach { assertThat(report(it).status).isEqualTo(403) }
        val ordinary = user(s.owner.token,setOf("field.report.all","field.report.submit","kpi.team.view"))
        assertThat(report(Actor(ordinary.first,ordinary.second)).status).isEqualTo(403)
        assertThat(request("GET","/api/v1/field/kpi?month=2026-10",ordinary.first).status).isEqualTo(403)
        assertThat(ok("GET","/api/v1/field/reports?month=2026-10",foreign.owner).path("content")).isEmpty()
        ok("POST","/api/v1/field/calendar/2026/holidays",s.owner,mapOf("expectedRevision" to 0,"date" to today,"name" to "Libur manual"))
        val holiday = ok("GET","/api/v1/field/reports/completeness?date=$today",s.manager)
        assertThat(holiday.path("workingDay").asBoolean()).isFalse()
        assertThat(holiday.path("incomplete").asInt()).isZero()
        assertThat(report(s.ne).status).isEqualTo(201)
    }
    @Test fun `failed second photo rolls back revision and removes newly uploaded evidence`() {
        val s = actors(); val keys = mutableListOf<String>()
        doAnswer { call ->
            keys.add(call.getArgument(0))
            if(keys.size==2) throw IllegalStateException("Simulated storage failure")
            call.callRealMethod()
        }.`when`(storage).put(anyString(),anyString(),any(ByteArray::class.java) ?: byteArrayOf())
        assertThatThrownBy { report(s.ne,files=listOf(png,png)) }
            .hasRootCauseInstanceOf(IllegalStateException::class.java)
            .hasRootCauseMessage("Simulated storage failure")
        assertThat(ok("GET","/api/v1/field/reports?month=2026-10",s.owner).path("content")).isEmpty()
        assertThat(keys).hasSize(2)
        val tenantId = ok("GET","/api/me",s.owner).path("tenantId").asString()
        assertThat(storage.list(tenantId,"$tenantId/daily-reports/").objects).isEmpty()
    }
}
