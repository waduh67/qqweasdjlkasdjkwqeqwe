package com.duluin.ftth

import org.assertj.core.api.Assertions.assertThat
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.http.HttpMethod
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

internal object WorkOrderSettlementTestSetup {
    private val mapper = jacksonObjectMapper()

    fun create(mvc: MockMvc, admin: String, body: String): String {
        val meResponse = mvc.perform(request(HttpMethod.GET, "/api/me").header("Authorization", "Bearer $admin")).andReturn().response
        assertThat(meResponse.status).isEqualTo(200)
        val me = mapper.readTree(meResponse.contentAsString)
        val areaResponse = mvc.perform(request(HttpMethod.POST, "/api/areas").header("Authorization", "Bearer $admin")
            .contentType(MediaType.APPLICATION_JSON).content("""{"code":"SETTLEMENT","name":"Settlement test area"}""")).andReturn().response
        assertThat(areaResponse.status).isEqualTo(201)
        val area = mapper.readTree(areaResponse.contentAsString).path("id").asString()
        val access = mvc.perform(request(HttpMethod.PUT, "/api/users/${me.path("id").asString()}/access")
            .header("Authorization", "Bearer $admin").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(mapOf("roleIds" to me.path("roleIds"), "areaIds" to listOf(area))))).andReturn().response
        assertThat(access.status).isEqualTo(200)
        val response = mvc.perform(request(HttpMethod.POST, "/api/work-orders").header("Authorization", "Bearer $admin")
            .contentType(MediaType.APPLICATION_JSON).content(body.dropLast(1) + ",\"areaId\":\"$area\"}")).andReturn().response
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(201)
        return response.contentAsString
    }

    fun prepare(mvc: MockMvc, admin: String, workOrder: String) {
        fun call(method: HttpMethod, path: String, body: String = "", token: String = admin): JsonNode {
            val response = mvc.perform(request(method, path).header("Authorization", "Bearer $token")
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().response
            assertThat(response.status).withFailMessage(response.contentAsString).isIn(200, 201)
            return mapper.readTree(response.contentAsString)
        }
        val me = call(HttpMethod.GET, "/api/me")
        val area = me.path("areaIds")[0].asString()
        val job = call(HttpMethod.GET, "/api/work-orders/$workOrder").path("workOrder")
        val technician = job.path("assignees")[0].path("id").asString()
        val user = call(HttpMethod.GET, "/api/users/$technician")
        for (principal in listOf(me, user)) {
            call(HttpMethod.PUT, "/api/users/${principal.path("id").asString()}/access",
                mapper.writeValueAsString(mapOf("roleIds" to principal.path("roleIds"), "areaIds" to listOf(area))))
        }
        val material = call(HttpMethod.GET, "/api/work-orders/$workOrder/materials")
        val revision = material.path("revisions").path("workOrderRevision").asLong()
        call(HttpMethod.PUT, "/api/work-orders/$workOrder/materials/plan",
            """{"expectedRevision":0,"workOrderRevision":$revision,"materialMode":"NONE","reason":"No physical material in this service lifecycle fixture","lines":[]}""")
        call(HttpMethod.POST, "/api/work-orders/$workOrder/materials/submit-request", """{"expectedRevision":1,"workOrderRevision":$revision}""")
        val login = call(HttpMethod.POST, "/api/auth/login", mapper.writeValueAsString(mapOf("tenantSlug" to me.path("tenantSlug").asString(),
            "email" to user.path("email").asString(), "password" to "secret12345")))
        call(HttpMethod.POST, "/api/work-orders/$workOrder/materials/report-use",
            """{"expectedRevision":0,"planRevision":1,"workOrderRevision":$revision,"materialMode":"NONE","evidenceReference":"service-lifecycle-fixture","reason":"No physical material","lines":[]}""",
            login.path("accessToken").asString())
    }
}
