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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

/**
 * Uji end-to-end Phase 1: rantai inventory OLT→ODC→ODP, aturan penempatan ONU
 * pada port ODP, komposisi lintas-module di endpoint GIS, dan isolasi tenant
 * untuk aset jaringan.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NetworkEndToEndIT {

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

    private fun idOf(json: String): String = JsonPath.read(json, "$.id")

    /** Membangun rantai lengkap POP → OLT → PON → ODC → ODP dan mengembalikan id ODP. */
    private fun buildChain(token: String, capacity: Int = 8): String {
        val suffix = uniq().uppercase()
        val site = idOf(
            post(
                "/api/sites", token,
                """{"code":"POP-$suffix","name":"POP $suffix","location":{"longitude":106.98,"latitude":-6.23}}""",
            ),
        )
        val olt = idOf(
            post(
                "/api/olts", token,
                """{"siteId":"$site","code":"OLT-$suffix","name":"OLT $suffix","vendor":"ZTE",
                    "managementIp":"10.0.0.1","snmpCommunity":"rahasia"}""",
            ),
        )
        val pon = idOf(post("/api/olts/$olt/pon-ports", token, """{"label":"1/1/1"}"""))
        val odc = idOf(
            post(
                "/api/odcs", token,
                """{"code":"ODC-$suffix","name":"ODC $suffix","location":{"longitude":106.99,"latitude":-6.24},
                    "ponPortId":"$pon","splitterRatio":"1:8","capacity":64}""",
            ),
        )
        return idOf(
            post(
                "/api/odps", token,
                """{"code":"ODP-$suffix","name":"ODP $suffix","location":{"longitude":106.995,"latitude":-6.245},
                    "odcId":"$odc","splitterRatio":"1:8","capacity":$capacity}""",
            ),
        )
    }

    private fun attachNewCustomer(token: String, odpId: String, port: Int, expected: Int = 200): String {
        val suffix = uniq().uppercase()
        val customer = idOf(
            post(
                "/api/customers", token,
                """{"code":"CUST-$suffix","name":"Pelanggan $suffix","address":"Jl. Uji No. 1",
                    "location":{"longitude":106.996,"latitude":-6.246}}""",
            ),
        )
        val onu = idOf(post("/api/customers/$customer/onus", token, """{"serialNumber":"SN-$suffix"}"""))
        post("/api/customers/onus/$onu/attach", token, """{"odpId":"$odpId","portNumber":$port}""", expected)
        return customer
    }

    private data class Sub(val customerId: String, val serial: String)

    /** Seperti [attachNewCustomer], tapi mengembalikan juga serial ONU agar bisa dikirimi metrik. */
    private fun attachSub(token: String, odpId: String, port: Int): Sub {
        val suffix = uniq().uppercase()
        val customer = idOf(
            post(
                "/api/customers", token,
                """{"code":"CUST-$suffix","name":"Pelanggan $suffix","address":"Jl. Uji No. 1",
                    "location":{"longitude":106.996,"latitude":-6.246}}""",
            ),
        )
        val serial = "SN-$suffix"
        val onu = idOf(post("/api/customers/$customer/onus", token, """{"serialNumber":"$serial"}"""))
        post("/api/customers/onus/$onu/attach", token, """{"odpId":"$odpId","portNumber":$port}""", 200)
        return Sub(customer, serial)
    }

    /** ODP tambahan di bawah ODC yang sudah ada — untuk membangun dua ODP satu PON port. */
    private fun addOdp(token: String, odcId: String, capacity: Int = 8): String {
        val suffix = uniq().uppercase()
        return idOf(
            post(
                "/api/odps", token,
                """{"code":"ODP-$suffix","name":"ODP $suffix","location":{"longitude":106.997,"latitude":-6.247},
                    "odcId":"$odcId","splitterRatio":"1:8","capacity":$capacity}""",
            ),
        )
    }

    private fun firstOdcId(token: String): String = JsonPath.read(
        mockMvc.perform(get("/api/odcs").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsString,
        "$.content[0].id",
    )

    private fun newCollector(token: String): String =
        JsonPath.read(post("/api/monitoring/collectors", token, """{"name":"C-${uniq()}","pollIntervalSeconds":60}"""), "$.apiKey")

    private fun reading(serial: String, status: String, rx: Double?) =
        """{"serialNumber":"$serial","oltCode":"OLT-X","ponPortLabel":"1/1/1","status":"$status","rxPowerDbm":${rx ?: "null"},"txPowerDbm":null,"uptimeSeconds":null,"distanceMeters":null,"observedAt":"${Instant.now()}"}"""

    private fun sendMetrics(apiKey: String, vararg readings: String) {
        mockMvc.perform(
            post("/api/collector/metrics").header(CollectorProtocol.API_KEY_HEADER, apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"batchId":"b-${uniq()}","collectedAt":"${Instant.now()}","readings":[${readings.joinToString(",")}]}"""),
        ).andExpect(status().isOk)
    }

    private fun getJson(url: String, token: String): String =
        mockMvc.perform(get(url).header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsString

    /** Langganan aktif untuk pelanggan yang sudah ada; kembalikan id langganan. */
    private fun activateSubscription(token: String, customerId: String): String {
        val planId = idOf(
            post(
                "/api/catalog/plans", token,
                """{"name":"Home 20 ${uniq()}","description":null,"price":150000,"downMbps":20,"upMbps":10,"serviceTypes":["PPPOE"]}""",
            ),
        )
        val sub = idOf(
            post(
                "/api/customers/$customerId/subscriptions", token,
                """{"planId":"$planId"}""",
            ),
        )
        post("/api/customers/subscriptions/$sub/activate", token, "", expected = 200)
        return sub
    }

    /** Akun PPPoE pada sebuah BRAS untuk sebuah langganan; kembalikan username-nya. */
    private fun provisionPppoe(token: String, subscriptionId: String, nasId: String): String {
        val planId = idOf(
            post(
                "/api/catalog/plans", token,
                """{"name":"Home 20/10 ${uniq()}","description":null,"price":150000,"downMbps":20,"upMbps":10,"serviceTypes":["PPPOE"]}""",
            ),
        )
        val username = "pppoe${uniq()}"
        post(
            "/api/bng/access", token,
            """{"subscriptionId":"$subscriptionId","username":"$username","secret":"rahasia123","planId":"$planId","nasId":"$nasId"}""",
        )
        return username
    }

    private fun registerNas(token: String, name: String): String =
        idOf(
            post(
                "/api/bng/nas", token,
                """{"name":"$name","vendor":"MIKROTIK","address":"10.0.0.1","nasIdentifier":"$name","coaSecret":null,"collectorId":null}""",
            ),
        )

    /** BRAS melaporkan satu sesi PPPoE hidup lewat gerbang collector. */
    private fun reportBngSession(apiKey: String, nasId: String, username: String) {
        val reading = """{"username":"$username","online":true,"framedIp":"10.20.30.40","nasIp":"10.0.0.1",
            "sessionId":"sess-1","callingStationId":"AA:BB:CC:DD:EE:FF","uptimeSeconds":3600,
            "inOctets":500000000,"outOctets":1000000000}"""
        mockMvc.perform(
            post("/api/collector/bng-sessions").header(CollectorProtocol.API_KEY_HEADER, apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"batchId":"b-${uniq()}","nasId":"$nasId","collectedAt":"${Instant.now()}","sessions":[$reading]}"""),
        ).andExpect(status().isOk)
    }

    @Test
    fun `satu port ODP hanya boleh ditempati satu ONU`() {
        val token = newTenantAdmin("port")
        val odp = buildChain(token)

        attachNewCustomer(token, odp, port = 3)
        // Port yang sama untuk pelanggan lain harus ditolak sebagai konflik.
        attachNewCustomer(token, odp, port = 3, expected = 409)
    }

    @Test
    fun `nomor port di luar kapasitas ODP ditolak`() {
        val token = newTenantAdmin("cap")
        val odp = buildChain(token, capacity = 8)

        attachNewCustomer(token, odp, port = 99, expected = 400)
    }

    @Test
    fun `ODP yang masih dipakai pelanggan tidak bisa dihapus`() {
        val token = newTenantAdmin("del")
        val odp = buildChain(token)
        attachNewCustomer(token, odp, port = 1)

        // Menjaga agar ONU tidak menggantung: FK-nya ON DELETE SET NULL, sehingga
        // tanpa penjagaan ini penghapusan berhasil diam-diam.
        mockMvc.perform(delete("/api/odps/$odp").header("Authorization", "Bearer $token"))
            .andExpect(status().isConflict)
    }

    @Test
    fun `panel ODP menampilkan hulu lengkap dan pelanggan yang menempel`() {
        val token = newTenantAdmin("panel")
        val odp = buildChain(token, capacity = 8)
        attachNewCustomer(token, odp, port = 2)
        attachNewCustomer(token, odp, port = 5)

        val json = mockMvc.perform(get("/api/gis/odps/$odp").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsString

        assertThat(JsonPath.read<Int>(json, "$.usedPorts")).isEqualTo(2)
        assertThat(JsonPath.read<Int>(json, "$.capacity")).isEqualTo(8)
        assertThat(JsonPath.read<Int>(json, "$.utilizationPercent")).isEqualTo(25)
        assertThat(JsonPath.read<List<Int>>(json, "$.availablePortNumbers")).containsExactly(1, 3, 4, 6, 7, 8)
        // Rantai hulu terisi penuh sampai site — prasyarat monitoring di Phase 2.
        assertThat(JsonPath.read<Boolean>(json, "$.upstream.complete")).isTrue()
        assertThat(JsonPath.read<List<Int>>(json, "$.occupants[*].portNumber")).containsExactly(2, 5)
    }

    @Test
    fun `blast radius ODC mendaftar seluruh pelanggan di hilirnya`() {
        val token = newTenantAdmin("blast")
        val odp = buildChain(token, capacity = 8)
        attachNewCustomer(token, odp, port = 1)
        attachNewCustomer(token, odp, port = 2)

        val odc = JsonPath.read<String>(
            mockMvc.perform(get("/api/odcs").header("Authorization", "Bearer $token"))
                .andExpect(status().isOk).andReturn().response.contentAsString,
            "$.content[0].id",
        )

        val json = mockMvc.perform(get("/api/gis/odcs/$odc/blast-radius").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsString

        assertThat(JsonPath.read<Int>(json, "$.odpCount")).isEqualTo(1)
        assertThat(JsonPath.read<Int>(json, "$.customerCount")).isEqualTo(2)
        assertThat(JsonPath.read<Boolean>(json, "$.energized")).isTrue()
        assertThat(JsonPath.read<List<String>>(json, "$.customers[*].odpCode")).hasSize(2)
        // downCount harus konsisten dengan status yang benar-benar dilaporkan —
        // berapa pun status bawaan ONU yang belum dipantau.
        val statuses: List<String> = JsonPath.read(json, "$.customers[*].onuStatus")
        val expectedDown = statuses.count { it == "LOS" || it == "OFFLINE" }
        assertThat(JsonPath.read<Int>(json, "$.downCount")).isEqualTo(expectedDown)
    }

    @Test
    fun `telusur jalur pelanggan sampai OLT beserta anggaran redaman`() {
        val token = newTenantAdmin("trace")
        val odp = buildChain(token)
        val customer = attachNewCustomer(token, odp, port = 1)

        val json = mockMvc.perform(
            get("/api/gis/trace/customers/$customer").header("Authorization", "Bearer $token"),
        ).andExpect(status().isOk).andReturn().response.contentAsString

        assertThat(JsonPath.read<List<String>>(json, "$.hops[*].kind"))
            .containsExactly("CUSTOMER", "ODP", "ODC", "PON_PORT", "OLT", "SITE")
        // Dua tingkat splitter 1:8 => 2 x 10,5 dB, ditambah redaman serat.
        assertThat(JsonPath.read<Double>(json, "$.upstream.splitterLossDb")).isEqualTo(21.0)
        assertThat(JsonPath.read<Double>(json, "$.estimatedLossDb")).isGreaterThan(21.0)
        // Tanpa akun PPPoE: hop BRAS tak muncul dan blok bras kosong.
        assertThat(JsonPath.read<List<String>>(json, "$.hops[*].kind")).doesNotContain("BRAS")
        assertThat(JsonPath.read<Any?>(json, "$.bras")).isNull()
    }

    @Test
    fun `telusur jalur diperkaya hop BRAS di puncak dan Rx optik hidup pada ONT`() {
        val token = newTenantAdmin("trace-bras")
        val apiKey = newCollector(token)
        val odp = buildChain(token, capacity = 8)
        val sub = attachSub(token, odp, port = 1)

        // Identitas jaringan: langganan aktif + akun PPPoE pada sebuah BRAS.
        val subscription = activateSubscription(token, sub.customerId)
        val nasId = registerNas(token, "BRAS-Trace")
        val username = provisionPppoe(token, subscription, nasId)

        // BRAS melapor sesi hidup, OLT melapor Rx optik — dua sumber berbeda dipertemukan.
        reportBngSession(apiKey, nasId, username)
        sendMetrics(apiKey, reading(sub.serial, "ONLINE", -21.5))

        val json = getJson("/api/gis/trace/customers/${sub.customerId}", token)

        // Hop BRAS berada di puncak jalur, tepat di atas SITE.
        assertThat(JsonPath.read<List<String>>(json, "$.hops[*].kind"))
            .containsExactly("CUSTOMER", "ODP", "ODC", "PON_PORT", "OLT", "SITE", "BRAS")
        assertThat(JsonPath.read<List<Boolean>>(json, "$.hops[?(@.kind=='BRAS')].online")).containsExactly(true)

        // Blok BRAS terstruktur: sesi online, IP framed, NAS teresolusi ke namanya.
        assertThat(JsonPath.read<Boolean>(json, "$.bras.online")).isTrue()
        assertThat(JsonPath.read<String>(json, "$.bras.username")).isEqualTo(username)
        assertThat(JsonPath.read<String>(json, "$.bras.framedIp")).isEqualTo("10.20.30.40")
        assertThat(JsonPath.read<String>(json, "$.bras.nasName")).isEqualTo("BRAS-Trace")

        // Bacaan optik HIDUP menempel pada simpul ONT (beda dari redaman baseline instalasi).
        assertThat(JsonPath.read<String>(json, "$.liveOnuStatus")).isEqualTo("ONLINE")
        assertThat(JsonPath.read<Double>(json, "$.liveRxPowerDbm")).isEqualTo(-21.5)
        assertThat(JsonPath.read<List<String>>(json, "$.hops[?(@.kind=='CUSTOMER')].detail").first())
            .contains("Rx").contains("-21.5")
    }

    @Test
    fun `tetangga sejalur mendaftar se-ODP dan se-PON dengan bacaan hidup`() {
        val token = newTenantAdmin("neighbor")
        // Dua ODP di bawah satu ODC (karena itu satu PON port): A dan B se-ODP,
        // C tetangga se-PON tapi beda ODP.
        val odp1 = buildChain(token, capacity = 8)
        val odp2 = addOdp(token, firstOdcId(token))
        val a = attachSub(token, odp1, port = 1)
        val b = attachSub(token, odp1, port = 2)
        val c = attachSub(token, odp2, port = 1)

        // Bacaan hidup: yang ditelusur (A) online, tetangga se-ODP (B) sedang LOS,
        // tetangga se-PON di ODP lain (C) online.
        val apiKey = newCollector(token)
        sendMetrics(apiKey, reading(a.serial, "ONLINE", -21.0), reading(b.serial, "LOS", null), reading(c.serial, "ONLINE", -20.0))

        val json = mockMvc.perform(
            get("/api/gis/trace/customers/${a.customerId}/neighbors").header("Authorization", "Bearer $token"),
        ).andExpect(status().isOk).andReturn().response.contentAsString

        // Se-ODP hanya penghuni ODP-1; se-PON supersetnya, termasuk C di ODP-2.
        assertThat(JsonPath.read<List<String>>(json, "$.sameOdp[*].customerId"))
            .containsExactlyInAnyOrder(a.customerId, b.customerId)
        assertThat(JsonPath.read<List<String>>(json, "$.samePonPort[*].customerId"))
            .containsExactlyInAnyOrder(a.customerId, b.customerId, c.customerId)

        // Pelanggan yang ditelusur ditandai `self`, dan hanya dia.
        assertThat(JsonPath.read<List<String>>(json, "$.samePonPort[?(@.self==true)].customerId"))
            .containsExactly(a.customerId)

        // Bacaan hidup nyampai: A online dengan Rx -21, tetangga B tampak LOS —
        // inti fiturnya, "siapa lagi di jalur yang sama dan kondisinya apa".
        assertThat(JsonPath.read<List<String>>(json, "$.sameOdp[?(@.self==true)].liveStatus"))
            .containsExactly("ONLINE")
        assertThat(JsonPath.read<List<Double>>(json, "$.sameOdp[?(@.self==true)].liveRxPowerDbm"))
            .containsExactly(-21.0)
        assertThat(JsonPath.read<List<String>>(json, "$.sameOdp[?(@.customerId=='${b.customerId}')].liveStatus"))
            .containsExactly("LOS")
    }

    @Test
    fun `community string SNMP tidak pernah dikembalikan API`() {
        val token = newTenantAdmin("snmp")
        buildChain(token)

        val json = mockMvc.perform(get("/api/olts").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsString

        assertThat(json).doesNotContain("rahasia")
        assertThat(JsonPath.read<Boolean>(json, "$.content[0].snmpConfigured")).isTrue()
    }

    @Test
    fun `aset jaringan tenant lain tidak terlihat`() {
        val tokenA = newTenantAdmin("neta")
        val tokenB = newTenantAdmin("netb")
        buildChain(tokenA)

        val json = mockMvc.perform(get("/api/odps").header("Authorization", "Bearer $tokenB"))
            .andExpect(status().isOk).andReturn().response.contentAsString

        assertThat(JsonPath.read<Int>(json, "$.totalElements")).isZero()
    }

    @Test
    fun `kabel drop tidak boleh menghubungkan pasangan simpul yang mustahil`() {
        val token = newTenantAdmin("cable")
        val suffix = uniq().uppercase()
        val site = idOf(
            post(
                "/api/sites", token,
                """{"code":"POP-$suffix","name":"POP $suffix","location":{"longitude":106.98,"latitude":-6.23}}""",
            ),
        )
        val olt = idOf(
            post(
                "/api/olts", token,
                """{"siteId":"$site","code":"OLT-$suffix","name":"OLT $suffix","vendor":"ZTE"}""",
            ),
        )
        val odp = buildChain(token)

        // DROP hanya sah dari ODP ke rumah pelanggan, bukan dari OLT.
        post(
            "/api/cables", token,
            """{"code":"CBL-$suffix","name":"Kabel $suffix","cableType":"DROP","coreCount":1,
                "route":[{"longitude":106.98,"latitude":-6.23},{"longitude":106.99,"latitude":-6.24}],
                "fromKind":"OLT","fromId":"$olt","toKind":"ODP","toId":"$odp"}""",
            expected = 400,
        )
    }

    @Test
    fun `vector tile berisi layer jaringan dan pelanggan`() {
        val token = newTenantAdmin("tile")
        val odp = buildChain(token)
        attachNewCustomer(token, odp, port = 1)

        // Tile z14 yang meliputi titik uji di sekitar Bekasi.
        val bytes = mockMvc.perform(get("/api/gis/tiles/14/13061/8476.mvt").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray

        assertThat(bytes).isNotEmpty()
        // Nama layer tersimpan apa adanya di protobuf MVT, jadi cukup dicari
        // sebagai byte — menghindari menyeret dependensi parser hanya untuk uji ini.
        val text = String(bytes, Charsets.ISO_8859_1)
        assertThat(text).contains("odp")
        assertThat(text).contains("customer")
    }
}
