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
 * Uji module vpn end-to-end lewat HTTP dalam model VPN-as-a-service: HUB adalah infrastruktur
 * PLATFORM (dibuat admin platform, aplikasi jadi CA-nya), TENANT cukup men-generate akun yang
 * di-AUTO-ASSIGN ke hub. Menegakkan yang paling gampang salah: hub platform-only (tenant 403),
 * generate mengembalikan kredensial sekali-tampil, provisioning tetap via callback token node,
 * token gagal-aman, rotasi mencabut token lama, isolasi kepemilikan antar-tenant, dan scoping
 * per-hub (token satu hub tak bisa mengautentikasi akun di hub lain).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VpnIT {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var onboarding: OnboardTenantUseCase

    private val pass = "secret12345"

    private fun uniq() = UUID.randomUUID().toString().substring(0, 8)

    private fun login(slug: String, email: String, password: String = pass): String {
        val json = mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"tenantSlug":"$slug","email":"$email","password":"$password"}"""),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return JsonPath.read(json, "$.accessToken")
    }

    /** Admin platform (lintas-tenant) — satu-satunya yang boleh mengelola hub. */
    private fun platformToken(): String = login("platform", "root@ftth.local", "rootadmin123")

    private fun newTenantAdmin(prefix: String): String {
        val slug = "$prefix${uniq()}"
        val admin = "admin@$slug.test"
        onboarding.onboard(OnboardTenantCommand(slug, "Tenant $slug", admin, "Admin", pass))
        return login(slug, admin)
    }

    private fun perform(method: String, url: String, token: String, body: String) =
        mockMvc.perform(
            (if (method == "GET") get(url) else post(url))
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(body),
        )

    private fun post(url: String, token: String, body: String, expected: Int = 200): String =
        mockMvc.perform(
            post(url).header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect { assertThat(it.response.status).isEqualTo(expected) }
            .andReturn().response.contentAsString

    private fun statusOf(method: String, url: String, token: String, body: String = ""): Int =
        perform(method, url, token, body).andReturn().response.status

    /** Varian [post] untuk verb selain POST (penerusan port memakai PUT/DELETE). */
    private fun send(method: String, url: String, token: String, body: String = "", expected: Int = 200): String =
        mockMvc.perform(
            (if (method == "PUT") put(url) else delete(url))
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect { assertThat(it.response.status).isEqualTo(expected) }
            .andReturn().response.contentAsString

    private fun getText(url: String, token: String): String =
        mockMvc.perform(get(url).header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsString

    private fun id(json: String): String = JsonPath.read(json, "$.id")

    /** Admin platform membuat hub; kembalikan (serverId, serverName, rawNodeToken). */
    private fun newHub(namePrefix: String = "Hub", cidr: String = "10.8.0.0/24"): Triple<String, String, String> {
        val name = "$namePrefix ${uniq()}"
        val body = post(
            "/api/vpn/servers", platformToken(),
            """{"name":"$name","host":"vpn.example.com","port":1194,"protocol":"UDP","tunnelCidr":"$cidr"}""",
            expected = 201,
        )
        assertThat(JsonPath.read<Boolean>(body, "$.pkiReady")).isTrue()
        return Triple(id(body), name, JsonPath.read(body, "$.nodeToken"))
    }

    /** Tenant men-generate akun (auto-assign); kembalikan body view mentah. */
    private fun generate(token: String, username: String? = null): String {
        val body = username?.let { """{"username":"$it"}""" } ?: "{}"
        return post("/api/vpn/accounts/generate", token, body, expected = 200)
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

    /**
     * Token node hub TEMPAT AKUN MENDARAT: auto-assign bebas memilih hub terlengang mana pun,
     * jadi jangan berasumsi akun jatuh di hub yang baru dibuat — cari lewat namanya lalu rotasi
     * tokennya (rotasi mengembalikan token mentah, satu-satunya cara membacanya lagi).
     */
    private fun nodeTokenOfHub(serverName: String): String {
        val platform = platformToken()
        val servers = getText("/api/vpn/servers", platform)
        val serverId = JsonPath.read<List<String>>(servers, "$[?(@.name=='$serverName')].id").single()
        val rotated = post("/api/vpn/servers/$serverId/regenerate-token", platform, "", expected = 200)
        return JsonPath.read(rotated, "$.nodeToken")
    }

    private fun forwardTable(rawToken: String): Pair<Int, String> {
        val res = mockMvc.perform(
            post("/api/vpn/provision/forwards").param("token", rawToken),
        ).andReturn().response
        return res.status to res.contentAsString
    }

    @Test
    fun `hub adalah platform-only — tenant tak bisa mengelolanya`() {
        val tenant = newTenantAdmin("vpn-noserver")
        // Tenant tak punya izin vpn.server.* → dilarang lihat maupun buat hub.
        assertThat(statusOf("GET", "/api/vpn/servers", tenant)).isEqualTo(403)
        assertThat(
            statusOf(
                "POST", "/api/vpn/servers", tenant,
                """{"name":"Ilegal","host":"x","port":1194,"protocol":"UDP","tunnelCidr":"10.8.0.0/24"}""",
            ),
        ).isEqualTo(403)
    }

    @Test
    fun `provisioning end-to-end — platform bikin hub, tenant generate akun, callback verifikasi`() {
        val (_, hubName, nodeToken) = newHub()
        val tenant = newTenantAdmin("vpn")

        // Installer satu-perintah (tanpa bearer, auth via token) memuat callback + PKI aplikasi.
        val script = installScript(nodeToken)
        assertThat(script).contains("/api/vpn/provision/authenticate")
        assertThat(script).contains("/api/vpn/provision/client-connect")
        assertThat(script).contains("BEGIN CERTIFICATE")
        assertThat(script).doesNotContain("{{")

        // Satu klik generate → akun di-auto-assign ke hub + kredensial sekali-tampil.
        val acc = generate(tenant, username = "bras-jkt")
        val accId = id(acc)
        val username = JsonPath.read<String>(acc, "$.username")
        val overlayIp = JsonPath.read<String>(acc, "$.overlayIp")
        val remotePort = JsonPath.read<Int>(acc, "$.remotePort")
        val password = JsonPath.read<String>(acc, "$.password")
        assertThat(username).isEqualTo("bras-jkt")
        assertThat(JsonPath.read<String>(acc, "$.serverName")).isEqualTo(hubName)
        assertThat(JsonPath.read<String>(acc, "$.host")).isEqualTo("vpn.example.com")
        assertThat(JsonPath.read<String>(acc, "$.securityType")).contains("AES-256-GCM")
        // Port remote di rentang default + winboxAddress = host:remotePort siap tempel.
        assertThat(remotePort).isBetween(20000, 40000)
        assertThat(JsonPath.read<String>(acc, "$.winboxAddress")).isEqualTo("vpn.example.com:$remotePort")
        // Perintah RouterOS v7 siap-tempel ikut sekali-tampil (berisi username + password).
        val routerOsCommand = JsonPath.read<String>(acc, "$.routerOsCommand")
        assertThat(routerOsCommand)
            .startsWith("/interface/ovpn-client/add ")
            .contains("connect-to=vpn.example.com")
            .contains("user=\"$username\"")
            .contains("password=\"$password\"")
            .contains("cipher=aes256-gcm")

        // Password sekali-tampil: GET biasa tak lagi membocorkannya (juga perintah RouterOS-nya).
        val fetched = getText("/api/vpn/accounts/$accId", tenant)
        assertThat(JsonPath.read<Any?>(fetched, "$.password")).isNull()
        assertThat(JsonPath.read<Any?>(fetched, "$.routerOsCommand")).isNull()

        // auth-user-pass-verify: kredensial benar → 204, salah → 403.
        assertThat(authenticate(nodeToken, username, password)).isEqualTo(204)
        assertThat(authenticate(nodeToken, username, "passwordSalah")).isEqualTo(403)

        // client-connect: kunci IP overlay tetap + port remote (VPS memakainya untuk ifconfig-push + DNAT).
        val (ccStatus, ccBody) = clientConnect(nodeToken, username)
        assertThat(ccStatus).isEqualTo(200)
        assertThat(ccBody.trim()).isEqualTo("$overlayIp 255.255.255.0 $remotePort")

        // Akun nonaktif ditolak di kedua callback.
        post("/api/vpn/accounts/$accId/disable", tenant, "", expected = 200)
        assertThat(authenticate(nodeToken, username, password)).isEqualTo(403)
        assertThat(clientConnect(nodeToken, username).first).isEqualTo(403)
    }

    @Test
    fun `penerusan port — bawaan Winbox, tambah layanan lain, pindah port perangkat, cabut`() {
        newHub("Hub Forward", cidr = "10.30.0.0/24")
        val tenant = newTenantAdmin("vpn-fwd")

        val acc = generate(tenant, username = "mikrotik-fwd")
        val accId = id(acc)
        val overlayIp = JsonPath.read<String>(acc, "$.overlayIp")
        val winboxPublic = JsonPath.read<Int>(acc, "$.remotePort")
        val nodeToken = nodeTokenOfHub(JsonPath.read(acc, "$.serverName"))

        // Bawaan satu pintu: Winbox 8291, beralamat siap tempel `host:portPublik`.
        assertThat(JsonPath.read<List<Int>>(acc, "$.forwards[*].devicePort")).containsExactly(8291)
        assertThat(JsonPath.read<List<String>>(acc, "$.forwards[*].label")).containsExactly("Winbox")
        assertThat(JsonPath.read<List<String>>(acc, "$.forwards[*].address"))
            .containsExactly("vpn.example.com:$winboxPublic")
        val winboxId = JsonPath.read<String>(acc, "$.forwards[0].id")

        // Perangkat melayani lebih dari satu hal: tambah API Mikrotik. Port publiknya dialokasikan
        // sistem (tak boleh bentrok di hub), labelnya ditebak dari port yang umum dikenal.
        val withApi = post("/api/vpn/accounts/$accId/forwards", tenant, """{"devicePort":8728}""")
        assertThat(JsonPath.read<List<Int>>(withApi, "$.forwards[*].devicePort")).containsExactly(8291, 8728)
        assertThat(JsonPath.read<List<String>>(withApi, "$.forwards[*].label")).containsExactly("Winbox", "API")
        val apiPublic = JsonPath.read<List<Int>>(withApi, "$.forwards[*].publicPort").last()
        assertThat(apiPublic).isNotEqualTo(winboxPublic).isBetween(20000, 40000)
        // Port utama tetap yang terendah → alamat yang sudah dipegang teknisi tak bergeser.
        assertThat(JsonPath.read<Int>(withApi, "$.remotePort")).isEqualTo(winboxPublic)

        // Winbox di perangkat dipindah dari 8291 ke 9291: yang berubah HANYA sisi perangkat.
        val moved = send("PUT", "/api/vpn/accounts/$accId/forwards/$winboxId", tenant, """{"devicePort":9291}""")
        assertThat(JsonPath.read<List<Int>>(moved, "$.forwards[*].publicPort")).containsExactly(winboxPublic, apiPublic)
        assertThat(JsonPath.read<List<Int>>(moved, "$.forwards[*].devicePort")).containsExactly(9291, 8728)
        assertThat(JsonPath.read<Int>(moved, "$.remotePort")).isEqualTo(winboxPublic)

        // Hub menarik tabelnya: penanda + satu baris per penerusan, tersasar ke IP overlay peer.
        val (tblStatus, tbl) = forwardTable(nodeToken)
        assertThat(tblStatus).isEqualTo(200)
        assertThat(tbl.lines().first()).isEqualTo("#ftth-forwards")
        assertThat(tbl.lines()).contains("$winboxPublic $overlayIp 9291 tcp", "$apiPublic $overlayIp 8728 tcp")

        // Cabut satu penerusan → pintunya hilang dari tabel hub (penyelaras mencabut aturannya).
        val cut = send("DELETE", "/api/vpn/accounts/$accId/forwards/$winboxId", tenant)
        assertThat(JsonPath.read<List<Int>>(cut, "$.forwards[*].publicPort")).containsExactly(apiPublic)
        assertThat(JsonPath.read<Int>(cut, "$.remotePort")).isEqualTo(apiPublic)
        assertThat(forwardTable(nodeToken).second.lines()).doesNotContain("$winboxPublic $overlayIp 9291 tcp")

        // Akun dinonaktifkan = SEMUA pintunya tertutup, tanpa mencabut penerusannya satu-satu.
        post("/api/vpn/accounts/$accId/disable", tenant, "", expected = 200)
        assertThat(forwardTable(nodeToken).second).doesNotContain(" $overlayIp ")
    }

    @Test
    fun `token invalid gagal-aman di semua endpoint provisioning`() {
        val bogus = "ftthv_tokentaklaku"
        installScript(bogus, expected = 404)
        assertThat(authenticate(bogus, "siapa", "apa")).isEqualTo(403)
        assertThat(clientConnect(bogus, "siapa").first).isEqualTo(403)
        // Tabel penerusan ikut gagal-aman: VPS tanpa token sah tak boleh memetakan hub.
        assertThat(forwardTable(bogus).first).isEqualTo(403)
    }

    @Test
    fun `rotasi token mencabut token lama`() {
        val (serverId, _, oldToken) = newHub("Hub Rotasi")

        val rotated = post("/api/vpn/servers/$serverId/regenerate-token", platformToken(), "", expected = 200)
        val newToken = JsonPath.read<String>(rotated, "$.nodeToken")
        assertThat(newToken).isNotEqualTo(oldToken)

        // Token lama tak lagi berlaku; yang baru bekerja.
        installScript(oldToken, expected = 404)
        installScript(newToken, expected = 200)
    }

    @Test
    fun `akun tenant lain tak terlihat & tak bisa diunduh`() {
        newHub("Hub Bersama")
        val tenantA = newTenantAdmin("vpn-own-a")
        val tenantB = newTenantAdmin("vpn-own-b")

        val accA = id(generate(tenantA))

        // Tenant B: akun A bukan miliknya → 404 pada get maupun unduh config, dan tak muncul di list.
        assertThat(statusOf("GET", "/api/vpn/accounts/$accA", tenantB)).isEqualTo(404)
        assertThat(statusOf("GET", "/api/vpn/accounts/$accA/ovpn", tenantB)).isEqualTo(404)
        val listB = getText("/api/vpn/accounts", tenantB)
        assertThat(JsonPath.read<List<String>>(listB, "$[*].id")).doesNotContain(accA)
    }

    @Test
    fun `token satu hub tak bisa mengautentikasi akun di hub lain`() {
        // Dua hub siap-pakai. Auto-assign least-loaded: akun-1 → hub A (nama lebih dulu),
        // lalu hub A terisi → akun-2 → hub B. Jadi tiap akun jatuh di hub berbeda.
        val (_, nameA, nodeA) = newHub("Hub A", cidr = "10.20.0.0/24")
        val (_, nameB, nodeB) = newHub("Hub B", cidr = "10.21.0.0/24")
        val tenant = newTenantAdmin("vpn-scope")

        val acc1 = generate(tenant, username = "peer-satu")
        val acc2 = generate(tenant, username = "peer-dua")
        assertThat(JsonPath.read<String>(acc1, "$.serverName")).isEqualTo(nameA)
        assertThat(JsonPath.read<String>(acc2, "$.serverName")).isEqualTo(nameB)

        val pass1 = JsonPath.read<String>(acc1, "$.password")
        val pass2 = JsonPath.read<String>(acc2, "$.password")

        // Tiap akun hanya sah lewat token node hub-nya sendiri.
        assertThat(authenticate(nodeA, "peer-satu", pass1)).isEqualTo(204)
        assertThat(authenticate(nodeB, "peer-satu", pass1)).isEqualTo(403)
        assertThat(authenticate(nodeB, "peer-dua", pass2)).isEqualTo(204)
        assertThat(authenticate(nodeA, "peer-dua", pass2)).isEqualTo(403)
    }
}
