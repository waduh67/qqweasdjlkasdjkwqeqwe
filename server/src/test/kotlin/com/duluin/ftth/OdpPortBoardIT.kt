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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * Papan port ODP: catatan pemasangan ONU disandingkan dengan serat yang
 * sungguh-sungguh dilas, lengkap dengan selisihnya.
 *
 * Satu kotak dibuat memuat keempat keadaan yang benar-benar ditemui di lapangan
 * sekaligus — beres, tercatat tapi belum dilas, dilas tapi belum tercatat, dan
 * catatan yang menunjuk orang lain — sebab keempatnya memang hidup berdampingan
 * di ODP yang sudah lama dipakai, dan yang diuji justru kemampuan membedakannya.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OdpPortBoardIT {

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

    private fun getJson(url: String, token: String): String =
        mockMvc.perform(get(url).header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsString

    private fun idOf(json: String): String = JsonPath.read(json, "$.id")

    private fun coreId(token: String, cable: String, number: Int): String =
        JsonPath.read(getJson("/api/cables/$cable/cores", token), "$.cores[${number - 1}].id")

    private fun splitterOf(token: String, ownerId: String): String =
        JsonPath.read(getJson("/api/splitters?ownerKind=ODP&ownerId=$ownerId", token), "$.splitters[0].id")

    /** Melas core sebuah kabel ke kaki splitter di dalam ODP. */
    private fun spliceLeg(token: String, odp: String, coreId: String, splitter: String, leg: Int) {
        post(
            "/api/fiber-connections", token,
            """{"closureKind":"ODP","closureId":"$odp","a":{"kind":"CORE","coreId":"$coreId"},
                "b":{"kind":"SPLITTER_OUT","nodeId":"$splitter","portNumber":$leg}}""",
        )
    }

    private fun newCustomer(token: String, name: String): String = idOf(
        post(
            "/api/customers", token,
            """{"code":"C-${uniq().uppercase()}","name":"$name","address":"Jl. Uji",
                "location":{"longitude":106.996,"latitude":-6.246}}""",
        ),
    )

    private fun attachOnu(token: String, customer: String, serial: String, odp: String, port: Int) {
        val onu = idOf(post("/api/customers/$customer/onus", token, """{"serialNumber":"$serial"}"""))
        post("/api/customers/onus/$onu/attach", token, """{"odpId":"$odp","portNumber":$port}""", expected = 200)
    }

    /** Kabel drop dari slot [port] sebuah ODP ke rumah pelanggan; kembalikan id kabelnya. */
    private fun newDrop(token: String, odp: String, port: Int, customer: String): String = idOf(
        post(
            "/api/cables", token,
            """{"name":"Drop $port","cableType":"DROP","coreCount":1,
                "route":[{"longitude":106.995,"latitude":-6.245},{"longitude":106.996,"latitude":-6.246}],
                "fromKind":"ODP","fromId":"$odp","fromPortNumber":$port,
                "toKind":"CUSTOMER","toId":"$customer"}""",
        ),
    )

    @Test
    fun `papan port menyandingkan catatan pemasangan dengan serat yang benar-benar dilas`() {
        val token = newTenantAdmin("odpport")
        val odc = idOf(
            post(
                "/api/odcs", token,
                """{"code":"ODC-${uniq().uppercase()}","name":"ODC uji",
                    "location":{"longitude":106.99,"latitude":-6.24},"splitterRatio":"1:8","capacity":8}""",
            ),
        )
        val odp = idOf(
            post(
                "/api/odps", token,
                """{"code":"ODP-${uniq().uppercase()}","name":"ODP uji",
                    "location":{"longitude":106.995,"latitude":-6.245},
                    "odcId":"$odc","splitterRatio":"1:8","capacity":8}""",
            ),
        )
        val splitter = splitterOf(token, odp)

        // Kabel distribusi menyuapi input modulnya — keadaan awal yang wajar,
        // sekaligus penjaga agar kaki tak dikira "belum ada yang masuk".
        val dist = idOf(
            post(
                "/api/cables", token,
                """{"name":"Distribusi uji","cableType":"DISTRIBUTION","coreCount":8,
                    "route":[{"longitude":106.99,"latitude":-6.24},{"longitude":106.995,"latitude":-6.245}],
                    "fromKind":"ODC","fromId":"$odc","toKind":"ODP","toId":"$odp"}""",
            ),
        )
        post(
            "/api/fiber-connections", token,
            """{"closureKind":"ODP","closureId":"$odp","a":{"kind":"CORE","coreId":"${coreId(token, dist, 1)}"},
                "b":{"kind":"SPLITTER_IN","nodeId":"$splitter"}}""",
        )

        // Port 1 — beres: ONU tercatat DAN kakinya dilas ke drop menuju rumahnya.
        val budi = newCustomer(token, "Budi Santoso")
        attachOnu(token, budi, "ZTEG${uniq().uppercase()}", odp, 1)
        val dropBudi = newDrop(token, odp, 1, budi)
        spliceLeg(token, odp, coreId(token, dropBudi, 1), splitter, 1)

        // Port 2 — pemasangan dibukukan, serat belum disentuh.
        val siti = newCustomer(token, "Siti Aminah")
        attachOnu(token, siti, "ZTEG${uniq().uppercase()}", odp, 2)

        // Port 3 — kabelnya sudah sampai rumah dan dilas, ONU-nya belum didaftarkan.
        val joko = newCustomer(token, "Joko Widodo")
        spliceLeg(token, odp, coreId(token, newDrop(token, odp, 3, joko), 1), splitter, 3)

        // Port 4 — catatan menyebut Rina, seratnya bermuara di rumah Agus.
        val rina = newCustomer(token, "Rina Marlina")
        val agus = newCustomer(token, "Agus Salim")
        attachOnu(token, rina, "ZTEG${uniq().uppercase()}", odp, 4)
        spliceLeg(token, odp, coreId(token, newDrop(token, odp, 4, agus), 1), splitter, 4)

        val board = getJson("/api/odps/$odp/ports", token)
        assertThat(JsonPath.read<Int>(board, "$.capacity")).isEqualTo(8)
        assertThat(JsonPath.read<List<Any>>(board, "$.ports")).hasSize(8)
        assertThat(JsonPath.read<List<String>>(board, "$.splitterCodes")).containsExactly("SPL-1")

        assertThat(JsonPath.read<String>(board, "$.ports[0].legLabel")).isEqualTo("SPL-1 kaki 1")
        assertThat(JsonPath.read<String>(board, "$.ports[0].customerName")).isEqualTo("Budi Santoso")
        assertThat(JsonPath.read<String>(board, "$.ports[0].servedBy")).contains("Budi Santoso")
        assertThat(JsonPath.read<Any?>(board, "$.ports[0].issue")).isNull()

        assertThat(JsonPath.read<String>(board, "$.ports[1].customerName")).isEqualTo("Siti Aminah")
        assertThat(JsonPath.read<String>(board, "$.ports[1].issue")).isEqualTo("PORT_WITHOUT_FIBER")
        assertThat(JsonPath.read<Boolean>(board, "$.ports[1].legConnected")).isFalse()

        assertThat(JsonPath.read<Any?>(board, "$.ports[2].customerName")).isNull()
        assertThat(JsonPath.read<String>(board, "$.ports[2].issue")).isEqualTo("FIBER_WITHOUT_PORT")
        assertThat(JsonPath.read<String>(board, "$.ports[2].servedBy")).contains("Joko Widodo")

        assertThat(JsonPath.read<String>(board, "$.ports[3].customerName")).isEqualTo("Rina Marlina")
        assertThat(JsonPath.read<String>(board, "$.ports[3].issue")).isEqualTo("PORT_MISMATCH")
        assertThat(JsonPath.read<String>(board, "$.ports[3].servedBy")).contains("Agus Salim")
        assertThat(JsonPath.read<String>(board, "$.ports[3].issueDetail")).contains("masih bayar")

        // Kaki yang memang belum disentuh bukan masalah — dan tak boleh dihitung sebagai masalah.
        assertThat(JsonPath.read<Any?>(board, "$.ports[4].issue")).isNull()
        assertThat(JsonPath.read<Int>(board, "$.occupiedCount")).isEqualTo(3)
        assertThat(JsonPath.read<Int>(board, "$.issueCount")).isEqualTo(3)

        // Meja sambung ikut menyebut jalurnya, supaya "kaki 1" berhenti jadi nomor
        // tanpa arti bagi yang berdiri di depan kotaknya.
        val workbench = getJson("/api/fiber-connections/workbench?closureKind=ODP&closureId=$odp", token)
        val leg1 = JsonPath.read<List<String>>(workbench, "$.points[?(@.label == 'SPL-1 kaki 1')].serves")
        assertThat(leg1).hasSize(1)
        assertThat(leg1.first()).contains("Budi Santoso")
        val idle = JsonPath.read<List<Any?>>(workbench, "$.points[?(@.label == 'SPL-1 kaki 5')].serves")
        assertThat(idle.filterNotNull()).isEmpty()
    }
}
