package com.duluin.ftth.fieldservice

import com.duluin.ftth.iam.application.port.inbound.OnboardTenantCommand
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantUseCase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.mockito.Mockito.doReturn
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import com.duluin.ftth.fieldservice.application.service.B2BOperationalClock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class B2BMonthlyIT {
    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var onboarding: OnboardTenantUseCase
    @MockitoSpyBean private lateinit var clock: B2BOperationalClock
    private val mapper=jacksonObjectMapper()
    private val month=YearMonth.of(2026,10)
    @BeforeEach fun operationalDate() { doReturn(month.atDay(10)).`when`(clock).today() }
    private val png=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")
    private data class Actor(val token: String,val id: String)
    private data class Setup(val owner: Actor,val admin: Actor,val ne: Actor,val other: Actor,val fo: Actor,val manager: Actor)
    private fun req(method: String,path: String,token: String,body: String?=null,key: String=UUID.randomUUID().toString()) =
        mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method),path).header("Authorization","Bearer $token")
            .header("Idempotency-Key",key).apply { if(body!=null) contentType(MediaType.APPLICATION_JSON).content(body) }).andReturn().response
    private fun ok(method: String,path: String,actor: Actor,body: String?=null,status: Int=200,key: String=UUID.randomUUID().toString()): JsonNode {
        val result=req(method,path,actor.token,body,key)
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(status)
        return mapper.readTree(result.contentAsString)
    }
    private fun setup(): Setup {
        val slug="b2b-${UUID.randomUUID().toString().take(8)}"
        val email="owner@$slug.test"
        onboarding.onboard(OnboardTenantCommand(slug,"B2B",email,"Owner","secret12345"))
        fun login(email: String): Actor {
            val response=mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("tenantSlug" to slug,"email" to email,"password" to "secret12345")))).andReturn().response
            assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
            val token=mapper.readTree(response.contentAsString).path("accessToken").asString()
            return Actor(token,mapper.readTree(req("GET","/api/me",token).contentAsString).path("id").asString())
        }
        val owner=login(email)
        fun member(roleName: String): Actor {
            val role=ok("GET","/api/roles",owner).single { it.path("name").asString()==roleName }.path("id").asString()
            val memberEmail="member-${UUID.randomUUID()}@$slug.test"
            ok("POST","/api/users",owner,mapper.writeValueAsString(mapOf("name" to roleName,"email" to memberEmail,
                "password" to "secret12345","roleIds" to listOf(role))),201)
            return login(memberEmail)
        }
        return Setup(owner,member("Admin"),member("Teknisi NE"),member("Teknisi NE"),member("Teknisi FO"),member("Manager"))
    }
    private fun client(s: Setup,target: Int=4)=ok("POST","/api/v1/b2b/clients",s.admin,body(s.ne.id,target),201)
    private fun body(tech: String,target: Int=4,revision: Long=0,active: Boolean=true,name: String="Kantor B2B")=
        mapper.writeValueAsString(mapOf("name" to name,"address" to "Jl. Kantor 12","contact" to "PIC 08123456789",
            "technicianId" to tech,"target" to target,"expectedRevision" to revision,"active" to active))
    private fun visit(id: String,actor: Actor,notes: String="Periksa koneksi",key: String=UUID.randomUUID().toString(),bytes: ByteArray=png,photoCount: Int=1)=
        mvc.perform(multipart("/api/v1/b2b/clients/$id/visits").apply {
            repeat(photoCount) { file(MockMultipartFile("photos","proof.png","image/png",bytes)) }
            param("notes",notes); header("Authorization","Bearer ${actor.token}"); header("Idempotency-Key",key)
        }).andReturn().response

    @Test fun `client target assignment and active edits are frozen until next month`() {
        val s=setup(); val created=client(s); val id=created.path("id").asString()
        assertThat(created.path("current").path("setting").path("target").asInt()).isEqualTo(4)
        val updated=ok("PUT","/api/v1/b2b/clients/$id",s.admin,body(s.other.id,6,0,false,"Nama baru"))
        assertThat(updated.path("current").path("setting").path("name").asString()).isEqualTo("Kantor B2B")
        assertThat(updated.path("current").path("setting").path("technicianId").asString()).isEqualTo(s.ne.id)
        assertThat(updated.path("current").path("setting").path("active").asBoolean()).isTrue()
        assertThat(updated.path("next").path("target").asInt()).isEqualTo(6)
        assertThat(updated.path("next").path("active").asBoolean()).isFalse()
        assertThat(visit(id,s.ne).status).isEqualTo(201)
        assertThat(visit(id,s.other).status).isEqualTo(404)
        assertThat(req("GET","/api/v1/b2b/reports?month=${month.plusMonths(1)}",s.admin.token).status).isEqualTo(400)
        assertThat(ok("GET","/api/v1/b2b/reports?month=${month.minusMonths(1)}",s.admin).path("content")).isEmpty()
        assertThat(req("PUT","/api/v1/b2b/clients/$id",s.admin.token,body(s.ne.id)).status).isEqualTo(409)
    }
    @Test fun `same day extras exact retry and changed retry preserve one counted visit and private proof`() {
        val s=setup(); val id=client(s).path("id").asString(); val key=UUID.randomUUID().toString()
        val first=visit(id,s.ne,key=key,photoCount=2)
        assertThat(first.status).withFailMessage(first.contentAsString).isEqualTo(201)
        val replay=visit(id,s.ne,key=key,photoCount=2)
        assertThat(replay.status).isEqualTo(201)
        assertThat(mapper.readTree(replay.contentAsString)).isEqualTo(mapper.readTree(first.contentAsString))
        assertThat(visit(id,s.ne,notes="Berubah",key=key,photoCount=2).status).isEqualTo(409)
        val extra=visit(id,s.ne)
        assertThat(extra.status).isEqualTo(201)
        assertThat(mapper.readTree(extra.contentAsString).path("counted").asBoolean()).isFalse()
        val report=ok("GET","/api/v1/b2b/reports?month=$month",s.admin).path("content").single()
        assertThat(report.path("counted").asInt()).isEqualTo(1)
        assertThat(report.path("remaining").asInt()).isEqualTo(3)
        assertThat(report.path("weeks").sumOf { it.path("counted").asInt() }).isEqualTo(1)
        val weeks=report.path("weeks")
        assertThat(weeks.first().path("start").asString()).isEqualTo(month.atDay(1).toString())
        assertThat(weeks.last().path("end").asString()).isEqualTo(month.atEndOfMonth().toString())
        weeks.forEach { week ->
            if(LocalDate.parse(week.path("end").asString())>=month.atDay(10))
                assertThat(week.path("missed").asBoolean()).isFalse()
        }
        val saved=mapper.readTree(first.contentAsString); val visitId=saved.path("id").asString()
        val photoId=saved.path("photos").first().path("id").asString(); val path="/api/v1/b2b/visits/$visitId/photos/$photoId"
        assertThat(req("GET",path,s.admin.token).contentAsByteArray).isEqualTo(png)
        assertThat(req("GET",path,s.ne.token).status).isEqualTo(200)
        assertThat(req("GET",path,s.other.token).status).isEqualTo(404)
        assertThat(req("DELETE","/api/v1/b2b/clients/$id?expectedRevision=0",s.admin.token).status).isEqualTo(409)
    }
    @Test fun `roles tenant isolation validation and client creation retries are enforced`() {
        val s=setup(); val foreign=setup(); val key=UUID.randomUUID().toString()
        val saved=ok("POST","/api/v1/b2b/clients",s.admin,body(s.ne.id),201,key)
        assertThat(ok("POST","/api/v1/b2b/clients",s.admin,body(s.ne.id),201,key)).isEqualTo(saved)
        assertThat(req("POST","/api/v1/b2b/clients",s.admin.token,body(s.ne.id,7),key).status).isEqualTo(409)
        assertThat(req("POST","/api/v1/b2b/clients",s.admin.token,body(s.fo.id)).status).isEqualTo(400)
        assertThat(req("POST","/api/v1/b2b/clients",s.admin.token,body(foreign.ne.id)).status).isEqualTo(400)
        val id=saved.path("id").asString()
        listOf(s.fo,s.manager,s.admin).forEach { assertThat(visit(id,it).status).isEqualTo(403) }
        assertThat(visit(id,foreign.ne).status).isEqualTo(404)
        assertThat(visit(id,s.ne,bytes="fake".toByteArray()).status).isEqualTo(400)
        assertThat(visit(id,s.ne,notes=" ").status).isEqualTo(400)
        assertThat(visit(id,s.ne,photoCount=0).status).isEqualTo(400)
        assertThat(ok("GET","/api/v1/b2b/visits?month=$month",s.ne).path("content")).isEmpty()
        assertThat(req("GET","/api/v1/b2b/clients",s.ne.token).status).isEqualTo(403)
        val technicianIds = ok("GET","/api/v1/b2b/technicians",s.admin).path("content").values().map { it.path("id").asString() }.toSet()
        assertThat(technicianIds == setOf(s.ne.id, s.other.id)).withFailMessage("NE selector returned %s", technicianIds).isTrue()
        val firstPage=ok("GET","/api/v1/b2b/technicians?size=1",s.admin)
        val secondPage=ok("GET","/api/v1/b2b/technicians?size=1&page=1",s.admin)
        assertThat(firstPage.path("totalElements").asInt()).isEqualTo(2)
        assertThat(secondPage.path("totalElements").asInt()).isEqualTo(2)
        assertThat(firstPage.path("content").first().path("id").asString()).isNotEqualTo(secondPage.path("content").first().path("id").asString())
        assertThat(ok("GET","/api/v1/b2b/reports?month=$month",foreign.admin).path("content")).isEmpty()
        assertThat(req("DELETE","/api/v1/b2b/clients/$id?expectedRevision=0",s.admin.token).status).isEqualTo(204)
    }
    @Test fun `skipped months retain effective assignments inactive weeks and original reports`() {
        val s=setup(); val id=client(s).path("id").asString()
        assertThat(visit(id,s.ne).status).isEqualTo(201)
        ok("PUT","/api/v1/b2b/clients/$id",s.admin,body(s.other.id,6,0,false,"Bulan berikutnya"))
        doReturn(month.plusMonths(3).atDay(10)).`when`(clock).today()
        val now=ok("GET","/api/v1/b2b/reports?month=${month.plusMonths(3)}",s.admin).path("content").single()
        assertThat(now.path("setting").path("name").asString()).isEqualTo("Bulan berikutnya")
        assertThat(now.path("setting").path("technicianId").asString()).isEqualTo(s.other.id)
        assertThat(visit(id,s.other).status).isEqualTo(400)
        val skipped=ok("GET","/api/v1/b2b/reports?month=${month.plusMonths(1)}",s.admin).path("content").single()
        assertThat(skipped.path("setting").path("target").asInt()).isEqualTo(6)
        assertThat(skipped.path("weeks").any { it.path("missed").asBoolean() }).isFalse()
        val original=ok("GET","/api/v1/b2b/reports?month=$month",s.admin).path("content").single()
        assertThat(original.path("setting").path("name").asString()).isEqualTo("Kantor B2B")
        assertThat(original.path("counted").asInt()).isEqualTo(1)
        assertThat(original.path("weeks").any { it.path("missed").asBoolean() }).isTrue()
        assertThat(ok("GET","/api/v1/b2b/reports?month=$month",s.other).path("content")).isEmpty()
    }
    @Test fun `concurrent same day visits are counted once and exact concurrent retry is replayed`() {
        val s=setup(); val id=client(s).path("id").asString()
        val executor=java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val start=java.util.concurrent.CountDownLatch(1)
            val commands=(1..2).map { executor.submit<Int> {
                start.await(); visit(id,s.ne).status
            } }
            start.countDown()
            commands.forEach { assertThat(it.get(30,java.util.concurrent.TimeUnit.SECONDS).toInt()).isEqualTo(201) }
            val report=ok("GET","/api/v1/b2b/reports?month=$month",s.admin).path("content").single()
            assertThat(report.path("counted").asInt()).isEqualTo(1)
            assertThat(ok("GET","/api/v1/b2b/visits?month=$month",s.ne).path("totalElements").asInt()).isEqualTo(2)
            val key=UUID.randomUUID().toString()
            val retries=(1..2).map { executor.submit<String> {
                val result=visit(id,s.ne,key=key); assertThat(result.status).isEqualTo(201); result.contentAsString
            } }
            assertThat(retries[0].get(30,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(retries[1].get(30,java.util.concurrent.TimeUnit.SECONDS))
        } finally { executor.shutdownNow() }
    }
}
