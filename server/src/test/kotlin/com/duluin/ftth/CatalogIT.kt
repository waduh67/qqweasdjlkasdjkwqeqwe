package com.duluin.ftth

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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * Uji modul CATALOG: paket internet sebagai sumber tunggal. Menegakkan yang paling
 * gampang salah: generator Mikrotik-Rate-Limit yang dirakit server & muncul di respons
 * (operator tak mengetik profil sendiri), validasi kaitan burst≥rate, keunikan nama,
 * nonaktifkan alih-alih hapus keras, isolasi tenant (RLS), dan izin.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CatalogIT {

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

    private fun newTenantAdmin(prefix: String): String {
        val slug = "$prefix${uniq()}"
        val admin = "admin@$slug.test"
        onboarding.onboard(OnboardTenantCommand(slug, "Tenant $slug", admin, "Admin", pass))
        return login(slug, admin)
    }

    private fun post(url: String, token: String, body: String, expected: Int = 201): String =
        mockMvc.perform(
            post(url).header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect { assertThat(it.response.status).isEqualTo(expected) }
            .andReturn().response.contentAsString

    private fun put(url: String, token: String, body: String, expected: Int = 200): String =
        mockMvc.perform(
            put(url).header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect { assertThat(it.response.status).isEqualTo(expected) }
            .andReturn().response.contentAsString

    private fun getJson(url: String, token: String): String =
        mockMvc.perform(get(url).header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsString

    private fun id(json: String): String = JsonPath.read(json, "$.id")

    @Test
    fun `buat paket kaya merakit rate-limit yang tampil di respons`() {
        val token = newTenantAdmin("cat")
        val body = post(
            "/api/catalog/plans", token,
            """
            {"name":"Home 50/10","description":"Paket rumahan","price":150000,
             "downMbps":50,"upMbps":10,"downBurstMbps":100,"upBurstMbps":20,
             "downThresholdMbps":75,"upThresholdMbps":15,"burstTimeSec":8,
             "priority":5,"connectionLimit":2,"serviceTypes":["PPPOE"]}
            """.trimIndent(),
        )
        // Server merakit string Mikrotik-Rate-Limit (urutan up/down) — bukan diketik operator.
        assertThat(JsonPath.read<String>(body, "$.rateLimit")).isEqualTo("10M/50M 20M/100M 15M/75M 8/8 5")
        assertThat(JsonPath.read<Boolean>(body, "$.active")).isTrue()
        assertThat(JsonPath.read<Int>(body, "$.price")).isEqualTo(150000)
        assertThat(JsonPath.read<List<String>>(body, "$.serviceTypes")).containsExactly("PPPOE")
    }

    @Test
    fun `paket FUP mengembalikan rate throttle terpisah`() {
        val token = newTenantAdmin("cat-fup")
        val body = post(
            "/api/catalog/plans", token,
            """
            {"name":"Unli 100","description":null,"price":300000,"downMbps":100,"upMbps":20,
             "fupEnabled":true,"fupQuotaMb":500000,"fupDownMbps":10,"fupUpMbps":4,"serviceTypes":["PPPOE"]}
            """.trimIndent(),
        )
        assertThat(JsonPath.read<String>(body, "$.rateLimit")).isEqualTo("20M/100M")
        assertThat(JsonPath.read<String>(body, "$.fupRateLimit")).isEqualTo("4M/10M")
        assertThat(JsonPath.read<Boolean>(body, "$.fupEnabled")).isTrue()
    }

    @Test
    fun `burst lebih kecil dari rate ditolak 400`() {
        val token = newTenantAdmin("cat-bad")
        post(
            "/api/catalog/plans", token,
            """{"name":"Aneh","description":null,"price":100000,"downMbps":50,"upMbps":10,
                "downBurstMbps":40,"upBurstMbps":20,"serviceTypes":["PPPOE"]}""",
            expected = 400,
        )
    }

    @Test
    fun `nama paket unik per tenant`() {
        val token = newTenantAdmin("cat-uniq")
        val payload = """{"name":"Sama","description":null,"price":100000,"downMbps":20,"upMbps":10,"serviceTypes":["PPPOE"]}"""
        post("/api/catalog/plans", token, payload)
        post("/api/catalog/plans", token, payload, expected = 409)
    }

    @Test
    fun `ubah paket dan nonaktifkan lewat active false`() {
        val token = newTenantAdmin("cat-upd")
        val planId = id(
            post(
                "/api/catalog/plans", token,
                """{"name":"Awal","description":null,"price":100000,"downMbps":20,"upMbps":10,"serviceTypes":["PPPOE"]}""",
            ),
        )
        val updated = put(
            "/api/catalog/plans/$planId", token,
            """{"name":"Awal","description":null,"price":120000,"downMbps":30,"upMbps":15,"serviceTypes":["PPPOE"],"active":false}""",
        )
        assertThat(JsonPath.read<Int>(updated, "$.price")).isEqualTo(120000)
        assertThat(JsonPath.read<String>(updated, "$.rateLimit")).isEqualTo("15M/30M")
        assertThat(JsonPath.read<Boolean>(updated, "$.active")).isFalse()
    }

    @Test
    fun `tenant lain tak melihat paket`() {
        val tokenA = newTenantAdmin("cat-iso-a")
        val tokenB = newTenantAdmin("cat-iso-b")
        val planId = id(
            post(
                "/api/catalog/plans", tokenA,
                """{"name":"Rahasia A","description":null,"price":100000,"downMbps":20,"upMbps":10,"serviceTypes":["PPPOE"]}""",
            ),
        )
        assertThat(JsonPath.read<List<String>>(getJson("/api/catalog/plans", tokenB), "$[*].id")).doesNotContain(planId)
    }

    @Test
    fun `izin lihat paket saja tak boleh mengelola`() {
        val slug = "cat-perm${uniq()}"
        val admin = "admin@$slug.test"
        onboarding.onboard(OnboardTenantCommand(slug, "Catalog Perm Co", admin, "Admin", pass))
        val adminToken = login(slug, admin)

        val permsJson = getJson("/api/permissions", adminToken)
        val viewPermId = JsonPath.read<List<String>>(permsJson, "$[?(@.code=='catalog.plan.view')].id").first()
        val roleId = id(
            post("/api/roles", adminToken, """{"name":"Lihat Paket","permissionIds":["$viewPermId"]}""", expected = 201),
        )
        val limitedEmail = "viewer@$slug.test"
        post("/api/users", adminToken, """{"email":"$limitedEmail","name":"Viewer","password":"$pass","roleIds":["$roleId"]}""")
        val limitedToken = login(slug, limitedEmail)

        getJson("/api/catalog/plans", limitedToken)
        post(
            "/api/catalog/plans", limitedToken,
            """{"name":"Nekat","description":null,"price":100000,"downMbps":10,"upMbps":5,"serviceTypes":["PPPOE"]}""",
            expected = 403,
        )
    }
}
