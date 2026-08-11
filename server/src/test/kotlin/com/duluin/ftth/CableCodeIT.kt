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
 * Kode kabel harus bisa DIUCAPKAN.
 *
 * Kode itu dipakai lewat radio ("buka DIST-ODC-JKT-01-ODP-07 ya"), ditulis tangan
 * di label selubung, dan dicari orang di daftar sepanjang ratusan baris. Selama
 * ini backend memberi UUID mentah saat kolomnya dikosongkan — unik sempurna, tapi
 * tak seorang pun sanggup mengejanya di lapangan, dan meja sambung jadi berisi
 * baris-baris yang tak dikenali siapa pun.
 *
 * Yang dijaga di sini: kode bawaan dirakit dari kode kedua ujungnya, bentrok antar
 * kode buatan sistem diselesaikan dengan akhiran angka (dua selubung antara sepasang
 * kotak yang sama itu wajar — rute utara & rute selatan), kode yang DIKETIK orang
 * tak pernah digeser diam-diam, dan kode boleh dirapikan belakangan tanpa menggambar
 * ulang jalurnya — itu satu-satunya jalan keluar bagi kabel lama yang terlanjur ber-UUID.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CableCodeIT {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var onboarding: OnboardTenantUseCase

    private val pass = "secret12345"
    private val lat = -6.31

    private fun uniq() = UUID.randomUUID().toString().substring(0, 8).uppercase()

    private fun newTenantAdmin(prefix: String): String {
        val slug = "$prefix${uniq().lowercase()}"
        val admin = "admin@$slug.test"
        onboarding.onboard(OnboardTenantCommand(slug, "Tenant $slug", admin, "Admin", pass))
        val body = """{"tenantSlug":"$slug","email":"$admin","password":"$pass"}"""
        val json = mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return JsonPath.read(json, "$.accessToken")
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

    private fun codeOf(json: String): String = JsonPath.read(json, "$.code")

    /** Sepasang kotak berkode manusiawi — bahan mentah kode kabelnya. */
    private data class Ends(val odc: String, val odcCode: String, val odp: String, val odpCode: String)

    private fun newEnds(token: String): Ends {
        val odcCode = "ODC-${uniq()}"
        val odpCode = "ODP-${uniq()}"
        val odc = idOf(
            post(
                "/api/odcs", token,
                """{"code":"$odcCode","name":"ODC uji","location":{"longitude":107.10,"latitude":$lat},
                    "splitterRatio":"1:8","capacity":8}""",
            ),
        )
        val odp = idOf(
            post(
                "/api/odps", token,
                """{"code":"$odpCode","name":"ODP uji","location":{"longitude":107.12,"latitude":$lat},
                    "splitterRatio":"1:8","capacity":8}""",
            ),
        )
        return Ends(odc, odcCode, odp, odpCode)
    }

    private fun cableBody(ends: Ends, extra: String = ""): String =
        """{"name":"Distribusi uji","cableType":"DISTRIBUTION","coreCount":12,
            "route":[{"longitude":107.10,"latitude":$lat},{"longitude":107.12,"latitude":$lat}],
            "fromKind":"ODC","fromId":"${ends.odc}","toKind":"ODP","toId":"${ends.odp}"$extra}"""

    /**
     * Yang paling sering terjadi: operator menggambar kabel dan tak mengisi kolom kode.
     * Hasilnya harus langsung berbunyi seperti kabel — jenis, lalu kedua kotak yang
     * dihubungkannya — bukan deretan heksadesimal.
     */
    @Test
    fun `kode bawaan dirakit dari kode kedua ujungnya`() {
        val token = newTenantAdmin("kbl")
        val ends = newEnds(token)

        val code = codeOf(post("/api/cables", token, cableBody(ends)))

        assertThat(code).isEqualTo("DIST-${ends.odcCode}-${ends.odpCode}")
    }

    /**
     * Ruas kedua antara kotak yang sama bukan kesalahan — biasanya rute cadangan lewat
     * jalan lain. Karena itu bentrok kode BUATAN SISTEM diberi akhiran angka, bukan
     * ditolak: menggagalkan penyimpanan kabel yang sudah tergambar cuma karena tabrakan
     * nama yang dibuat backend sendiri akan membuat orang berhenti memakai peta.
     */
    @Test
    fun `ruas kedua antara pasangan yang sama diberi akhiran angka`() {
        val token = newTenantAdmin("kbl")
        val ends = newEnds(token)
        val first = codeOf(post("/api/cables", token, cableBody(ends)))

        val second = codeOf(post("/api/cables", token, cableBody(ends)))

        assertThat(second).isEqualTo("$first-2")
    }

    /**
     * Drop ke pelanggan: kode pelanggan milik module customer dan sengaja tak ditarik ke
     * network. Slot ODP asalnya justru pembeda yang lebih berguna — begitulah orang
     * lapangan menyebutnya: "drop dari kotak itu, port tiga".
     */
    @Test
    fun `drop ke pelanggan berkode kotak plus nomor slotnya`() {
        val token = newTenantAdmin("kbl")
        val ends = newEnds(token)

        val code = codeOf(
            post(
                "/api/cables", token,
                """{"name":"Drop uji","cableType":"DROP","coreCount":1,
                    "route":[{"longitude":107.12,"latitude":$lat},{"longitude":107.121,"latitude":$lat}],
                    "fromKind":"ODP","fromId":"${ends.odp}","fromPortNumber":3,
                    "toKind":"CUSTOMER","toId":"${UUID.randomUUID()}"}""",
            ),
        )

        assertThat(code).isEqualTo("DROP-${ends.odpCode}-P3")
    }

    /**
     * Kode yang diketik orang dipakai apa adanya — huruf besar, tanpa digeser. Kalau
     * ternyata sudah dipakai, penyimpanan DITOLAK: menggeser diam-diam kode yang
     * diketik berarti label di selubung dan label di layar berbeda, dan yang berangkat
     * ke lapangan membawa kertas yang salah.
     */
    @Test
    fun `kode ketikan operator dipakai apa adanya dan bentroknya ditolak`() {
        val token = newTenantAdmin("kbl")
        val ends = newEnds(token)
        val ketikan = "dist-${uniq()}"

        val code = codeOf(post("/api/cables", token, cableBody(ends, ""","code":"$ketikan"""")))
        assertThat(code).isEqualTo(ketikan.uppercase())

        val lain = newEnds(token)
        val gagal = post("/api/cables", token, cableBody(lain, ""","code":"$ketikan""""), expected = 409)
        assertThat(gagal).contains("sudah dipakai")
    }

    /**
     * Jalan keluar untuk kabel yang terlanjur berkode buruk (termasuk UUID warisan):
     * kodenya boleh diganti tanpa menggambar ulang jalurnya. Dan klien lama yang tak
     * mengenal kolom ini tak boleh MENGHAPUS kode yang sudah tertulis di selubung
     * hanya karena ia tak mengirimkannya — kosong berarti "biarkan".
     */
    @Test
    fun `kode boleh dirapikan belakangan dan tak hilang saat tak dikirim`() {
        val token = newTenantAdmin("kbl")
        val ends = newEnds(token)
        val cable = idOf(post("/api/cables", token, cableBody(ends)))
        val rapi = "DIST-RAPI-${uniq()}"

        put("/api/cables/$cable", token, cableBody(ends, ""","code":"$rapi""""))
        assertThat(codeOf(getJson("/api/cables/$cable", token))).isEqualTo(rapi)

        put("/api/cables/$cable", token, cableBody(ends))
        assertThat(codeOf(getJson("/api/cables/$cable", token))).isEqualTo(rapi)
    }

    /** Kode yang sudah dipakai kabel lain tak bisa direbut lewat penyuntingan. */
    @Test
    fun `mengganti kode ke milik kabel lain ditolak`() {
        val token = newTenantAdmin("kbl")
        val ends = newEnds(token)
        val tetangga = codeOf(post("/api/cables", token, cableBody(ends)))
        val cable = idOf(post("/api/cables", token, cableBody(ends)))

        val gagal = put("/api/cables/$cable", token, cableBody(ends, ""","code":"$tetangga""""), expected = 409)

        assertThat(gagal).contains("sudah dipakai")
    }
}
