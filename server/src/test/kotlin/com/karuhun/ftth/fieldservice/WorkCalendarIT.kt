package com.karuhun.ftth.fieldservice

import com.karuhun.ftth.common.domain.error.ValidationException
import com.karuhun.ftth.fieldservice.application.service.HolidaySource
import com.karuhun.ftth.iam.application.port.inbound.OnboardTenantCommand
import com.karuhun.ftth.iam.application.port.inbound.OnboardTenantUseCase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkCalendarIT {
    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var onboarding: OnboardTenantUseCase
    @MockitoSpyBean private lateinit var source: HolidaySource
    private val mapper = jacksonObjectMapper()
    private data class Actors(val owner: String, val admin: String, val fo: String)
    private fun request(method: String, path: String, token: String, body: Any? = null, key: String = UUID.randomUUID().toString()) =
        mvc.perform(request(HttpMethod.valueOf(method), path).header("Authorization", "Bearer $token").header("Idempotency-Key", key)
            .apply { if (body != null) contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)) }).andReturn().response
    private fun ok(method: String, path: String, token: String, body: Any? = null, key: String = UUID.randomUUID().toString()): JsonNode {
        val response = request(method, path, token, body, key)
        assertThat(response.status).withFailMessage(response.contentAsString).isBetween(200, 201)
        return mapper.readTree(response.contentAsString)
    }
    private fun setup(): Actors {
        val slug = "calendar-${UUID.randomUUID().toString().take(8)}"
        val ownerEmail = "owner@$slug.test"
        onboarding.onboard(OnboardTenantCommand(slug, "Calendar", ownerEmail, "Owner", "secret12345"))
        fun login(email: String): String {
            val result = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(
                mapOf("tenantSlug" to slug, "email" to email, "password" to "secret12345")))).andReturn().response
            assertThat(result.status).isEqualTo(200)
            return mapper.readTree(result.contentAsString).path("accessToken").asString()
        }
        val owner = login(ownerEmail)
        fun member(name: String): String {
            val role = ok("GET", "/api/roles", owner).single { it.path("name").asString() == name }.path("id").asString()
            val email = "${UUID.randomUUID()}@$slug.test"
            ok("POST", "/api/users", owner, mapOf("name" to name, "email" to email, "password" to "secret12345", "roleIds" to listOf(role)))
            return login(email)
        }
        return Actors(owner, member("Admin"), member("Teknisi FO"))
    }
    private fun holiday(revision: Long, date: String, name: String, previous: String? = null) =
        mapOf("expectedRevision" to revision, "date" to date, "name" to name, "previousDate" to previous)
    @Test fun `owner commands preserve overrides across refresh cross year moves and failed sync`() {
        val s = setup()
        doReturn(mapOf("2026-01-01" to "Imported", "2026-10-14" to "Original")).`when`(source).fetch(2026)
        val initial = ok("GET", "/api/v1/field/calendar/2026", s.owner)
        assertThat(initial.path("revision").asLong()).isEqualTo(1)
        val renamed = ok("POST", "/api/v1/field/calendar/2026/holidays", s.owner, holiday(1, "2026-01-01", "Nama manual", "2026-01-01"))
        val moved = ok("POST", "/api/v1/field/calendar/2026/holidays", s.owner, holiday(renamed.path("revision").asLong(), "2027-01-04", "Dipindah", "2026-10-14"))
        assertThat(moved.path("holidays").asSequence().map { it.path("date").asString() }.toList()).isEqualTo(listOf("2026-01-01"))
        val key = UUID.randomUUID().toString()
        val input = mapOf("expectedRevision" to moved.path("revision").asLong())
        val refreshed = ok("POST", "/api/v1/field/calendar/2026/sync", s.owner, input, key)
        assertThat(refreshed.path("holidays").single().path("name").asString()).isEqualTo("Nama manual")
        doThrow(ValidationException("Sumber gagal")).`when`(source).fetch(2026)
        assertThat(ok("POST", "/api/v1/field/calendar/2026/sync", s.owner, input, key)).isEqualTo(refreshed)
        assertThat(request("POST", "/api/v1/field/calendar/2026/sync", s.owner, mapOf("expectedRevision" to refreshed.path("revision").asLong())).status).isEqualTo(400)
        assertThat(ok("GET", "/api/v1/field/calendar/2026", s.owner)).isEqualTo(refreshed)
        doThrow(ValidationException("Belum tersedia")).`when`(source).fetch(2027)
        val destination = ok("GET", "/api/v1/field/calendar/2027", s.owner)
        assertThat(destination.path("holidays").single().path("name").asString()).isEqualTo("Dipindah")
        assertThat(destination.path("syncError").asString()).isEqualTo("Belum tersedia")
        val removed = ok("DELETE", "/api/v1/field/calendar/2026/holidays/2026-01-01", s.owner, mapOf("expectedRevision" to refreshed.path("revision").asLong()))
        assertThat(removed.path("holidays")).isEmpty()
    }
    @Test fun `settings commands enforce owner isolation exact replay revisions and tenant scope`() {
        val s = setup(); val other = setup()
        val input = mapOf("expectedRevision" to 0, "saturdayOff" to false, "foOverdueLimit" to 5, "foPointTarget" to 21)
        val key = UUID.randomUUID().toString()
        val saved = ok("PUT", "/api/v1/field/settings", s.owner, input, key)
        assertThat(ok("PUT", "/api/v1/field/settings", s.owner, input, key)).isEqualTo(saved)
        assertThat(request("PUT", "/api/v1/field/settings", s.owner, input).status).isEqualTo(409)
        assertThat(request("PUT", "/api/v1/field/settings", s.owner, input + ("foPointTarget" to 22), key).status).isEqualTo(409)
        assertThat(request("PUT", "/api/v1/field/settings", s.admin, input).status).isEqualTo(403)
        assertThat(request("PUT", "/api/v1/field/settings", s.fo, input).status).isEqualTo(403)
        assertThat(request("GET", "/api/v1/field/settings", s.admin).status).isEqualTo(403)
        assertThat(ok("GET", "/api/v1/field/settings", other.owner).path("saturdayOff").asBoolean()).isTrue()
        doReturn(mapOf("2026-01-01" to "Tahun baru")).`when`(source).fetch(2026)
        assertThat(ok("GET", "/api/v1/field/calendar/2026", s.fo).path("saturdayOff").asBoolean()).isFalse()
        assertThat(ok("GET", "/api/v1/field/calendar/2026", s.admin).path("holidays")).hasSize(1)
        assertThat(request("POST", "/api/v1/field/calendar/2026/holidays", s.admin, holiday(1, "2026-10-16", "Denied")).status).isEqualTo(403)
        assertThat(request("GET", "/api/v1/field/calendar/1999", s.owner).status).isEqualTo(400)
    }
}
