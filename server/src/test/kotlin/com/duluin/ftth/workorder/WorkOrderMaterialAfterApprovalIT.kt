package com.duluin.ftth.workorder

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.fulfillment.FulfillmentOutboxWorker
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantCommand
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantUseCase
import com.duluin.ftth.tenancy.TenantApi
import com.jayway.jsonpath.JsonPath
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

/**
 * Catatan material sebuah WO berhenti bisa ditulis begitu hasil kerjanya DISETUJUI.
 *
 * Persetujuan penyelia adalah titik saga memotong saldo: material ter-consume dari van teknisi
 * dan unit tarikan mendarat di van sebagai RETURNED. Sebelum penjaga ini ada, setiap tulisan
 * masih diterima SESUDAH itu — dan yang paling tajam adalah pembatalan baris penarikan, yang
 * membuat barisnya berbunyi "dibatalkan" sementara unitnya nyata-nyata sudah bertambah di van.
 * Hasilnya bukan error, melainkan dua sumber yang saling membantah tanpa ada yang bisa
 * menunjuk mana yang benar: ledger memuat mutasi yang catatan WO-nya bilang tidak pernah ada.
 *
 * Yang TIDAK boleh ikut terkunci sama pentingnya. WO yang DITOLAK justru harus bisa diperbaiki —
 * itu seluruh guna penolakan, dan saldonya memang belum bergerak sedikit pun. Begitu juga WO
 * yang masih menunggu keputusan penyelia. Penjaga yang kebablasan mengunci keduanya akan
 * memaksa teknisi mengulang seluruh WO dari nol untuk satu nomor seri yang salah ketik.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkOrderMaterialAfterApprovalIT {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var onboarding: OnboardTenantUseCase
    @Autowired private lateinit var tenants: TenantApi
    @Autowired private lateinit var worker: FulfillmentOutboxWorker

    private val pass = "secret12345"
    private val custodian = UUID.randomUUID()

    private companion object {
        val PNG = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 1, 2, 3, 4, 5)
    }

    // ------------------------------------------------------------------ HTTP

    private fun uniq() = UUID.randomUUID().toString().replace("-", "").substring(0, 8)

    private fun post(url: String, token: String, body: String, expected: Int = 201): String =
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

    private fun getJson(url: String, token: String, expected: Int = 200): String =
        mockMvc.perform(get(url).header("Authorization", "Bearer $token")).andReturn().response.let {
            assertThat(it.status).describedAs("GET $url -> ${it.contentAsString}").isEqualTo(expected)
            it.contentAsString
        }

    private fun id(json: String): String = JsonPath.read(json, "$.id")

    /** Pesan RFC-7807 yang benar-benar dibaca teknisi di layar. */
    private fun detail(problem: String): String = JsonPath.read(problem, "$.detail")

    private fun tenantIdOf(token: String): UUID =
        tenants.findBySlug(JsonPath.read(getJson("/api/me", token), "$.tenantSlug"))!!.id

    /** Lihat [com.duluin.ftth.inventory.WorkOrderAssetRecoveryIT] untuk alasan pengulangannya. */
    private fun drainFulfillment(token: String, times: Int = 3) {
        val tenantId = tenantIdOf(token)
        repeat(times) {
            TenantContext.runAs(tenantId) {
                worker.processNext(tenantId, "test-${uniq()}", Instant.now().plusSeconds(120))
            }
        }
    }

    private fun login(slug: String, email: String): String = JsonPath.read(
        mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"tenantSlug":"$slug","email":"$email","password":"$pass"}"""),
        ).andExpect(status().isOk).andReturn().response.contentAsString,
        "$.accessToken",
    )

    private fun adminToken(prefix: String): String {
        val slug = "$prefix${uniq()}"
        val admin = "admin@$slug.test"
        onboarding.onboard(OnboardTenantCommand(slug, "Tenant $slug", admin, "Admin", pass))
        return login(slug, admin)
    }

    // -------------------------------------------------------------- Fixtures

    private fun location(token: String, code: String, kind: String): String =
        id(post("/api/inventory/locations", token, """{"code":"$code","kind":"$kind"}"""))

    private fun item(token: String, code: String, category: String, unit: String, serialized: Boolean): String = id(
        post(
            "/api/inventory/item-master", token,
            """{"code":"$code","name":"Barang $code","category":"$category","unit":"$unit","serialized":$serialized}""",
        ),
    )

    private fun balance(token: String, itemId: String, locationId: String, status: String): Int {
        val rows: List<Map<String, Any>> = JsonPath.read(getJson("/api/inventory/balances?itemId=$itemId", token), "$")
        return rows.filter { it["locationId"] == locationId && it["status"] == status }
            .sumOf { (it["quantity"] as Number).toInt() }
    }

    private fun assetStatus(token: String, serialNumber: String): String {
        val rows: List<Map<String, Any>> = JsonPath.read(getJson("/api/inventory/items", token), "$")
        return rows.first { it["serialNumber"] == serialNumber }["status"] as String
    }

    private fun newTechnician(token: String, name: String): String {
        val roles = getJson("/api/roles", token)
        val names = JsonPath.read<List<String>>(roles, "$[*].name")
        val ids = JsonPath.read<List<String>>(roles, "$[*].id")
        val roleId = ids[names.indexOf("Teknisi")]
        return id(
            post(
                "/api/users", token,
                """{"email":"tech-${uniq()}@x.test","name":"$name","password":"$pass","roleIds":["$roleId"]}""",
            ),
        )
    }

    /** Penyelia terpisah: pembuat submission tidak boleh menyetujui hasilnya sendiri. */
    private fun newApprover(token: String): String {
        val roles = getJson("/api/roles", token)
        val names = JsonPath.read<List<String>>(roles, "$[*].name")
        val ids = JsonPath.read<List<String>>(roles, "$[*].id")
        val roleIndex = names.indexOfFirst { it.contains("Admin", ignoreCase = true) }.takeIf { it >= 0 } ?: 0
        val email = "approver-${uniq()}@x.test"
        post("/api/users", token, """{"email":"$email","name":"Approver","password":"$pass","roleIds":["${ids[roleIndex]}"]}""")
        val slug = JsonPath.read<String>(getJson("/api/me", token), "$.tenantSlug")
        return login(slug, email)
    }

    private fun customerWithSubscription(token: String): Pair<String, String> {
        val s = uniq().uppercase()
        val planId = id(
            post(
                "/api/catalog/plans", token,
                """{"name":"Paket ${uniq()}","description":null,"price":150000,"downMbps":20,"upMbps":10,"serviceTypes":["PPPOE"]}""",
            ),
        )
        val customer = post(
            "/api/customers", token,
            """{"code":"C-$s","name":"Pelanggan $s","address":"Jl. Uji",
                "location":{"longitude":106.99,"latitude":-6.24},"planId":"$planId"}""",
        )
        val customerId = id(customer)
        val subscriptionId = JsonPath.read<String>(customer, "$.subscription.id")
        post("/api/bng/access", token, """{"subscriptionId":"$subscriptionId","planId":"$planId","nasId":null}""")
        return customerId to subscriptionId
    }

    private fun completionBody(workOrderId: String, token: String, note: String? = null): String {
        val evidenceRevisionIds = listOf(
            "FAT", "ODP", "DROPCORE", "ONT", "ONU", "OPTICAL_BEFORE", "OPTICAL_AFTER", "TECHNICIAN_SIGNATURE", "LOCATION",
        ).associateWith { kind ->
            val evidence = mockMvc.perform(
                multipart("/api/work-orders/$workOrderId/evidence")
                    .file(MockMultipartFile("file", "$kind.png", MediaType.IMAGE_PNG_VALUE, PNG))
                    .param("kind", kind)
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isCreated).andReturn().response.contentAsString
            JsonPath.read<String>(evidence, "$.revisionId")
        }
        val acknowledgement = mockMvc.perform(
            multipart(org.springframework.http.HttpMethod.PUT, "/api/work-orders/$workOrderId/signature")
                .file(MockMultipartFile("file", "acknowledgement.png", MediaType.IMAGE_PNG_VALUE, PNG))
                .param("signerName", "Pelanggan")
                .param("correctionReason", "Persetujuan pelanggan diperbarui")
                .header("Authorization", "Bearer $token"),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val acknowledgementRevisionId = JsonPath.read<String>(acknowledgement, "$.revisionId")
        val revision = JsonPath.read<String>(getJson("/api/work-orders/$workOrderId/proof-of-work", token), "$.revision")
        val artifacts = listOf(
            "FAT", "ODP", "DROPCORE", "ONT", "ONU", "OPTICAL_BEFORE", "OPTICAL_AFTER",
            "TECHNICIAN_SIGNATURE", "CUSTOMER_ACKNOWLEDGEMENT", "LOCATION",
        ).joinToString(",") { kind ->
            val revisionId =
                if (kind == "CUSTOMER_ACKNOWLEDGEMENT") acknowledgementRevisionId else evidenceRevisionIds.getValue(kind)
            """{"kind":"$kind","revisionId":"$revisionId"}"""
        }
        val resolution = note?.let { ""","resolutionNote":"$it"""" }.orEmpty()
        return """{"proofRevision":"$revision","artifacts":[$artifacts]$resolution}"""
    }

    private data class Installed(
        val customerId: String,
        val subscriptionId: String,
        val technicianId: String,
        val serialNumber: String,
    )

    /** ONT terpasang lewat jalur PSB sungguhan sampai CONSUMED — titik berangkat penarikan. */
    private fun installOnt(token: String, warehouse: String, van: String, ontItem: String): Installed {
        val sn = "SN-${uniq().uppercase()}"
        post(
            "/api/inventory/serialized/bulk", token,
            """{"itemId":"$ontItem","locationId":"$warehouse","custodianId":"$custodian","reason":"kiriman ONT",
                "operationKey":"sn-${uniq()}","payloadHash":"sn-hash",
                "serials":[{"serialNumber":"$sn"}]}""",
        )
        val (customerId, subscriptionId) = customerWithSubscription(token)
        val woId = id(
            post(
                "/api/work-orders", token,
                """{"type":"PSB","title":"Pasang baru","customerId":"$customerId","subscriptionId":"$subscriptionId"}""",
            ),
        )
        val techId = newTechnician(token, "Teknisi PSB")
        post("/api/work-orders/$woId/assign", token, """{"technicianIds":["$techId"]}""", 200)
        post("/api/work-orders/$woId/start", token, "", 200)
        put("/api/work-orders/$woId/materials", token, """{"lines":[{"itemId":"$ontItem","quantity":1}]}""")
        post(
            "/api/work-orders/$woId/materials/issues", token,
            """{"fromLocationId":"$warehouse","custodianId":"$custodian","technicianId":"$techId",
                "technicianLocationId":"$van","reason":"bekal PSB",
                "operationKey":"iss-${uniq()}","payloadHash":"iss-hash",
                "lines":[{"itemId":"$ontItem","quantity":1,"serialNumbers":["$sn"]}]}""",
            expected = 200,
        )
        val completion = completionBody(woId, token, "Terpasang")
        post("/api/work-orders/$woId/materials/serials", token, """{"serialNumber":"$sn","outcome":"INSTALLED"}""", 200)
        post("/api/work-orders/$woId/complete", token, completion, 200)
        post("/api/work-orders/$woId/approve", newApprover(token), """{"note":"Disetujui"}""", 200)
        drainFulfillment(token)
        assertThat(assetStatus(token, sn)).isEqualTo("CONSUMED")
        return Installed(customerId, subscriptionId, techId, sn)
    }

    /** WO DISMANTLE yang sudah ditugaskan dan dimulai. */
    private fun dismantleWorkOrder(token: String, installed: Installed): String {
        val woId = id(
            post(
                "/api/work-orders", token,
                """{"type":"DISMANTLE","title":"Bongkar","customerId":"${installed.customerId}",
                    "subscriptionId":"${installed.subscriptionId}"}""",
            ),
        )
        post("/api/work-orders/$woId/assign", token, """{"technicianIds":["${installed.technicianId}"]}""", 200)
        post("/api/work-orders/$woId/start", token, "", 200)
        return woId
    }

    private fun recoverBody(sn: String, technicianId: String, van: String, condition: String = "GOOD") =
        """{"serialNumber":"$sn","technicianId":"$technicianId","technicianLocationId":"$van",
            "condition":"$condition","note":"ONT ditarik dari rumah pelanggan"}"""

    private fun approvalStatusOf(token: String, woId: String): String? =
        JsonPath.read(getJson("/api/work-orders/$woId", token), "$.workOrder.approvalStatus")

    // ------------------------------------------------------------------ Tes

    /**
     * WO yang sudah DISETUJUI: semua tulisan material & penarikan aset ditolak 409.
     *
     * Pembatalan dipakai sebagai contoh utamanya karena di sanalah kerusakannya paling tidak
     * kasat mata — 200 di sini menghasilkan baris "dibatalkan" untuk unit yang saldonya SUDAH
     * naik, dan tak satu pun laporan setelahnya bisa menjelaskan selisih itu.
     */
    @Test
    fun `tulisan material dan pembatalan penarikan ditolak setelah hasil kerja disetujui`() {
        val token = adminToken("wo-appr")
        val warehouse = location(token, "WH-${uniq()}", "WAREHOUSE")
        val van = location(token, "VAN-${uniq()}", "VEHICLE")
        val ontItem = item(token, "ONT-${uniq()}", "ONT", "PCS", serialized = true)

        val installed = installOnt(token, warehouse, van, ontItem)
        val woId = dismantleWorkOrder(token, installed)
        val recoveredId = id(
            post(
                "/api/work-orders/$woId/materials/recovered-assets", token,
                recoverBody(installed.serialNumber, installed.technicianId, van), expected = 200,
            ),
        )

        post("/api/work-orders/$woId/complete", token, completionBody(woId, token, "Dibongkar"), 200)
        post("/api/work-orders/$woId/approve", newApprover(token), """{"note":"Disetujui"}""", 200)
        drainFulfillment(token)

        // Prasyarat yang bikin penjaga ini ada: saldonya BENAR-BENAR sudah bergerak.
        assertThat(approvalStatusOf(token, woId)).isEqualTo("APPROVED")
        assertThat(balance(token, ontItem, van, "RETURNED")).isEqualTo(1)
        assertThat(assetStatus(token, installed.serialNumber)).isEqualTo("RETURNED")

        val cancel = post(
            "/api/work-orders/$woId/materials/recovered-assets/$recoveredId/cancel", token,
            """{"reason":"katanya salah scan"}""", expected = 409,
        )
        // Pesannya harus memberi tahu LANGKAH PENGGANTINYA, bukan sekadar "tidak boleh".
        assertThat(detail(cancel)).contains("penyesuaian stok")
        assertThat(detail(cancel)).contains("disetujui")

        val serial = post(
            "/api/work-orders/$woId/materials/serials", token,
            """{"serialNumber":"${installed.serialNumber}","outcome":"LOST"}""", expected = 409,
        )
        assertThat(detail(serial)).contains("penyesuaian stok")

        val usage = post(
            "/api/work-orders/$woId/materials/usage", token,
            """{"itemId":"$ontItem","usedQuantity":1}""", expected = 409,
        )
        assertThat(detail(usage)).contains("penyesuaian stok")

        // Rencana & pengeluaran ikut terkunci: keduanya sama-sama mengubah angka yang sudah
        // dipakai memotong saldo.
        put("/api/work-orders/$woId/materials", token, """{"lines":[{"itemId":"$ontItem","quantity":2}]}""", 409)
        post(
            "/api/work-orders/$woId/materials/recovered-assets", token,
            recoverBody(installed.serialNumber, installed.technicianId, van), expected = 409,
        )

        // Dan tidak ada satu pun yang berubah gara-gara percobaan di atas.
        assertThat(balance(token, ontItem, van, "RETURNED")).isEqualTo(1)
        val rows = JsonPath.read<List<Map<String, Any>>>(
            getJson("/api/work-orders/$woId/materials/recovered-assets", token), "$[*]",
        )
        assertThat(rows).hasSize(1)
        assertThat(rows.first()["cancelledAt"]).isNull()
    }

    /**
     * Jalur BACA tidak boleh ikut terkunci.
     *
     * Justru setelah disetujui-lah catatan itu paling sering dibuka: saat orang gudang mencari
     * asal-usul selisih opname. Penjaga yang ikut menutup layarnya menghapus satu-satunya
     * penjelasan yang tersisa.
     */
    @Test
    fun `layar material dan penarikan aset tetap bisa dibaca setelah WO disetujui`() {
        val token = adminToken("wo-appr")
        val warehouse = location(token, "WH-${uniq()}", "WAREHOUSE")
        val van = location(token, "VAN-${uniq()}", "VEHICLE")
        val ontItem = item(token, "ONT-${uniq()}", "ONT", "PCS", serialized = true)

        val installed = installOnt(token, warehouse, van, ontItem)
        val woId = dismantleWorkOrder(token, installed)
        post(
            "/api/work-orders/$woId/materials/recovered-assets", token,
            recoverBody(installed.serialNumber, installed.technicianId, van), expected = 200,
        )
        post("/api/work-orders/$woId/complete", token, completionBody(woId, token, "Dibongkar"), 200)
        post("/api/work-orders/$woId/approve", newApprover(token), """{"note":"Disetujui"}""", 200)
        drainFulfillment(token)
        assertThat(approvalStatusOf(token, woId)).isEqualTo("APPROVED")

        getJson("/api/work-orders/$woId/materials", token, expected = 200)
        getJson("/api/work-orders/$woId/materials/template", token, expected = 200)
        val recovered = JsonPath.read<List<Map<String, Any>>>(
            getJson("/api/work-orders/$woId/materials/recovered-assets", token, expected = 200), "$[*]",
        )
        assertThat(recovered).hasSize(1)
        assertThat(recovered.first()["serialNumber"]).isEqualTo(installed.serialNumber)
    }

    /**
     * Menunggu keputusan penyelia BUKAN keputusan.
     *
     * Antara "selesai" dan "disetujui" bisa lewat berjam-jam, dan selama itu saldonya belum
     * bergerak sama sekali. Mengunci di sini berarti salah-scan yang ketahuan sore hari tidak
     * bisa diperbaiki sampai penyelia memutuskan — lalu setelah diputuskan, sudah terlambat.
     */
    @Test
    fun `pembatalan penarikan masih diterima selagi WO menunggu persetujuan`() {
        val token = adminToken("wo-appr")
        val warehouse = location(token, "WH-${uniq()}", "WAREHOUSE")
        val van = location(token, "VAN-${uniq()}", "VEHICLE")
        val ontItem = item(token, "ONT-${uniq()}", "ONT", "PCS", serialized = true)

        val installed = installOnt(token, warehouse, van, ontItem)
        val woId = dismantleWorkOrder(token, installed)
        val recoveredId = id(
            post(
                "/api/work-orders/$woId/materials/recovered-assets", token,
                recoverBody(installed.serialNumber, installed.technicianId, van), expected = 200,
            ),
        )
        post("/api/work-orders/$woId/complete", token, completionBody(woId, token, "Dibongkar"), 200)
        assertThat(approvalStatusOf(token, woId)).isEqualTo("PENDING")

        val cancelled = post(
            "/api/work-orders/$woId/materials/recovered-assets/$recoveredId/cancel", token,
            """{"reason":"salah scan, ONT-nya milik tetangga"}""", expected = 200,
        )
        assertThat(JsonPath.read<String?>(cancelled, "$.cancelledAt")).isNotNull()

        // Dan pembatalan itu benar-benar mencegah saldo bergerak saat persetujuan akhirnya turun.
        post("/api/work-orders/$woId/approve", newApprover(token), """{"note":"Disetujui"}""", 200)
        drainFulfillment(token)
        assertThat(balance(token, ontItem, van, "RETURNED")).isZero()
        assertThat(assetStatus(token, installed.serialNumber)).isEqualTo("CONSUMED")
    }

    /**
     * WO yang DITOLAK harus tetap bisa diperbaiki — itu seluruh gunanya penolakan.
     *
     * Penolakan mengembalikan WO ke IN_PROGRESS justru supaya catatannya dibetulkan sebelum
     * diajukan lagi. Mengunci material di sini membuat penyelia hanya punya dua pilihan yang
     * sama-sama buruk: menyetujui hasil yang dia tahu salah, atau menyuruh teknisi membuat WO
     * baru dari nol untuk satu baris yang keliru.
     */
    @Test
    fun `WO yang ditolak penyelia masih menerima tulisan material dan penarikan aset`() {
        val token = adminToken("wo-appr")
        val warehouse = location(token, "WH-${uniq()}", "WAREHOUSE")
        val van = location(token, "VAN-${uniq()}", "VEHICLE")
        val ontItem = item(token, "ONT-${uniq()}", "ONT", "PCS", serialized = true)

        val installed = installOnt(token, warehouse, van, ontItem)
        val woId = dismantleWorkOrder(token, installed)
        val recoveredId = id(
            post(
                "/api/work-orders/$woId/materials/recovered-assets", token,
                recoverBody(installed.serialNumber, installed.technicianId, van), expected = 200,
            ),
        )
        post("/api/work-orders/$woId/complete", token, completionBody(woId, token, "Dibongkar"), 200)
        post(
            "/api/work-orders/$woId/reject", newApprover(token),
            """{"reason":"nomor seri di berita acara tidak cocok"}""", expected = 200,
        )
        assertThat(approvalStatusOf(token, woId)).isEqualTo("REJECTED")

        // Baris yang keliru dibatalkan...
        post(
            "/api/work-orders/$woId/materials/recovered-assets/$recoveredId/cancel", token,
            """{"reason":"scan ulang sesuai berita acara"}""", expected = 200,
        )
        // ...lalu unitnya di-scan ulang. Keduanya wajib lolos, kalau tidak WO ini buntu.
        post(
            "/api/work-orders/$woId/materials/recovered-assets", token,
            recoverBody(installed.serialNumber, installed.technicianId, van, condition = "DAMAGED"), expected = 200,
        )
        // Rencana material pun masih boleh disimpan ulang (kosong = pakai BOM apa adanya).
        put("/api/work-orders/$woId/materials", token, """{"lines":[]}""")

        // Perbaikannya sampai ke pembukuan begitu pengajuan ulang disetujui.
        post("/api/work-orders/$woId/complete", token, completionBody(woId, token, "Dibongkar ulang"), 200)
        post("/api/work-orders/$woId/approve", newApprover(token), """{"note":"Sudah cocok"}""", 200)
        drainFulfillment(token)
        assertThat(balance(token, ontItem, van, "RETURNED")).isEqualTo(1)
        assertThat(assetStatus(token, installed.serialNumber)).isEqualTo("RETURNED")
    }
}
