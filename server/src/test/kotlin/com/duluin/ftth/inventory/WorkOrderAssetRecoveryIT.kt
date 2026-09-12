package com.duluin.ftth.inventory

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
 * Jalur PULANG aset pelanggan: WO DISMANTLE yang benar-benar mengembalikan ONT ke pembukuan.
 *
 * Sebelum ini jalurnya TIDAK ADA. `InventoryApi.returnFulfillment` sudah lengkap sejak lama —
 * idempotensi, ledger, proyeksi saldo — tapi nol pemanggil produksi, dan `CONSUMED` adalah
 * status terminal. Akibatnya setiap pembongkaran berakhir sama: ONT-nya nyata ada di tangan
 * teknisi, tapi di pembukuan ia tetap "terpasang di rumah pelanggan yang sudah berhenti
 * berlangganan" selamanya — tidak terhitung di saldo mana pun, tidak bisa dikeluarkan lagi,
 * tidak pernah muncul saat opname.
 *
 * Yang dijaga tes ini satu kalimat: unit yang ditarik BENAR-BENAR bertambah di saldo van
 * teknisi DAN baris asetnya ikut pindah status, atau tidak terjadi apa-apa sama sekali.
 * Ledger yang rapi dengan saldo yang bohong adalah kerusakan yang paling mahal di modul ini,
 * karena tidak ada satu error pun yang menunjukkan keduanya bertentangan.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkOrderAssetRecoveryIT {

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
     * [TenantContext.runAs] WAJIB membungkusnya: `processNext` itu `@Transactional`, jadi
     * koneksinya diambil SAAT MASUK proxy. Tanpa pembungkus ini GUC `app.tenant_id` masih kosong,
     * `claimPending` yang berupa native query memulangkan NOL baris karena RLS, dan semuanya
     * terlihat seperti "tidak ada pekerjaan" TANPA satu pun error.
     *
     * Dipanggil BERULANG dengan sengaja. Rantai efek WO DISMANTLE berhenti di percobaan pertama
     * dengan `BNG_TERMINATION_NOT_CONFIRMED`: pemutusan akun BNG dipicu listener AFTER_COMMIT
     * dari transaksi saga itu sendiri, jadi saat efek PROVISIONING memeriksanya akun masih ACTIVE.
     * Di produksi `FulfillmentOutboxWorker.drain()` mengulangnya beberapa detik kemudian. Kalau
     * pengulangan itu tidak ditiru di sini, efek INVENTORY TIDAK PERNAH dijalankan dan tes ini
     * gagal karena sebab yang sama sekali berbeda dari yang mau diuji.
     */
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

    /** Pelanggan + langganan PENDING + akun BNG; ketiganya wajib supaya rantai efek sampai ke INVENTORY. */
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

    /**
     * Pasang satu ONT di rumah pelanggan lewat jalur PSB yang SEBENARNYA, sampai statusnya CONSUMED.
     *
     * Sengaja tidak memalsukan baris aset langsung ke basis data. Yang diuji tes ini adalah
     * penarikan unit yang BENAR-BENAR pernah dipasang lewat saga — termasuk bahwa transisi
     * CONSUMED -> RETURNED yang baru dibuka itu bekerja pada aset yang custody dan lokasinya
     * dibentuk jalur produksi, bukan yang disetel seenaknya oleh tes.
     */
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
        // Titik berangkat seluruh tes ini. Kalau di sini saja sudah bukan CONSUMED, apa pun yang
        // diuji setelahnya menguji hal lain.
        assertThat(assetStatus(token, sn)).isEqualTo("CONSUMED")
        return Installed(customerId, subscriptionId, techId, sn)
    }

    /** WO DISMANTLE yang siap di-scan: sudah ditugaskan ke teknisinya dan sudah dimulai. */
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

    // ------------------------------------------------------------------ Tes

    /**
     * Ujung jalurnya: unit tarikan HARUS bisa naik ke rak.
     *
     * Penarikan mendaratkan ONT di status RETURNED di van teknisi. Selama retur ke gudang hanya
     * menerima asal ISSUED, unit itu terjebak di van SELAMANYA — jalur penarikannya lengkap dan
     * berhasil, tapi ujungnya buntu, dan gejalanya justru bukan error melainkan van stock yang
     * terus menggelembung tanpa ada yang bisa menurunkannya.
     *
     * Yang diuji sampai ke ember saldonya, bukan cuma status asetnya: leg OUT-nya wajib keluar
     * dari ember RETURNED. Kalau ia disamaratakan sebagai ISSUED, ember ISSUED jadi minus
     * sementara ember RETURNED di van tidak pernah berkurang — barangnya sudah nyata di rak, tapi
     * laporan van stock tetap menghitungnya.
     */
    @Test
    fun `unit hasil penarikan bisa diretur ke gudang dan naik ke rak, bukan mandek di van`() {
        val token = adminToken("wo-rec")
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
        assertThat(balance(token, ontItem, van, "RETURNED")).isEqualTo(1)

        val custodian = newTechnician(token, "Penjaga Gudang")
        post(
            "/api/inventory/returns", token,
            """{"fromLocationId":"$van","technicianId":"${installed.technicianId}","toLocationId":"$warehouse",
                "custodianId":"$custodian","quarantine":false,
                "lines":[{"itemId":"$ontItem","quantity":1,"serialNumbers":["${installed.serialNumber}"]}],
                "reason":"ONT bekas dismantle masuk rak","operationKey":"ret-${uniq()}","payloadHash":"ret-hash"}""",
            expected = 201,
        )

        assertThat(assetStatus(token, installed.serialNumber)).isEqualTo("AVAILABLE")
        assertThat(balance(token, ontItem, warehouse, "AVAILABLE")).isEqualTo(1)
        // Embernya BENAR-BENAR kosong lagi, bukan sekadar asetnya yang pindah.
        assertThat(balance(token, ontItem, van, "RETURNED")).isZero()
        assertThat(balance(token, ontItem, van, "ISSUED")).isZero()
    }

    @Test
    fun `ONT terpasang ditarik lewat WO DISMANTLE - saldo RETURNED van teknisi bertambah dan aset ikut pindah status`() {
        val token = adminToken("wo-rec")
        val warehouse = location(token, "WH-${uniq()}", "WAREHOUSE")
        val van = location(token, "VAN-${uniq()}", "VEHICLE")
        val ontItem = item(token, "ONT-${uniq()}", "ONT", "PCS", serialized = true)

        val installed = installOnt(token, warehouse, van, ontItem)
        assertThat(balance(token, ontItem, van, "RETURNED")).isZero()

        val woId = dismantleWorkOrder(token, installed)
        val recovered = post(
            "/api/work-orders/$woId/materials/recovered-assets", token,
            recoverBody(installed.serialNumber, installed.technicianId, van), expected = 200,
        )
        assertThat(JsonPath.read<String>(recovered, "$.serialNumber")).isEqualTo(installed.serialNumber)
        assertThat(JsonPath.read<String>(recovered, "$.condition")).isEqualTo("GOOD")
        assertThat(JsonPath.read<String>(recovered, "$.technicianLocationId")).isEqualTo(van)

        /*
         * (D6b) Unit yang sama TIDAK BOLEH ditarik dua kali. Ini penjaga terpenting seluruh
         * fitur: satu barang nyata yang tercatat ditarik dua kali langsung jadi satu unit hantu
         * di saldo — lahir dari dua mutasi yang keduanya terlihat sah, jadi tak ada laporan yang
         * bisa menunjuk mana yang palsu.
         */
        post(
            "/api/work-orders/$woId/materials/recovered-assets", token,
            recoverBody(installed.serialNumber, installed.technicianId, van), expected = 409,
        )

        // Sampai di sini saldo BELUM bergerak: pencatatan bukan komitmen. Persetujuan WO-nya
        // yang jadi mata kedua (D7), dan sebelum itu tidak boleh ada satu unit pun bertambah.
        assertThat(balance(token, ontItem, van, "RETURNED")).isZero()
        assertThat(assetStatus(token, installed.serialNumber)).isEqualTo("CONSUMED")

        post("/api/work-orders/$woId/complete", token, completionBody(woId, token, "Dibongkar"), 200)
        post("/api/work-orders/$woId/approve", newApprover(token), """{"note":"Disetujui"}""", 200)
        drainFulfillment(token)

        // INTI P2.6: unitnya benar-benar kembali ke pembukuan, di van teknisi yang membawanya.
        assertThat(balance(token, ontItem, van, "RETURNED")).isEqualTo(1)
        /*
         * (D8) Dan baris asetnya IKUT pindah. Kalau hanya saldonya yang bergerak, hasilnya persis
         * kerusakan yang paling mahal: proyeksi bilang ada satu ONT RETURNED di van, baris aset
         * bilang unit itu masih terpasang di rumah pelanggan, dan retur ke gudang menolaknya
         * karena "statusnya CONSUMED".
         */
        assertThat(assetStatus(token, installed.serialNumber)).isEqualTo("RETURNED")

        val list = JsonPath.read<List<Map<String, Any>>>(
            getJson("/api/work-orders/$woId/materials/recovered-assets", token), "$[*]",
        )
        assertThat(list).hasSize(1)
        assertThat(list.first()["cancelledAt"]).isNull()
    }

    @Test
    fun `unit yang tidak berstatus CONSUMED dan nomor seri karangan tidak bisa ditarik`() {
        val token = adminToken("wo-rec")
        val warehouse = location(token, "WH-${uniq()}", "WAREHOUSE")
        val van = location(token, "VAN-${uniq()}", "VEHICLE")
        val ontItem = item(token, "ONT-${uniq()}", "ONT", "PCS", serialized = true)
        val diRak = "SN-${uniq().uppercase()}"

        post(
            "/api/inventory/serialized/bulk", token,
            """{"itemId":"$ontItem","locationId":"$warehouse","custodianId":"$custodian","reason":"kiriman ONT",
                "operationKey":"sn-${uniq()}","payloadHash":"sn-hash",
                "serials":[{"serialNumber":"$diRak"}]}""",
        )

        val (customerId, subscriptionId) = customerWithSubscription(token)
        val woId = id(
            post(
                "/api/work-orders", token,
                """{"type":"DISMANTLE","title":"Bongkar","customerId":"$customerId","subscriptionId":"$subscriptionId"}""",
            ),
        )
        val techId = newTechnician(token, "Teknisi Bongkar")
        post("/api/work-orders/$woId/assign", token, """{"technicianIds":["$techId"]}""", 200)
        post("/api/work-orders/$woId/start", token, "", 200)

        /*
         * (D6a) Unit yang masih AVAILABLE di rak TIDAK sedang terpasang di rumah siapa pun.
         * Menerimanya di sini berarti menambah satu unit ke saldo untuk barang yang saldonya
         * SUDAH dihitung di rak — cara termudah menyulap stok dari udara, dan mutasinya terlihat
         * sah seperti penarikan biasa.
         */
        post(
            "/api/work-orders/$woId/materials/recovered-assets", token,
            recoverBody(diRak, techId, van), expected = 409,
        )
        // Nomor seri karangan tidak pernah jadi barang.
        post(
            "/api/work-orders/$woId/materials/recovered-assets", token,
            recoverBody("SN-TIDAK-ADA", techId, van), expected = 404,
        )
        // Kondisi di luar GOOD/DAMAGED ditolak, bukan diam-diam dianggap layak jual.
        post(
            "/api/work-orders/$woId/materials/recovered-assets", token,
            recoverBody(diRak, techId, van, condition = "SEADANYA"), expected = 400,
        )
        // Teknisi yang tidak ditugaskan di WO ini tidak boleh jadi penerima unit tarikan:
        // kalau boleh, aset pelanggan bisa "ditarik" ke van siapa pun yang id-nya diketik.
        post(
            "/api/work-orders/$woId/materials/recovered-assets", token,
            recoverBody(diRak, UUID.randomUUID().toString(), van), expected = 409,
        )
    }

    @Test
    fun `satu WO DISMANTLE sekaligus memakai material baru dan menarik ONT lama dalam satu persetujuan`() {
        val token = adminToken("wo-rec")
        val warehouse = location(token, "WH-${uniq()}", "WAREHOUSE")
        val van = location(token, "VAN-${uniq()}", "VEHICLE")
        val ontItem = item(token, "ONT-${uniq()}", "ONT", "PCS", serialized = true)
        val patchcord = item(token, "PC-${uniq()}", "PATCHCORD", "PCS", serialized = false)

        val installed = installOnt(token, warehouse, van, ontItem)
        post(
            "/api/inventory/receipts", token,
            """{"locationId":"$warehouse","custodianId":"$custodian","reason":"kiriman patchcord",
                "operationKey":"grn-${uniq()}","payloadHash":"grn-hash",
                "lines":[{"itemId":"$patchcord","quantity":10}]}""",
        )

        val woId = dismantleWorkOrder(token, installed)
        put("/api/work-orders/$woId/materials", token, """{"lines":[{"itemId":"$patchcord","quantity":1}]}""")
        post(
            "/api/work-orders/$woId/materials/issues", token,
            """{"fromLocationId":"$warehouse","custodianId":"$custodian","technicianId":"${installed.technicianId}",
                "technicianLocationId":"$van","reason":"bekal bongkar",
                "operationKey":"iss-${uniq()}","payloadHash":"iss-hash",
                "lines":[{"itemId":"$patchcord","quantity":1}]}""",
            expected = 200,
        )
        post("/api/work-orders/$woId/materials/usage", token, """{"itemId":"$patchcord","usedQuantity":1}""", 200)
        post(
            "/api/work-orders/$woId/materials/recovered-assets", token,
            recoverBody(installed.serialNumber, installed.technicianId, van, condition = "DAMAGED"), expected = 200,
        )

        assertThat(balance(token, patchcord, van, "ISSUED")).isEqualTo(1)
        post("/api/work-orders/$woId/complete", token, completionBody(woId, token, "Dibongkar"), 200)
        post("/api/work-orders/$woId/approve", newApprover(token), """{"note":"Disetujui"}""", 200)
        drainFulfillment(token)

        /*
         * Dua arah, satu persetujuan, satu daftar alokasi (D5). Kalau penarikan punya jalur efek
         * sendiri, ada keadaan di mana ONT-nya sudah masuk saldo sementara patch cord-nya belum
         * keluar — separuh jadi, dan tidak terwakili di checkpoint mana pun.
         */
        assertThat(balance(token, patchcord, van, "ISSUED")).isZero()
        assertThat(balance(token, ontItem, van, "RETURNED")).isEqualTo(1)
        assertThat(assetStatus(token, installed.serialNumber)).isEqualTo("RETURNED")
    }

    @Test
    fun `baris penarikan yang dibatalkan tidak ikut menambah saldo saat WO disetujui`() {
        val token = adminToken("wo-rec")
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

        val cancelled = post(
            "/api/work-orders/$woId/materials/recovered-assets/$recoveredId/cancel", token,
            """{"reason":"salah scan, ONT-nya ternyata milik tetangga"}""", expected = 200,
        )
        assertThat(JsonPath.read<String?>(cancelled, "$.cancelledAt")).isNotNull()
        // Membatalkan dua kali adalah tanda layar teknisi kehilangan jejak; ditolak, bukan diam.
        post(
            "/api/work-orders/$woId/materials/recovered-assets/$recoveredId/cancel", token,
            """{"reason":"iseng"}""", expected = 409,
        )

        // Unit yang batal ditarik boleh ditarik ULANG — indeks unik parsial sengaja tidak
        // mengurung baris yang sudah dibatalkan. Kalau ia ikut mengurung, satu salah-scan akan
        // membakar unit itu selamanya: barangnya nyata dibawa pulang tapi tidak akan pernah bisa
        // dicatat lagi oleh siapa pun.
        val ulangId = id(
            post(
                "/api/work-orders/$woId/materials/recovered-assets", token,
                recoverBody(installed.serialNumber, installed.technicianId, van), expected = 200,
            ),
        )
        post(
            "/api/work-orders/$woId/materials/recovered-assets/$ulangId/cancel", token,
            """{"reason":"teknisi salah WO"}""", expected = 200,
        )

        post("/api/work-orders/$woId/complete", token, completionBody(woId, token, "Dibongkar"), 200)
        post("/api/work-orders/$woId/approve", newApprover(token), """{"note":"Disetujui"}""", 200)
        drainFulfillment(token)

        /*
         * Pembatalan hanya berarti sesuatu kalau ia BENAR-BENAR mencegah saldo bergerak. Kalau
         * baris batal tetap dipancarkan ke saga, "batal" cuma jadi hiasan di layar sementara
         * stoknya tetap bertambah — dan justru salah-scan yang sudah diakui itulah yang paling
         * sering jadi asal-usul unit hantu.
         */
        assertThat(balance(token, ontItem, van, "RETURNED")).isZero()
        assertThat(assetStatus(token, installed.serialNumber)).isEqualTo("CONSUMED")

        // Jejaknya TETAP terbaca: baris batal menjawab "kenapa saldo tidak bertambah padahal
        // teknisi bilang sudah men-scan". Baris yang dihapus tidak bisa menjawab apa pun.
        val list = JsonPath.read<List<Map<String, Any>>>(
            getJson("/api/work-orders/$woId/materials/recovered-assets", token), "$[*]",
        )
        assertThat(list).hasSize(2)
        assertThat(list.map { it["cancelReason"] })
            .containsExactly("salah scan, ONT-nya ternyata milik tetangga", "teknisi salah WO")
    }
}
