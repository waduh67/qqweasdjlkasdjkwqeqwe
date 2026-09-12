package com.duluin.ftth.inventory

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantCommand
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantUseCase
import com.duluin.ftth.inventory.application.service.DecideInventoryApproval
import com.duluin.ftth.inventory.application.service.InventoryApprovalService
import com.duluin.ftth.inventory.domain.model.InventoryApprovalDecision
import com.duluin.ftth.tenancy.TenantApi
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
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Persetujuan gudang: siapa yang menentukan tier, dan apa yang benar-benar terjadi setelah
 * keputusan diambil.
 *
 * Dua hal yang diuji di sini adalah dua lubang terbesar sebelum P1. Pertama, kebijakan
 * persetujuan datang dari BODY REQUEST: petugas yang mengajukan penghapusbukuan aset boleh
 * menuliskan sendiri "cukup satu tier, approver: saya" dan snapshot-nya tersimpan rapi
 * seolah-olah sah. Kedua, "disetujui" hanyalah label — mutasi stok yang digantungnya tidak
 * pernah benar-benar berlaku maupun dibatalkan.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InventoryApprovalIT {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var onboarding: OnboardTenantUseCase
    @Autowired private lateinit var tenantApi: TenantApi
    @Autowired private lateinit var approvals: InventoryApprovalService

    private val pass = "secret12345"
    private val custodian = UUID.randomUUID()
    private val approverA = UUID.randomUUID()
    private val approverB = UUID.randomUUID()

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

    private fun post(url: String, token: String, body: String, expected: Int = 200): String =
        mockMvc.perform(
            post(url).header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andReturn().response.let {
            assertThat(it.status).describedAs("POST $url -> ${it.contentAsString}").isEqualTo(expected)
            it.contentAsString
        }

    private fun put(url: String, token: String, body: String, expected: Int = 200): String =
        mockMvc.perform(
            put(url).header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andReturn().response.let {
            assertThat(it.status).describedAs("PUT $url -> ${it.contentAsString}").isEqualTo(expected)
            it.contentAsString
        }

    private fun getJson(url: String, token: String): String =
        mockMvc.perform(get(url).header("Authorization", "Bearer $token")).andReturn().response.let {
            assertThat(it.status).describedAs("GET $url -> ${it.contentAsString}").isEqualTo(200)
            it.contentAsString
        }

    private fun warehouse(token: String): String =
        JsonPath.read(
            post("/api/inventory/locations", token, """{"code":"WH-${uniq()}","kind":"WAREHOUSE"}""", expected = 201),
            "$.id",
        )

    private fun item(token: String): String =
        JsonPath.read(
            post(
                "/api/inventory/item-master", token,
                """{"code":"KBL-${uniq()}","name":"Kabel Drop","category":"DROPCORE","unit":"METER","serialized":false}""",
                expected = 201,
            ),
            "$.id",
        )

    private fun configure(token: String, type: String, tiers: String, expiryHours: Int = 24, emergency: Boolean = false) =
        put(
            "/api/inventory/approvals/policies/$type", token,
            """{"expiryHours":$expiryHours,"emergencyAllowed":$emergency,"tiers":[$tiers]}""",
        )

    private fun tier(number: Int, minimumAmount: Long, role: String, approver: UUID) =
        """{"number":$number,"minimumAmount":$minimumAmount,"approverRole":"$role","approverIds":["$approver"]}"""

    private fun decide(tenantId: UUID, approvalId: String, approver: UUID, decision: InventoryApprovalDecision, key: String) =
        TenantContext.runAs(tenantId) {
            approvals.decide(UUID.fromString(approvalId), DecideInventoryApproval(tenantId, approver, decision, key, key))
        }

    private fun balance(token: String, itemId: String): Int {
        val rows: List<Map<String, Any>> = JsonPath.read(getJson("/api/inventory/balances?itemId=$itemId", token), "$")
        return rows.sumOf { (it["quantity"] as Number).toInt() }
    }

    /**
     * Bawaan produk adalah DUA tier dan SATU pun approver belum ditunjuk — repo ini belum punya
     * cara menanyakan "siapa pemegang peran X" ke modul iam. Permintaan bertipe itu WAJIB gagal
     * keras dengan pesan yang menyebut tier mana, bukan diam-diam melewati tier tanpa approver.
     */
    @Test
    fun `kebijakan bawaan dua tier dan permintaan ditolak sampai approver ditunjuk`() {
        val admin = admin("appr-def")

        val policies = getJson("/api/inventory/approvals/policies", admin.token)
        assertThat(JsonPath.read<List<Any>>(policies, "$")).hasSize(7)
        assertThat(JsonPath.read<List<Boolean>>(policies, "$[*].configured")).allMatch { !it }
        assertThat(JsonPath.read<List<String>>(policies, "$[?(@.type=='ADJUSTMENT')].tiers[*].approverRole"))
            .containsExactly("Kepala Gudang", "Manajer Operasional")

        post(
            "/api/inventory/approvals", admin.token,
            """{"type":"ADJUSTMENT","amount":10,"custodianId":"$custodian",
                "operationKey":"ap-${uniq()}","operationHash":"h"}""",
            expected = 400,
        )
    }

    /**
     * Tier yang dikarang klien DIABAIKAN.
     *
     * Body di bawah sengaja menyelipkan `tiers`, `expiryHours`, dan `policySnapshotHash` —
     * persis bentuk yang dulu diterima server. Yang tersimpan harus tetap matriks tenant:
     * kalau tidak, kontrol empat-mata berubah jadi formulir yang diisi sendiri oleh orang
     * yang mau dikontrol, dan basis data tetap terlihat rapi sesudahnya.
     */
    @Test
    fun `tier yang dikarang klien diabaikan server`() {
        val admin = admin("appr-forge")
        configure(admin.token, "ADJUSTMENT", tier(1, 0, "Kepala Gudang", approverA))
        val forged = UUID.randomUUID()

        val created = post(
            "/api/inventory/approvals", admin.token,
            """{"type":"ADJUSTMENT","amount":999999,"custodianId":"$custodian",
                "operationKey":"ap-${uniq()}","operationHash":"h",
                "tiers":[{"number":1,"minimumAmount":0,"approverIds":["$forged"]}],
                "policy":{"version":9,"tiers":[{"number":1,"minimumAmount":0,"approverIds":["$forged"]}]},
                "policySnapshotHash":"palsu","expiryHours":99999}""",
        )

        assertThat(JsonPath.read<List<String>>(created, "$.policy.tiers[*].approverIds[*]"))
            .containsExactly(approverA.toString())
        assertThat(JsonPath.read<String>(created, "$.policySnapshotHash")).isNotEqualTo("palsu")

        // Dan approver karangan itu memang tidak bisa memutuskan apa pun.
        val approvalId = JsonPath.read<String>(created, "$.approvalId")
        assertThat(runCatching { decide(admin.tenantId, approvalId, forged, InventoryApprovalDecision.APPROVE, "d-forge") }.isFailure).isTrue()
    }

    /**
     * Loop approval -> mutasi ditutup: APPROVED benar-benar memberlakukan restock-nya.
     *
     * Sebelum ini "disetujui" hanya label di tabel approval — mutasinya diam di
     * PENDING_APPROVAL, saldo tidak bertambah sebaris pun, dan selisihnya baru ketahuan saat
     * stok fisik diadu dengan sistem berbulan-bulan kemudian.
     */
    @Test
    fun `restock yang disetujui penuh memberlakukan mutasinya`() {
        val admin = admin("appr-loop")
        configure(
            admin.token, "RESTOCK",
            tier(1, 0, "Kepala Gudang", approverA) + "," + tier(2, 100, "Manajer Operasional", approverB),
        )
        val location = warehouse(admin.token)
        val kabel = item(admin.token)

        val result = post(
            "/api/inventory/restock-requests", admin.token,
            """{"locationId":"$location","custodianId":"$custodian","reason":"restock bulanan",
                "operationKey":"rs-${uniq()}","payloadHash":"rs-hash",
                "lines":[{"itemId":"$kabel","quantity":150}]}""",
            expected = 201,
        )
        assertThat(JsonPath.read<String>(result, "$.movement.state")).isEqualTo("PENDING_APPROVAL")
        // Saldo BELUM bergerak: kalau bertambah sekarang, penolakan approval meninggalkan stok
        // yang terlanjur bertambah dan gudang mengaku punya barang yang tak pernah datang.
        assertThat(balance(admin.token, kabel)).isZero()

        val approvalId = JsonPath.read<String>(result, "$.approval.approvalId")
        // 150 unit melewati ambang tier 2, jadi satu tanda tangan TIDAK cukup.
        assertThat(decide(admin.tenantId, approvalId, approverA, InventoryApprovalDecision.APPROVE, "d-1").status.name)
            .isEqualTo("PENDING")
        assertThat(balance(admin.token, kabel)).isZero()

        assertThat(decide(admin.tenantId, approvalId, approverB, InventoryApprovalDecision.APPROVE, "d-2").status.name)
            .isEqualTo("APPROVED")
        assertThat(balance(admin.token, kabel)).isEqualTo(150)
        assertThat(JsonPath.read<Int>(getJson("/api/inventory/ledger?page=0&size=20&state=APPLIED", admin.token), "$.totalElements"))
            .isEqualTo(1)
    }

    /** Penolakan MEMATIKAN mutasinya; kalau tidak, tersisa mutasi hantu yang bisa disahkan lewat jalur lain. */
    @Test
    fun `penyesuaian yang ditolak tidak pernah menggerakkan saldo`() {
        val admin = admin("appr-reject")
        configure(admin.token, "ADJUSTMENT", tier(1, 0, "Kepala Gudang", approverA))
        val location = warehouse(admin.token)
        val kabel = item(admin.token)

        val result = post(
            "/api/inventory/adjustments", admin.token,
            """{"locationId":"$location","custodianId":"$custodian","kind":"CORRECTION","increase":true,
                "reason":"koreksi hitung","operationKey":"adj-${uniq()}","payloadHash":"adj-hash",
                "lines":[{"itemId":"$kabel","quantity":12}]}""",
            expected = 201,
        )
        val approvalId = JsonPath.read<String>(result, "$.approval.approvalId")
        decide(admin.tenantId, approvalId, approverA, InventoryApprovalDecision.REJECT, "d-r")

        assertThat(balance(admin.token, kabel)).isZero()
        assertThat(JsonPath.read<Int>(getJson("/api/inventory/ledger?page=0&size=20&state=FAILED_PERMANENT", admin.token), "$.totalElements"))
            .isEqualTo(1)
    }

    /**
     * Susut barang: mutasi SATU ARAH (leg OUT saja) yang tetap wajib lewat persetujuan.
     *
     * Jalur ini pernah mustahil dipakai — invariant leg berpasangan menolaknya sebelum sampai
     * ke approval — dan tidak ada tes yang menangkapnya karena semua kasus penyesuaian memakai
     * CORRECTION. Sekarang diuji sampai saldonya benar-benar berkurang.
     */
    @Test
    fun `susut barang mengurangi saldo hanya setelah disetujui`() {
        val admin = admin("appr-loss")
        configure(admin.token, "LOSS", tier(1, 0, "Kepala Gudang", approverA))
        val location = warehouse(admin.token)
        val kabel = item(admin.token)

        post(
            "/api/inventory/receipts", admin.token,
            """{"locationId":"$location","custodianId":"$custodian","reason":"terima awal",
                "operationKey":"grn-${uniq()}","payloadHash":"grn-hash",
                "lines":[{"itemId":"$kabel","quantity":30}]}""",
            expected = 201,
        )
        assertThat(balance(admin.token, kabel)).isEqualTo(30)

        val result = post(
            "/api/inventory/adjustments", admin.token,
            """{"locationId":"$location","custodianId":"$custodian","kind":"LOSS","increase":false,
                "reason":"kabel hilang di lapangan","operationKey":"loss-${uniq()}","payloadHash":"loss-hash",
                "lines":[{"itemId":"$kabel","quantity":4}]}""",
            expected = 201,
        )
        // Saldo TIDAK boleh bergerak selama permintaannya masih menggantung.
        assertThat(balance(admin.token, kabel)).isEqualTo(30)

        val approvalId = JsonPath.read<String>(result, "$.approval.approvalId")
        decide(admin.tenantId, approvalId, approverA, InventoryApprovalDecision.APPROVE, "d-loss")

        assertThat(balance(admin.token, kabel)).isEqualTo(26)
    }

    /**
     * Penyapu tenggat: permintaan yang tak pernah disentuh siapa pun harus MATI, dan mutasi
     * yang digantungnya ikut dibatalkan. Tanpa ini barangnya tidak pernah benar-benar masuk
     * MAUPUN dilepaskan — bentuk kebocoran yang tidak muncul di laporan mana pun.
     */
    @Test
    fun `permintaan lewat tenggat disapu dan mutasinya dibatalkan`() {
        val admin = admin("appr-expiry")
        configure(admin.token, "RESTOCK", tier(1, 0, "Kepala Gudang", approverA))
        val location = warehouse(admin.token)
        val kabel = item(admin.token)

        val result = post(
            "/api/inventory/restock-requests", admin.token,
            """{"locationId":"$location","custodianId":"$custodian","reason":"restock",
                "operationKey":"rs-${uniq()}","payloadHash":"rs-hash",
                "lines":[{"itemId":"$kabel","quantity":8}]}""",
            expected = 201,
        )
        val approvalId = UUID.fromString(JsonPath.read<String>(result, "$.approval.approvalId"))

        // Waktunya dimajukan lewat parameter, bukan lewat menunggu: tenggat terpendek yang
        // diizinkan matriks adalah 5 menit, dan tes yang benar-benar menunggu selama itu
        // akan dimatikan orang, bukan diperbaiki.
        val swept = TenantContext.runAs(admin.tenantId) {
            approvals.expireOverdue(admin.tenantId, Instant.now().plus(Duration.ofDays(2)))
        }
        assertThat(swept).contains(approvalId)
        assertThat(JsonPath.read<String>(getJson("/api/inventory/approvals/$approvalId", admin.token), "$.status"))
            .isEqualTo("EXPIRED")
        assertThat(balance(admin.token, kabel)).isZero()
        assertThat(JsonPath.read<Int>(getJson("/api/inventory/ledger?page=0&size=20&state=FAILED_PERMANENT", admin.token), "$.totalElements"))
            .isEqualTo(1)
    }

    /**
     * Override darurat memotong SELURUH rantai tier, jadi jejaknya wajib ada dan wajib terbaca
     * pengawas. Jejak yang hanya lewat `AuditRecorder` tidak cukup: publikasinya ditulis setelah
     * commit dan kegagalannya sengaja ditelan — persis pada kasus yang paling perlu ditelusuri.
     */
    @Test
    fun `override darurat langsung berlaku dan meninggalkan jejak`() {
        val admin = admin("appr-emg")
        configure(
            admin.token, "ADJUSTMENT",
            tier(1, 0, "Kepala Gudang", approverA) + "," + tier(2, 10, "Manajer Operasional", approverB),
            emergency = true,
        )
        val location = warehouse(admin.token)
        val kabel = item(admin.token)

        val result = post(
            "/api/inventory/adjustments", admin.token,
            """{"locationId":"$location","custodianId":"$custodian","kind":"CORRECTION","increase":true,
                "reason":"stok opname mendadak","emergencyReason":"kabel dipakai perbaikan gardu malam ini",
                "operationKey":"adj-${uniq()}","payloadHash":"adj-hash",
                "lines":[{"itemId":"$kabel","quantity":40}]}""",
            expected = 201,
        )
        assertThat(JsonPath.read<String>(result, "$.approval.status")).isEqualTo("APPROVED")
        assertThat(JsonPath.read<String>(result, "$.movement.state")).isEqualTo("APPLIED")
        assertThat(balance(admin.token, kabel)).isEqualTo(40)

        val overrides = getJson("/api/inventory/approvals/emergency-overrides", admin.token)
        assertThat(JsonPath.read<List<Any>>(overrides, "$")).hasSize(1)
        assertThat(JsonPath.read<List<Int>>(overrides, "$[0].bypassedTiers")).containsExactly(1, 2)
        assertThat(JsonPath.read<String>(overrides, "$[0].reason")).contains("gardu")
    }
}
