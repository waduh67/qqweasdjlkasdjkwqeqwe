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
 * Layar material work order WAJIB membawa nama orangnya sendiri.
 *
 * Sebelum ini ketiga view-nya hanya berisi UUID: `technicianId`, `scannedBy`, `recoveredBy`,
 * `cancelledBy`. Yang membaca layar itu teknisi lapangan dan petugas gudang, dan keduanya TIDAK
 * memegang `iam.user.view` — jadi penggabungan klien ke `/api/users` berakhir 403 dan barisnya
 * tampil sebagai UUID telanjang tepat pada layar yang jadi pekerjaan sehari-hari mereka. Ini
 * penyakit yang sama yang baru saja disembuhkan di read model gudang
 * ([InventoryReadModelNamesIT]).
 *
 * Yang dijaga di sini bukan cuma jalur BACA. Jalur TULIS juga memulangkan view yang sama, dan
 * kalau salah satunya lupa mengisi namanya, layar berkedip dari nama ke UUID tepat setelah tombol
 * ditekan — bug yang hanya terlihat sekejap dan nyaris mustahil dilaporkan ulang.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkOrderMaterialNamesIT {

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

    private fun getJson(url: String, token: String): String =
        mockMvc.perform(get(url).header("Authorization", "Bearer $token")).andReturn().response.let {
            assertThat(it.status).describedAs("GET $url -> ${it.contentAsString}").isEqualTo(200)
            it.contentAsString
        }

    private fun id(json: String): String = JsonPath.read(json, "$.id")

    private fun rows(json: String): List<Map<String, Any?>> = JsonPath.read(json, "$")

    @Suppress("UNCHECKED_CAST")
    private fun serialsOf(row: Map<String, Any?>) = row["serials"] as List<Map<String, Any?>>

    private fun login(slug: String, email: String): String = JsonPath.read(
        mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"tenantSlug":"$slug","email":"$email","password":"$pass"}"""),
        ).andExpect(status().isOk).andReturn().response.contentAsString,
        "$.accessToken",
    )

    /** Nama admin SENGAJA khas: dialah `scannedBy`/`recoveredBy`/`cancelledBy` di seluruh tes ini. */
    private fun adminToken(prefix: String, adminName: String): String {
        val slug = "$prefix${uniq()}"
        val admin = "admin@$slug.test"
        onboarding.onboard(OnboardTenantCommand(slug, "Tenant $slug", admin, adminName, pass))
        return login(slug, admin)
    }

    private fun tenantIdOf(token: String): UUID =
        tenants.findBySlug(JsonPath.read(getJson("/api/me", token), "$.tenantSlug"))!!.id

    /** Sama seperti [WorkOrderAssetRecoveryIT.drainFulfillment]: wajib dibungkus tenant + diulang. */
    private fun drainFulfillment(token: String, times: Int = 3) {
        val tenantId = tenantIdOf(token)
        repeat(times) {
            TenantContext.runAs(tenantId) {
                worker.processNext(tenantId, "test-${uniq()}", Instant.now().plusSeconds(120))
            }
        }
    }

    // -------------------------------------------------------------- Fixtures

    private fun location(token: String, code: String, kind: String): String =
        id(post("/api/inventory/locations", token, """{"code":"$code","kind":"$kind"}"""))

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
        val subscriptionId = JsonPath.read<String>(customer, "$.subscription.id")
        post("/api/bng/access", token, """{"subscriptionId":"$subscriptionId","planId":"$planId","nasId":null}""")
        return id(customer) to subscriptionId
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

    /** WO PSB yang sudah ditugaskan dan dimulai — siap menerima rencana material. */
    private fun startedWorkOrder(token: String, technicianId: String): Triple<String, String, String> {
        val (customerId, subscriptionId) = customerWithSubscription(token)
        val woId = id(
            post(
                "/api/work-orders", token,
                """{"type":"PSB","title":"Uji nama","customerId":"$customerId","subscriptionId":"$subscriptionId"}""",
            ),
        )
        post("/api/work-orders/$woId/assign", token, """{"technicianIds":["$technicianId"]}""", 200)
        post("/api/work-orders/$woId/start", token, "", 200)
        return Triple(woId, customerId, subscriptionId)
    }

    // ------------------------------------------------------------------ Tes

    /**
     * Jalur baca DAN jalur tulis sama-sama bernama.
     *
     * `POST .../serials` memulangkan view yang persis sama dengan `GET .../materials`; kalau hanya
     * jalur bacanya yang diperkaya, nama yang barusan tampil berubah jadi UUID tepat setelah
     * teknisi men-scan — lalu benar lagi saat layarnya di-refresh.
     */
    @Test
    fun `material WO membawa nama teknisi dan nama peng-scan, bukan UUID`() {
        val token = adminToken("wo-mat-nm", adminName = "Sri Gudang")
        val warehouse = location(token, "WH-${uniq()}", "WAREHOUSE")
        val van = location(token, "VAN-${uniq()}", "VEHICLE")
        val ontItem = item(token, "ONT-${uniq()}", "ONT", serialized = true)
        val technicianId = newTechnician(token, "Budi Teknisi")
        val sn = "SN-${uniq().uppercase()}"

        post(
            "/api/inventory/serialized/bulk", token,
            """{"itemId":"$ontItem","locationId":"$warehouse","custodianId":"$custodian","reason":"kiriman ONT",
                "operationKey":"sn-${uniq()}","payloadHash":"sn-hash",
                "serials":[{"serialNumber":"$sn"}]}""",
        )

        val woId = startedWorkOrder(token, technicianId).first
        put("/api/work-orders/$woId/materials", token, """{"lines":[{"itemId":"$ontItem","quantity":1}]}""")

        // Jalur tulis pertama yang menyebut teknisi: pengeluaran barang sudah harus bernama.
        val issued = rows(
            post(
                "/api/work-orders/$woId/materials/issues", token,
                """{"fromLocationId":"$warehouse","custodianId":"$custodian","technicianId":"$technicianId",
                    "technicianLocationId":"$van","reason":"bekal PSB",
                    "operationKey":"iss-${uniq()}","payloadHash":"iss-hash",
                    "lines":[{"itemId":"$ontItem","quantity":1,"serialNumbers":["$sn"]}]}""",
                expected = 200,
            ),
        ).single()
        assertThat(issued["technicianName"]).isEqualTo("Budi Teknisi")

        // Jalur tulis kedua: respons scan LANGSUNG memuat nama peng-scan-nya.
        val scanned = post(
            "/api/work-orders/$woId/materials/serials", token,
            """{"serialNumber":"$sn","outcome":"INSTALLED"}""", 200,
        )
        assertThat(JsonPath.read<String>(scanned, "$.technicianName")).isEqualTo("Budi Teknisi")
        assertThat(JsonPath.read<String>(scanned, "$.serials[0].scannedByName")).isEqualTo("Sri Gudang")

        // Jalur baca: nama yang sama, dan BUKAN UUID-nya.
        val row = rows(getJson("/api/work-orders/$woId/materials", token)).single()
        assertThat(row["technicianId"]).isEqualTo(technicianId)
        assertThat(row["technicianName"]).isEqualTo("Budi Teknisi")
        assertThat(row["technicianName"]).isNotEqualTo(technicianId)
        val serial = serialsOf(row).single()
        assertThat(serial["scannedByName"]).isEqualTo("Sri Gudang")
        assertThat(serial["scannedByName"]).isNotEqualTo(serial["scannedBy"])
    }

    /**
     * Baris yang belum pernah keluar gudang memang belum punya teknisi.
     *
     * `technicianName` di situ WAJIB `null` — bukan string "null", bukan UUID karangan, bukan nama
     * siapa pun. Kolom yang diisi paksa membuat rencana material yang belum diambil terlihat
     * seperti barang yang sudah ada di tangan orang.
     */
    @Test
    fun `rencana yang belum dikeluarkan gudang memulangkan technicianName null`() {
        val token = adminToken("wo-mat-nil", adminName = "Sri Gudang")
        val patchcord = item(token, "PC-${uniq()}", "PATCHCORD", serialized = false)
        val technicianId = newTechnician(token, "Budi Teknisi")

        val woId = startedWorkOrder(token, technicianId).first
        val planned = rows(
            put("/api/work-orders/$woId/materials", token, """{"lines":[{"itemId":"$patchcord","quantity":2}]}"""),
        ).single()
        assertThat(planned["technicianId"]).isNull()
        assertThat(planned).containsKey("technicianName")
        assertThat(planned["technicianName"]).isNull()

        val row = rows(getJson("/api/work-orders/$woId/materials", token)).single()
        assertThat(row["technicianId"]).isNull()
        assertThat(row).containsKey("technicianName")
        assertThat(row["technicianName"]).isNull()
        assertThat(serialsOf(row)).isEmpty()
    }

    /**
     * Penarikan aset menyebut TIGA orang sekaligus, dan ketiganya harus bernama.
     *
     * Baris yang dibatalkan justru yang paling perlu terbaca: ia menjawab "kenapa saldo tidak
     * bertambah padahal teknisi bilang sudah men-scan", dan jawaban itu tak ada artinya kalau
     * pembatalnya cuma UUID.
     */
    @Test
    fun `penarikan aset membawa nama teknisi, penarik, dan pembatalnya`() {
        val token = adminToken("wo-rec-nm", adminName = "Sri Gudang")
        val warehouse = location(token, "WH-${uniq()}", "WAREHOUSE")
        val van = location(token, "VAN-${uniq()}", "VEHICLE")
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
        val (psbId, customerId, subscriptionId) = startedWorkOrder(token, technicianId)
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
        post("/api/work-orders/$psbId/approve", newApprover(token), """{"note":"Disetujui"}""", 200)
        drainFulfillment(token)

        val dismantleId = id(
            post(
                "/api/work-orders", token,
                """{"type":"DISMANTLE","title":"Bongkar","customerId":"$customerId","subscriptionId":"$subscriptionId"}""",
            ),
        )
        post("/api/work-orders/$dismantleId/assign", token, """{"technicianIds":["$technicianId"]}""", 200)
        post("/api/work-orders/$dismantleId/start", token, "", 200)

        // Jalur TULIS: respons scan penarikan langsung bernama.
        val recovered = post(
            "/api/work-orders/$dismantleId/materials/recovered-assets", token,
            """{"serialNumber":"$sn","technicianId":"$technicianId","technicianLocationId":"$van",
                "condition":"GOOD","note":"ONT ditarik dari rumah pelanggan"}""",
            expected = 200,
        )
        assertThat(JsonPath.read<String>(recovered, "$.technicianName")).isEqualTo("Budi Teknisi")
        assertThat(JsonPath.read<String>(recovered, "$.recoveredByName")).isEqualTo("Sri Gudang")
        val recoveredId = id(recovered)

        // Jalur BACA: sama, dan pembatalnya masih kosong karena barisnya memang belum dibatalkan.
        val before = rows(getJson("/api/work-orders/$dismantleId/materials/recovered-assets", token)).single()
        assertThat(before["technicianName"]).isEqualTo("Budi Teknisi")
        assertThat(before["technicianName"]).isNotEqualTo(technicianId)
        assertThat(before["recoveredByName"]).isEqualTo("Sri Gudang")
        assertThat(before["recoveredByName"]).isNotEqualTo(before["recoveredBy"])
        assertThat(before).containsKey("cancelledByName")
        assertThat(before["cancelledByName"]).isNull()

        val cancelled = post(
            "/api/work-orders/$dismantleId/materials/recovered-assets/$recoveredId/cancel", token,
            """{"reason":"salah scan"}""", expected = 200,
        )
        assertThat(JsonPath.read<String>(cancelled, "$.cancelledByName")).isEqualTo("Sri Gudang")

        val after = rows(getJson("/api/work-orders/$dismantleId/materials/recovered-assets", token)).single()
        assertThat(after["cancelledByName"]).isEqualTo("Sri Gudang")
        assertThat(after["cancelledByName"]).isNotEqualTo(after["cancelledBy"])
    }
}
