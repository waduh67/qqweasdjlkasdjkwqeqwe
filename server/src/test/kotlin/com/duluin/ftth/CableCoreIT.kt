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
 * Core kabel: tiap kabel lahir dengan barisan seratnya sendiri (bernomor,
 * ber-tube, berwarna TIA-598), statusnya bisa disetel sekaligus banyak, dan
 * jumlah core yang berubah menyeret barisannya tanpa menghapus serat yang
 * masih terpakai.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CableCoreIT {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var onboarding: OnboardTenantUseCase

    private val pass = "secret12345"

    private fun uniq() = UUID.randomUUID().toString().substring(0, 8)

    private fun login(slug: String, email: String): String {
        val body = """{"tenantSlug":"$slug","email":"$email","password":"$pass"}"""
        val json = mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body),
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

    private fun idOf(json: String): String = JsonPath.read(json, "$.id")

    /** ODC → ODP dengan [coreCount] core; ini bentuk kabel distribusi yang lazim. */
    private fun newCable(token: String, coreCount: Int): String {
        val suffix = uniq().uppercase()
        val odc = idOf(
            post(
                "/api/odcs", token,
                """{"code":"ODC-$suffix","name":"ODC $suffix","location":{"longitude":106.99,"latitude":-6.24},
                    "splitterRatio":"1:8","capacity":8}""",
            ),
        )
        val odp = idOf(
            post(
                "/api/odps", token,
                """{"code":"ODP-$suffix","name":"ODP $suffix","location":{"longitude":106.995,"latitude":-6.245},
                    "splitterRatio":"1:8","capacity":8}""",
            ),
        )
        return idOf(
            post(
                "/api/cables", token,
                """{"code":"DST-$suffix","name":"Distribusi $suffix","cableType":"DISTRIBUTION",
                    "coreCount":$coreCount,
                    "route":[{"longitude":106.99,"latitude":-6.24},{"longitude":106.995,"latitude":-6.245}],
                    "fromKind":"ODC","fromId":"$odc","toKind":"ODP","toId":"$odp"}""",
            ),
        )
    }

    /** Badan update kabel yang HANYA mengubah jumlah core; sisanya tetap. */
    private fun coreCountBody(cableJson: String, coreCount: Int): String {
        val name = JsonPath.read<String>(cableJson, "$.name")
        val fromId = JsonPath.read<String>(cableJson, "$.fromId")
        val toId = JsonPath.read<String>(cableJson, "$.toId")
        return """{"name":"$name","cableType":"DISTRIBUTION","coreCount":$coreCount,
            "route":[{"longitude":106.99,"latitude":-6.24},{"longitude":106.995,"latitude":-6.245}],
            "fromKind":"ODC","fromId":"$fromId","toKind":"ODP","toId":"$toId"}"""
    }

    @Test
    fun `kabel baru langsung punya barisan core bernomor, ber-tube, dan berwarna baku`() {
        val token = newTenantAdmin("core")
        val cable = newCable(token, coreCount = 24)

        val cores = getJson("/api/cables/$cable/cores", token)
        assertThat(JsonPath.read<Int>(cores, "$.coreCount")).isEqualTo(24)
        assertThat(JsonPath.read<Int>(cores, "$.coresPerTube")).isEqualTo(12)
        assertThat(JsonPath.read<Int>(cores, "$.free")).isEqualTo(24)
        assertThat(JsonPath.read<List<Any>>(cores, "$.cores")).hasSize(24)

        // Core 1: tube 1, biru — awal urutan TIA-598.
        assertThat(JsonPath.read<Int>(cores, "$.cores[0].tubeNumber")).isEqualTo(1)
        assertThat(JsonPath.read<String>(cores, "$.cores[0].color")).isEqualTo("Biru")
        assertThat(JsonPath.read<String>(cores, "$.cores[0].status")).isEqualTo("FREE")

        // Core 3: hijau, masih tube 1.
        assertThat(JsonPath.read<String>(cores, "$.cores[2].color")).isEqualTo("Hijau")

        // Core 13: warna MENGULANG dari biru, tapi sudah tube 2 (jingga) — inilah
        // alasan nomor tube wajib ada; tanpa itu "core biru" menunjuk dua serat.
        assertThat(JsonPath.read<Int>(cores, "$.cores[12].tubeNumber")).isEqualTo(2)
        assertThat(JsonPath.read<Int>(cores, "$.cores[12].positionInTube")).isEqualTo(1)
        assertThat(JsonPath.read<String>(cores, "$.cores[12].color")).isEqualTo("Biru")
        assertThat(JsonPath.read<String>(cores, "$.cores[12].tubeColor")).isEqualTo("Jingga")
    }

    @Test
    fun `setel banyak core sekaligus tanpa menimpa catatan masing-masing`() {
        val token = newTenantAdmin("coreset")
        val cable = newCable(token, coreCount = 8)

        // Satu core dikasih catatan lapangan sendiri.
        put("/api/cables/$cable/cores", token, """{"coreNumbers":[3],"note":"ke ODP-3 Jl. Melati"}""")

        // Lalu tiga core ditandai terpakai TANPA menyertakan catatan.
        val after = put("/api/cables/$cable/cores", token, """{"coreNumbers":[1,2,3],"status":"USED"}""")

        assertThat(JsonPath.read<Int>(after, "$.used")).isEqualTo(3)
        assertThat(JsonPath.read<Int>(after, "$.free")).isEqualTo(5)
        // Catatan core 3 selamat: status null-able terpisah dari catatan null-able.
        assertThat(JsonPath.read<String>(after, "$.cores[2].note")).isEqualTo("ke ODP-3 Jl. Melati")
        assertThat(JsonPath.read<Any?>(after, "$.cores[0].note")).isNull()

        // clearNote mengosongkannya secara eksplisit.
        val cleared = put("/api/cables/$cable/cores", token, """{"coreNumbers":[3],"clearNote":true}""")
        assertThat(JsonPath.read<Any?>(cleared, "$.cores[2].note")).isNull()
        assertThat(JsonPath.read<String>(cleared, "$.cores[2].status")).isEqualTo("USED")

        // Core yang tak ada di kabel ini ditolak, bukan diam-diam dibuat.
        put("/api/cables/$cable/cores", token, """{"coreNumbers":[99],"status":"USED"}""", expected = 404)
        // Permintaan tanpa perubahan apa pun juga ditolak.
        put("/api/cables/$cable/cores", token, """{"coreNumbers":[1]}""", expected = 400)
    }

    @Test
    fun `jumlah core naik menambah serat, turun ditolak selama masih ada yang terpakai`() {
        val token = newTenantAdmin("coresync")
        val cable = newCable(token, coreCount = 8)
        val cableJson = getJson("/api/cables/$cable", token)

        put("/api/cables/$cable/cores", token, """{"coreNumbers":[8],"status":"USED","note":"pelanggan ujung"}""")

        // Naik 8 → 12: core lama (status & catatannya) tak tersentuh, 4 core baru bebas.
        put("/api/cables/$cable", token, coreCountBody(cableJson, 12))
        val grown = getJson("/api/cables/$cable/cores", token)
        assertThat(JsonPath.read<List<Any>>(grown, "$.cores")).hasSize(12)
        assertThat(JsonPath.read<String>(grown, "$.cores[7].note")).isEqualTo("pelanggan ujung")
        assertThat(JsonPath.read<Int>(grown, "$.used")).isEqualTo(1)

        // Turun 12 → 4 ditolak: core 8 masih terpakai dan akan ikut terbuang.
        put("/api/cables/$cable", token, coreCountBody(cableJson, 4), expected = 409)

        // Setelah core 8 dibebaskan, penyusutan jalan dan sisanya terpotong rapi.
        put("/api/cables/$cable/cores", token, """{"coreNumbers":[8],"status":"FREE","clearNote":true}""")
        put("/api/cables/$cable", token, coreCountBody(cableJson, 4))
        val shrunk = getJson("/api/cables/$cable/cores", token)
        assertThat(JsonPath.read<List<Any>>(shrunk, "$.cores")).hasSize(4)
        assertThat(JsonPath.read<Int>(shrunk, "$.free")).isEqualTo(4)
    }
}
