package com.duluin.ftth

import com.duluin.ftth.iam.application.port.inbound.OnboardTenantCommand
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantUseCase
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.tenancy.TenantApi
import com.duluin.ftth.workorder.application.service.EvidenceRetentionWorker
import com.duluin.ftth.workorder.adapter.outbound.persistence.WorkOrderEvidenceJpaRepository
import com.duluin.ftth.workorder.domain.model.EvidenceRevisionState
import com.jayway.jsonpath.JsonPath
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import java.time.Instant

/**
 * Uji bukti pengerjaan Phase 4.2: foto & tanda tangan disimpan via object storage
 * (di-stub in-memory pada profil test), di-proxy balik lewat endpoint content yang
 * ter-gate izin, dan hanya boleh dilampirkan saat pekerjaan benar-benar berlangsung.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkOrderEvidenceIT {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var onboarding: OnboardTenantUseCase
    @Autowired private lateinit var tenantApi: TenantApi
    @Autowired private lateinit var retentionWorker: EvidenceRetentionWorker
    @Autowired private lateinit var evidenceJpa: WorkOrderEvidenceJpaRepository

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

    private fun newTenantSession(prefix: String): Pair<String, UUID> {
        val slug = "$prefix${uniq()}"
        val admin = "admin@$slug.test"
        onboarding.onboard(OnboardTenantCommand(slug, "Tenant $slug", admin, "Admin", pass))
        return login(slug, admin) to tenantApi.findBySlug(slug)!!.id
    }

    private fun id(json: String): String = JsonPath.read(json, "$.id")

    private fun evidenceRevisionId(json: String): String = JsonPath.read(json, "$.revisionId")

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

    private fun newTechnician(token: String): String {
        val s = uniq()
        val roles = get("/api/roles", token)
        val names = JsonPath.read<List<String>>(roles, "$[*].name")
        val ids = JsonPath.read<List<String>>(roles, "$[*].id")
        val roleId = ids[names.indexOf("Teknisi")]
        return id(post("/api/users", token, """{"email":"tech-$s@x.test","name":"Teknisi $s","password":"$pass","roleIds":["$roleId"]}"""))
    }

    private fun newWorkOrder(token: String) =
        id(post("/api/work-orders", token, """{"type":"REPAIR","title":"Perbaikan drop"}"""))

    /** Bawa work order ke IN_PROGRESS: buat → tugaskan → mulai. */
    private fun startedWorkOrder(token: String): String {
        val woId = newWorkOrder(token)
        val techId = newTechnician(token)
        post("/api/work-orders/$woId/assign", token, """{"technicianIds":["$techId"]}""", 200)
        post("/api/work-orders/$woId/start", token, "", 200)
        return woId
    }

    private fun png(name: String = "foto.png") =
        MockMultipartFile("file", name, MediaType.IMAGE_PNG_VALUE, byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 1, 2, 3, 4, 5))

    @Test
    fun `foto bukti diunggah, didaftar, diunduh, lalu dihapus`() {
        val token = newTenantAdmin("wo")
        val woId = startedWorkOrder(token)

        val uploaded = mockMvc.perform(
            multipart("/api/work-orders/$woId/evidence").file(png())
                .param("kind", "FAT").param("caption", "FAT terpasang")
                .header("Authorization", "Bearer $token"),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        assertThat(JsonPath.read<String>(uploaded, "$.kind")).isEqualTo("FAT")
        assertThat(JsonPath.read<String>(uploaded, "$.caption")).isEqualTo("FAT terpasang")
        assertThat(JsonPath.read<Int>(uploaded, "$.sizeBytes")).isEqualTo(13)
        assertThat(JsonPath.read<String>(uploaded, "$.uploadedByName")).isEqualTo("Admin")
        val evidenceId = evidenceRevisionId(uploaded)

        val proof = get("/api/work-orders/$woId/proof-of-work", token)
        assertThat(JsonPath.read<String>(proof, "$.revision")).hasSize(64)
        assertThat(JsonPath.read<List<String>>(proof, "$.artifacts[*].revisionId")).containsExactly(evidenceId)
        assertThat(JsonPath.read<List<String>>(proof, "$.artifacts[*].kind")).containsExactly("FAT")

        // Terdaftar di galeri work order.
        val list = get("/api/work-orders/$woId/evidence", token)
        assertThat(JsonPath.read<List<Any>>(list, "$[*]")).hasSize(1)

        // Byte di-proxy balik apa adanya lewat endpoint content.
        val content = mockMvc.perform(
            get("/api/work-orders/$woId/evidence/$evidenceId/content").header("Authorization", "Bearer $token"),
        ).andExpect(status().isOk).andReturn().response
        assertThat(content.contentType).startsWith(MediaType.IMAGE_PNG_VALUE)
        assertThat(content.contentAsByteArray).containsExactly(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 1, 2, 3, 4, 5)

        // Dihapus → hilang dari galeri.
        mockMvc.perform(
            delete("/api/work-orders/$woId/evidence/$evidenceId").header("Authorization", "Bearer $token"),
        ).andExpect(status().isNoContent)
        assertThat(JsonPath.read<List<Any>>(get("/api/work-orders/$woId/evidence", token), "$[*]")).isEmpty()
    }

    @Test
    fun `retention purge hides photo and signature from list proof and download APIs`() {
        val (token, tenantId) = newTenantSession("retention-api")
        val woId = startedWorkOrder(token)
        val uploaded = mockMvc.perform(
            multipart("/api/work-orders/$woId/evidence").file(png())
                .param("kind", "FAT").header("Authorization", "Bearer $token"),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val evidenceId = evidenceRevisionId(uploaded)
        mockMvc.perform(
            multipart(HttpMethod.PUT, "/api/work-orders/$woId/signature").file(png("ttd.png"))
                .param("signerName", "Customer").header("Authorization", "Bearer $token"),
        ).andExpect(status().isOk)

        TenantContext.runAs(tenantId) { retentionWorker.purge(Instant.now().plusSeconds(1)) }

        assertThat(JsonPath.read<List<Any>>(get("/api/work-orders/$woId/evidence", token), "$[*]")).isEmpty()
        assertThat(JsonPath.read<List<Any>>(get("/api/work-orders/$woId/proof-of-work", token), "$.artifacts[*]")).isEmpty()
        mockMvc.perform(
            get("/api/work-orders/$woId/evidence/$evidenceId/content").header("Authorization", "Bearer $token"),
        ).andExpect(status().isNotFound)
        mockMvc.perform(get("/api/work-orders/$woId/signature").header("Authorization", "Bearer $token"))
            .andExpect(status().isNoContent)
        mockMvc.perform(get("/api/work-orders/$woId/signature/content").header("Authorization", "Bearer $token"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `hidden photo metadata cannot be downloaded while bytes remain`() {
        val (token, tenantId) = newTenantSession("hidden-photo")
        val woId = startedWorkOrder(token)
        val first = mockMvc.perform(
            multipart("/api/work-orders/$woId/evidence").file(png())
                .param("kind", "FAT").header("Authorization", "Bearer $token"),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val tombstonedId = evidenceRevisionId(first)
        TenantContext.runAs(tenantId) {
            evidenceJpa.findById(UUID.fromString(tombstonedId)).orElseThrow().apply {
                revisionState = EvidenceRevisionState.TOMBSTONED
            }.let(evidenceJpa::save)
        }
        mockMvc.perform(
            get("/api/work-orders/$woId/evidence/$tombstonedId/content").header("Authorization", "Bearer $token"),
        ).andExpect(status().isNotFound)

        val second = mockMvc.perform(
            multipart("/api/work-orders/$woId/evidence").file(png("claimed.png"))
                .param("kind", "FAT").header("Authorization", "Bearer $token"),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val claimedId = evidenceRevisionId(second)
        TenantContext.runAs(tenantId) {
            evidenceJpa.findById(UUID.fromString(claimedId)).orElseThrow().apply {
                purgeState = "CLAIMED"
                purgeClaimId = UUID.randomUUID()
            }.let(evidenceJpa::save)
        }
        mockMvc.perform(
            get("/api/work-orders/$woId/evidence/$claimedId/content").header("Authorization", "Bearer $token"),
        ).andExpect(status().isNotFound)
        assertThat(JsonPath.read<List<Any>>(get("/api/work-orders/$woId/evidence", token), "$[*]")).isEmpty()
        assertThat(JsonPath.read<List<Any>>(get("/api/work-orders/$woId/proof-of-work", token), "$.artifacts[*]")).isEmpty()
    }

    @Test
    fun `foto pada work order draft ditolak`() {
        val token = newTenantAdmin("wo")
        val woId = newWorkOrder(token) // masih DRAFT
        mockMvc.perform(
            multipart("/api/work-orders/$woId/evidence").file(png())
                .header("Authorization", "Bearer $token"),
        ).andExpect(status().isConflict)
    }

    @Test
    fun `berkas non-gambar ditolak`() {
        val token = newTenantAdmin("wo")
        val woId = startedWorkOrder(token)
        val notImage = MockMultipartFile("file", "catatan.txt", MediaType.TEXT_PLAIN_VALUE, "halo".toByteArray())
        mockMvc.perform(
            multipart("/api/work-orders/$woId/evidence").file(notImage)
                .header("Authorization", "Bearer $token"),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `tanda tangan direkam, diganti, diunduh, lalu dihapus`() {
        val token = newTenantAdmin("wo")
        val woId = startedWorkOrder(token)

        // Belum ada tanda tangan → 204.
        mockMvc.perform(get("/api/work-orders/$woId/signature").header("Authorization", "Bearer $token"))
            .andExpect(status().isNoContent)

        val signed = mockMvc.perform(
            multipart(HttpMethod.PUT, "/api/work-orders/$woId/signature").file(png("ttd.png"))
                .param("signerName", "Pak RT").header("Authorization", "Bearer $token"),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        assertThat(JsonPath.read<String>(signed, "$.signerName")).isEqualTo("Pak RT")

        val fetched = get("/api/work-orders/$woId/signature", token)
        assertThat(JsonPath.read<String>(fetched, "$.signerName")).isEqualTo("Pak RT")

        // Konten byte di-proxy balik.
        val content = mockMvc.perform(
            get("/api/work-orders/$woId/signature/content").header("Authorization", "Bearer $token"),
        ).andExpect(status().isOk).andReturn().response
        assertThat(content.contentAsByteArray).containsExactly(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 1, 2, 3, 4, 5)

        // Satu tanda tangan per work order: PUT ulang menggantikan yang lama.
        mockMvc.perform(
            multipart(HttpMethod.PUT, "/api/work-orders/$woId/signature").file(png("ttd2.png"))
                .param("signerName", "Bu Lurah").param("correctionReason", "Tanda tangan diperbarui")
                .header("Authorization", "Bearer $token"),
        ).andExpect(status().isOk)
        assertThat(JsonPath.read<String>(get("/api/work-orders/$woId/signature", token), "$.signerName"))
            .isEqualTo("Bu Lurah")

        // Dihapus → kembali 204.
        mockMvc.perform(delete("/api/work-orders/$woId/signature").header("Authorization", "Bearer $token"))
            .andExpect(status().isNoContent)
        mockMvc.perform(get("/api/work-orders/$woId/signature").header("Authorization", "Bearer $token"))
            .andExpect(status().isNoContent)
    }

    @Test
    fun `tanda tangan sebelum pekerjaan berlangsung ditolak`() {
        val token = newTenantAdmin("wo")

        // DRAFT ditolak.
        val draft = newWorkOrder(token)
        mockMvc.perform(
            multipart(HttpMethod.PUT, "/api/work-orders/$draft/signature").file(png("ttd.png"))
                .param("signerName", "X").header("Authorization", "Bearer $token"),
        ).andExpect(status().isConflict)

        // ASSIGNED (belum mulai) juga ditolak.
        val assigned = newWorkOrder(token)
        val techId = newTechnician(token)
        post("/api/work-orders/$assigned/assign", token, """{"technicianIds":["$techId"]}""", 200)
        mockMvc.perform(
            multipart(HttpMethod.PUT, "/api/work-orders/$assigned/signature").file(png("ttd.png"))
                .param("signerName", "X").header("Authorization", "Bearer $token"),
        ).andExpect(status().isConflict)
    }

    @Test
    fun `teknisi hanya dapat membaca bukti work order yang ditugaskan`() {
        val admin = newTenantAdmin("wo")
        val roles = get("/api/roles", admin)
        val names = JsonPath.read<List<String>>(roles, "$[*].name")
        val ids = JsonPath.read<List<String>>(roles, "$[*].id")
        val technicianRole = ids[names.indexOf("Teknisi")]
        val oneEmail = "one-${uniq()}@x.test"
        val twoEmail = "two-${uniq()}@x.test"
        val one = id(post("/api/users", admin, """{"email":"$oneEmail","name":"Satu","password":"$pass","roleIds":["$technicianRole"]}"""))
        val two = id(post("/api/users", admin, """{"email":"$twoEmail","name":"Dua","password":"$pass","roleIds":["$technicianRole"]}"""))
        val oneToken = login(admin, oneEmail)
        val twoToken = login(admin, twoEmail)
        val woId = id(post("/api/work-orders", admin, """{"type":"REPAIR","title":"Bukti"}"""))
        post("/api/work-orders/$woId/assign", admin, """{"technicianIds":["$one"]}""", 200)
        post("/api/work-orders/$woId/start", admin, "", 200)
        val uploaded = mockMvc.perform(
            multipart("/api/work-orders/$woId/evidence").file(png()).header("Authorization", "Bearer $admin"),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val evidenceId = evidenceRevisionId(uploaded)

        get("/api/work-orders/$woId/evidence", oneToken, 200)
        get("/api/work-orders/$woId/evidence", twoToken, 404)
        get("/api/work-orders/$woId/evidence/$evidenceId/content", oneToken, 200)
        get("/api/work-orders/$woId/evidence/$evidenceId/content", twoToken, 404)
    }
}
