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
        val overlay = mockMvc.perform(get("/api/gis/impacted").header("Authorization", "Bearer $token"))
            .andReturn().response.contentAsString
        val severities: List<String> = JsonPath.read(overlay, "$.cables[*].severity")
        assertThat(severities).contains("CRITICAL")
        // Tiap kabel merah membawa penyebabnya: alarm OLT tak-terjangkau di hulu,
        // sehingga klik kabel di peta bisa menjawab "kenapa merah".
        val causeKinds: List<String> = JsonPath.read(overlay, "$.cables[*].causes[*].kind")
        assertThat(causeKinds).contains("OLT_UNREACHABLE")
        // Perangkat OLT sendiri ikut tersorot merah (bukan cuma kabelnya) — inilah
        // yang mewarnai marker OLT di peta saat perangkatnya modar.
        val impactedNodeIds: List<String> = JsonPath.read(overlay, "$.nodes[*].id")
        assertThat(impactedNodeIds).contains(olt)
        val oltSeverity: List<String> = JsonPath.read(overlay, "$.nodes[?(@.id=='$olt')].severity")
        assertThat(oltSeverity).containsExactly("CRITICAL")

        // Denyut pulih: alarm menutup sendiri → tidak ada lagi kabel merah maupun simpul terdampak.
        heartbeat(
            apiKey,
            """{"agentVersion":"0.2.0","lastCycle":{"startedAt":"2026-07-20T05:05:00Z",
                "finishedAt":"2026-07-20T05:05:05Z","targetsPolled":1,"targetsFailed":0,"readingsCollected":24,
                "failures":[]}}""",
        )
        assertThat(impactedCodes(token)).isEmpty()
        val recovered = mockMvc.perform(get("/api/gis/impacted").header("Authorization", "Bearer $token"))
            .andReturn().response.contentAsString
        assertThat(JsonPath.read<List<String>>(recovered, "$.nodes[*].id")).isEmpty()
    }

    /**
     * Satu pelanggan bermasalah BUKAN gangguan kotak.
     *
     * Dulu satu alarm ONU langsung memerahkan ODP-nya, dan karena kabel mewarisi
     * warna ujungnya, kabel distribusi yang menyuapi kotak itu ikut merah bersama
     * drop tetangga yang sehat-sehat saja: peta memberitakan satu kotak tumbang
     * padahal yang terganggu satu rumah. Sekarang kotaknya baru merah kalau lebih
     * dari satu penghuninya mengeluh — pola yang benar-benar menunjuk serat atau
     * splitter yang mereka pakai bersama, dan yang memang pantas didatangi.
     */
    @Test
    fun `alarm satu pelanggan tak memerahkan ODP-nya, dua penghuni yang mengeluh baru memerahkan`() {
        val slug = "share${uniq()}"
        val admin = "admin@$slug.test"
        onboarding.onboard(OnboardTenantCommand(slug, "Share Co", admin, "Admin", pass))
        val token = login(slug, admin)
        val s = uniq().uppercase()

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
        post(
            "/api/cables", token,
            """{"code":"DST-$s","name":"Distribusi $s","cableType":"DISTRIBUTION","coreCount":12,
                "route":[{"longitude":106.99,"latitude":-6.24},{"longitude":106.995,"latitude":-6.245}],
                "fromKind":"ODC","fromId":"$odc","toKind":"ODP","toId":"$odp"}""",
        )

        // Dua penghuni kotak yang sama, masing-masing dengan kabel drop-nya sendiri.
        fun tenant(port: Int): Pair<String, String> {
            val customer = id(
                post(
                    "/api/customers", token,
                    """{"code":"C$port-$s","name":"Pelanggan $port $s","address":"Jl. Uji",
                        "location":{"longitude":106.996,"latitude":-6.246}}""",
                ),
            )
            val onu = id(post("/api/customers/$customer/onus", token, """{"serialNumber":"SN$port-$s"}"""))
            post(
                "/api/customers/onus/$onu/attach", token,
                """{"odpId":"$odp","portNumber":$port,"installRxPowerDbm":-22.0}""", expected = 200,
            )
            post(
                "/api/cables", token,
                """{"code":"DRP$port-$s","name":"Drop $port $s","cableType":"DROP","coreCount":1,
                    "route":[{"longitude":106.995,"latitude":-6.245},{"longitude":106.996,"latitude":-6.246}],
                    "fromKind":"ODP","fromId":"$odp","toKind":"CUSTOMER","toId":"$customer"}""",
            )
            return customer to "SN$port-$s"
        }
        val (customer1, serial1) = tenant(1)
        val (customer2, serial2) = tenant(2)

        val apiKey = JsonPath.read<String>(
            post("/api/monitoring/collectors", token, """{"name":"C-$s","pollIntervalSeconds":60}"""),
            "$.apiKey",
        )
        fun reportLos(vararg serials: String) {
            val readings = serials.joinToString(",") {
                """{"serialNumber":"$it","oltCode":"OLT-$s","ponPortLabel":"1/1/1","status":"LOS",
                    "rxPowerDbm":null,"txPowerDbm":null,"uptimeSeconds":null,"distanceMeters":null,
                    "observedAt":"${java.time.Instant.now()}"}"""
            }
            mockMvc.perform(
                post("/api/collector/metrics").header(CollectorProtocol.API_KEY_HEADER, apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"batchId":"${uniq()}","collectedAt":"${java.time.Instant.now()}","readings":[$readings]}""",
                    ),
            ).andExpect(status().isOk)
        }
        fun overlay(): String =
            mockMvc.perform(get("/api/gis/impacted").header("Authorization", "Bearer $token"))
                .andExpect(status().isOk).andReturn().response.contentAsString

        // Satu penghuni putus: rumahnya merah, kotaknya tidak — dan yang tersorot
        // cuma kabel drop miliknya, bukan distribusi yang dipakai bersama.
        reportLos(serial1)
        val alone = overlay()
        val aloneNodes: List<String> = JsonPath.read(alone, "$.nodes[*].id")
        assertThat(aloneNodes).contains(customer1).doesNotContain(odp, customer2)
        assertThat(JsonPath.read<List<String>>(alone, "$.cables[*].cableType"))
            .containsExactly("DROP")

        // Tetangganya menyusul putus: sekarang yang mereka pakai bersama-lah
        // tersangkanya, jadi kotak & kabel distribusinya ikut merah.
        reportLos(serial1, serial2)
        val shared = overlay()
        assertThat(JsonPath.read<List<String>>(shared, "$.nodes[*].id"))
            .contains(customer1, customer2, odp)
        assertThat(JsonPath.read<List<String>>(shared, "$.cables[*].cableType"))
            .contains("DISTRIBUTION", "DROP")
    }
}
