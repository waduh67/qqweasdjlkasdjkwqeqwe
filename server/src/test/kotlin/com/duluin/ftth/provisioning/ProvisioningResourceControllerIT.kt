package com.duluin.ftth.provisioning

import com.duluin.ftth.iam.application.port.inbound.OnboardTenantCommand
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantUseCase
import com.jayway.jsonpath.JsonPath
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProvisioningResourceControllerIT {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var onboarding: OnboardTenantUseCase

    @Test
    fun `tenant CRUD persists revisions rejects stale update and remains isolated`() {
        val first = tenant("resource-a")
        val second = tenant("resource-b")
        val poolBody = postJson(
            "/api/provisioning/vlan-pools",
            first.token,
            """{"name":"Residential","vlanStart":100,"vlanEnd":199,"reserved":[]}""",
            201,
        )
        val poolId: String = JsonPath.read(poolBody, "$.value.id")
        assertThat(JsonPath.read<Int>(poolBody, "$.revision")).isEqualTo(1)

        val stale = sendJson(
            "PUT",
            "/api/provisioning/vlan-pools/$poolId",
            first.token,
            """{"revision":9,"name":"Changed","vlanStart":100,"vlanEnd":199,"reserved":[]}""",
        )
        assertThat(stale).isEqualTo(409)

        val profile = postJson(
            "/api/provisioning/segment-profiles",
            first.token,
            """{"name":"Default","poolId":"$poolId"}""",
            201,
        )
        val profileId: String = JsonPath.read(profile, "$.value.id")
        postJson(
            "/api/provisioning/intents",
            first.token,
            """{"subscriptionId":"${UUID.randomUUID()}","segmentProfileId":"$profileId","dedicatedVlanId":null}""",
            201,
        )

        val firstPools = getJson("/api/provisioning/vlan-pools", first.token)
        val secondPools = getJson("/api/provisioning/vlan-pools", second.token)
        val audit = getJson("/api/audit-logs?page=0&size=100", first.token)
        assertThat(JsonPath.read<List<String>>(firstPools, "\$[*].value.id")).contains(poolId)
        assertThat(JsonPath.read<List<String>>(secondPools, "\$[*].value.id")).doesNotContain(poolId)
        assertThat(JsonPath.read<List<String>>(audit, "$.content[*].action"))
            .contains("provisioning.vlan_pool.created")
    }

    private fun tenant(prefix: String): Session {
        val slug = "$prefix-${UUID.randomUUID().toString().take(8)}"
        val email = "admin@$slug.test"
        val tenant = onboarding.onboard(OnboardTenantCommand(slug, slug, email, "Admin", PASSWORD)).tenant
        return Session(tenant.id, login(slug, email))
    }

    private fun login(slug: String, email: String): String {
        val response = mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"tenantSlug":"$slug","email":"$email","password":"$PASSWORD"}"""),
        ).andReturn().response.contentAsString
        return JsonPath.read(response, "$.accessToken")
    }

    private fun postJson(url: String, token: String, body: String, expected: Int): String {
        val response = mockMvc.perform(
            post(url).header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON).content(body),
        ).andReturn().response
        assertThat(response.status).isEqualTo(expected)
        return response.contentAsString
    }

    private fun sendJson(method: String, url: String, token: String, body: String): Int {
        val builder = if (method == "PUT") put(url) else post(url)
        return mockMvc.perform(
            builder.header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON).content(body),
        ).andReturn().response.status
    }

    private fun getJson(url: String, token: String): String = mockMvc.perform(
        get(url).header("Authorization", "Bearer $token"),
    ).andReturn().response.contentAsString

    private data class Session(val tenantId: UUID, val token: String)

    private companion object { const val PASSWORD = "secret12345" }
}
