package com.karuhun.ftth.network

import com.karuhun.ftth.iam.application.port.inbound.OnboardTenantCommand
import com.karuhun.ftth.iam.application.port.inbound.OnboardTenantUseCase
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
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PonPortImportIT {
    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var onboard: OnboardTenantUseCase
    private fun post(path: String, token: String, body: String) = mvc.perform(post(path).apply { if (token.isNotEmpty()) header("Authorization", "Bearer $token") }
        .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().response
    private fun get(path: String, token: String) = mvc.perform(get(path).header("Authorization", "Bearer $token")).andReturn().response
    private fun fixture(): Pair<String, String> {
        val slug = "pon-${UUID.randomUUID().toString().take(8)}"
        onboard.onboard(OnboardTenantCommand(slug, slug, "admin@$slug.test", "Admin", "secret12345"))
        val login = post("/api/auth/login", "", """{"tenantSlug":"$slug","email":"admin@$slug.test","password":"secret12345"}""")
        assertThat(login.status).isEqualTo(200)
        val token: String = JsonPath.read(login.contentAsString, "$.accessToken")
        val site = post("/api/sites", token, """{"code":"POP","name":"POP","location":{"longitude":107,"latitude":-6}}""")
        assertThat(site.status).isEqualTo(201)
        val siteId: String = JsonPath.read(site.contentAsString, "$.id")
        val olt = post("/api/olts", token, """{"siteId":"$siteId","code":"OLT","name":"OLT","vendor":"HSGQ","model":"HSGQ-G01ID"}""")
        assertThat(olt.status).isEqualTo(201)
        return token to JsonPath.read(olt.contentAsString, "$.id")
    }
    @Test fun `manual HSGQ labels and legacy notation work while repeated import preserves existing identity and status`() {
        val (token, olt) = fixture()
        val manual = post("/api/olts/$olt/pon-ports", token, """{"label":"pon01","description":"Keep this","status":"MAINTENANCE"}""")
        assertThat(manual.status).isEqualTo(201)
        val portId: String = JsonPath.read(manual.contentAsString, "$.id")
        assertThat(JsonPath.read<String>(manual.contentAsString, "$.label")).isEqualTo("PON1")
        assertThat(post("/api/olts/$olt/pon-ports", token, """{"label":"PON1"}""").status).isEqualTo(409)
        repeat(2) { round ->
            val result = post("/api/olts/$olt/pon-ports/import", token, """{"labels":["PON1","pon02","PON2","0/1/1"]}""")
            assertThat(result.status).isEqualTo(200)
            assertThat(JsonPath.read<Int>(result.contentAsString, "$.created")).isEqualTo(if (round == 0) 2 else 0)
        }
        val ports = get("/api/olts/$olt/pon-ports", token)
        assertThat(JsonPath.read<List<String>>(ports.contentAsString, "$[*].label")).containsExactlyInAnyOrder("PON1", "PON2", "0/1/1")
        assertThat(JsonPath.read<List<String>>(ports.contentAsString, "$[?(@.label == 'PON1')].id")).containsExactly(portId)
        assertThat(JsonPath.read<List<String>>(ports.contentAsString, "$[?(@.label == 'PON1')].status")).containsExactly("MAINTENANCE")
        assertThat(JsonPath.read<List<String>>(ports.contentAsString, "$[?(@.label == 'PON1')].description")).containsExactly("Keep this")
    }
    @Test fun `invalid batches are atomic and another tenant cannot read discover or import these ports`() {
        val (token, olt) = fixture()
        assertThat(post("/api/olts/$olt/pon-ports/import", token, """{"labels":["PON1","bad"]}""").status).isEqualTo(400)
        assertThat(post("/api/olts/$olt/pon-ports/import", token, """{"labels":[]}""").status).isEqualTo(400)
        assertThat(get("/api/olts/$olt/pon-ports", token).contentAsString).isEqualTo("[]")
        val (other, _) = fixture()
        assertThat(get("/api/olts/$olt/pon-ports", other).status).isEqualTo(404)
        assertThat(get("/api/monitoring/olts/$olt/pon-ports", other).status).isEqualTo(404)
        assertThat(post("/api/olts/$olt/pon-ports/import", other, """{"labels":["PON1"]}""").status).isEqualTo(404)
    }
    @Test fun `concurrent retries insert each port once`() {
        val (token, olt) = fixture()
        val requests = (1..2).map { CompletableFuture.supplyAsync {
            post("/api/olts/$olt/pon-ports/import", token, """{"labels":["PON1","PON2"]}""")
        } }
        val results = requests.map { it.get(20, TimeUnit.SECONDS) }
        assertThat(results.map { it.status }).containsOnly(200)
        assertThat(results.sumOf { JsonPath.read<Int>(it.contentAsString, "$.created") }).isEqualTo(2)
        assertThat(JsonPath.read<List<String>>(get("/api/olts/$olt/pon-ports", token).contentAsString, "$[*].label")).containsExactly("PON1", "PON2")
    }
}
