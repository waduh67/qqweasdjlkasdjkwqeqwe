package com.duluin.ftth.inventory

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantCommand
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantUseCase
import com.duluin.ftth.inventory.application.service.DecideInventoryApproval
import com.duluin.ftth.inventory.application.service.InventoryApprovalService
import com.duluin.ftth.inventory.domain.model.InventoryApprovalDecision
import com.jayway.jsonpath.JsonPath
import com.duluin.ftth.common.domain.error.ConflictException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
import java.util.UUID

/**
 * Penghapusbukuan unit BERSERIAL: ONT yang hilang, rusak, atau dihapus dari pembukuan.
 *
 * Sebelum ini jalurnya tidak ada sama sekali. Penyesuaian stok menolak barang berserial
 * mentah-mentah ("item berserial dan tidak bisa lewat penyesuaian stok curah") sementara
 * satu-satunya cara lain — memindahkan status aset langsung — tidak menyentuh saldo. Jadi
 * setiap ONT yang benar-benar hilang hanya punya dua akhir: tetap berdiri AVAILABLE di rak
 * selamanya, atau hilang dari daftar aset dengan saldo yang tetap menghitungnya.
 *
 * Yang diuji di sini adalah satu janji yang mahal kalau diingkari: SALDO DAN STATUS ASET
 * TIDAK PERNAH BERBEDA ARAH. Keduanya bergerak di transaksi yang sama, atau tidak
 * bergerak sama sekali.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InventorySerialWriteOffIT {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var onboarding: OnboardTenantUseCase
    @Autowired private lateinit var approvals: InventoryApprovalService

    private val pass = "secret12345"
    private val custodian = UUID.randomUUID()
    private val approver = UUID.randomUUID()

    private fun uniq() = UUID.randomUUID().toString().replace("-", "").substring(0, 8)

    private data class Admin(val token: String, val tenantId: UUID)

    private fun admin(prefix: String): Admin {
        val slug = "$prefix${uniq()}"
        val email = "admin@$slug.test"
        val result = onboarding.onboard(OnboardTenantCommand(slug, "Tenant $slug", email, "Admin", pass))
        val json = mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"tenantSlug":"$slug","email":"$email","password":"$pass"}"""),
        ).andReturn().response.contentAsString
        return Admin(JsonPath.read(json, "$.accessToken"), result.tenant.id)
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

    private fun location(token: String, kind: String): String =
        JsonPath.read(post("/api/inventory/locations", token, """{"code":"${kind.take(3)}-${uniq()}","kind":"$kind"}"""), "$.id")

    private fun ontItem(token: String): String = JsonPath.read(
        post(
            "/api/inventory/item-master", token,
            """{"code":"ONT-${uniq()}","name":"ONT Pelanggan","category":"ONT","unit":"PCS","serialized":true}""",
        ),
        "$.id",
    )

    private fun configure(token: String, type: String) =
        mockMvc.perform(
            put("/api/inventory/approvals/policies/$type").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"expiryHours":24,"emergencyAllowed":false,
                        "tiers":[{"number":1,"minimumAmount":0,"approverRole":"Kepala Gudang","approverIds":["$approver"]}]}""",
                ),
        ).andReturn().response.let {
            assertThat(it.status).describedAs("PUT policy $type -> ${it.contentAsString}").isEqualTo(200)
        }

    private fun decide(tenantId: UUID, approvalId: String, decision: InventoryApprovalDecision, key: String) =
        TenantContext.runAs(tenantId) {
            approvals.decide(UUID.fromString(approvalId), DecideInventoryApproval(tenantId, approver, decision, key, key))
        }

    /** Saldo satu dimensi (item + lokasi + status) — nol kalau barisnya memang tidak ada. */
    private fun balance(token: String, itemId: String, locationId: String, status: String): Int {
        val rows: List<Map<String, Any>> = JsonPath.read(getJson("/api/inventory/balances?itemId=$itemId", token), "$")
        return rows.filter { it["locationId"] == locationId && it["status"] == status }
            .sumOf { (it["quantity"] as Number).toInt() }
    }

    /** Status baris aset menurut daftar aset serial — null kalau barisnya sudah tidak ada. */
    private fun assetStatus(token: String, serial: String): String? {
        val rows: List<Map<String, Any>> = JsonPath.read(getJson("/api/inventory/items", token), "$")
        return rows.firstOrNull { it["serialNumber"] == serial }?.get("status") as String?
    }

    private fun register(token: String, item: String, location: String, vararg serials: String): String = post(
        "/api/inventory/serialized/bulk", token,
        """{"itemId":"$item","locationId":"$location","custodianId":"$custodian",
            "reason":"kiriman ONT","operationKey":"sn-${uniq()}","payloadHash":"sn-hash",
            "serials":[${serials.joinToString(",") { """{"serialNumber":"$it"}""" }}]}""",
    )

    private fun writeOff(
        token: String,
        item: String,
        location: String,
        serial: String,
        kind: String = "SCRAP",
        expected: Int = 201,
    ): String = post(
        "/api/inventory/adjustments", token,
        """{"locationId":"$location","custodianId":"$custodian","kind":"$kind",
            "reason":"unit tidak bisa dipakai lagi","operationKey":"wo-${uniq()}","payloadHash":"wo-hash",
            "lines":[{"itemId":"$item","quantity":1,"serialNumbers":["$serial"]}]}""",
        expected,
    )

    /**
     * Alur penuh satu unit yang dimusnahkan, plus tiga pintu yang harus tertutup sesudahnya.
     *
     * Bagian terpenting justru yang TIDAK terjadi saat permintaan diajukan: status asetnya
     * belum boleh bergerak. Kalau ONT sudah ditandai DISPOSED begitu formulir dikirim,
     * penolakan approval meninggalkan unit sehat yang tidak bisa dikeluarkan siapa pun lagi —
     * barang nyata yang mati di pembukuan karena satu formulir yang akhirnya ditolak.
     */
    @Test
    fun `unit berserial yang dihapusbukukan pindah ke dimensi terminal dan tidak bisa dipakai lagi`() {
        val admin = admin("wo-scrap")
        configure(admin.token, "SCRAP")
        val gudang = location(admin.token, "WAREHOUSE")
        val van = location(admin.token, "VEHICLE")
        val ont = ontItem(admin.token)
        val sn = "SN-${uniq().uppercase()}"
        val lain = "SN-${uniq().uppercase()}"
        register(admin.token, ont, gudang, sn, lain)
        assertThat(balance(admin.token, ont, gudang, "AVAILABLE")).isEqualTo(2)

        val result = writeOff(admin.token, ont, gudang, sn)
        assertThat(JsonPath.read<String>(result, "$.movement.state")).isEqualTo("PENDING_APPROVAL")
        // Belum disetujui: saldo utuh DAN unitnya masih sehat. Dua-duanya, bukan salah satu.
        assertThat(balance(admin.token, ont, gudang, "AVAILABLE")).isEqualTo(2)
        assertThat(assetStatus(admin.token, sn)).isEqualTo("AVAILABLE")

        decide(admin.tenantId, JsonPath.read(result, "$.approval.approvalId"), InventoryApprovalDecision.APPROVE, "d-scrap")

        assertThat(balance(admin.token, ont, gudang, "AVAILABLE")).isEqualTo(1)
        // Unitnya TIDAK lenyap dari saldo, ia pindah ke dimensi terminal: "berapa unit yang
        // dimusnahkan di gudang ini" adalah pertanyaan audit pertama, dan saldo yang hanya
        // berkurang tanpa sisa tidak bisa menjawabnya.
        assertThat(balance(admin.token, ont, gudang, "DISPOSED")).isEqualTo(1)
        assertThat(assetStatus(admin.token, sn)).isEqualTo("DISPOSED")

        // (a) Unit yang sudah dimusnahkan tidak bisa dikeluarkan lagi ke teknisi.
        post(
            "/api/inventory/issues", admin.token,
            """{"fromLocationId":"$gudang","custodianId":"$custodian","technicianId":"${UUID.randomUUID()}",
                "technicianLocationId":"$van","reason":"pasang di pelanggan",
                "operationKey":"iss-${uniq()}","payloadHash":"iss-hash",
                "lines":[{"itemId":"$ont","quantity":1,"serialNumbers":["$sn"]}]}""",
            expected = 409,
        )
        // (b) Dan tidak bisa "dihidupkan ulang" dengan mendaftarkan nomor serinya sekali lagi.
        post(
            "/api/inventory/serialized/bulk", admin.token,
            """{"itemId":"$ont","locationId":"$gudang","custodianId":"$custodian",
                "reason":"daftar ulang","operationKey":"sn-${uniq()}","payloadHash":"sn-hash-2",
                "serials":[{"serialNumber":"$sn"}]}""",
            expected = 409,
        )
        // (c) Hapus buku kedua ditolak DI MUKA, bukan nanti di tangan approver: kalau lolos,
        // saldo DISPOSED akan dihitung dua kali untuk satu unit fisik yang sama.
        writeOff(admin.token, ont, gudang, sn, expected = 409)

        // Unit sehat di rak yang sama sama sekali tidak tersentuh.
        assertThat(assetStatus(admin.token, lain)).isEqualTo("AVAILABLE")
    }

    /**
     * Dua permintaan hapus buku atas SATU serial yang diajukan sebelum salah satunya diputuskan.
     *
     * Pemeriksaan di muka tidak bisa menangkap ini: saat kedua formulir dikirim, unitnya memang
     * masih sehat. Tidak ada pula kunci lintas-permintaan di tingkat aset, jadi keduanya sah
     * menggantung bersamaan — dan di gudang yang ramai ini bukan kasus aneh, cukup dua petugas
     * melaporkan ONT rusak yang sama.
     *
     * Unit sehat kedua di rak yang sama SENGAJA ada, dan justru itu inti tesnya. Dia yang
     * membuat saldo AVAILABLE tetap cukup saat keputusan kedua diproses, sehingga penjaga saldo
     * negatif TIDAK ikut menahan apa pun. Tanpa penjaga status aset, keputusan kedua akan lewat
     * tanpa suara: DISPOSED terhitung dua unit padahal cuma satu yang dimusnahkan, AVAILABLE
     * jatuh ke nol padahal barangnya masih di rak, dan unit yang masih nyata itu jadi yatim —
     * ada di daftar aset, tidak ada di saldo.
     */
    @Test
    fun `dua permintaan hapus buku atas serial yang sama tidak pernah memotong saldo dua kali`() {
        val admin = admin("wo-ganda")
        configure(admin.token, "SCRAP")
        val gudang = location(admin.token, "WAREHOUSE")
        val ont = ontItem(admin.token)
        val sn = "SN-${uniq().uppercase()}"
        val lain = "SN-${uniq().uppercase()}"
        register(admin.token, ont, gudang, sn, lain)
        assertThat(balance(admin.token, ont, gudang, "AVAILABLE")).isEqualTo(2)

        val pertama = writeOff(admin.token, ont, gudang, sn)
        val kedua = writeOff(admin.token, ont, gudang, sn)

        decide(admin.tenantId, JsonPath.read(pertama, "$.approval.approvalId"), InventoryApprovalDecision.APPROVE, "d-ganda-1")
        assertThat(balance(admin.token, ont, gudang, "AVAILABLE")).isEqualTo(1)
        assertThat(balance(admin.token, ont, gudang, "DISPOSED")).isEqualTo(1)

        // Keputusan kedua gagal sebagai konflik yang terbaca approver — menyebut nomor serinya
        // dan status barunya — bukan 500 telanjang dan bukan sukses palsu.
        assertThatThrownBy {
            decide(admin.tenantId, JsonPath.read(kedua, "$.approval.approvalId"), InventoryApprovalDecision.APPROVE, "d-ganda-2")
        }.isInstanceOf(ConflictException::class.java)

        // Kegagalan itu tidak menggerakkan apa pun sedikit pun.
        assertThat(balance(admin.token, ont, gudang, "AVAILABLE")).isEqualTo(1)
        assertThat(balance(admin.token, ont, gudang, "DISPOSED")).isEqualTo(1)
        assertThat(assetStatus(admin.token, sn)).isEqualTo("DISPOSED")
        assertThat(assetStatus(admin.token, lain)).isEqualTo("AVAILABLE")
    }

    /**
     * Sisi OUT dibaca dari BARIS ASETNYA, bukan dari isi permintaan.
     *
     * ONT yang hilang biasanya hilang di tangan teknisi, bukan di rak. Kalau leg OUT memakai
     * custodian yang diketik petugas, saldo dipotong dari dimensi gudang yang tak pernah
     * berisi unit ini: dimensi teknisi tetap penuh (unit hantu yang terus terhitung) dan
     * dimensi gudang ditolak "stok tidak cukup" tanpa alasan yang masuk akal bagi petugas.
     */
    @Test
    fun `unit yang hilang di tangan teknisi dipotong dari dimensi teknisi`() {
        val admin = admin("wo-loss")
        configure(admin.token, "LOSS")
        val gudang = location(admin.token, "WAREHOUSE")
        val van = location(admin.token, "VEHICLE")
        val ont = ontItem(admin.token)
        val sn = "SN-${uniq().uppercase()}"
        val teknisi = UUID.randomUUID()
        register(admin.token, ont, gudang, sn)

        post(
            "/api/inventory/issues", admin.token,
            """{"fromLocationId":"$gudang","custodianId":"$custodian","technicianId":"$teknisi",
                "technicianLocationId":"$van","reason":"bekal harian",
                "operationKey":"iss-${uniq()}","payloadHash":"iss-hash",
                "lines":[{"itemId":"$ont","quantity":1,"serialNumbers":["$sn"]}]}""",
        )
        assertThat(balance(admin.token, ont, van, "ISSUED")).isEqualTo(1)

        val result = writeOff(admin.token, ont, van, sn, kind = "LOSS")
        decide(admin.tenantId, JsonPath.read(result, "$.approval.approvalId"), InventoryApprovalDecision.APPROVE, "d-loss")

        assertThat(balance(admin.token, ont, van, "ISSUED")).isZero()
        assertThat(balance(admin.token, ont, van, "LOST")).isEqualTo(1)
        assertThat(balance(admin.token, ont, gudang, "AVAILABLE")).isZero()
        assertThat(assetStatus(admin.token, sn)).isEqualTo("LOST")
    }

    /** Hapus buku yang DITOLAK harus mengembalikan unitnya persis seperti sebelum diminta. */
    @Test
    fun `hapus buku yang ditolak meninggalkan unitnya utuh`() {
        val admin = admin("wo-rej")
        configure(admin.token, "WRITE_OFF")
        val gudang = location(admin.token, "WAREHOUSE")
        val ont = ontItem(admin.token)
        val sn = "SN-${uniq().uppercase()}"
        register(admin.token, ont, gudang, sn)

        val result = writeOff(admin.token, ont, gudang, sn, kind = "WRITE_OFF")
        decide(admin.tenantId, JsonPath.read(result, "$.approval.approvalId"), InventoryApprovalDecision.REJECT, "d-rej")

        assertThat(balance(admin.token, ont, gudang, "AVAILABLE")).isEqualTo(1)
        assertThat(balance(admin.token, ont, gudang, "DISPOSED")).isZero()
        assertThat(assetStatus(admin.token, sn)).isEqualTo("AVAILABLE")

        // Dan unitnya masih benar-benar bisa dipakai — bukan sekadar terlihat sehat di layar.
        writeOff(admin.token, ont, gudang, sn, kind = "WRITE_OFF")
    }

    /**
     * Koreksi JUMLAH tidak berarti apa-apa untuk barang bernomor seri: yang ada hanya unit
     * tertentu yang hilang atau rusak. Menerimanya akan memotong saldo tanpa satu pun baris
     * aset yang ikut berpindah — persis perpecahan yang seluruh fitur ini dibuat untuk menutup.
     */
    @Test
    fun `koreksi jumlah ditolak untuk item berserial`() {
        val admin = admin("wo-corr")
        val gudang = location(admin.token, "WAREHOUSE")
        val ont = ontItem(admin.token)
        val sn = "SN-${uniq().uppercase()}"
        register(admin.token, ont, gudang, sn)

        writeOff(admin.token, ont, gudang, sn, kind = "CORRECTION", expected = 400)
    }

    /**
     * Restock berserial: unitnya terdaftar lebih dulu supaya duplikat SN tertangkap sebelum
     * kiriman disetujui, TAPI ia belum jadi stok.
     *
     * AWAITING_RECEIPT ada justru untuk celah ini. Tanpa status itu, pendaftaran awal harus
     * memakai AVAILABLE — dan gudang langsung mengaku punya barang yang belum pernah datang,
     * padahal seluruh alasan restock lewat persetujuan adalah supaya klaim itu tidak bisa
     * dibuat sepihak.
     */
    @Test
    fun `restock berserial menunggu tanpa jadi stok sampai disetujui`() {
        val admin = admin("wo-rs")
        configure(admin.token, "RESTOCK")
        val gudang = location(admin.token, "WAREHOUSE")
        val ont = ontItem(admin.token)
        val sn = "SN-${uniq().uppercase()}"

        val result = post(
            "/api/inventory/serialized/restock-requests", admin.token,
            """{"itemId":"$ont","locationId":"$gudang","custodianId":"$custodian",
                "reason":"kiriman pemasok","operationKey":"rs-${uniq()}","payloadHash":"rs-hash",
                "serials":[{"serialNumber":"$sn"}]}""",
        )
        assertThat(assetStatus(admin.token, sn)).isEqualTo("AWAITING_RECEIPT")
        // AWAITING_RECEIPT adalah SATU-SATUNYA status aset yang tidak pernah punya baris saldo.
        assertThat(balance(admin.token, ont, gudang, "AWAITING_RECEIPT")).isZero()
        assertThat(balance(admin.token, ont, gudang, "AVAILABLE")).isZero()

        decide(admin.tenantId, JsonPath.read(result, "$.approval.approvalId"), InventoryApprovalDecision.APPROVE, "d-rs")

        assertThat(balance(admin.token, ont, gudang, "AVAILABLE")).isEqualTo(1)
        assertThat(assetStatus(admin.token, sn)).isEqualTo("AVAILABLE")
    }

    /**
     * Restock berserial yang DITOLAK harus melepaskan nomor serinya.
     *
     * Barang yang dijanjikan itu tidak pernah ada secara fisik. Kalau barisnya ditinggalkan
     * hidup (dalam status apa pun), `existsHistoricalSerial` akan menolak pendaftaran ulang
     * selamanya — dan kiriman yang BENAR-BENAR datang kemudian, dengan nomor seri yang sama
     * dari pemasok yang sama, tidak bisa dimasukkan siapa pun.
     */
    @Test
    fun `restock berserial yang ditolak melepaskan nomor serinya`() {
        val admin = admin("wo-rsrej")
        configure(admin.token, "RESTOCK")
        val gudang = location(admin.token, "WAREHOUSE")
        val ont = ontItem(admin.token)
        val sn = "SN-${uniq().uppercase()}"

        val result = post(
            "/api/inventory/serialized/restock-requests", admin.token,
            """{"itemId":"$ont","locationId":"$gudang","custodianId":"$custodian",
                "reason":"kiriman pemasok","operationKey":"rs-${uniq()}","payloadHash":"rs-hash",
                "serials":[{"serialNumber":"$sn"}]}""",
        )
        decide(admin.tenantId, JsonPath.read(result, "$.approval.approvalId"), InventoryApprovalDecision.REJECT, "d-rsrej")

        assertThat(assetStatus(admin.token, sn)).isNull()
        assertThat(balance(admin.token, ont, gudang, "AVAILABLE")).isZero()

        // Kiriman yang akhirnya benar-benar datang bisa didaftarkan seperti biasa.
        register(admin.token, ont, gudang, sn)
        assertThat(assetStatus(admin.token, sn)).isEqualTo("AVAILABLE")
        assertThat(balance(admin.token, ont, gudang, "AVAILABLE")).isEqualTo(1)
    }
}
