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
        return id(post("/api/users", token, """{"email":"tech-$s@x.test","name":"$name","password":"$pass"}"""))
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

    @Test
    fun `siklus hidup work order ditegakkan dan nama teknisi diresolusi`() {
        val token = newTenantAdmin("wo")

        val created = createWorkOrder(token, """{"type":"REPAIR","title":"Ganti drop core putus","priority":"HIGH"}""")
        assertThat(JsonPath.read<String>(created, "$.status")).isEqualTo("DRAFT")
        assertThat(JsonPath.read<String>(created, "$.code")).startsWith("WO-")
        assertThat(JsonPath.read<Any?>(created, "$.assignedTo")).isNull()
        val woId = id(created)

        // Tugaskan ke teknisi → naik ke ASSIGNED, nama teknisi ikut diresolusi.
        val techId = newTechnician(token, "Budi Teknisi")
        val assigned = post("/api/work-orders/$woId/assign", token, """{"technicianId":"$techId"}""", 200)
        assertThat(JsonPath.read<String>(assigned, "$.status")).isEqualTo("ASSIGNED")
        assertThat(JsonPath.read<String>(assigned, "$.assignedTo")).isEqualTo(techId)
        assertThat(JsonPath.read<String>(assigned, "$.assignedToName")).isEqualTo("Budi Teknisi")

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
        post("/api/work-orders/$woId/assign", token, """{"technicianId":"${UUID.randomUUID()}"}""", expected = 404)
    }

    @Test
    fun `assign teknisi nonaktif ditolak 409`() {
        val token = newTenantAdmin("wo")
        val woId = id(createWorkOrder(token, """{"type":"REPAIR","title":"Perbaikan"}"""))
        val techId = newTechnician(token, "Teknisi Cuti")
        post("/api/users/$techId/disable", token, "", 200)
        post("/api/work-orders/$woId/assign", token, """{"technicianId":"$techId"}""", expected = 409)
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
        post("/api/work-orders/$woId/assign", token, """{"technicianId":"$techId"}""", 200)
        mockMvc.perform(delete("/api/work-orders/$woId").header("Authorization", "Bearer $token"))
            .andExpect(status().isConflict)

        val cancelled = post("/api/work-orders/$woId/cancel", token, """{"reason":"Duplikat"}""", 200)
        assertThat(JsonPath.read<String>(cancelled, "$.status")).isEqualTo("CANCELLED")
        assertThat(JsonPath.read<String>(cancelled, "$.cancelReason")).isEqualTo("Duplikat")
    }
}
