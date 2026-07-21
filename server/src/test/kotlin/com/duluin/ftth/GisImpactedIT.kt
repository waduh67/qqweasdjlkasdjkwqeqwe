package com.duluin.ftth

import com.duluin.ftth.contract.CollectorProtocol
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * Uji "blast radius" di peta: OLT yang dilaporkan collector tidak terjangkau
 * menjadi alarm, dan alarm itu menjalar ke seluruh kabel di hilirnya (feeder →
 * distribusi → drop) lewat `GET /api/gis/impacted`. Saat OLT pulih, sorotan
 * menutup sendiri.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GisImpactedIT {

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var onboarding: OnboardTenantUseCase

    private val pass = "secret12345"

    private fun uniq() = UUID.randomUUID().toString().substring(0, 8)

    private fun login(slug: String, email: String): String {
        val json = mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"tenantSlug":"$slug","email":"$email","password":"$pass"}"""),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return JsonPath.read(json, "$.accessToken")
    }

    private fun post(url: String, token: String, body: String, expected: Int = 201): String =
        mockMvc.perform(
            post(url).header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect { assertThat(it.response.status).isEqualTo(expected) }
            .andReturn().response.contentAsString

    private fun heartbeat(apiKey: String, body: String): String =
        mockMvc.perform(
            post("/api/collector/heartbeat").header(CollectorProtocol.API_KEY_HEADER, apiKey)
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isOk).andReturn().response.contentAsString

    private fun id(json: String): String = JsonPath.read(json, "$.id")

    private fun impactedCodes(token: String): List<String> {
        val json = mockMvc.perform(get("/api/gis/impacted").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsString
        // ImpactedOverlay tidak mengembalikan `code`; pakai tipe kabel untuk memeriksa.
        return JsonPath.read(json, "$.cables[*].cableType")
    }

    @Test
    fun `OLT tak terjangkau menyorot merah feeder dan distribusi di hilirnya, lalu pulih`() {
        val slug = "blast${uniq()}"
        val admin = "admin@$slug.test"
        onboarding.onboard(OnboardTenantCommand(slug, "Blast Co", admin, "Admin", pass))
        val token = login(slug, admin)
        val s = uniq().uppercase()

        // Rantai POP → OLT → PON → ODC → ODP
        val site = id(
            post("/api/sites", token, """{"code":"POP-$s","name":"POP $s","location":{"longitude":106.98,"latitude":-6.23}}"""),
        )
        val olt = id(
            post(
                "/api/olts", token,
                """{"siteId":"$site","code":"OLT-$s","name":"OLT $s","vendor":"ZTE",
                    "managementIp":"10.0.0.1","snmpCommunity":"rahasia"}""",
            ),
        )
        val pon = id(post("/api/olts/$olt/pon-ports", token, """{"label":"1/1/1"}"""))
        val odc = id(
            post(
                "/api/odcs", token,
                """{"code":"ODC-$s","name":"ODC $s","location":{"longitude":106.99,"latitude":-6.24},
                    "ponPortId":"$pon","splitterRatio":"1:8","capacity":64}""",
            ),
        )
        val odp = id(
            post(
                "/api/odps", token,
                """{"code":"ODP-$s","name":"ODP $s","location":{"longitude":106.995,"latitude":-6.245},
                    "odcId":"$odc","splitterRatio":"1:8","capacity":8}""",
            ),
        )

        // Feeder POP→ODC dan distribusi ODC→ODP
        post(
            "/api/cables", token,
            """{"code":"FDR-$s","name":"Feeder $s","cableType":"FEEDER","coreCount":24,
                "route":[{"longitude":106.98,"latitude":-6.23},{"longitude":106.99,"latitude":-6.24}],
                "fromKind":"SITE","fromId":"$site","toKind":"ODC","toId":"$odc"}""",
        )
        post(
            "/api/cables", token,
            """{"code":"DST-$s","name":"Distribusi $s","cableType":"DISTRIBUTION","coreCount":12,
                "route":[{"longitude":106.99,"latitude":-6.24},{"longitude":106.995,"latitude":-6.245}],
                "fromKind":"ODC","fromId":"$odc","toKind":"ODP","toId":"$odp"}""",
        )

        // Tidak ada alarm → tidak ada kabel merah.
        assertThat(impactedCodes(token)).isEmpty()

        // Collector, denyut pertama untuk memperoleh konfigurasi.
        val apiKey = JsonPath.read<String>(
            post("/api/monitoring/collectors", token, """{"name":"C-$s","pollIntervalSeconds":60}"""),
            "$.apiKey",
        )
        heartbeat(apiKey, """{"agentVersion":"0.2.0"}""")

        // Denyut kedua: OLT dilaporkan gagal → alarm OLT_UNREACHABLE terangkat.
        heartbeat(
            apiKey,
            """{"agentVersion":"0.2.0","lastCycle":{"startedAt":"2026-07-20T05:00:00Z",
                "finishedAt":"2026-07-20T05:00:05Z","targetsPolled":0,"targetsFailed":1,"readingsCollected":0,
                "failures":[{"oltId":"$olt","oltCode":"OLT-$s","message":"timeout SNMP"}]}}""",
        )

        // Blast radius: feeder DAN distribusi ikut merah, keduanya CRITICAL.
        val impacted = impactedCodes(token)
        assertThat(impacted).contains("FEEDER", "DISTRIBUTION")
        val severities: List<String> = JsonPath.read(
            mockMvc.perform(get("/api/gis/impacted").header("Authorization", "Bearer $token"))
                .andReturn().response.contentAsString,
            "$.cables[*].severity",
        )
        assertThat(severities).contains("CRITICAL")

        // Denyut pulih: alarm menutup sendiri → tidak ada lagi kabel merah.
        heartbeat(
            apiKey,
            """{"agentVersion":"0.2.0","lastCycle":{"startedAt":"2026-07-20T05:05:00Z",
                "finishedAt":"2026-07-20T05:05:05Z","targetsPolled":1,"targetsFailed":0,"readingsCollected":24,
                "failures":[]}}""",
        )
        assertThat(impactedCodes(token)).isEmpty()
    }
}
