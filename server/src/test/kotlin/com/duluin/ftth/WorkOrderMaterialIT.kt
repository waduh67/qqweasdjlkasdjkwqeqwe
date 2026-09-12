package com.duluin.ftth

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
 * Jembatan work order <-> gudang diuji ujung ke ujung lewat HTTP.
 *
 * Yang dijaga di sini bukan bentuk JSON-nya, melainkan satu janji tunggal: material yang
 * dipakai sebuah work order benar-benar KELUAR dari saldo gudang, tercatat per unit, dan
 * bisa ditelusuri sampai nomor serinya. Sebelum P2 rantai itu putus di dua tempat —
 * alokasi fulfillment selalu kosong, dan efek persetujuan tidak pernah menggerakkan
 * proyeksi saldo — sehingga laporan mutasi dan laporan saldo bisa saling bertentangan
 * tanpa satu pun error yang menunjukkannya.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkOrderMaterialIT {

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

    private fun tenantIdOf(token: String): UUID =
        tenants.findBySlug(JsonPath.read(getJson("/api/me", token), "$.tenantSlug"))!!.id

    /**
     * Jalankan ulang outbox fulfillment seperti scheduler produksi.
     *
     * Percobaan pertama saga berjalan di dalam listener AFTER_COMMIT approval, dan di
     * transaksi itu akun BNG BELUM aktif: pengaktifannya sendiri dipicu listener AFTER_COMMIT
     * lain yang baru jalan setelah transaksi saga selesai. Efek PROVISIONING karena itu
     * berhenti di `BNG_ACTIVATION_NOT_CONFIRMED` dan rantai efek tidak pernah sampai ke
     * INVENTORY. Di produksi `FulfillmentOutboxWorker.drain()` mengulangnya beberapa detik
     * kemudian; di sini pengulangan itu dipanggil eksplisit dengan `now` yang dimajukan agar
     * sewa (lease) 60 detik dari klaim pertama sudah lewat.
     *
     * [TenantContext.runAs] WAJIB membungkus pemanggilannya, persis seperti
     * `WorkOrderFulfillmentListener`. `processNext` itu `@Transactional`, jadi transaksinya
     * (dan koneksinya) dibuka SAAT MASUK proxy — sebelum `runAs` di dalam badan metode sempat
     * jalan. Tanpa pembungkus ini GUC `app.tenant_id` masih kosong saat koneksi diambil,
     * `claimPending` yang berupa native query mengembalikan NOL baris karena RLS, dan
     * semuanya terlihat seperti "tidak ada pekerjaan" tanpa satu pun error.
     */
    private fun drainFulfillment(token: String) {
        val tenantId = tenantIdOf(token)
        TenantContext.runAs(tenantId) {
            worker.processNext(tenantId, "test-${uniq()}", Instant.now().plusSeconds(120))
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

    /** Saldo satu dimensi (item + lokasi + status) — nol kalau barisnya memang belum ada. */
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

    /**
     * Pelanggan + langganan PENDING + akun BNG yang menunggu aktivasi.
     *
     * Ketiganya WAJIB ada supaya rantai efek saga sampai ke INVENTORY. Efek dijalankan
     * berurutan (SUBSCRIPTION -> PROVISIONING -> INVENTORY) dan berhenti di kegagalan
     * PERTAMA: tanpa tautan langganan, preflight menolak dengan `SUBSCRIPTION_LINK_NOT_FOUND`;
     * dengan langganan tapi tanpa akun BNG, efek PROVISIONING berhenti di
     * `BNG_ACTIVATION_NOT_CONFIRMED`. Keduanya membuat pemotongan saldo yang diuji di sini
     * TIDAK PERNAH dijalankan, dan tesnya gagal karena sebab yang sama sekali berbeda.
     */
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

    // ------------------------------------------------------------------ Tes

    @Test
    fun `material PSB - pra-isi BOM, keluar gudang, scan seri, lalu saldo benar-benar terpotong saat disetujui`() {
        val token = adminToken("wo-mat")
        val warehouse = location(token, "WH-${uniq()}", "WAREHOUSE")
        val van = location(token, "VAN-${uniq()}", "VEHICLE")
        val ont = item(token, "ONT-${uniq()}", "ONT", "PCS", serialized = true)
        val kabel = item(token, "DC-${uniq()}", "DROPCORE", "METER", serialized = false)
        val sn = "SN-${uniq().uppercase()}"

        post(
            "/api/inventory/receipts", token,
            """{"locationId":"$warehouse","custodianId":"$custodian","reason":"kiriman kabel",
                "operationKey":"grn-${uniq()}","payloadHash":"grn-hash",
                "lines":[{"itemId":"$kabel","quantity":100}]}""",
        )
        post(
            "/api/inventory/serialized/bulk", token,
            """{"itemId":"$ont","locationId":"$warehouse","custodianId":"$custodian","reason":"kiriman ONT",
                "operationKey":"sn-${uniq()}","payloadHash":"sn-hash",
                "serials":[{"serialNumber":"$sn"}]}""",
        )
        assertThat(balance(token, kabel, warehouse, "AVAILABLE")).isEqualTo(100)
        assertThat(balance(token, ont, warehouse, "AVAILABLE")).isEqualTo(1)

        // BOM jenis PSB: 1 ONT + 80 meter dropcore.
        put(
            "/api/inventory/material-templates/PSB", token,
            """{"lines":[{"itemId":"$ont","plannedQuantity":1,"note":"satu per rumah"},
                        {"itemId":"$kabel","plannedQuantity":80}]}""",
        )

        val (customerId, subscriptionId) = customerWithSubscription(token)
        val woId = id(
            post(
                "/api/work-orders", token,
                """{"type":"PSB","title":"Pasang baru","customerId":"$customerId","subscriptionId":"$subscriptionId"}""",
            ),
        )
        val techId = newTechnician(token, "Teknisi Material")
        post("/api/work-orders/$woId/assign", token, """{"technicianIds":["$techId"]}""", 200)
        post("/api/work-orders/$woId/start", token, "", 200)

        // Template terbaca dari sisi WO, tanpa klien perlu tahu jenis WO-nya.
        val template = getJson("/api/work-orders/$woId/materials/template", token)
        assertThat(JsonPath.read<List<Any>>(template, "$[*]")).hasSize(2)

        // Rencana kosong = pra-isi dari BOM. Inilah bentuk "template mempra-isi, bukan memagari".
        val planned = put("/api/work-orders/$woId/materials", token, """{"lines":[]}""")
        assertThat(JsonPath.read<List<Any>>(planned, "$[*]")).hasSize(2)
        val plannedByItem = JsonPath.read<List<Map<String, Any>>>(planned, "$[*]").associateBy { it["itemId"] }
        assertThat(plannedByItem.getValue(ont)["plannedQuantity"]).isEqualTo(1)
        assertThat(plannedByItem.getValue(kabel)["plannedQuantity"]).isEqualTo(80)
        assertThat(plannedByItem.getValue(kabel)["templateQuantity"]).isEqualTo(80)

        // Barang yang tidak direncanakan tidak bisa dikeluarkan begitu saja.
        post(
            "/api/work-orders/$woId/materials/issues", token,
            """{"fromLocationId":"$warehouse","custodianId":"$custodian","technicianId":"$techId",
                "technicianLocationId":"$van","reason":"bekal","operationKey":"x-${uniq()}","payloadHash":"h",
                "lines":[{"itemId":"${UUID.randomUUID()}","quantity":1}]}""",
            expected = 404,
        )
        // Melebihi rencana juga ditolak — rencana boleh menyimpang, tapi harus diubah dulu.
        post(
            "/api/work-orders/$woId/materials/issues", token,
            """{"fromLocationId":"$warehouse","custodianId":"$custodian","technicianId":"$techId",
                "technicianLocationId":"$van","reason":"bekal","operationKey":"y-${uniq()}","payloadHash":"h",
                "lines":[{"itemId":"$kabel","quantity":200}]}""",
            expected = 409,
        )

        post(
            "/api/work-orders/$woId/materials/issues", token,
            """{"fromLocationId":"$warehouse","custodianId":"$custodian","technicianId":"$techId",
                "technicianLocationId":"$van","reason":"bekal PSB",
                "operationKey":"iss-${uniq()}","payloadHash":"iss-hash",
                "lines":[{"itemId":"$ont","quantity":1,"serialNumbers":["$sn"]},
                         {"itemId":"$kabel","quantity":80}]}""",
            expected = 200,
        )
        // Barang benar-benar pindah rak -> van, bukan sekadar tercatat di WO.
        assertThat(balance(token, kabel, warehouse, "AVAILABLE")).isEqualTo(20)
        assertThat(balance(token, ont, warehouse, "AVAILABLE")).isZero()
        assertThat(balance(token, kabel, van, "ISSUED")).isEqualTo(80)
        assertThat(balance(token, ont, van, "ISSUED")).isEqualTo(1)
        assertThat(assetStatus(token, sn)).isEqualTo("ISSUED")

        // Bukti pengerjaan disusun SEKALI lalu dipakai ulang. Menyusunnya lagi tiap percobaan
        // berarti mengunggah bukti baru, dan revisi Proof of Work ikut berubah — percobaan
        // yang ditolak akan ditolak karena revisinya basi, bukan karena materialnya kurang,
        // dan tes ini diam-diam berhenti menguji apa pun yang dimaksud.
        val completion = completionBody(woId, token, "Terpasang")

        // Barang berserial TIDAK BOLEH dicatat dengan angka ketik — hanya lewat scan.
        post(
            "/api/work-orders/$woId/materials/usage", token,
            """{"itemId":"$ont","usedQuantity":1}""", expected = 400,
        )
        // Sisa kabel belum dilaporkan nasibnya -> WO belum boleh ditutup.
        post("/api/work-orders/$woId/materials/usage", token, """{"itemId":"$kabel","usedQuantity":75}""", 200)
        post("/api/work-orders/$woId/complete", token, completion, expected = 400)

        // Dilaporkan lengkap tapi menyimpang dari rencana tanpa alasan -> tetap ditolak.
        post(
            "/api/work-orders/$woId/materials/usage", token,
            """{"itemId":"$kabel","usedQuantity":75,"returnedQuantity":5}""", 200,
        )
        post("/api/work-orders/$woId/complete", token, completion, expected = 400)

        post(
            "/api/work-orders/$woId/materials/usage", token,
            """{"itemId":"$kabel","usedQuantity":75,"returnedQuantity":5,
                "varianceReason":"rumah pelanggan lebih dekat dari perkiraan"}""",
            200,
        )
        // ONT-nya masih belum di-scan: penjaga penyelesaian tetap menolak.
        post("/api/work-orders/$woId/complete", token, completion, expected = 400)

        val scanned = post(
            "/api/work-orders/$woId/materials/serials", token,
            """{"serialNumber":"$sn","outcome":"INSTALLED"}""", 200,
        )
        assertThat(JsonPath.read<Int>(scanned, "$.usedQuantity")).isEqualTo(1)
        assertThat(JsonPath.read<Int>(scanned, "$.unscannedQuantity")).isZero()
        assertThat(JsonPath.read<List<String>>(scanned, "$.serials[*].serialNumber")).containsExactly(sn)

        post("/api/work-orders/$woId/complete", token, completion, 200)

        // Sampai di sini saldo BELUM bergerak: pemakaian baru komitmen setelah disetujui.
        assertThat(balance(token, kabel, van, "ISSUED")).isEqualTo(80)
        assertThat(balance(token, ont, van, "ISSUED")).isEqualTo(1)

        post("/api/work-orders/$woId/approve", newApprover(token), """{"note":"Disetujui"}""", 200)
        drainFulfillment(token)

        // INILAH inti P2: material yang terpakai benar-benar keluar dari saldo teknisi.
        assertThat(balance(token, kabel, van, "ISSUED")).isEqualTo(5)
        assertThat(balance(token, ont, van, "ISSUED")).isZero()
        // Unit fisiknya ikut pindah status: kalau tertinggal ISSUED, ia akan terus terhitung
        // saat opname van stock dan bisa dipilih lagi sebagai barang yang mau diretur.
        assertThat(assetStatus(token, sn)).isEqualTo("CONSUMED")
        // Langganan ikut aktif -> seluruh rantai efek saga memang jalan, bukan cuma gudangnya.
        assertThat(JsonPath.read<String>(getJson("/api/customers/$customerId/subscription", token), "$.status"))
            .isEqualTo("ACTIVE")

        // Jejak per unit tetap bisa dibaca setelah WO ditutup.
        val after = getJson("/api/work-orders/$woId/materials", token)
        val afterByItem = JsonPath.read<List<Map<String, Any>>>(after, "$[*]").associateBy { it["itemId"] }
        assertThat(afterByItem.getValue(ont)["usedQuantity"]).isEqualTo(1)
        assertThat(afterByItem.getValue(kabel)["returnedQuantity"]).isEqualTo(5)
        assertThat(afterByItem.getValue(kabel)["varianceReason"]).isEqualTo("rumah pelanggan lebih dekat dari perkiraan")
    }

    @Test
    fun `nomor seri yang tidak dipegang teknisi WO ini ditolak`() {
        val token = adminToken("wo-mat")
        val warehouse = location(token, "WH-${uniq()}", "WAREHOUSE")
        val van = location(token, "VAN-${uniq()}", "VEHICLE")
        val ont = item(token, "ONT-${uniq()}", "ONT", "PCS", serialized = true)
        val dipakai = "SN-${uniq().uppercase()}"
        val diRak = "SN-${uniq().uppercase()}"

        post(
            "/api/inventory/serialized/bulk", token,
            """{"itemId":"$ont","locationId":"$warehouse","custodianId":"$custodian","reason":"kiriman ONT",
                "operationKey":"sn-${uniq()}","payloadHash":"sn-hash",
                "serials":[{"serialNumber":"$dipakai"},{"serialNumber":"$diRak"}]}""",
        )

        val (customerId, _) = customerWithSubscription(token)
        val woId = id(
            post("/api/work-orders", token, """{"type":"PSB","title":"Pasang baru","customerId":"$customerId"}"""),
        )
        val techId = newTechnician(token, "Teknisi Seri")
        post("/api/work-orders/$woId/assign", token, """{"technicianIds":["$techId"]}""", 200)
        post("/api/work-orders/$woId/start", token, "", 200)
        put("/api/work-orders/$woId/materials", token, """{"lines":[{"itemId":"$ont","quantity":1}]}""")
        post(
            "/api/work-orders/$woId/materials/issues", token,
            """{"fromLocationId":"$warehouse","custodianId":"$custodian","technicianId":"$techId",
                "technicianLocationId":"$van","reason":"bekal PSB",
                "operationKey":"iss-${uniq()}","payloadHash":"iss-hash",
                "lines":[{"itemId":"$ont","quantity":1,"serialNumbers":["$dipakai"]}]}""",
            expected = 200,
        )

        // Masih di rak gudang: statusnya AVAILABLE, bukan ISSUED ke teknisi ini.
        post(
            "/api/work-orders/$woId/materials/serials", token,
            """{"serialNumber":"$diRak","outcome":"INSTALLED"}""", expected = 409,
        )
        // Nomor seri karangan tidak pernah jadi "barang yang terpasang".
        post(
            "/api/work-orders/$woId/materials/serials", token,
            """{"serialNumber":"SN-TIDAK-ADA","outcome":"INSTALLED"}""", expected = 404,
        )
        // Nasib di luar INSTALLED/RETURNED/LOST ditolak, bukan diam-diam dianggap terpasang.
        post(
            "/api/work-orders/$woId/materials/serials", token,
            """{"serialNumber":"$dipakai","outcome":"MUNGKIN"}""", expected = 400,
        )

        post("/api/work-orders/$woId/materials/serials", token, """{"serialNumber":"$dipakai","outcome":"INSTALLED"}""", 200)
        // Baris yang barangnya sudah keluar gudang tidak bisa dicoret dari rencana.
        put("/api/work-orders/$woId/materials", token, """{"lines":[]}""", expected = 409)
    }

    @Test
    fun `work order tanpa pelanggan tidak bisa merencanakan material`() {
        val token = adminToken("wo-mat")
        val ont = item(token, "ONT-${uniq()}", "ONT", "PCS", serialized = true)
        val woId = id(post("/api/work-orders", token, """{"type":"REPAIR","title":"Perbaikan lepasan"}"""))

        // Efek fulfillment gudang menuntut `customer_id` NOT NULL; material tanpa pelanggan
        // hanya akan jadi efek yang selalu gagal dan menyangkutkan WO-nya di rekonsiliasi.
        put("/api/work-orders/$woId/materials", token, """{"lines":[{"itemId":"$ont","quantity":1}]}""", expected = 409)
    }
}
