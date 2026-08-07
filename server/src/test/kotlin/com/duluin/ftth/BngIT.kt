package com.duluin.ftth

import com.duluin.ftth.contract.CollectorProtocol
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantCommand
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantUseCase
import com.jayway.jsonpath.JsonPath
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
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
import java.time.Instant
import java.util.UUID

/**
 * Uji module BNG (BRAS/RADIUS) slice fondasi: kelola paket & registri BRAS, lalu
 * provisi identitas jaringan (akun PPPoE) untuk langganan. Menegakkan hal-hal yang
 * paling gampang salah: rahasia tak pernah bocor lewat API, keunikan username &
 * satu-akun-per-langganan, penolakan hapus objek terpakai, sinkronisasi status
 * mengikuti daur hidup langganan (event), isolasi tenant (RLS), dan izin.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BngIT {

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

    /** Paket katalog yang dirujuk akun PPPoE (bng tak lagi punya katalog sendiri). */
    private fun plan(token: String, name: String, down: Int = 20, up: Int = 10): String =
        catalogPlan(token, name, down, up)

    private fun nas(token: String, name: String): String =
        id(
            post(
                "/api/bng/nas", token,
                """{"name":"$name","vendor":"MIKROTIK","address":"10.0.0.1",
                    "nasIdentifier":"$name","coaSecret":null,"collectorId":null}""",
            ),
        )

    /** Membuat collector dan mengembalikan API key mentahnya (untuk gerbang collector). */
    private fun newCollector(token: String): String =
        JsonPath.read(
            post("/api/monitoring/collectors", token, """{"name":"Collector ${uniq()}","pollIntervalSeconds":60}"""),
            "$.apiKey",
        )

    /** Mengirim ke gerbang collector memakai API key, bukan JWT pengguna. */
    private fun postAsCollector(url: String, apiKey: String, body: String, expected: Int = 200): String =
        mockMvc.perform(
            post(url).header(CollectorProtocol.API_KEY_HEADER, apiKey)
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect { assertThat(it.response.status).isEqualTo(expected) }
            .andReturn().response.contentAsString

    /** Satu sesi PPPoE wire (contract.RadiusSessionReading) yang dilaporkan BRAS. */
    private fun sessionReading(username: String, out: Long, inn: Long): String =
        """
        {"username":"$username","online":true,"framedIp":"10.20.30.40","nasIp":"10.0.0.1",
         "sessionId":"sess-1","callingStationId":"AA:BB:CC:DD:EE:FF","uptimeSeconds":3600,
         "inOctets":$inn,"outOctets":$out}
        """.trimIndent()

    private fun bngBatch(nasId: String, collectedAt: Instant, reading: String, batchId: String = uniq()): String =
        """{"batchId":"$batchId","nasId":"$nasId","collectedAt":"$collectedAt","sessions":[$reading]}"""

    /** Membuat paket katalog dan mengembalikan id-nya (langganan merujuk ini, bukan teks bebas). */
    private fun catalogPlan(token: String, name: String, down: Int = 20, up: Int = 10, price: Int = 150000): String =
        id(
            post(
                "/api/catalog/plans", token,
                """{"name":"$name","description":null,"price":$price,"downMbps":$down,"upMbps":$up,"serviceTypes":["PPPOE"]}""",
            ),
        )

    /** Pelanggan + langganan aktif merujuk paket katalog; kembalikan (customerId, subscriptionId). */
    private fun activeSubscription(token: String): Pair<String, String> {
        val s = uniq().uppercase()
        val customer = id(
            post(
                "/api/customers", token,
                """{"code":"C-$s","name":"Pelanggan $s","address":"Jl. Uji",
                    "location":{"longitude":106.996,"latitude":-6.246}}""",
            ),
        )
        val planId = catalogPlan(token, "Paket $s")
        val sub = id(
            post(
                "/api/customers/$customer/subscriptions", token,
                """{"planId":"$planId"}""",
            ),
        )
        post("/api/customers/subscriptions/$sub/activate", token, "", expected = 200)
        return customer to sub
    }

    @Test
    fun `provisi akun PPPoE untuk langganan, tanpa pernah membocorkan password`() {
        val token = newTenantAdmin("bng")
        val planId = plan(token, "Home 20/10")
        val nasId = id(
            post(
                "/api/bng/nas", token,
                """{"name":"BRAS-Pusat","vendor":"MIKROTIK","address":"10.0.0.1",
                    "nasIdentifier":"bras-pusat","coaSecret":"coaRahasia987","collectorId":null}""",
            ),
        )
        val (customer, sub) = activeSubscription(token)

        val secret = "pppoeRahasia123"
        val created = post(
            "/api/bng/access", token,
            """{"subscriptionId":"$sub","username":"pppoe${uniq()}","secret":"$secret","planId":"$planId","nasId":"$nasId"}""",
        )
        assertThat(JsonPath.read<String>(created, "$.status")).isEqualTo("ACTIVE")
        assertThat(JsonPath.read<String>(created, "$.authType")).isEqualTo("PPPOE")
        assertThat(JsonPath.read<String>(created, "$.planName")).isEqualTo("Home 20/10")
        assertThat(JsonPath.read<String>(created, "$.nasName")).isEqualTo("BRAS-Pusat")
        assertThat(JsonPath.read<String>(created, "$.subscriptionId")).isEqualTo(sub)
        assertThat(JsonPath.read<String>(created, "$.customerId")).isEqualTo(customer)
        // Password tak pernah muncul di respons mana pun.
        assertThat(created).doesNotContain(secret)

        val accessId = id(created)

        // Terbaca lewat langganan maupun pelanggan.
        assertThat(JsonPath.read<List<String>>(getJson("/api/bng/subscriptions/$sub/access", token), "$[*].id"))
            .containsExactly(accessId)
        assertThat(JsonPath.read<List<String>>(getJson("/api/bng/access?customerId=$customer", token), "$[*].id"))
            .containsExactly(accessId)

        // Ganti password: tetap tak bocor.
        val afterReset = post("/api/bng/access/$accessId/reset-secret", token, """{"secret":"gantiPass456"}""", expected = 200)
        assertThat(afterReset).doesNotContain("gantiPass456")

        // Pindah paket.
        val plan2 = plan(token, "Home 50/20", down = 50, up = 20)
        val moved = put("/api/bng/access/$accessId", token, """{"planId":"$plan2","nasId":"$nasId"}""")
        assertThat(JsonPath.read<String>(moved, "$.planName")).isEqualTo("Home 50/20")
    }

    @Test
    fun `secret CoA BRAS tak pernah dibalikan, hanya penanda hasCoaSecret`() {
        val token = newTenantAdmin("bng-nas")
        val body = post(
            "/api/bng/nas", token,
            """{"name":"BRAS-Cab","vendor":"OTHER","address":"10.9.9.9",
                "nasIdentifier":"cab","coaSecret":"secretCoADiam111","collectorId":null}""",
        )
        assertThat(JsonPath.read<Boolean>(body, "$.hasCoaSecret")).isTrue()
        assertThat(body).doesNotContain("secretCoADiam111")
        assertThat(body).doesNotContain("coaSecret")

        // Update tanpa mengirim secret: penanda tetap true (secret tak terhapus).
        val nasId = id(body)
        val updated = put(
            "/api/bng/nas/$nasId", token,
            """{"name":"BRAS-Cab","vendor":"OTHER","address":"10.9.9.9",
                "nasIdentifier":"cab","coaSecret":null,"collectorId":null,"enabled":true}""",
        )
        assertThat(JsonPath.read<Boolean>(updated, "$.hasCoaSecret")).isTrue()
    }

    @Test
    fun `kredensial kontrol BRAS tersimpan, password tak pernah dibalikan`() {
        val token = newTenantAdmin("bng-cred")
        val body = post(
            "/api/bng/nas", token,
            """{"name":"BRAS-Kred","vendor":"MIKROTIK","address":"10.9.9.9","nasIdentifier":"kred",
                "coaSecret":"coaDiam111","collectorId":null,
                "apiUsername":"ftth-api","apiSecret":"apiPassDiam222","apiPort":8729,"apiUseTls":false}""",
        )
        // Kredensial non-rahasia dibalikkan apa adanya; password hanya sebagai penanda.
        assertThat(JsonPath.read<String>(body, "$.apiUsername")).isEqualTo("ftth-api")
        assertThat(JsonPath.read<Int>(body, "$.apiPort")).isEqualTo(8729)
        assertThat(JsonPath.read<Boolean>(body, "$.apiUseTls")).isFalse()
        assertThat(JsonPath.read<Boolean>(body, "$.hasApiSecret")).isTrue()
        assertThat(JsonPath.read<Boolean>(body, "$.hasCoaSecret")).isTrue()
        // Rahasia tak pernah muncul di respons.
        assertThat(body).doesNotContain("apiPassDiam222")
        assertThat(body).doesNotContain("coaDiam111")
        assertThat(body).doesNotContain("apiSecret")

        // Update tanpa mengirim password: penanda tetap true (password tak terhapus),
        // field non-rahasia ikut berubah.
        val nasId = id(body)
        val updated = put(
            "/api/bng/nas/$nasId", token,
            """{"name":"BRAS-Kred","vendor":"MIKROTIK","address":"10.9.9.9","nasIdentifier":"kred",
                "coaSecret":null,"collectorId":null,
                "apiUsername":"ftth-api2","apiSecret":null,"apiPort":8443,"apiUseTls":false,"enabled":true}""",
        )
        assertThat(JsonPath.read<Boolean>(updated, "$.hasApiSecret")).isTrue()
        assertThat(JsonPath.read<String>(updated, "$.apiUsername")).isEqualTo("ftth-api2")
        assertThat(JsonPath.read<Int>(updated, "$.apiPort")).isEqualTo(8443)
    }

    @Test
    fun `username PPPoE unik per tenant`() {
        val token = newTenantAdmin("bng-uname")
        val planId = plan(token, "Paket A")
        val (_, sub1) = activeSubscription(token)
        val (_, sub2) = activeSubscription(token)
        val uname = "duplikat${uniq()}"

        post("/api/bng/access", token, """{"subscriptionId":"$sub1","username":"$uname","secret":"rahasia123","planId":"$planId","nasId":null}""")
        post(
            "/api/bng/access", token,
            """{"subscriptionId":"$sub2","username":"$uname","secret":"rahasia123","planId":"$planId","nasId":null}""",
            expected = 409,
        )
    }

    @Test
    fun `satu langganan maksimal satu akun`() {
        val token = newTenantAdmin("bng-one")
        val planId = plan(token, "Paket B")
        val (_, sub) = activeSubscription(token)

        post("/api/bng/access", token, """{"subscriptionId":"$sub","username":"a${uniq()}","secret":"rahasia123","planId":"$planId","nasId":null}""")
        post(
            "/api/bng/access", token,
            """{"subscriptionId":"$sub","username":"b${uniq()}","secret":"rahasia123","planId":"$planId","nasId":null}""",
            expected = 409,
        )
    }

    @Test
    fun `provisi langganan tak dikenal ditolak 404`() {
        val token = newTenantAdmin("bng-nosub")
        val planId = plan(token, "Paket C")
        post(
            "/api/bng/access", token,
            """{"subscriptionId":"${UUID.randomUUID()}","username":"x${uniq()}","secret":"rahasia123","planId":"$planId","nasId":null}""",
            expected = 404,
        )
    }

    @Test
    fun `isolir lalu terminasi langganan menyelaraskan status akun`() {
        val token = newTenantAdmin("bng-life")
        val planId = plan(token, "Paket Hidup")
        val (_, sub) = activeSubscription(token)
        val accessId = id(
            post("/api/bng/access", token, """{"subscriptionId":"$sub","username":"h${uniq()}","secret":"rahasia123","planId":"$planId","nasId":null}"""),
        )
        assertThat(JsonPath.read<String>(getJson("/api/bng/access/$accessId", token), "$.status")).isEqualTo("ACTIVE")

        // Isolir langganan → listener AFTER_COMMIT mengisolir akun.
        post("/api/customers/subscriptions/$sub/isolate", token, "", expected = 200)
        assertThat(JsonPath.read<String>(getJson("/api/bng/access/$accessId", token), "$.status")).isEqualTo("ISOLATED")

        // Terminasi langganan → akun ikut dihentikan.
        post("/api/customers/subscriptions/$sub/terminate", token, "", expected = 200)
        assertThat(JsonPath.read<String>(getJson("/api/bng/access/$accessId", token), "$.status")).isEqualTo("TERMINATED")
    }

    @Test
    fun `tenant lain tak melihat akun pelanggan`() {
        val tokenA = newTenantAdmin("bng-iso-a")
        val tokenB = newTenantAdmin("bng-iso-b")
        val planId = plan(tokenA, "Rahasia A")
        val (customerA, sub) = activeSubscription(tokenA)
        post("/api/bng/access", tokenA, """{"subscriptionId":"$sub","username":"iso${uniq()}","secret":"rahasia123","planId":"$planId","nasId":null}""")

        // Akun A lewat customerId A tetap kosong dari sisi B (RLS). Isolasi katalog paket
        // diuji tersendiri di CatalogIT.
        assertThat(JsonPath.read<List<String>>(getJson("/api/bng/access?customerId=$customerA", tokenB), "$[*].id")).isEmpty()
    }

    @Test
    fun `sesi PPPoE terkini dan tren trafik terbaca dari laporan collector`() {
        val token = newTenantAdmin("bng-sess")
        val apiKey = newCollector(token)
        val nasId = nas(token, "BRAS-Read")
        val planId = plan(token, "Paket Sesi")
        val (_, sub) = activeSubscription(token)
        val username = "pppoe${uniq()}"
        val accessId = id(
            post(
                "/api/bng/access", token,
                """{"subscriptionId":"$sub","username":"$username","secret":"rahasia123","planId":"$planId","nasId":"$nasId"}""",
            ),
        )

        // Sebelum ada laporan: akun dikenal namun offline — bukan 404. Membedakan
        // "belum terpantau" dari "akun tak dikenal".
        val before = getJson("/api/bng/access/$accessId/session", token)
        assertThat(JsonPath.read<Boolean>(before, "$.online")).isFalse()
        assertThat(JsonPath.read<String>(before, "$.username")).isEqualTo(username)

        // Dua poll berjarak 30 detik (selang poll nyata), penghitung octet tumbuh: 25 Mbps
        // unduh, 8 Mbps unggah. out_octets = arah unduh (keluar BRAS), in_octets = unggah.
        val t1 = Instant.now()
        val t0 = t1.minusSeconds(30)
        val out0 = 1_000_000_000L
        val out1 = out0 + 3_125_000L * 30 // Δ = 25 Mbps selama 30 detik
        val in0 = 500_000_000L
        val in1 = in0 + 1_000_000L * 30 // Δ = 8 Mbps selama 30 detik
        postAsCollector("/api/collector/bng-sessions", apiKey, bngBatch(nasId, t0, sessionReading(username, out0, in0)))
        postAsCollector("/api/collector/bng-sessions", apiKey, bngBatch(nasId, t1, sessionReading(username, out1, in1)))

        // Sesi terkini: online, IP framed, NAS teresolusi ke namanya.
        val session = getJson("/api/bng/access/$accessId/session", token)
        assertThat(JsonPath.read<Boolean>(session, "$.online")).isTrue()
        assertThat(JsonPath.read<String>(session, "$.framedIp")).isEqualTo("10.20.30.40")
        assertThat(JsonPath.read<String>(session, "$.nasName")).isEqualTo("BRAS-Read")

        // Tren trafik rentang 1 jam → ember = selang poll (30 dtk), tiap cuplikan jadi titik
        // sendiri: titik pertama tak berlaju (belum ada pembanding), titik kedua ≈25/8 Mbps.
        val traffic = getJson("/api/bng/access/$accessId/traffic?hours=1", token)
        assertThat(JsonPath.read<List<*>>(traffic, "$.points")).hasSize(2)
        assertThat(JsonPath.read<Any?>(traffic, "$.points[0].downMbps")).isNull()
        assertThat(JsonPath.read<Double>(traffic, "$.points[1].downMbps")).isCloseTo(25.0, within(0.1))
        assertThat(JsonPath.read<Double>(traffic, "$.points[1].upMbps")).isCloseTo(8.0, within(0.1))
        // Ringkasan hidup: throughput "sekarang" = titik terakhir, total pemakaian = Σ delta.
        assertThat(JsonPath.read<Double>(traffic, "$.currentDownMbps")).isCloseTo(25.0, within(0.1))
        assertThat(JsonPath.read<Double>(traffic, "$.currentUpMbps")).isCloseTo(8.0, within(0.1))
        assertThat((JsonPath.read<Any>(traffic, "$.totalBytes") as Number).toLong()).isEqualTo(123_750_000L)
    }

    @Test
    fun `heartbeat menyertakan BRAS sebagai target polling dengan daftar akun aktif`() {
        val token = newTenantAdmin("bng-cfg")
        val apiKey = newCollector(token)
        val nasId = nas(token, "BRAS-Cfg")
        val planId = plan(token, "Paket Cfg")
        val (_, sub) = activeSubscription(token)
        val username = "pppoe${uniq()}"
        post(
            "/api/bng/access", token,
            """{"subscriptionId":"$sub","username":"$username","secret":"rahasia123","planId":"$planId","nasId":"$nasId"}""",
        )

        // Konfigurasi yang dikembalikan denyut collector kini memuat BRAS ini sebagai
        // target polling, dengan username akun aktif untuk dipakai simulator.
        val config = postAsCollector("/api/collector/heartbeat", apiKey, """{"agentVersion":"test-1.0"}""")
        assertThat(JsonPath.read<List<String>>(config, "$.nasTargets[*].name")).contains("BRAS-Cfg")
        assertThat(JsonPath.read<List<String>>(config, "$.nasTargets[*].expectedUsernames[*]")).contains(username)
    }

    @Test
    fun `isolir mengantre DISCONNECT yang muncul di denyut lalu tuntas setelah collector ACK`() {
        val token = newTenantAdmin("bng-kendali")
        val apiKey = newCollector(token)
        val nasId = nas(token, "BRAS-Kendali")
        val planId = plan(token, "Paket Kendali")
        val (_, sub) = activeSubscription(token)
        val username = "pppoe${uniq()}"
        val accessId = id(
            post(
                "/api/bng/access", token,
                """{"subscriptionId":"$sub","username":"$username","secret":"rahasia123","planId":"$planId","nasId":"$nasId"}""",
            ),
        )

        // Isolir dari UI: status jadi ISOLATED sekaligus mengantre satu DISCONNECT.
        val isolated = post("/api/bng/access/$accessId/isolate", token, "", expected = 200)
        assertThat(JsonPath.read<String>(isolated, "$.status")).isEqualTo("ISOLATED")

        // Denyut collector membawa perintah DISCONNECT untuk akun itu (jalur turun) — di
        // samping PROVISION awal (kredensial + keanggotaan grup) yang juga masih menunggu.
        val config = postAsCollector("/api/collector/heartbeat", apiKey, """{"agentVersion":"test-1.0"}""")
        val disconnect = "$.bngActions[?(@.username=='$username' && @.kind=='DISCONNECT')]"
        assertThat(JsonPath.read<List<String>>(config, "$disconnect.kind")).containsExactly("DISCONNECT")
        val actionId = JsonPath.read<List<String>>(config, "$disconnect.actionId").single()

        // Collector meng-ACK sukses lewat denyut berikutnya → DISCONNECT dituntaskan
        // (listener AFTER_COMMIT), sehingga tak dikirim ulang di denyut sesudahnya.
        postAsCollector(
            "/api/collector/heartbeat", apiKey,
            """{"agentVersion":"test-1.0","actionResults":[{"actionId":"$actionId","success":true}]}""",
        )
        val after = postAsCollector("/api/collector/heartbeat", apiKey, """{"agentVersion":"test-1.0"}""")
        assertThat(JsonPath.read<List<String>>(after, "$disconnect.actionId")).isEmpty()
    }

    @Test
    fun `Reset Login memutus sesi pada akun ber-BRAS, ditolak pada akun tanpa BRAS`() {
        val token = newTenantAdmin("bng-reset")
        val apiKey = newCollector(token)
        val nasId = nas(token, "BRAS-Reset")
        val planId = plan(token, "Paket Reset")

        // Akun tanpa BRAS: tak ada sesi untuk diputus → 409 (bukan diam-diam sukses).
        val (_, subNoNas) = activeSubscription(token)
        val accessNoNas = id(
            post("/api/bng/access", token, """{"subscriptionId":"$subNoNas","username":"n${uniq()}","secret":"rahasia123","planId":"$planId","nasId":null}"""),
        )
        post("/api/bng/access/$accessNoNas/reset-login", token, "", expected = 409)

        // Akun ber-BRAS: Reset Login mengantre DISCONNECT tanpa mengubah status akun.
        val (_, sub) = activeSubscription(token)
        val username = "r${uniq()}"
        val accessId = id(
            post("/api/bng/access", token, """{"subscriptionId":"$sub","username":"$username","secret":"rahasia123","planId":"$planId","nasId":"$nasId"}"""),
        )
        val reset = post("/api/bng/access/$accessId/reset-login", token, "", expected = 200)
        assertThat(JsonPath.read<String>(reset, "$.status")).isEqualTo("ACTIVE")

        val config = postAsCollector("/api/collector/heartbeat", apiKey, """{"agentVersion":"test-1.0"}""")
        assertThat(JsonPath.read<List<String>>(config, "$.bngActions[?(@.username=='$username' && @.kind=='DISCONNECT')].kind"))
            .containsExactly("DISCONNECT")
    }

    @Test
    fun `ganti paket pada akun aktif mendorong CoA dengan kecepatan paket baru`() {
        val token = newTenantAdmin("bng-coa")
        val apiKey = newCollector(token)
        val nasId = nas(token, "BRAS-CoA")
        val plan1 = plan(token, "Lambat", down = 20, up = 10)
        val (_, sub) = activeSubscription(token)
        val username = "c${uniq()}"
        val accessId = id(
            post("/api/bng/access", token, """{"subscriptionId":"$sub","username":"$username","secret":"rahasia123","planId":"$plan1","nasId":"$nasId"}"""),
        )

        // Pindah ke paket lebih cepat pada akun aktif → CoA membawa kecepatan baru (di
        // samping PROVISION untuk memindah keanggotaan grup ke paket baru).
        val plan2 = plan(token, "Cepat", down = 100, up = 30)
        put("/api/bng/access/$accessId", token, """{"planId":"$plan2","nasId":"$nasId"}""")

        val config = postAsCollector("/api/collector/heartbeat", apiKey, """{"agentVersion":"test-1.0"}""")
        val coa = "$.bngActions[?(@.username=='$username' && @.kind=='COA')]"
        assertThat(JsonPath.read<List<String>>(config, "$coa.kind")).containsExactly("COA")
        assertThat(JsonPath.read<List<Int>>(config, "$coa.downMbps")).containsExactly(100)
        assertThat(JsonPath.read<List<Int>>(config, "$coa.upMbps")).containsExactly(30)
    }
}
