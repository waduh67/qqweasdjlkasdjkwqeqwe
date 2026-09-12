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
 * Teknisi harus bisa MENEMUKAN vannya sendiri — tanpa dititipi kunci gudang.
 *
 * `POST .../materials/recovered-assets` MEWAJIBKAN `technicianLocationId`, dan sampai endpoint
 * ini ada satu-satunya cara mendapatkannya adalah `GET /api/inventory/locations` yang dijaga
 * `inventory.location.view` — izin yang katalognya sendiri menyebutnya "Lihat gudang dan bin".
 * Teknisi lapangan tidak memegangnya, jadi orang yang justru mencabut ONT dari rumah pelanggan
 * tidak punya satu pun jalan mengisi bidang yang wajib itu: fiturnya lengkap di server dan mati
 * di tangan pemakainya. Menaikkan izin teknisi ke `inventory.location.view` menukar satu bidang
 * isian dengan akses baca ke tata letak gudang perusahaan; `/van-locations` memulangkan PERSIS
 * dua bidang yang dibutuhkan layar penarikan dan tidak satu pun lebih.
 *
 * Yang dijaga tes ini ada dua sisi, dan keduanya sama pentingnya:
 *
 *  * pintunya BENAR-BENAR terbuka bagi pemegang izin material (kalau tidak, tidak ada yang
 *    berubah dari keadaan buntu sebelumnya); dan
 *  * pintunya tetap SEMPIT — bukan gudang, bukan bin, bukan van tenant sebelah, dan bukan untuk
 *    siapa pun yang tidak memegang salah satu dari dua izin material itu.
 *
 * Ikut dijaga di sini: `technicianLocationCode` pada baris penarikan. Tanpa kolom itu layar
 * penyelisik stok hanya punya UUID untuk menjawab "unit ini mendarat di van mana", dan aturan
 * repo melarang klien menggabungkan id->nama sendiri (izinnya memang tidak ada).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkOrderVanLocationIT {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var onboarding: OnboardTenantUseCase
    @Autowired private lateinit var tenants: TenantApi
    @Autowired private lateinit var worker: FulfillmentOutboxWorker

    private val pass = "secret12345"
    private val custodian = UUID.randomUUID()

    private companion object {
        val PNG = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 1, 2, 3, 4, 5)
        const val VAN_LIST = "van-locations"
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

    private fun getJson(url: String, token: String): String =
        mockMvc.perform(get(url).header("Authorization", "Bearer $token")).andReturn().response.let {
            assertThat(it.status).describedAs("GET $url -> ${it.contentAsString}").isEqualTo(200)
            it.contentAsString
        }

    private fun getStatus(url: String, token: String): Int =
        mockMvc.perform(get(url).header("Authorization", "Bearer $token")).andReturn().response.status

    private fun id(json: String): String = JsonPath.read(json, "$.id")

    private fun rows(json: String): List<Map<String, Any?>> = JsonPath.read(json, "$")

    private fun login(slug: String, email: String): String = JsonPath.read(
        mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"tenantSlug":"$slug","email":"$email","password":"$pass"}"""),
        ).andExpect(status().isOk).andReturn().response.contentAsString,
        "$.accessToken",
    )

    // -------------------------------------------------------------- Fixtures

    private class Tenant(val slug: String, val adminToken: String)

    private fun newTenant(prefix: String): Tenant {
        val slug = "$prefix${uniq()}"
        val admin = "admin@$slug.test"
        onboarding.onboard(OnboardTenantCommand(slug, "Tenant $slug", admin, "Admin", pass))
        return Tenant(slug, login(slug, admin))
    }

    /**
     * Pengguna dengan PERSIS izin yang diminta, di tenant yang sama dengan [tenant].
     *
     * Peran rakitan tangan, BUKAN "Tenant Admin" yang dipangkas: peran sistem itu di-resync ke
     * seluruh katalog izin setiap kali bootstrap berjalan, jadi pemangkasannya sembuh sendiri dan
     * tes yang bergantung padanya lulus karena alasan yang salah. Resepnya sama dengan
     * [InventoryCountAccessIT].
     */
    private fun tokenWith(tenant: Tenant, vararg codes: String): String {
        val permsJson = getJson("/api/permissions", tenant.adminToken)
        val permissionIds = codes.map { code ->
            JsonPath.read<List<String>>(permsJson, "$[?(@.code=='$code')].id").firstOrNull()
                ?: error("Izin $code tidak ada di katalog — seeder-nya belum jalan?")
        }
        val roleId = id(
            post(
                "/api/roles", tenant.adminToken,
                """{"name":"Peran ${uniq()}","permissionIds":${permissionIds.joinToString(",", "[", "]") { "\"$it\"" }}}""",
            ),
        )
        val email = "petugas-${uniq()}@${tenant.slug}.test"
        post(
            "/api/users", tenant.adminToken,
            """{"email":"$email","name":"Petugas Lapangan","password":"$pass","roleIds":["$roleId"]}""",
        )
        return login(tenant.slug, email)
    }

    private fun location(token: String, code: String, kind: String, parentId: String? = null): String {
        val parent = parentId?.let { ""","parentId":"$it"""" }.orEmpty()
        return id(post("/api/inventory/locations", token, """{"code":"$code","kind":"$kind"$parent}"""))
    }

    private fun item(token: String, code: String, category: String, serialized: Boolean): String = id(
        post(
            "/api/inventory/item-master", token,
            """{"code":"$code","name":"Barang $code","category":"$category","unit":"PCS","serialized":$serialized}""",
        ),
    )

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

    private fun newApprover(tenant: Tenant): String {
        val roles = getJson("/api/roles", tenant.adminToken)
        val names = JsonPath.read<List<String>>(roles, "$[*].name")
        val ids = JsonPath.read<List<String>>(roles, "$[*].id")
        val roleIndex = names.indexOfFirst { it.contains("Admin", ignoreCase = true) }.takeIf { it >= 0 } ?: 0
        val email = "approver-${uniq()}@x.test"
        post(
            "/api/users", tenant.adminToken,
            """{"email":"$email","name":"Approver","password":"$pass","roleIds":["${ids[roleIndex]}"]}""",
        )
        return login(tenant.slug, email)
    }

    /** WO paling murah yang bisa dipakai sebagai alamat endpoint: tanpa pelanggan, tanpa teknisi. */
    private fun bareWorkOrder(token: String): String =
        id(post("/api/work-orders", token, """{"type":"PSB","title":"Uji daftar van"}"""))

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
        val subscriptionId = JsonPath.read<String>(customer, "$.subscription.id")
        post("/api/bng/access", token, """{"subscriptionId":"$subscriptionId","planId":"$planId","nasId":null}""")
        return id(customer) to subscriptionId
    }

    /** Sama seperti [WorkOrderAssetRecoveryIT.drainFulfillment]: wajib dibungkus tenant + diulang. */
    private fun drainFulfillment(tenant: Tenant, times: Int = 3) {
        val tenantId = tenants.findBySlug(tenant.slug)!!.id
        repeat(times) {
            TenantContext.runAs(tenantId) {
                worker.processNext(tenantId, "test-${uniq()}", Instant.now().plusSeconds(120))
            }
        }
    }

    private fun completionBody(workOrderId: String, token: String, note: String): String {
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
        return """{"proofRevision":"$revision","artifacts":[$artifacts],"resolutionNote":"$note"}"""
    }

    // ------------------------------------------------------------------ Tes

    /**
     * INTI TUGASNYA. Teknisi yang cuma boleh MENCATAT material — tanpa `inventory.location.view`,
     * tanpa `workorder.material.view` — bisa membuka daftar vannya.
     *
     * Kalau tes ini gagal, `POST .../recovered-assets` kembali jadi bidang isian yang tak ada
     * sumbernya, dan ONT yang dicabut tetap dibawa pulang tanpa jejak di pembukuan.
     */
    @Test
    fun `pemegang izin catat material saja bisa membuka daftar van`() {
        val tenant = newTenant("van-rec")
        val vanCode = "VAN-${uniq()}"
        val vanId = location(tenant.adminToken, vanCode, "VEHICLE")
        val woId = bareWorkOrder(tenant.adminToken)

        val token = tokenWith(tenant, "workorder.material.record")
        val list = rows(getJson("/api/work-orders/$woId/materials/$VAN_LIST", token))

        assertThat(list.map { it["id"] }).containsExactly(vanId)
        assertThat(list.single()["code"]).isEqualTo(vanCode.uppercase())
        // Dua bidang, titik. Bidang ketiga apa pun berarti master data gudang mulai bocor lewat
        // izin material work order — persis yang dihindari endpoint terpisah ini.
        assertThat(list.single().keys).containsExactlyInAnyOrder("id", "code")
    }

    /** Pembaca material (dispatcher, penyelia) memakai daftar yang sama untuk membaca layarnya. */
    @Test
    fun `pemegang izin baca material saja juga bisa`() {
        val tenant = newTenant("van-view")
        val vanId = location(tenant.adminToken, "VAN-${uniq()}", "VEHICLE")
        val woId = bareWorkOrder(tenant.adminToken)

        val token = tokenWith(tenant, "workorder.material.view")

        assertThat(rows(getJson("/api/work-orders/$woId/materials/$VAN_LIST", token)).map { it["id"] })
            .containsExactly(vanId)
    }

    /**
     * `canAny` bukan stempel karet.
     *
     * Dua pemanggil yang punya urusan dengan gudang atau dengan WO tetap ditolak selama keduanya
     * bukan pemegang izin material: yang membuka daftar ini HARUS persis dua izin di penjaganya.
     * Tanpa tes negatif, penjaga yang kelak kebobolan satu izin terlalu longgar tidak akan
     * menggagalkan apa pun.
     */
    @Test
    fun `yang tidak memegang izin material sama sekali ditolak 403`() {
        val tenant = newTenant("van-no")
        location(tenant.adminToken, "VAN-${uniq()}", "VEHICLE")
        val woId = bareWorkOrder(tenant.adminToken)
        val url = "/api/work-orders/$woId/materials/$VAN_LIST"

        // Pemegang izin gudang yang LEBAR sekalipun tidak lewat dari sini — ia punya jalannya
        // sendiri di `/api/inventory/locations`.
        assertThat(getStatus(url, tokenWith(tenant, "inventory.location.view"))).isEqualTo(403)
        // Dan izin material yang lain (petugas gudang pengeluar barang) juga bukan kuncinya.
        assertThat(getStatus(url, tokenWith(tenant, "workorder.material.issue"))).isEqualTo(403)
    }

    /**
     * HANYA van yang keluar.
     *
     * Gudang dan bin bukan tujuan sah unit tarikan: menaruhnya di daftar pilihan membuat teknisi
     * bisa "menarik" ONT langsung ke rak tanpa pernah menyerahkannya, dan saldo van-nya tidak
     * pernah mencatat bahwa barang itu sempat ada di tangannya.
     *
     * TECHNICIAN ikut bersama VEHICLE: keduanya sama-sama berarti van stock seorang teknisi di
     * repo ini (lihat `IssueWorkOrderMaterialCommand.technicianLocationId`), dan tenant yang
     * memodelkan vannya begitu tidak boleh mendapat daftar kosong.
     */
    @Test
    fun `gudang dan bin tidak ikut, van dan lokasi teknisi ikut`() {
        val tenant = newTenant("van-kind")
        val warehouse = location(tenant.adminToken, "WH-${uniq()}", "WAREHOUSE")
        location(tenant.adminToken, "BIN-${uniq()}", "BIN", parentId = warehouse)
        location(tenant.adminToken, "QRT-${uniq()}", "QUARANTINE")
        val van = location(tenant.adminToken, "VAN-${uniq()}", "VEHICLE")
        val tech = location(tenant.adminToken, "TEK-${uniq()}", "TECHNICIAN")
        val woId = bareWorkOrder(tenant.adminToken)

        val token = tokenWith(tenant, "workorder.material.record")

        assertThat(rows(getJson("/api/work-orders/$woId/materials/$VAN_LIST", token)).map { it["id"] })
            .containsExactlyInAnyOrder(van, tech)
    }

    /**
     * Van tenant sebelah TIDAK PERNAH ikut.
     *
     * Kode van menyebut wilayah dan nama orang di banyak operator; daftar yang bocor lintas
     * tenant adalah kebocoran data pelanggan, bukan sekadar salah tampil. Tenant-nya diambil dari
     * baris WO-nya (yang sudah lewat RLS), bukan dari apa pun yang dikirim pemanggil.
     */
    @Test
    fun `van tenant lain tidak ikut terbawa`() {
        val tetangga = newTenant("van-oth")
        val vanTetangga = location(tetangga.adminToken, "VAN-${uniq()}", "VEHICLE")

        val tenant = newTenant("van-own")
        val vanSendiri = location(tenant.adminToken, "VAN-${uniq()}", "VEHICLE")
        val woId = bareWorkOrder(tenant.adminToken)

        val token = tokenWith(tenant, "workorder.material.record")
        val list = rows(getJson("/api/work-orders/$woId/materials/$VAN_LIST", token))

        assertThat(list.map { it["id"] }).containsExactly(vanSendiri)
        assertThat(list.map { it["id"] }).doesNotContain(vanTetangga)

        // Dan WO tenant sebelah tidak bisa dipakai sebagai pintu belakang untuk mengintip:
        // barisnya tidak terlihat sama sekali (RLS), jadi jawabannya 404 — bukan daftar van.
        val woTetangga = bareWorkOrder(tetangga.adminToken)
        assertThat(getStatus("/api/work-orders/$woTetangga/materials/$VAN_LIST", token)).isEqualTo(404)
    }

    /**
     * Baris penarikan membawa KODE vannya, di jalur baca DAN kedua jalur tulis.
     *
     * "Unit ini mendarat di van mana" adalah pertanyaan wajib saat menyelisik selisih stok, dan
     * UUID tidak menjawabnya. Kalau salah satu jalur TULIS lupa mengisinya, kolomnya berkedip dari
     * kode ke UUID tepat setelah tombol ditekan lalu benar lagi saat layarnya di-refresh — bug
     * yang nyaris mustahil dilaporkan ulang.
     */
    @Test
    fun `penarikan aset membawa kode van, bukan UUID`() {
        val tenant = newTenant("van-code")
        val token = tenant.adminToken
        val warehouse = location(token, "WH-${uniq()}", "WAREHOUSE")
        val vanCode = "VAN-${uniq()}"
        val van = location(token, vanCode, "VEHICLE")
        val ontItem = item(token, "ONT-${uniq()}", "ONT", serialized = true)
        val technicianId = newTechnician(token, "Budi Teknisi")
        val sn = "SN-${uniq().uppercase()}"

        // Unit harus benar-benar CONSUMED sebelum bisa ditarik (D6a) — dipasang lewat jalur PSB
        // yang sebenarnya, resepnya sama dengan `WorkOrderAssetRecoveryIT.installOnt`.
        post(
            "/api/inventory/serialized/bulk", token,
            """{"itemId":"$ontItem","locationId":"$warehouse","custodianId":"$custodian","reason":"kiriman ONT",
                "operationKey":"sn-${uniq()}","payloadHash":"sn-hash",
                "serials":[{"serialNumber":"$sn"}]}""",
        )
        val (customerId, subscriptionId) = customerWithSubscription(token)
        val psbId = id(
            post(
                "/api/work-orders", token,
                """{"type":"PSB","title":"Pasang baru","customerId":"$customerId","subscriptionId":"$subscriptionId"}""",
            ),
        )
        post("/api/work-orders/$psbId/assign", token, """{"technicianIds":["$technicianId"]}""", 200)
        post("/api/work-orders/$psbId/start", token, "", 200)
        put("/api/work-orders/$psbId/materials", token, """{"lines":[{"itemId":"$ontItem","quantity":1}]}""")
        post(
            "/api/work-orders/$psbId/materials/issues", token,
            """{"fromLocationId":"$warehouse","custodianId":"$custodian","technicianId":"$technicianId",
                "technicianLocationId":"$van","reason":"bekal PSB",
                "operationKey":"iss-${uniq()}","payloadHash":"iss-hash",
                "lines":[{"itemId":"$ontItem","quantity":1,"serialNumbers":["$sn"]}]}""",
            expected = 200,
        )
        val psbCompletion = completionBody(psbId, token, "Terpasang")
        post("/api/work-orders/$psbId/materials/serials", token, """{"serialNumber":"$sn","outcome":"INSTALLED"}""", 200)
        post("/api/work-orders/$psbId/complete", token, psbCompletion, 200)
        post("/api/work-orders/$psbId/approve", newApprover(tenant), """{"note":"Disetujui"}""", 200)
        drainFulfillment(tenant)

        val dismantleId = id(
            post(
                "/api/work-orders", token,
                """{"type":"DISMANTLE","title":"Bongkar","customerId":"$customerId","subscriptionId":"$subscriptionId"}""",
            ),
        )
        post("/api/work-orders/$dismantleId/assign", token, """{"technicianIds":["$technicianId"]}""", 200)
        post("/api/work-orders/$dismantleId/start", token, "", 200)

        // Van yang dikirim di body memang datang dari daftar yang baru saja dibuka teknisi —
        // itulah seluruh alasan `/van-locations` ada.
        val pilihan = rows(getJson("/api/work-orders/$dismantleId/materials/$VAN_LIST", token)).single()
        assertThat(pilihan["id"]).isEqualTo(van)

        // Jalur TULIS pertama: respons scan penarikan LANGSUNG memuat kode vannya.
        val recovered = post(
            "/api/work-orders/$dismantleId/materials/recovered-assets", token,
            """{"serialNumber":"$sn","technicianId":"$technicianId","technicianLocationId":"$van",
                "condition":"GOOD","note":"ONT ditarik dari rumah pelanggan"}""",
            expected = 200,
        )
        assertThat(JsonPath.read<String>(recovered, "$.technicianLocationId")).isEqualTo(van)
        assertThat(JsonPath.read<String>(recovered, "$.technicianLocationCode")).isEqualTo(vanCode.uppercase())
        val recoveredId = id(recovered)

        // Jalur BACA: kode yang sama, dan BUKAN UUID-nya.
        val row = rows(getJson("/api/work-orders/$dismantleId/materials/recovered-assets", token)).single()
        assertThat(row["technicianLocationCode"]).isEqualTo(vanCode.uppercase())
        assertThat(row["technicianLocationCode"]).isNotEqualTo(row["technicianLocationId"])

        // Jalur TULIS kedua: pembatalan memulangkan view yang sama dan harus ikut bernama.
        val cancelled = post(
            "/api/work-orders/$dismantleId/materials/recovered-assets/$recoveredId/cancel", token,
            """{"reason":"salah scan"}""", expected = 200,
        )
        assertThat(JsonPath.read<String>(cancelled, "$.technicianLocationCode")).isEqualTo(vanCode.uppercase())
    }
}
