package com.duluin.ftth

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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * Uji work order Phase 4.1: siklus hidup dispatcher (draft → tugaskan → kerjakan
 * → selesai / batal) ditegakkan di domain, penugasan divalidasi terhadap pengguna
 * iam, dan nama teknisi/pelanggan diresolusi lintas-module saat menyusun view.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkOrderIT {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var onboarding: OnboardTenantUseCase

    private val pass = "secret12345"
    private fun uniq() = UUID.randomUUID().toString().substring(0, 8)

    private fun login(slug: String, email: String): String {
        val json = mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"tenantSlug":"$slug","email":"$email","password":"$pass"}"""),
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

    private fun get(url: String, token: String, expected: Int = 200): String =
        mockMvc.perform(get(url).header("Authorization", "Bearer $token"))
            .andExpect { assertThat(it.response.status).isEqualTo(expected) }
            .andReturn().response.contentAsString

    private fun put(url: String, token: String, body: String, expected: Int = 200): String =
        mockMvc.perform(
            put(url).header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect { assertThat(it.response.status).isEqualTo(expected) }
            .andReturn().response.contentAsString

    private fun id(json: String): String = JsonPath.read(json, "$.id")

    /** Buat pengguna (calon teknisi), kembalikan id-nya. */
    private fun newTechnician(token: String, name: String): String {
        val s = uniq()
        val roles = get("/api/roles", token)
        val names = JsonPath.read<List<String>>(roles, "$[*].name")
        val ids = JsonPath.read<List<String>>(roles, "$[*].id")
        val roleId = ids[names.indexOf("Teknisi")]
        return id(post("/api/users", token, """{"email":"tech-$s@x.test","name":"$name","password":"$pass","roleIds":["$roleId"]}"""))
    }

    private fun newCustomer(token: String): Pair<String, String> {
        val s = uniq().uppercase()
        val name = "Pelanggan $s"
        val cid = id(
            post("/api/customers", token, """{"code":"C-$s","name":"$name","address":"Jl. Uji","location":{"longitude":106.99,"latitude":-6.24}}"""),
        )
        return cid to name
    }

    private fun createWorkOrder(token: String, body: String) = post("/api/work-orders", token, body)

    /** Buat paket katalog, kembalikan id-nya (langganan merujuk ini). */
    private fun catalogPlan(token: String, name: String): String =
        id(
            post(
                "/api/catalog/plans", token,
                """{"name":"$name","description":null,"price":150000,"downMbps":20,"upMbps":10,"serviceTypes":["PPPOE"]}""",
            ),
        )

    /** Pelanggan + langganan PENDING merujuk paket; kembalikan (customerId, subscriptionId). */
    private fun pendingSubscription(token: String): Pair<String, String> {
        val (customerId, _) = newCustomer(token)
        val planId = catalogPlan(token, "Paket ${uniq()}")
        val sub = id(put("/api/customers/$customerId/subscription", token, """{"planId":"$planId"}"""))
        return customerId to sub
    }

    /** Status langganan pelanggan (enum sebagai string) — dia hanya punya satu. */
    private fun subscriptionStatus(token: String, customerId: String): String =
        JsonPath.read(get("/api/customers/$customerId/subscription", token), "$.status")

    @Test
    fun `siklus hidup work order ditegakkan dan nama teknisi diresolusi`() {
        val token = newTenantAdmin("wo")

        val created = createWorkOrder(token, """{"type":"REPAIR","title":"Ganti drop core putus","priority":"HIGH"}""")
        assertThat(JsonPath.read<String>(created, "$.status")).isEqualTo("DRAFT")
        assertThat(JsonPath.read<String>(created, "$.code")).startsWith("WO-")
        assertThat(JsonPath.read<List<Any>>(created, "$.assignees")).isEmpty()
        val woId = id(created)

        // Tugaskan ke teknisi → naik ke ASSIGNED, nama teknisi ikut diresolusi.
        val techId = newTechnician(token, "Budi Teknisi")
        val assigned = post("/api/work-orders/$woId/assign", token, """{"technicianIds":["$techId"]}""", 200)
        assertThat(JsonPath.read<String>(assigned, "$.status")).isEqualTo("ASSIGNED")
        assertThat(JsonPath.read<List<String>>(assigned, "$.assignees[*].id")).containsExactly(techId)
        assertThat(JsonPath.read<List<String>>(assigned, "$.assignees[*].name")).containsExactly("Budi Teknisi")

        // Kerjakan lalu selesaikan.
        val started = post("/api/work-orders/$woId/start", token, "", 200)
        assertThat(JsonPath.read<String>(started, "$.status")).isEqualTo("IN_PROGRESS")
        val done = post("/api/work-orders/$woId/complete", token, """{"resolutionNote":"Core disambung ulang"}""", 200)
        assertThat(JsonPath.read<String>(done, "$.status")).isEqualTo("DONE")
        assertThat(JsonPath.read<String>(done, "$.resolutionNote")).isEqualTo("Core disambung ulang")

        // Timeline merekam tiap transisi, terlama lebih dulu.
        val detail = get("/api/work-orders/$woId", token)
        assertThat(JsonPath.read<List<String>>(detail, "$.timeline[*].type"))
            .containsExactly("CREATED", "ASSIGNED", "STARTED", "COMPLETED")

        // Daftar berisi work order tadi.
        val list = get("/api/work-orders", token)
        assertThat(JsonPath.read<List<Any>>(list, "$.content[*]")).hasSize(1)
        assertThat(JsonPath.read<String>(list, "$.content[0].id")).isEqualTo(woId)
    }

    @Test
    fun `mulai sebelum ditugaskan ditolak`() {
        val token = newTenantAdmin("wo")
        val woId = id(createWorkOrder(token, """{"type":"PSB","title":"Pasang baru"}"""))
        post("/api/work-orders/$woId/start", token, "", expected = 409)
    }

    @Test
    fun `assign teknisi tidak ada ditolak 404`() {
        val token = newTenantAdmin("wo")
        val woId = id(createWorkOrder(token, """{"type":"PSB","title":"Pasang baru"}"""))
        post("/api/work-orders/$woId/assign", token, """{"technicianIds":["${UUID.randomUUID()}"]}""", expected = 404)
    }

    @Test
    fun `assign teknisi nonaktif ditolak 409`() {
        val token = newTenantAdmin("wo")
        val woId = id(createWorkOrder(token, """{"type":"REPAIR","title":"Perbaikan"}"""))
        val techId = newTechnician(token, "Teknisi Cuti")
        post("/api/users/$techId/disable", token, "", 200)
        post("/api/work-orders/$woId/assign", token, """{"technicianIds":["$techId"]}""", expected = 409)
    }

    @Test
    fun `assign pengguna aktif tanpa role Teknisi ditolak`() {
        val token = newTenantAdmin("wo")
        val woId = id(createWorkOrder(token, """{"type":"REPAIR","title":"Perbaikan"}"""))
        val userId = newTechnician(token, "Pengguna Operasional")
        put("/api/users/$userId/access", token, """{"roleIds":[],"areaIds":[]}""", 200)
        post("/api/work-orders/$woId/assign", token, """{"technicianIds":["$userId"]}""", expected = 409)
    }

    @Test
    fun `work order tertaut pelanggan menampilkan nama pelanggan dan menolak pelanggan fiktif`() {
        val token = newTenantAdmin("wo")
        val (customerId, customerName) = newCustomer(token)

        val created = createWorkOrder(
            token,
            """{"type":"MIGRATION","title":"Pindah ODP","customerId":"$customerId"}""",
        )
        assertThat(JsonPath.read<String>(created, "$.customerId")).isEqualTo(customerId)
        assertThat(JsonPath.read<String>(created, "$.customerName")).isEqualTo(customerName)

        post(
            "/api/work-orders", token,
            """{"type":"MIGRATION","title":"Pindah ODP","customerId":"${UUID.randomUUID()}"}""",
            expected = 404,
        )
    }

    @Test
    fun `filter by customer hanya mengembalikan work order pelanggan itu`() {
        val token = newTenantAdmin("wo")
        val (custA, _) = newCustomer(token)
        val (custB, _) = newCustomer(token)

        val woA = id(createWorkOrder(token, """{"type":"REPAIR","title":"Perbaikan A","customerId":"$custA"}"""))
        createWorkOrder(token, """{"type":"MIGRATION","title":"Pindah B","customerId":"$custB"}""")
        // WO tanpa pelanggan tak boleh ikut ketika difilter per pelanggan.
        createWorkOrder(token, """{"type":"PSB","title":"Pasang lepasan"}""")

        val listA = get("/api/work-orders?customerId=$custA", token)
        assertThat(JsonPath.read<List<Any>>(listA, "$.content[*]")).hasSize(1)
        assertThat(JsonPath.read<String>(listA, "$.content[0].id")).isEqualTo(woA)
        assertThat(JsonPath.read<String>(listA, "$.content[0].customerId")).isEqualTo(custA)

        // Tanpa param, ketiga WO tetap tampil (perilaku lama tak berubah).
        assertThat(JsonPath.read<List<Any>>(get("/api/work-orders", token), "$.content[*]")).hasSize(3)
    }

    @Test
    fun `redaman optik direkam bertahap, di luar rentang ditolak, dan tak bisa setelah batal`() {
        val token = newTenantAdmin("wo")
        val woId = id(createWorkOrder(token, """{"type":"PSB","title":"Pasang baru"}"""))

        // Bertahap: catat 'sebelum' saat teknisi tiba, lalu lengkapi 'sesudah'.
        val before = put("/api/work-orders/$woId/optical", token, """{"rxBeforeDbm":-24.5}""")
        assertThat(JsonPath.read<Double>(before, "$.rxBeforeDbm")).isEqualTo(-24.5)
        assertThat(JsonPath.read<Any?>(before, "$.rxAfterDbm")).isNull()

        val after = put("/api/work-orders/$woId/optical", token, """{"rxBeforeDbm":-24.5,"rxAfterDbm":-20.1}""")
        assertThat(JsonPath.read<Double>(after, "$.rxAfterDbm")).isEqualTo(-20.1)

        // Timeline mencatat pengukuran sebagai UPDATED (dua kali).
        val detail = get("/api/work-orders/$woId", token)
        assertThat(JsonPath.read<List<String>>(detail, "$.timeline[*].type")).containsExactly("CREATED", "UPDATED", "UPDATED")

        // Di luar rentang wajar (−40..0 dBm) ditolak oleh validasi request.
        put("/api/work-orders/$woId/optical", token, """{"rxBeforeDbm":-55}""", expected = 400)
        put("/api/work-orders/$woId/optical", token, """{"rxAfterDbm":3.0}""", expected = 400)

        // Setelah dibatalkan, pengukuran tak bisa lagi direkam.
        post("/api/work-orders/$woId/cancel", token, """{"reason":"Salah alamat"}""", 200)
        put("/api/work-orders/$woId/optical", token, """{"rxAfterDbm":-21.0}""", expected = 409)
    }

    @Test
    fun `dashboard dispatch merangkum status, antrean belum ditugaskan, dan beban teknisi`() {
        val token = newTenantAdmin("wo")
        val techA = newTechnician(token, "Teknisi A")
        val techB = newTechnician(token, "Teknisi B")

        // #1 tetap DRAFT (belum ditugaskan → masuk antrean dispatch).
        createWorkOrder(token, """{"type":"PSB","title":"Pasang baru"}""")

        // #2 ditugaskan ke A (ASSIGNED).
        val wo2 = id(createWorkOrder(token, """{"type":"REPAIR","title":"Perbaikan"}"""))
        post("/api/work-orders/$wo2/assign", token, """{"technicianIds":["$techA"]}""", 200)

        // #3 ditugaskan ke A lalu dikerjakan (IN_PROGRESS).
        val wo3 = id(createWorkOrder(token, """{"type":"REPAIR","title":"Perbaikan lain"}"""))
        post("/api/work-orders/$wo3/assign", token, """{"technicianIds":["$techA"]}""", 200)
        post("/api/work-orders/$wo3/start", token, "", 200)

        // #4 ditugaskan ke B lalu diselesaikan (DONE → tak terhitung sebagai beban terbuka).
        val wo4 = id(createWorkOrder(token, """{"type":"MIGRATION","title":"Migrasi"}"""))
        post("/api/work-orders/$wo4/assign", token, """{"technicianIds":["$techB"]}""", 200)
        post("/api/work-orders/$wo4/start", token, "", 200)
        post("/api/work-orders/$wo4/complete", token, "", 200)

        val dash = get("/api/work-orders/dashboard", token)
        assertThat(JsonPath.read<Int>(dash, "$.total")).isEqualTo(4)
        assertThat(JsonPath.read<Int>(dash, "$.open")).isEqualTo(3)
        assertThat(JsonPath.read<Int>(dash, "$.unassignedOpen")).isEqualTo(1)
        assertThat(JsonPath.read<Int>(dash, "$.byStatus.DRAFT")).isEqualTo(1)
        assertThat(JsonPath.read<Int>(dash, "$.byStatus.ASSIGNED")).isEqualTo(1)
        assertThat(JsonPath.read<Int>(dash, "$.byStatus.IN_PROGRESS")).isEqualTo(1)
        assertThat(JsonPath.read<Int>(dash, "$.byStatus.DONE")).isEqualTo(1)
        assertThat(JsonPath.read<Int>(dash, "$.byStatus.CANCELLED")).isEqualTo(0)
        assertThat(JsonPath.read<Int>(dash, "$.byType.REPAIR")).isEqualTo(2)

        // Hanya A yang punya WO terbuka (2); B tidak muncul karena WO-nya sudah DONE.
        assertThat(JsonPath.read<List<Any>>(dash, "$.workloads[*]")).hasSize(1)
        assertThat(JsonPath.read<String>(dash, "$.workloads[0].technicianId")).isEqualTo(techA)
        assertThat(JsonPath.read<String>(dash, "$.workloads[0].technicianName")).isEqualTo("Teknisi A")
        assertThat(JsonPath.read<Int>(dash, "$.workloads[0].openCount")).isEqualTo(2)
    }

    /** Bawa sebuah WO baru sampai DONE (assign → start → complete), kembalikan id-nya. */
    private fun completeWorkOrder(token: String, title: String): String {
        val woId = id(createWorkOrder(token, """{"type":"PSB","title":"$title"}"""))
        val techId = newTechnician(token, "Teknisi $title")
        post("/api/work-orders/$woId/assign", token, """{"technicianIds":["$techId"]}""", 200)
        post("/api/work-orders/$woId/start", token, "", 200)
        post("/api/work-orders/$woId/complete", token, "", 200)
        return woId
    }

    @Test
    fun `hasil kerja masuk antrean persetujuan, penolakan membuka kembali, persetujuan mengunci`() {
        val token = newTenantAdmin("wo")
        val woId = completeWorkOrder(token, "Pasang baru")

        // Selesai → menunggu persetujuan; muncul di antrean & dashboard.
        val done = get("/api/work-orders/$woId", token)
        assertThat(JsonPath.read<String>(done, "$.workOrder.approvalStatus")).isEqualTo("PENDING")

        val dash = get("/api/work-orders/dashboard", token)
        assertThat(JsonPath.read<Int>(dash, "$.pendingApproval")).isEqualTo(1)

        val queue = get("/api/work-orders?approval=PENDING", token)
        assertThat(JsonPath.read<List<Any>>(queue, "$.content[*]")).hasSize(1)
        assertThat(JsonPath.read<String>(queue, "$.content[0].id")).isEqualTo(woId)

        // Alasan penolakan wajib (bean validation).
        post("/api/work-orders/$woId/reject", token, """{"reason":""}""", expected = 400)

        // Tolak → WO dibuka kembali ke IN_PROGRESS untuk dikerjakan ulang.
        val rejected = post("/api/work-orders/$woId/reject", token, """{"reason":"Redaman masih jelek"}""", 200)
        assertThat(JsonPath.read<String>(rejected, "$.status")).isEqualTo("IN_PROGRESS")
        assertThat(JsonPath.read<String>(rejected, "$.approvalStatus")).isEqualTo("REJECTED")
        assertThat(JsonPath.read<String>(rejected, "$.approvalNote")).isEqualTo("Redaman masih jelek")
        assertThat(JsonPath.read<String>(rejected, "$.approvedByName")).isEqualTo("Admin")
        assertThat(JsonPath.read<Any?>(rejected, "$.completedAt")).isNull()

        // Antrean kini kosong (sudah tak PENDING).
        assertThat(JsonPath.read<Int>(get("/api/work-orders/dashboard", token), "$.pendingApproval")).isEqualTo(0)

        // Selesaikan ulang → kembali PENDING, catatan penolakan lama tereset.
        val redone = post("/api/work-orders/$woId/complete", token, """{"resolutionNote":"Splice ulang"}""", 200)
        assertThat(JsonPath.read<String>(redone, "$.approvalStatus")).isEqualTo("PENDING")
        assertThat(JsonPath.read<Any?>(redone, "$.approvalNote")).isNull()

        // Setujui → terkunci APPROVED dengan pengambil keputusan tercatat.
        val approved = post("/api/work-orders/$woId/approve", token, """{"note":"Sekarang OK"}""", 200)
        assertThat(JsonPath.read<String>(approved, "$.status")).isEqualTo("DONE")
        assertThat(JsonPath.read<String>(approved, "$.approvalStatus")).isEqualTo("APPROVED")
        assertThat(JsonPath.read<String>(approved, "$.approvalNote")).isEqualTo("Sekarang OK")
        assertThat(JsonPath.read<String>(approved, "$.approvedByName")).isEqualTo("Admin")

        // Sudah disetujui → tak bisa disetujui/ditolak lagi.
        post("/api/work-orders/$woId/approve", token, "", expected = 409)
        post("/api/work-orders/$woId/reject", token, """{"reason":"berubah pikiran"}""", expected = 409)

        // Timeline memuat penolakan lalu persetujuan.
        val timeline = JsonPath.read<List<String>>(get("/api/work-orders/$woId", token), "$.timeline[*].type")
        assertThat(timeline).containsSubsequence("COMPLETED", "REJECTED", "COMPLETED", "APPROVED")
    }

    @Test
    fun `setujui work order yang belum selesai ditolak`() {
        val token = newTenantAdmin("wo")
        val woId = id(createWorkOrder(token, """{"type":"PSB","title":"Pasang baru"}"""))
        post("/api/work-orders/$woId/approve", token, "", expected = 409)
    }

    @Test
    fun `hapus hanya untuk draft, sisanya dibatalkan`() {
        val token = newTenantAdmin("wo")

        // Draft boleh dihapus.
        val draftId = id(createWorkOrder(token, """{"type":"PREVENTIVE","title":"Inspeksi rutin"}"""))
        mockMvc.perform(delete("/api/work-orders/$draftId").header("Authorization", "Bearer $token"))
            .andExpect(status().isNoContent)
        get("/api/work-orders/$draftId", token, expected = 404)

        // Yang sudah ditugaskan tak bisa dihapus — harus dibatalkan.
        val woId = id(createWorkOrder(token, """{"type":"REPAIR","title":"Perbaikan"}"""))
        val techId = newTechnician(token, "Teknisi")
        post("/api/work-orders/$woId/assign", token, """{"technicianIds":["$techId"]}""", 200)
        mockMvc.perform(delete("/api/work-orders/$woId").header("Authorization", "Bearer $token"))
            .andExpect(status().isConflict)

        val cancelled = post("/api/work-orders/$woId/cancel", token, """{"reason":"Duplikat"}""", 200)
        assertThat(JsonPath.read<String>(cancelled, "$.status")).isEqualTo("CANCELLED")
        assertThat(JsonPath.read<String>(cancelled, "$.cancelReason")).isEqualTo("Duplikat")
    }

    @Test
    fun `work order tertaut pelanggan membawa koordinat tujuan untuk navigasi teknisi`() {
        val token = newTenantAdmin("wo")
        val (customerId, _) = newCustomer(token) // lokasi lon 106.99 lat -6.24

        val created = createWorkOrder(token, """{"type":"PSB","title":"Pasang baru","customerId":"$customerId"}""")
        assertThat(JsonPath.read<Double>(created, "$.destinationLat")).isEqualTo(-6.24)
        assertThat(JsonPath.read<Double>(created, "$.destinationLng")).isEqualTo(106.99)

        // Ikut muncul di daftar (jalur resolusi batch).
        val list = get("/api/work-orders", token)
        assertThat(JsonPath.read<Double>(list, "$.content[0].destinationLat")).isEqualTo(-6.24)
        assertThat(JsonPath.read<Double>(list, "$.content[0].destinationLng")).isEqualTo(106.99)

        // WO tanpa pelanggan → koordinat null.
        val lepasan = createWorkOrder(token, """{"type":"PSB","title":"Pasang lepasan"}""")
        assertThat(JsonPath.read<Any?>(lepasan, "$.destinationLat")).isNull()
    }

    @Test
    fun `PSB selesai mengaktifkan langganan, ditolak lalu selesai ulang tetap aktif`() {
        val token = newTenantAdmin("wo")
        val (customerId, sub) = pendingSubscription(token)
        assertThat(subscriptionStatus(token, customerId)).isEqualTo("PENDING")

        val woId = id(
            createWorkOrder(
                token,
                """{"type":"PSB","title":"Pasang baru","customerId":"$customerId","subscriptionId":"$sub"}""",
            ),
        )
        assertThat(JsonPath.read<String>(get("/api/work-orders/$woId", token), "$.workOrder.subscriptionId")).isEqualTo(sub)

        val techId = newTechnician(token, "Teknisi PSB")
        post("/api/work-orders/$woId/assign", token, """{"technicianIds":["$techId"]}""", 200)
        post("/api/work-orders/$woId/start", token, "", 200)
        // Langganan masih PENDING sampai WO benar-benar selesai.
        assertThat(subscriptionStatus(token, customerId)).isEqualTo("PENDING")

        // Selesai → layanan resmi hidup.
        post("/api/work-orders/$woId/complete", token, """{"resolutionNote":"Terpasang"}""", 200)
        assertThat(subscriptionStatus(token, customerId)).isEqualTo("ACTIVE")

        // Penyelia menolak → WO dibuka lagi; aktivasi TIDAK dibatalkan (layanan sudah jalan).
        post("/api/work-orders/$woId/reject", token, """{"reason":"Rapikan kabel"}""", 200)
        assertThat(subscriptionStatus(token, customerId)).isEqualTo("ACTIVE")

        // Selesai ulang: idempoten (activate no-op karena bukan PENDING), tetap ACTIVE tanpa error.
        post("/api/work-orders/$woId/complete", token, """{"resolutionNote":"Sudah rapi"}""", 200)
        assertThat(subscriptionStatus(token, customerId)).isEqualTo("ACTIVE")
    }

    @Test
    fun `DISMANTLE selesai menerminasi langganan`() {
        val token = newTenantAdmin("wo")
        val (customerId, sub) = pendingSubscription(token)
        post("/api/customers/subscriptions/$sub/activate", token, "", 200)
        assertThat(subscriptionStatus(token, customerId)).isEqualTo("ACTIVE")

        val woId = id(
            createWorkOrder(
                token,
                """{"type":"DISMANTLE","title":"Bongkar","customerId":"$customerId","subscriptionId":"$sub"}""",
            ),
        )
        val techId = newTechnician(token, "Teknisi Bongkar")
        post("/api/work-orders/$woId/assign", token, """{"technicianIds":["$techId"]}""", 200)
        post("/api/work-orders/$woId/start", token, "", 200)
        post("/api/work-orders/$woId/complete", token, "", 200)

        assertThat(subscriptionStatus(token, customerId)).isEqualTo("TERMINATED")
    }

    @Test
    fun `WO non-PSB dengan langganan tak menggeser status langganan saat selesai`() {
        val token = newTenantAdmin("wo")
        val (customerId, sub) = pendingSubscription(token)

        val woId = id(
            createWorkOrder(
                token,
                """{"type":"REPAIR","title":"Cek redaman","customerId":"$customerId","subscriptionId":"$sub"}""",
            ),
        )
        val techId = newTechnician(token, "Teknisi Repair")
        post("/api/work-orders/$woId/assign", token, """{"technicianIds":["$techId"]}""", 200)
        post("/api/work-orders/$woId/start", token, "", 200)
        post("/api/work-orders/$woId/complete", token, "", 200)

        // REPAIR bukan pemasangan/pembongkaran → langganan tetap PENDING.
        assertThat(subscriptionStatus(token, customerId)).isEqualTo("PENDING")
    }

    @Test
    fun `teknisi lapangan hanya lihat dan kerjakan WO miliknya, WO lain ditolak 403`() {
        val slug = "wo${uniq()}"
        val adminEmail = "admin@$slug.test"
        onboarding.onboard(OnboardTenantCommand(slug, "Tenant $slug", adminEmail, "Admin", pass))
        val adminToken = login(slug, adminEmail)

        // Role sistem "Teknisi" tersedia sejak onboarding.
        val roles = get("/api/roles", adminToken)
        val roleNames = JsonPath.read<List<String>>(roles, "$[*].name")
        val roleIds = JsonPath.read<List<String>>(roles, "$[*].id")
        assertThat(roleNames).contains("Teknisi")
        val teknisiRoleId = roleIds[roleNames.indexOf("Teknisi")]

        // Dua teknisi ber-role Teknisi.
        val tech1Email = "tech1-${uniq()}@x.test"
        val tech1Id = id(
            post("/api/users", adminToken, """{"email":"$tech1Email","name":"Teknisi Satu","password":"$pass","roleIds":["$teknisiRoleId"]}"""),
        )
        val tech2Id = id(
            post("/api/users", adminToken, """{"email":"tech2-${uniq()}@x.test","name":"Teknisi Dua","password":"$pass","roleIds":["$teknisiRoleId"]}"""),
        )
        val tech1Token = login(slug, tech1Email)

        // WO milik tech1 dan WO milik tech2.
        val woMine = id(createWorkOrder(adminToken, """{"type":"PSB","title":"Pasang ke A"}"""))
        post("/api/work-orders/$woMine/assign", adminToken, """{"technicianIds":["$tech1Id"]}""", 200)
        val woOther = id(createWorkOrder(adminToken, """{"type":"PSB","title":"Pasang ke B"}"""))
        post("/api/work-orders/$woOther/assign", adminToken, """{"technicianIds":["$tech2Id"]}""", 200)

        // Papan tugas /mine hanya berisi WO tech1.
        val mine = get("/api/work-orders/mine", tech1Token)
        assertThat(JsonPath.read<List<Any>>(mine, "$.content[*]")).hasSize(1)
        assertThat(JsonPath.read<String>(mine, "$.content[0].id")).isEqualTo(woMine)

        // Kerjakan WO sendiri → boleh.
        post("/api/work-orders/$woMine/start", tech1Token, "", 200)
        post("/api/work-orders/$woMine/complete", tech1Token, """{"resolutionNote":"Selesai"}""", 200)

        // Sentuh WO teknisi lain → 403 (bukan pemiliknya).
        post("/api/work-orders/$woOther/start", tech1Token, "", expected = 403)
        post("/api/work-orders/$woOther/complete", tech1Token, "", expected = 403)

        // Dispatcher (admin) tetap bebas mengerjakan WO mana pun.
        post("/api/work-orders/$woOther/start", adminToken, "", 200)
    }

    @Test
    fun `tim datar — satu WO banyak teknisi tampil di papan semua anggota dan terhitung beban tiap anggota`() {
        val slug = "wo${uniq()}"
        val adminEmail = "admin@$slug.test"
        onboarding.onboard(OnboardTenantCommand(slug, "Tenant $slug", adminEmail, "Admin", pass))
        val adminToken = login(slug, adminEmail)

        val roles = get("/api/roles", adminToken)
        val roleNames = JsonPath.read<List<String>>(roles, "$[*].name")
        val roleIds = JsonPath.read<List<String>>(roles, "$[*].id")
        val teknisiRoleId = roleIds[roleNames.indexOf("Teknisi")]

        fun teknisi(name: String): Pair<String, String> {
            val email = "tech-${uniq()}@x.test"
            val uid = id(
                post("/api/users", adminToken, """{"email":"$email","name":"$name","password":"$pass","roleIds":["$teknisiRoleId"]}"""),
            )
            return uid to email
        }

        val (techAId, techAEmail) = teknisi("Teknisi A")
        val (techBId, techBEmail) = teknisi("Teknisi B")
        val (techCId, _) = teknisi("Teknisi C")
        val techAToken = login(slug, techAEmail)
        val techBToken = login(slug, techBEmail)

        // Tugaskan satu WO ke DUA teknisi sekaligus (tim datar).
        val woId = id(createWorkOrder(adminToken, """{"type":"PSB","title":"Pasang berdua"}"""))
        val assigned = post("/api/work-orders/$woId/assign", adminToken, """{"technicianIds":["$techAId","$techBId"]}""", 200)
        assertThat(JsonPath.read<List<String>>(assigned, "$.assignees[*].id")).containsExactlyInAnyOrder(techAId, techBId)
        assertThat(JsonPath.read<List<String>>(assigned, "$.assignees[*].name"))
            .containsExactlyInAnyOrder("Teknisi A", "Teknisi B")

        // Round-trip: ambil ulang detail dari persistence → roster utuh dua teknisi.
        val detail = get("/api/work-orders/$woId", adminToken)
        assertThat(JsonPath.read<List<String>>(detail, "$.workOrder.assignees[*].id"))
            .containsExactlyInAnyOrder(techAId, techBId)

        // Keduanya melihat WO di papan tugasnya masing-masing (membership tim datar).
        assertThat(JsonPath.read<List<String>>(get("/api/work-orders/mine", techAToken), "$.content[*].id")).containsExactly(woId)
        assertThat(JsonPath.read<List<String>>(get("/api/work-orders/mine", techBToken), "$.content[*].id")).containsExactly(woId)

        // Dashboard menghitung WO terbuka ini pada beban KEDUA teknisi.
        val dash = get("/api/work-orders/dashboard", adminToken)
        val wlIds = JsonPath.read<List<String>>(dash, "$.workloads[*].technicianId")
        val wlCounts = JsonPath.read<List<Int>>(dash, "$.workloads[*].openCount")
        assertThat(wlIds).contains(techAId, techBId)
        assertThat(wlCounts[wlIds.indexOf(techAId)]).isEqualTo(1)
        assertThat(wlCounts[wlIds.indexOf(techBId)]).isEqualTo(1)

        // Anggota mana pun boleh memulai (tim datar, semua setara).
        post("/api/work-orders/$woId/start", techBToken, "", 200)

        // Tugaskan ulang → roster diganti utuh ke satu teknisi C; A & B lepas.
        val reassigned = post("/api/work-orders/$woId/assign", adminToken, """{"technicianIds":["$techCId"]}""", 200)
        assertThat(JsonPath.read<List<String>>(reassigned, "$.assignees[*].id")).containsExactly(techCId)
        assertThat(JsonPath.read<List<Any>>(get("/api/work-orders/mine", techAToken), "$.content[*]")).isEmpty()
        assertThat(JsonPath.read<List<Any>>(get("/api/work-orders/mine", techBToken), "$.content[*]")).isEmpty()

        // Roster kosong ditolak oleh bean validation (@NotEmpty).
        post("/api/work-orders/$woId/assign", adminToken, """{"technicianIds":[]}""", expected = 400)
    }
}
