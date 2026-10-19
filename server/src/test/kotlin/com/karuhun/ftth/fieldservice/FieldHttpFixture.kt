package com.karuhun.ftth.fieldservice

import com.karuhun.ftth.fieldservice.application.service.B2BOperationalClock
import com.karuhun.ftth.fieldservice.application.service.HolidaySource
import com.karuhun.ftth.inventory.WarehouseMasterHttpFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.mockito.Mockito.doReturn
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.util.UUID

abstract class FieldHttpFixture: WarehouseMasterHttpFixture() {
    @MockitoSpyBean protected lateinit var clock: B2BOperationalClock
    @MockitoSpyBean protected lateinit var holidays: HolidaySource
    protected val today = LocalDate.of(2026,10,16)
    protected val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")
    protected data class Actor(val token: String,val id: String)
    protected data class Actors(val owner: Actor,val admin: Actor,val ne: Actor,val other: Actor,val fo: Actor,val manager: Actor)
    @BeforeEach fun clockAndCalendar() { doReturn(today).`when`(clock).today(); doReturn(emptyMap<String,String>()).`when`(holidays).fetch(2026) }
    protected fun ok(method: String,path: String,actor: Actor,body: Any?=null,status: Int=200,key: String=UUID.randomUUID().toString()): JsonNode {
        val response = request(method,path,actor.token,body?.let(mapper::writeValueAsString),key)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(status)
        return mapper.readTree(response.contentAsString)
    }
    protected fun actors(): Actors {
        val ownerToken = tenant()
        val owner = Actor(ownerToken,mapper.readTree(request("GET","/api/me",ownerToken).contentAsString).path("id").asString())
        val slug = ok("GET","/api/me",owner).path("email").asString().substringAfter('@').substringBefore(".test")
        fun member(name: String): Actor {
            val role = ok("GET","/api/roles",owner).single { it.path("name").asString()==name }.path("id").asString()
            val email = "${UUID.randomUUID()}@$slug.test"
            val saved = ok("POST","/api/users",owner,mapOf("name" to name,"email" to email,"password" to "secret12345","roleIds" to listOf(role)),201)
            val id = saved.path("id").asString()
            ok("PUT","/api/users/$id/access",owner,mapOf("roleIds" to listOf(role),"areaIds" to listOf(area(ownerToken))))
            return Actor(login(slug,email),id)
        }
        return Actors(owner,member("Admin"),member("Teknisi NE"),member("Teknisi NE"),member("Teknisi FO"),member("Manager"))
    }
    protected fun report(actor: Actor,slot: String="MORNING",revision: Long=0,notes: String="Pemeriksaan jaringan",
        date: LocalDate=today,key: String=UUID.randomUUID().toString(),files: List<ByteArray> = emptyList()) =
        mvc.perform(multipart("/api/v1/field/reports").apply {
            files.forEach { file(MockMultipartFile("photos","proof.png","image/png",it)) }
            param("date",date.toString()); param("slot",slot); param("notes",notes); param("expectedRevision",revision.toString())
            header("Authorization","Bearer ${actor.token}"); header("Idempotency-Key",key)
        }).andReturn().response
}
