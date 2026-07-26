package com.duluin.ftth.collector.adapter

import com.duluin.ftth.contract.BngActionCommand
import com.duluin.ftth.contract.BngActionKind
import com.duluin.ftth.contract.NasTarget
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Menguji adapter RouterOS terhadap REST v7 tiruan (HttpServer JDK, tanpa Spring —
 * collector memang bukan aplikasi Spring). Fokusnya kontrak kabel yang harus benar
 * agar server bisa dipercaya: sesi terbaca lengkap (dengan octet dari interface
 * dinamis & uptime terurai), Basic auth terkirim, DISCONNECT menghapus sesi dan
 * idempoten, serta CoA mengubah max-limit antrean dengan arah unggah/unduh yang tepat.
 */
class MikrotikRouterOsAdapterTest {

    private lateinit var server: HttpServer
    private val requests = mutableListOf<String>()
    private var lastAuth: String? = null
    private var lastPatchBody: String? = null

    // Satu sesi PPPoE hidup untuk budi@isp; ghost@isp sengaja tak ada di mana pun.
    private val activeJson = """
        [{".id":"*1","name":"budi@isp","address":"100.64.0.5",
          "caller-id":"AA:BB:CC:DD:EE:FF","session-id":"0x81000001","uptime":"1h2m3s"}]
    """.trimIndent()
    private val interfaceJson = """
        [{"name":"<pppoe-budi@isp>","rx-byte":"1000","tx-byte":"9000"},
         {"name":"ether1","rx-byte":"5","tx-byte":"5"}]
    """.trimIndent()
    private val queueJson = """[{".id":"*5","name":"<pppoe-budi@isp>"}]"""

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/rest") { exchange ->
            val method = exchange.requestMethod
            val path = exchange.requestURI.path
            requests += "$method $path"
            lastAuth = exchange.requestHeaders.getFirst("Authorization")
            val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            when {
                method == "GET" && path == "/rest/ppp/active" -> respond(exchange, activeJson)
                method == "GET" && path == "/rest/interface" -> respond(exchange, interfaceJson)
                method == "GET" && path == "/rest/queue/simple" -> respond(exchange, queueJson)
                method == "DELETE" && path.startsWith("/rest/ppp/active/") -> respond(exchange, "{}")
                method == "PATCH" && path.startsWith("/rest/queue/simple/") -> {
                    lastPatchBody = body
                    respond(exchange, "{}")
                }
                else -> respond(exchange, "not found", code = 404)
            }
        }
        server.start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    private fun respond(exchange: HttpExchange, body: String, code: Int = 200) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun target(host: String = "127.0.0.1") = NasTarget(
        nasId = "nas-1",
        name = "BRAS-BKS-01",
        vendor = "MIKROTIK",
        host = host,
        adapterType = "MIKROTIK",
        apiUsername = "ftth-api",
        apiSecret = "s3cr3t",
        apiPort = server.address.port,
        apiUseTls = false,
    )

    private val adapter = MikrotikRouterOsAdapter()

    @Test
    fun `poll memetakan sesi aktif lengkap dengan octet interface dan uptime`() {
        val sessions = adapter.pollSessions(target())

        assertEquals(1, sessions.size)
        val s = sessions.single()
        assertEquals("budi@isp", s.username)
        assertTrue(s.online)
        assertEquals("100.64.0.5", s.framedIp)
        assertEquals("127.0.0.1", s.nasIp)
        assertEquals("0x81000001", s.sessionId)
        assertEquals("AA:BB:CC:DD:EE:FF", s.callingStationId)
        assertEquals(3723L, s.uptimeSeconds) // 1h2m3s
        // rx interface (unggah, masuk BRAS) → inOctets; tx (unduh) → outOctets.
        assertEquals(1000L, s.inOctets)
        assertEquals(9000L, s.outOctets)
        // Basic auth base64("user:pass") terkirim.
        assertEquals("Basic " + Base64.getEncoder().encodeToString("ftth-api:s3cr3t".toByteArray()), lastAuth)
    }

    @Test
    fun `DISCONNECT menghapus sesi aktif yang cocok`() {
        adapter.execute(target(), BngActionCommand("a1", "nas-1", BngActionKind.DISCONNECT, "budi@isp"))
        assertTrue("DELETE /rest/ppp/active/*1" in requests, "harus menghapus sesi *1; nyatanya $requests")
    }

    @Test
    fun `DISCONNECT sesi yang sudah tak ada tidak melempar dan tak menghapus apa pun`() {
        adapter.execute(target(), BngActionCommand("a1", "nas-1", BngActionKind.DISCONNECT, "ghost@isp"))
        assertTrue(requests.none { it.startsWith("DELETE") }, "tak boleh ada DELETE; nyatanya $requests")
    }

    @Test
    fun `CoA mengubah max-limit antrean dengan urutan unggah-slash-unduh`() {
        adapter.execute(target(), BngActionCommand("a2", "nas-1", BngActionKind.COA, "budi@isp", downMbps = 100, upMbps = 30))
        assertTrue("PATCH /rest/queue/simple/*5" in requests, "harus PATCH antrean *5; nyatanya $requests")
        assertEquals("""{"max-limit":"30M/100M"}""", lastPatchBody)
    }

    @Test
    fun `CoA melempar bila antrean dinamis pelanggan tak ada`() {
        val ex = assertFailsWith<IllegalStateException> {
            adapter.execute(target(), BngActionCommand("a3", "nas-1", BngActionKind.COA, "ghost@isp", downMbps = 50, upMbps = 20))
        }
        assertTrue(ex.message!!.contains("<pppoe-ghost@isp>"), "pesan harus menyebut antrean yang dicari: ${ex.message}")
    }

    @Test
    fun `alamat manajemen kosong ditolak dengan pesan jelas`() {
        val ex = assertFailsWith<IllegalStateException> { adapter.pollSessions(target().copy(host = null)) }
        assertTrue(ex.message!!.contains("alamat manajemen"), ex.message)
    }

    @Test
    fun `parseRouterOsDuration menjumlahkan satuan dan mengabaikan sub-detik`() {
        assertEquals(3723L, MikrotikRouterOsAdapter.parseRouterOsDuration("1h2m3s"))
        assertEquals(698461L, MikrotikRouterOsAdapter.parseRouterOsDuration("1w1d2h1m1s")) // 604800+86400+7200+60+1
        assertEquals(45L, MikrotikRouterOsAdapter.parseRouterOsDuration("45s"))
        assertEquals(0L, MikrotikRouterOsAdapter.parseRouterOsDuration("990ms")) // sub-detik → 0
        assertNull(MikrotikRouterOsAdapter.parseRouterOsDuration(""))
    }
}
