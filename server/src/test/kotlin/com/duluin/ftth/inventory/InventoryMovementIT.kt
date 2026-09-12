package com.duluin.ftth.inventory

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
import java.util.UUID

/**
 * Permukaan TULIS gudang diuji lewat HTTP, bukan lewat service.
 *
 * Sampai P1 modul ini punya ledger yang rapi tapi nyaris tanpa jalan masuk: tidak ada
 * endpoint untuk menerima barang, memindahkan, mengeluarkan ke teknisi, atau mendaftarkan
 * ONT bernomor seri. Yang diuji di sini adalah hal-hal yang paling mahal kalau salah dan
 * paling sulit dilihat dari layar: stok tidak boleh minus, satu nomor seri tidak boleh
 * hidup dua kali, dan tombol yang ditekan dua kali tidak boleh jadi dua kali barang keluar.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InventoryMovementIT {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var onboarding: OnboardTenantUseCase

    private val pass = "secret12345"
    private val custodian = UUID.randomUUID()
    private val technician = UUID.randomUUID()

    private fun uniq() = UUID.randomUUID().toString().replace("-", "").substring(0, 8)

    private fun adminToken(prefix: String): String {
        val slug = "$prefix${uniq()}"
        val admin = "admin@$slug.test"
        onboarding.onboard(OnboardTenantCommand(slug, "Tenant $slug", admin, "Admin", pass))
        val json = mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"tenantSlug":"$slug","email":"$admin","password":"$pass"}"""),
        ).andReturn().response.contentAsString
        return JsonPath.read(json, "$.accessToken")
    }

    private fun post(url: String, token: String, body: String, expected: Int = 201): String =
        mockMvc.perform(
            post(url).header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andReturn().response.let {
            assertThat(it.status).describedAs("POST $url -> ${it.contentAsString}").isEqualTo(expected)
            it.contentAsString
        }

    private fun getJson(url: String, token: String): String =
        mockMvc.perform(get(url).header("Authorization", "Bearer $token")).andReturn().response.let {
            assertThat(it.status).describedAs("GET $url -> ${it.contentAsString}").isEqualTo(200)
            it.contentAsString
        }

    private fun id(json: String): String = JsonPath.read(json, "$.id")

    private fun location(token: String, code: String, kind: String, parentId: String? = null): String {
        val parent = parentId?.let { ""","parentId":"$it"""" } ?: ""
        return id(post("/api/inventory/locations", token, """{"code":"$code","kind":"$kind"$parent}"""))
    }

    private fun item(token: String, code: String, serialized: Boolean, trackMac: Boolean = false): String = id(
        post(
            "/api/inventory/item-master", token,
            """{"code":"$code","name":"Barang $code","category":"ONT","unit":"PCS",
                "serialized":$serialized,"trackMac":$trackMac}""",
        ),
    )

    /** Saldo satu dimensi (item + status) — nol kalau barisnya memang tidak ada. */
    private fun balance(token: String, itemId: String, locationId: String, status: String): Int {
        val rows: List<Map<String, Any>> = JsonPath.read(getJson("/api/inventory/balances?itemId=$itemId", token), "$")
        return rows.filter { it["locationId"] == locationId && it["status"] == status }
            .sumOf { (it["quantity"] as Number).toInt() }
    }

    @Test
    fun `alur gudang penuh - terima, transfer, keluarkan, retur, dan riwayatnya`() {
        val token = adminToken("inv-mov")
        val warehouse = location(token, "WH-${uniq()}", "WAREHOUSE")
        val bin = location(token, "BIN-${uniq()}", "BIN", warehouse)
        val van = location(token, "VAN-${uniq()}", "VEHICLE")
        val kabel = item(token, "KBL-${uniq()}", serialized = false)

        post(
            "/api/inventory/receipts", token,
            """{"locationId":"$warehouse","custodianId":"$custodian","reason":"kiriman awal",
                "operationKey":"grn-1","payloadHash":"grn-hash",
                "lines":[{"itemId":"$kabel","quantity":100}]}""",
        )
        assertThat(balance(token, kabel, warehouse, "AVAILABLE")).isEqualTo(100)

        post(
            "/api/inventory/issues", token,
            """{"fromLocationId":"$warehouse","custodianId":"$custodian","technicianId":"$technician",
                "technicianLocationId":"$van","reason":"bekal harian",
                "operationKey":"issue-1","payloadHash":"issue-hash",
                "lines":[{"itemId":"$kabel","quantity":20}]}""",
        )
        post(
            "/api/inventory/returns", token,
            """{"fromLocationId":"$van","technicianId":"$technician","toLocationId":"$warehouse",
                "custodianId":"$custodian","quarantine":true,"reason":"sisa rusak",
                "operationKey":"return-1","payloadHash":"return-hash",
                "lines":[{"itemId":"$kabel","quantity":5}]}""",
        )
        post(
            "/api/inventory/transfers", token,
            """{"fromLocationId":"$warehouse","fromCustodianId":"$custodian","toLocationId":"$bin",
                "toCustodianId":"$custodian","reason":"rapikan rak",
                "operationKey":"trf-1","payloadHash":"trf-hash",
                "lines":[{"itemId":"$kabel","quantity":10}]}""",
        )

        assertThat(balance(token, kabel, warehouse, "AVAILABLE")).isEqualTo(70)
        // Barang rusak masuk KARANTINA, bukan kembali tersedia: kalau tidak, unit cacat akan
        // dikeluarkan lagi ke teknisi berikutnya dan bolak-baliknya tak terlihat di laporan.
        assertThat(balance(token, kabel, warehouse, "QUARANTINE")).isEqualTo(5)
        assertThat(balance(token, kabel, bin, "AVAILABLE")).isEqualTo(10)
        assertThat(balance(token, kabel, van, "ISSUED")).isEqualTo(15)

        val van1: List<Map<String, Any>> = JsonPath.read(getJson("/api/inventory/van-stock?technicianId=$technician", token), "$")
        assertThat(van1).hasSize(1)

        // Riwayat ber-halaman: ledger gudang tidak pernah dipangkas, jadi layar riwayat WAJIB
        // tidak pernah memuat seluruhnya.
        val page = getJson("/api/inventory/ledger?page=0&size=2", token)
        assertThat(JsonPath.read<List<Any>>(page, "$.content")).hasSize(2)
        assertThat(JsonPath.read<Int>(page, "$.totalElements")).isEqualTo(4)
        // Nama barang dan lokasi ikut diterjemahkan; UUID telanjang membuat layar mustahil dibaca.
        assertThat(JsonPath.read<String>(page, "$.content[0].legs[0].itemCode")).isNotBlank()

        val filtered = getJson("/api/inventory/ledger?page=0&size=20&kind=TRANSFER", token)
        assertThat(JsonPath.read<Int>(filtered, "$.totalElements")).isEqualTo(1)
    }

    /**
     * Stok kurang adalah KONFLIK (409), bukan kesalahan server.
     *
     * [com.duluin.ftth.inventory.domain.model.InventoryInsufficientBalance] adalah
     * `IllegalStateException`, jadi tanpa terjemahan sengaja ia muncul sebagai 500 dan petugas
     * gudang akan mengira sistemnya rusak — lalu mencoba lagi, berkali-kali.
     */
    @Test
    fun `mengeluarkan lebih banyak dari stok ditolak dengan 409 dan tidak meninggalkan jejak`() {
        val token = adminToken("inv-short")
        val warehouse = location(token, "WH-${uniq()}", "WAREHOUSE")
        val van = location(token, "VAN-${uniq()}", "VEHICLE")
        val kabel = item(token, "KBL-${uniq()}", serialized = false)

        post(
            "/api/inventory/receipts", token,
            """{"locationId":"$warehouse","custodianId":"$custodian","reason":"kiriman awal",
                "operationKey":"grn-short","payloadHash":"grn-hash",
                "lines":[{"itemId":"$kabel","quantity":5}]}""",
        )
        post(
            "/api/inventory/issues", token,
            """{"fromLocationId":"$warehouse","custodianId":"$custodian","technicianId":"$technician",
                "technicianLocationId":"$van","reason":"ambil banyak",
                "operationKey":"issue-short","payloadHash":"issue-hash",
                "lines":[{"itemId":"$kabel","quantity":9}]}""",
            expected = 409,
        )

        assertThat(balance(token, kabel, warehouse, "AVAILABLE")).isEqualTo(5)
        // Mutasi yang ditolak TIDAK boleh punya baris di ledger: rebuild proyeksi harus bisa
        // mempercayai bahwa setiap baris di sana benar-benar pernah berlaku.
        assertThat(JsonPath.read<Int>(getJson("/api/inventory/ledger?page=0&size=20", token), "$.totalElements")).isEqualTo(1)
    }

    /**
     * Kunci operasi: tombol yang ditekan dua kali tidak boleh jadi dua kali barang masuk.
     * Kunci sama + payload BEDA adalah konflik, bukan replay — kalau dilonggarkan, retry yang
     * membawa jumlah berbeda diterima diam-diam sebagai "sudah pernah" dan stoknya salah.
     */
    @Test
    fun `kunci operasi yang sama tidak menambah stok dua kali`() {
        val token = adminToken("inv-idem")
        val warehouse = location(token, "WH-${uniq()}", "WAREHOUSE")
        val kabel = item(token, "KBL-${uniq()}", serialized = false)
        val body = """{"locationId":"$warehouse","custodianId":"$custodian","reason":"kiriman",
                "operationKey":"grn-idem","payloadHash":"grn-hash",
                "lines":[{"itemId":"$kabel","quantity":7}]}"""

        val first = post("/api/inventory/receipts", token, body)
        val replay = post("/api/inventory/receipts", token, body)
        assertThat(JsonPath.read<String>(replay, "$.movementId")).isEqualTo(JsonPath.read<String>(first, "$.movementId"))
        assertThat(balance(token, kabel, warehouse, "AVAILABLE")).isEqualTo(7)

        post("/api/inventory/receipts", token, body.replace("grn-hash", "hash-lain"), expected = 409)
    }

    /**
     * Pendaftaran serial massal: semua-atau-tidak.
     *
     * Menerima sebagian terdengar ramah, tapi petugas akan memperbaiki daftarnya lalu menempel
     * ulang seluruhnya — dan baris yang tadi berhasil kini ditolak sebagai "sudah terdaftar".
     * Ia akan mengira pendaftarannya gagal total dan mencari unit yang sebenarnya sudah ada.
     */
    @Test
    fun `pendaftaran serial massal menolak duplikat tanpa menyisakan separuh batch`() {
        val token = adminToken("inv-sn")
        val warehouse = location(token, "WH-${uniq()}", "WAREHOUSE")
        val van = location(token, "VAN-${uniq()}", "VEHICLE")
        val ont = item(token, "ONT-${uniq()}", serialized = true, trackMac = true)
        val prefix = uniq().uppercase()

        fun serials(vararg lines: Pair<String, String>) =
            lines.joinToString(",") { """{"serialNumber":"${it.first}","macAddress":"${it.second}"}""" }

        val registered = post(
            "/api/inventory/serialized/bulk", token,
            """{"itemId":"$ont","locationId":"$warehouse","custodianId":"$custodian",
                "reason":"kiriman ONT","operationKey":"sn-1","payloadHash":"sn-hash",
                "serials":[${serials("$prefix-1" to "AA:BB:CC:00:00:01", "$prefix-2" to "AA:BB:CC:00:00:02")}]}""",
        )
        assertThat(JsonPath.read<List<Any>>(registered, "$.registered")).hasSize(2)
        assertThat(JsonPath.read<Boolean>(registered, "$.replayed")).isFalse()
        assertThat(balance(token, ont, warehouse, "AVAILABLE")).isEqualTo(2)

        // (a) dobel DI DALAM satu daftar — termasuk unit ketiga yang sebenarnya sah.
        post(
            "/api/inventory/serialized/bulk", token,
            """{"itemId":"$ont","locationId":"$warehouse","custodianId":"$custodian",
                "reason":"salah tempel","operationKey":"sn-2","payloadHash":"sn-hash-2",
                "serials":[${serials("$prefix-3" to "AA:BB:CC:00:00:03", "$prefix-3" to "AA:BB:CC:00:00:04")}]}""",
            expected = 409,
        )
        // (b) serial yang SUDAH ada di sistem.
        post(
            "/api/inventory/serialized/bulk", token,
            """{"itemId":"$ont","locationId":"$warehouse","custodianId":"$custodian",
                "reason":"tempel ulang","operationKey":"sn-3","payloadHash":"sn-hash-3",
                "serials":[${serials("$prefix-1" to "AA:BB:CC:00:00:09")}]}""",
            expected = 409,
        )
        // (c) MAC yang sudah dipakai unit lain.
        post(
            "/api/inventory/serialized/bulk", token,
            """{"itemId":"$ont","locationId":"$warehouse","custodianId":"$custodian",
                "reason":"mac kembar","operationKey":"sn-4","payloadHash":"sn-hash-4",
                "serials":[${serials("$prefix-9" to "AA:BB:CC:00:00:01")}]}""",
            expected = 409,
        )

        val assets: List<Map<String, Any>> = JsonPath.read(getJson("/api/inventory/items", token), "$")
        assertThat(assets.map { it["serialNumber"] }).containsExactlyInAnyOrder("$prefix-1", "$prefix-2")
        assertThat(balance(token, ont, warehouse, "AVAILABLE")).isEqualTo(2)

        // Replay dengan kunci operasi yang sama: dijawab dari ledger, tanpa mendaftarkan ulang.
        val replay = post(
            "/api/inventory/serialized/bulk", token,
            """{"itemId":"$ont","locationId":"$warehouse","custodianId":"$custodian",
                "reason":"kiriman ONT","operationKey":"sn-1","payloadHash":"sn-hash",
                "serials":[${serials("$prefix-1" to "AA:BB:CC:00:00:01", "$prefix-2" to "AA:BB:CC:00:00:02")}]}""",
        )
        assertThat(JsonPath.read<Boolean>(replay, "$.replayed")).isTrue()
        assertThat(JsonPath.read<List<Any>>(getJson("/api/inventory/items", token), "$")).hasSize(2)

        // Unit berserial yang dikeluarkan WAJIB menyebut nomor serinya, dan status baris asetnya
        // ikut berpindah — bukan hanya angka saldonya.
        post(
            "/api/inventory/issues", token,
            """{"fromLocationId":"$warehouse","custodianId":"$custodian","technicianId":"$technician",
                "technicianLocationId":"$van","reason":"pasang di pelanggan",
                "operationKey":"issue-sn","payloadHash":"issue-hash",
                "lines":[{"itemId":"$ont","quantity":1,"serialNumbers":["$prefix-1"]}]}""",
        )
        val vanStock = getJson("/api/inventory/van-stock?technicianId=$technician", token)
        assertThat(JsonPath.read<List<String>>(vanStock, "$[0].lines[0].serialNumbers")).containsExactly("$prefix-1")

        // Jumlah nomor seri WAJIB sama dengan kuantitas; kalau tidak, saldo dan daftar aset
        // langsung berpisah jalan.
        post(
            "/api/inventory/issues", token,
            """{"fromLocationId":"$warehouse","custodianId":"$custodian","technicianId":"$technician",
                "technicianLocationId":"$van","reason":"tanpa serial",
                "operationKey":"issue-sn-2","payloadHash":"issue-hash-2",
                "lines":[{"itemId":"$ont","quantity":1}]}""",
            expected = 400,
        )
    }

    /** Barang berserial tidak boleh masuk lewat jalur curah — kalau boleh, satu kiriman tercatat dua kali. */
    @Test
    fun `penerimaan curah menolak item berserial`() {
        val token = adminToken("inv-bulk")
        val warehouse = location(token, "WH-${uniq()}", "WAREHOUSE")
        val ont = item(token, "ONT-${uniq()}", serialized = true)

        post(
            "/api/inventory/receipts", token,
            """{"locationId":"$warehouse","custodianId":"$custodian","reason":"salah jalur",
                "operationKey":"grn-sn","payloadHash":"grn-hash",
                "lines":[{"itemId":"$ont","quantity":3}]}""",
            expected = 400,
        )
    }
}
