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
 * Uji module vpn end-to-end lewat HTTP: dari membuat hub (aplikasi jadi CA-nya sendiri) sampai
 * alur provisioning yang dipanggil VPS dengan TOKEN NODE, bukan bearer. Menegakkan yang paling
 * gampang salah: PKI otomatis + perintah pasang sekali-tampil, verifikasi user/pass & IP overlay
 * tetap via callback, token gagal-aman (invalid → 404/403), rotasi mencabut token lama, dan
 * isolasi tenant (token satu hub tak bisa mengautentikasi peer hub lain).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VpnIT {

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

    private fun getText(url: String, token: String): String =
        mockMvc.perform(get(url).header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsString

    private fun id(json: String): String = JsonPath.read(json, "$.id")

    /** Buat hub; kembalikan (serverId, rawNodeToken). */
    private fun newHub(token: String, cidr: String = "10.8.0.0/24"): Pair<String, String> {
        val body = post(
            "/api/vpn/servers", token,
            """{"name":"Hub ${uniq()}","host":"vpn.example.com","port":1194,"protocol":"UDP","tunnelCidr":"$cidr"}""",
        )
        assertThat(JsonPath.read<Boolean>(body, "$.pkiReady")).isTrue()
        return id(body) to JsonPath.read(body, "$.nodeToken")
    }

    /** Buat peer; kembalikan (peerId, username, overlayIp). */
    private fun newPeer(token: String, serverId: String): Triple<String, String, String> {
        val body = post("/api/vpn/servers/$serverId/peers", token, """{"name":"BRAS ${uniq()}"}""")
        return Triple(id(body), JsonPath.read(body, "$.username"), JsonPath.read(body, "$.overlayIp"))
    }

    /** Password peer hanya keluar lewat unduh .ovpn (blok <auth-user-pass>), tak lewat view biasa. */
    private fun peerPassword(token: String, peerId: String): String {
        val ovpn = getText("/api/vpn/peers/$peerId/ovpn", token).lines()
        val idx = ovpn.indexOfFirst { it.trim() == "<auth-user-pass>" }
        return ovpn[idx + 2].trim()
    }

    // ---- Provisioning: dipanggil DARI VPS dengan token node, tanpa bearer JWT ----

    private fun installScript(rawToken: String, expected: Int = 200): String =
        mockMvc.perform(get("/api/vpn/provision/install.sh").param("token", rawToken))
            .andExpect { assertThat(it.response.status).isEqualTo(expected) }
            .andReturn().response.contentAsString

    private fun authenticate(rawToken: String, username: String, password: String): Int =
        mockMvc.perform(
            post("/api/vpn/provision/authenticate")
                .param("token", rawToken).param("username", username).param("password", password),
        ).andReturn().response.status

    private fun clientConnect(rawToken: String, username: String): Pair<Int, String> {
        val res = mockMvc.perform(
            post("/api/vpn/provision/client-connect").param("token", rawToken).param("username", username),
        ).andReturn().response
        return res.status to res.contentAsString
    }

    @Test
    fun `provisioning end-to-end hub sampai verifikasi peer lewat callback`() {
        val token = newTenantAdmin("vpn")
        val (serverId, nodeToken) = newHub(token)

        // Installer satu-perintah (tanpa bearer, auth via token) memuat callback + PKI aplikasi.
        val script = installScript(nodeToken)
        assertThat(script).contains("/api/vpn/provision/authenticate")
        assertThat(script).contains("/api/vpn/provision/client-connect")
        assertThat(script).contains("BEGIN CERTIFICATE")
        assertThat(script).doesNotContain("{{")

        val (peerId, username, overlayIp) = newPeer(token, serverId)
        val password = peerPassword(token, peerId)

        // auth-user-pass-verify: kredensial benar → 204, salah → 403.
        assertThat(authenticate(nodeToken, username, password)).isEqualTo(204)
        assertThat(authenticate(nodeToken, username, "passwordSalah")).isEqualTo(403)

        // client-connect: kunci IP overlay tetap peer.
        val (ccStatus, ccBody) = clientConnect(nodeToken, username)
        assertThat(ccStatus).isEqualTo(200)
        assertThat(ccBody.trim()).isEqualTo("ifconfig-push $overlayIp 255.255.255.0")

        // Peer nonaktif ditolak di kedua callback.
        post("/api/vpn/peers/$peerId/disable", token, "", expected = 200)
        assertThat(authenticate(nodeToken, username, password)).isEqualTo(403)
        assertThat(clientConnect(nodeToken, username).first).isEqualTo(403)
    }

    @Test
    fun `token invalid gagal-aman di semua endpoint provisioning`() {
        val bogus = "ftthv_tokentaklaku"
        installScript(bogus, expected = 404)
        assertThat(authenticate(bogus, "siapa", "apa")).isEqualTo(403)
        assertThat(clientConnect(bogus, "siapa").first).isEqualTo(403)
    }

    @Test
    fun `rotasi token mencabut token lama`() {
        val token = newTenantAdmin("vpn-rot")
        val (serverId, oldToken) = newHub(token)

        val rotated = post("/api/vpn/servers/$serverId/regenerate-token", token, "", expected = 200)
        val newToken = JsonPath.read<String>(rotated, "$.nodeToken")
        assertThat(newToken).isNotEqualTo(oldToken)

        // Token lama tak lagi berlaku; yang baru bekerja.
        installScript(oldToken, expected = 404)
        installScript(newToken, expected = 200)
    }

    @Test
    fun `token satu hub tak bisa mengautentikasi peer hub tenant lain`() {
        val tokenA = newTenantAdmin("vpn-iso-a")
        val tokenB = newTenantAdmin("vpn-iso-b")
        val (serverA, nodeA) = newHub(tokenA)
        val (peerA, usernameA, _) = newPeer(tokenA, serverA)
        val passwordA = peerPassword(tokenA, peerA)

        // Kredensial peer A sah dengan token A.
        assertThat(authenticate(nodeA, usernameA, passwordA)).isEqualTo(204)

        // Hub tenant B: token B tak mengenal username peer A (RLS + scope hub) → 403.
        val (serverB, nodeB) = newHub(tokenB)
        val (_, _, _) = newPeer(tokenB, serverB)
        assertThat(authenticate(nodeB, usernameA, passwordA)).isEqualTo(403)
    }
}
