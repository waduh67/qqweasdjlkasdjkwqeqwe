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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * Kabel mencatat SETIAP kotak yang disinggahinya, bukan cuma dua ujungnya.
 *
 * Satu selubung distribusi berangkat dari kabinet lalu dikupas di belasan ODP
 * sepanjang gang — dan sampai V99 kenyataan itu tak punya tempat di data.
 * Akibatnya sistem terpaksa menebak: kabel yang rutenya lewat dekat sebuah kotak
 * dianggap bisa disambung di sana. Tebakan itu salah dua arah sekaligus. Kabel
 * orang lain yang kebetulan melintas di atas ODP kita ikut ditawarkan untuk
 * disambung, sementara kabel yang rutenya digambar melenceng lima puluh meter
 * ditolak padahal kenyataannya memang dikupas di situ.
 *
 * Uji di sini menjaga penggantinya: keanggotaan ditentukan CATATAN PERBUATAN
 * orang atas selubung, dan letak kotak di peta cuma dipakai untuk mengurutkan
 * anggota yang keanggotaannya sudah diputuskan.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CableAttachmentIT {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var onboarding: OnboardTenantUseCase

    private val pass = "secret12345"
    private val lat = -6.24

    private fun uniq() = UUID.randomUUID().toString().substring(0, 8)

    private fun newTenantAdmin(prefix: String): String {
        val slug = "$prefix${uniq()}"
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

    private fun delete(url: String, token: String, expected: Int = 200): String =
        mockMvc.perform(delete(url).header("Authorization", "Bearer $token"))
            .andExpect { assertThat(it.response.status).isEqualTo(expected) }
            .andReturn().response.contentAsString

    private fun getJson(url: String, token: String): String =
        mockMvc.perform(get(url).header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsString

    private fun idOf(json: String): String = JsonPath.read(json, "$.id")

    private fun newSite(token: String): String = idOf(
        post(
            "/api/sites", token,
            """{"code":"POP-${uniq().uppercase()}","name":"POP uji",
                "location":{"longitude":107.00,"latitude":$lat}}""",
        ),
    )

    private fun newOdc(token: String, lon: Double): String = idOf(
        post(
            "/api/odcs", token,
            """{"code":"ODC-${uniq().uppercase()}","name":"ODC uji","location":{"longitude":$lon,"latitude":$lat},
                "splitterRatio":"1:8","capacity":8}""",
        ),
    )

    private fun newOdp(token: String, lon: Double): String = idOf(
        post(
            "/api/odps", token,
            """{"code":"ODP-${uniq().uppercase()}","name":"ODP uji","location":{"longitude":$lon,"latitude":$lat},
                "splitterRatio":"1:8","capacity":8}""",
        ),
    )

    /** Selubung distribusi dari kabinet di 107,00 menuju kotak terakhir di [toLon]. */
    private fun newCable(
        token: String,
        fromId: String,
        toId: String,
        toLon: Double,
        waypoints: String = "",
    ): String = post(
        "/api/cables", token,
        """{"name":"Kabel uji","cableType":"DISTRIBUTION","coreCount":12,
            "route":[{"longitude":107.00,"latitude":$lat},{"longitude":$toLon,"latitude":$lat}],
            "fromKind":"ODC","fromId":"$fromId","toKind":"ODP","toId":"$toId"$waypoints}""",
    )

    private fun waypoints(vararg entries: Pair<String, String>): String = entries.joinToString(
        prefix = ""","waypoints":[""",
        postfix = "]",
    ) { (id, role) -> """{"nodeKind":"ODP","nodeId":"$id","role":"$role"}""" }

    private fun tapped(token: String, cable: String, odp: String, role: String = "TAPPED", expected: Int = 200) =
        post(
            "/api/cables/$cable/attachments", token,
            """{"nodeKind":"ODP","nodeId":"$odp","role":"$role"}""",
            expected,
        )

    private fun codes(json: String): List<String?> = JsonPath.read(json, "$.attachments[*].nodeCode")
    private fun roles(json: String): List<String> = JsonPath.read(json, "$.attachments[*].role")
    private fun ids(json: String): List<String> = JsonPath.read(json, "$.attachments[*].nodeId")

    private fun coreId(token: String, cable: String, number: Int): String =
        JsonPath.read(getJson("/api/cables/$cable/cores", token), "$.cores[${number - 1}].id")

    private fun splitterOf(token: String, odp: String): String =
        JsonPath.read(getJson("/api/splitters?ownerKind=ODP&ownerId=$odp", token), "$.splitters[0].id")

    /**
     * Barisan singgahan disusun server dari letak kotaknya, bukan dari urutan
     * ketik klien.
     *
     * Yang menggambar kabel di peta wajar saja mendaftarkan kotak jauh lebih
     * dulu lalu teringat kotak dekat; kalau urutan itu dipercaya mentah-mentah,
     * pertanyaan "kotak berikutnya sesudah sini" — yang dipakai teknisi menyusuri
     * kabel — akan menjawab ngawur.
     */
    @Test
    fun `singgahan tengah bentang tersimpan urut sepanjang rute, meski dikirim terbalik`() {
        val token = newTenantAdmin("singgah")
        val odc = newOdc(token, 107.00)
        val jauh = newOdp(token, 107.04)
        val dekat = newOdp(token, 107.02)
        val ujung = newOdp(token, 107.06)

        val json = newCable(token, odc, ujung, 107.06, waypoints(jauh to "TAPPED", dekat to "TAPPED"))

        assertThat(ids(json)).containsExactly(odc, dekat, jauh, ujung)
        assertThat(roles(json)).containsExactly("END", "TAPPED", "TAPPED", "END")
        assertThat(codes(json)[1]).startsWith("ODP-")
        val jarak: List<Double> = JsonPath.read(json, "$.attachments[*].distanceMeters")
        assertThat(jarak).isSorted
        assertThat(jarak.first()).isZero
        // Kotak di 107,02 duduk sepertiga bentang dari pangkal — angka tampilan,
        // bukan penentu: yang menentukan tetap catatan perannya.
        assertThat(jarak[1]).isBetween(2000.0, 2600.0)
    }

    /**
     * Menyunting kabel tanpa menyebut singgahan tak boleh menghapus catatan
     * lapangan orang lain.
     *
     * Formulir kabel di peta cuma menanyakan kedua ujung. Kalau diamnya formulir
     * itu diperlakukan sebagai kehendak, sekali orang merapikan nama kabel
     * seluruh catatan "dikupas di ODP-3 sampai ODP-11" ikut lenyap — dan tak ada
     * yang tahu sampai ada teknisi berdiri di depan kotak yang datanya kosong.
     */
    @Test
    fun `menyunting kabel tanpa menyebut singgahan membiarkannya utuh, daftar kosong yang sadar mengosongkannya`() {
        val token = newTenantAdmin("sunting")
        val odc = newOdc(token, 107.00)
        val tengah = newOdp(token, 107.02)
        val ujung = newOdp(token, 107.06)
        val cable = idOf(newCable(token, odc, ujung, 107.06, waypoints(tengah to "TAPPED")))

        val diam = put(
            "/api/cables/$cable", token,
            """{"name":"Kabel uji (ganti nama)","cableType":"DISTRIBUTION","coreCount":12,
                "route":[{"longitude":107.00,"latitude":$lat},{"longitude":107.06,"latitude":$lat}],
                "fromKind":"ODC","fromId":"$odc","toKind":"ODP","toId":"$ujung"}""",
        )
        assertThat(ids(diam)).containsExactly(odc, tengah, ujung)

        val sadar = put(
            "/api/cables/$cable", token,
            """{"name":"Kabel uji","cableType":"DISTRIBUTION","coreCount":12,
                "route":[{"longitude":107.00,"latitude":$lat},{"longitude":107.06,"latitude":$lat}],
                "fromKind":"ODC","fromId":"$odc","toKind":"ODP","toId":"$ujung","waypoints":[]}""",
        )
        assertThat(ids(sadar)).containsExactly(odc, ujung)
    }

    /**
     * Meja sambung mencatat pengupasan saat perbuatannya terjadi, dalam satu
     * tindakan — bukan dengan menyuruh teknisi menyunting seluruh formulir kabel.
     * Sisipannya masuk pada urutan yang benar tanpa ada yang mengetik nomor urut.
     */
    @Test
    fun `menandai kabel dikupas di sebuah kotak menyisipkannya pada urutan yang benar`() {
        val token = newTenantAdmin("kupas")
        val odc = newOdc(token, 107.00)
        val jauh = newOdp(token, 107.04)
        val dekat = newOdp(token, 107.02)
        val ujung = newOdp(token, 107.06)
        val cable = idOf(newCable(token, odc, ujung, 107.06, waypoints(jauh to "TAPPED")))

        val json = tapped(token, cable, dekat)

        assertThat(ids(json)).containsExactly(odc, dekat, jauh, ujung)
        assertThat(roles(json)).containsExactly("END", "TAPPED", "TAPPED", "END")
    }

    /**
     * Kotak yang sudah tercatat cukup diperbarui perannya di tempat: yang tadinya
     * cuma dilewati lalu benar-benar dikupas tetap kotak yang sama, dan
     * memindahkannya ke urutan baru justru merusak barisan yang sudah benar.
     */
    @Test
    fun `mengubah peran singgahan yang sudah ada tidak menggandakannya`() {
        val token = newTenantAdmin("peran")
        val odc = newOdc(token, 107.00)
        val tengah = newOdp(token, 107.02)
        val ujung = newOdp(token, 107.06)
        val cable = idOf(newCable(token, odc, ujung, 107.06))

        tapped(token, cable, tengah, role = "PASSING")
        val json = tapped(token, cable, tengah, role = "TAPPED")

        assertThat(ids(json)).containsExactly(odc, tengah, ujung)
        assertThat(roles(json)).containsExactly("END", "TAPPED", "END")
        assertThat(JsonPath.read<List<Boolean>>(json, "$.attachments[*].spliceable"))
            .containsExactly(true, true, true)
    }

    /**
     * Ujung kabel bukan singgahan, dan bedanya bukan soal istilah: di ujung
     * SELURUH core terbuka, di singgahan cuma sebagian yang diambil. Menyamakan
     * keduanya membuat "sisa core yang masih jalan terus" tak bisa dijawab.
     */
    @Test
    fun `ujung kabel tak bisa didaftarkan ulang sebagai singgahan tengah`() {
        val token = newTenantAdmin("ujung")
        val odc = newOdc(token, 107.00)
        val ujung = newOdp(token, 107.06)
        val cable = idOf(newCable(token, odc, ujung, 107.06))

        val json = tapped(token, cable, ujung, expected = 400)

        assertThat(json).contains("ujung kabel")
    }

    /**
     * POP, badan OLT, dan rumah pelanggan memang tempat kabel berhenti — tapi tak
     * ada selubung yang dikupas di dalamnya, jadi ia tak pernah jadi singgahan.
     */
    @Test
    fun `simpul yang bukan kotak sambung ditolak jadi singgahan`() {
        val token = newTenantAdmin("bukankotak")
        val site = newSite(token)
        val odc = newOdc(token, 107.00)
        val ujung = newOdp(token, 107.06)
        val cable = idOf(newCable(token, odc, ujung, 107.06))

        val json = post(
            "/api/cables/$cable/attachments", token,
            """{"nodeKind":"SITE","nodeId":"$site","role":"TAPPED"}""",
            400,
        )

        assertThat(json).contains("kotak yang bisa dibuka teknisi")
    }

    /**
     * Selubung yang mengaku utuh padahal core-nya tersambung di dalam kotak itu
     * persis kebohongan yang bikin teknisi salah potong. Karena itu catatan
     * singgahan cuma bisa dicabut setelah sambungannya benar-benar dilepas.
     */
    @Test
    fun `singgahan yang seratnya masih tersambung tak bisa dicabut`() {
        val token = newTenantAdmin("cabut")
        val odc = newOdc(token, 107.00)
        val tengah = newOdp(token, 107.02)
        val ujung = newOdp(token, 107.06)
        val cable = idOf(newCable(token, odc, ujung, 107.06, waypoints(tengah to "TAPPED")))
        val sambungan = idOf(
            post(
                "/api/fiber-connections", token,
                """{"closureKind":"ODP","closureId":"$tengah",
                    "a":{"kind":"CORE","coreId":"${coreId(token, cable, 3)}"},
                    "b":{"kind":"SPLITTER_IN","nodeId":"${splitterOf(token, tengah)}"}}""",
            ),
        )

        val ditolak = delete("/api/cables/$cable/attachments/$tengah", token, 409)
        assertThat(ditolak).contains("sambungan serat")

        // Setelah sambungannya dilepas, catatannya boleh menyusul pergi.
        delete("/api/fiber-connections/$sambungan", token, 204)
        val json = delete("/api/cables/$cable/attachments/$tengah", token)
        assertThat(ids(json)).containsExactly(odc, ujung)
    }
}
