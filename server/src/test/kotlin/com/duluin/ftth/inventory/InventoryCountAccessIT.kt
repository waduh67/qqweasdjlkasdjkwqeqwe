package com.duluin.ftth.inventory

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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.util.UUID

/**
 * Siapa yang boleh MEMBACA daftar stock opname terbuka.
 *
 * Sebelum tes ini, `GET /api/inventory/counts/open` dijaga `inventory.count.perform`. Artinya
 * orang yang tugasnya MENYETUJUI selisih opname — pemegang `inventory.count.approve` — kena 403
 * di daftar yang isinya persis pekerjaannya, dan tidak punya satu pun jalan menemukan id yang
 * harus ia sahkan lewat `POST /counts/{id}/approval`. Kontrol empat-mata yang mati diam-diam:
 * dari layar ia terlihat "tidak ada opname yang perlu disetujui", bukan "kamu tidak boleh lihat".
 *
 * Tes ini menjaga BATASNYA dari dua sisi: yang tadinya tertutup harus terbuka, dan yang memang
 * tidak berkepentingan harus tetap tertutup.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InventoryCountAccessIT {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var onboarding: OnboardTenantUseCase

    private val pass = "secret12345"

    private fun uniq() = UUID.randomUUID().toString().replace("-", "").substring(0, 8)

    private fun login(slug: String, email: String): String {
        val json = mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"tenantSlug":"$slug","email":"$email","password":"$pass"}"""),
        ).andReturn().response.contentAsString
        return JsonPath.read(json, "$.accessToken")
    }

    private fun getStatus(url: String, token: String): Int =
        mockMvc.perform(get(url).header("Authorization", "Bearer $token")).andReturn().response.status

    /**
     * Satu tenant dengan satu peran rakitan tangan berisi PERSIS izin yang diminta.
     *
     * Sengaja peran baru, bukan "Tenant Admin" yang dikurangi: peran bawaan itu disetel ulang
     * ke seluruh katalog setiap kali izin baru ditambahkan, jadi menguranginya di sini akan
     * terhapus sendiri dan tesnya lulus karena alasan yang salah.
     */
    private fun tokenWithPermissions(prefix: String, vararg codes: String): String {
        val slug = "$prefix${uniq()}"
        val admin = "admin@$slug.test"
        onboarding.onboard(OnboardTenantCommand(slug, "Tenant $slug", admin, "Admin", pass))
        val adminToken = login(slug, admin)

        val permsJson = mockMvc.perform(
            get("/api/permissions").header("Authorization", "Bearer $adminToken"),
        ).andReturn().response.contentAsString
        val permissionIds = codes.map { code ->
            JsonPath.read<List<String>>(permsJson, "$[?(@.code=='$code')].id").firstOrNull()
                ?: error("Izin $code tidak ada di katalog — seeder-nya belum jalan?")
        }

        val roleJson = mockMvc.perform(
            post("/api/roles").header("Authorization", "Bearer $adminToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Peran ${uniq()}","permissionIds":${permissionIds.joinToString(",", "[", "]") { "\"$it\"" }}}"""),
        ).andReturn().response.contentAsString
        val roleId: String = JsonPath.read(roleJson, "$.id")

        val email = "petugas-${uniq()}@$slug.test"
        mockMvc.perform(
            post("/api/users").header("Authorization", "Bearer $adminToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","name":"Petugas","password":"$pass","roleIds":["$roleId"]}"""),
        ).andReturn().response.let { assertThat(it.status).describedAs(it.contentAsString).isEqualTo(201) }

        return login(slug, email)
    }

    @Test
    fun `penyetuju opname bisa membuka daftar yang harus ia setujui`() {
        val token = tokenWithPermissions("cntap", "inventory.count.approve")

        assertThat(getStatus("/api/inventory/counts/open", token)).isEqualTo(200)
    }

    @Test
    fun `izin baca yang baru cukup dengan sendirinya`() {
        val token = tokenWithPermissions("cntvw", "inventory.count.view")

        assertThat(getStatus("/api/inventory/counts/open", token)).isEqualTo(200)
    }

    /**
     * Jembatan ke peran lama. Izin di repo ini di-seed dari kode dan hanya peran sistem
     * "Tenant Admin" yang ikut diperbarui otomatis; peran rakitan tangan TIDAK di-backfill.
     * Kalau `inventory.count.perform` berhenti membuka daftar ini, petugas opname yang hari ini
     * bekerja normal mendadak buta — regresi yang lebih buruk daripada celah yang diperbaiki.
     */
    @Test
    fun `petugas opname lama tidak kehilangan aksesnya`() {
        val token = tokenWithPermissions("cntpf", "inventory.count.perform")

        assertThat(getStatus("/api/inventory/counts/open", token)).isEqualTo(200)
    }

    /**
     * Penjaganya masih menjaga. Tanpa tes ini, `canAny` yang kelak kebobolan satu izin terlalu
     * longgar tidak akan menggagalkan apa pun — daftar opname memuat nama pemegang barang dan
     * selisih yang belum disahkan, bukan data yang boleh dibaca siapa saja yang login.
     */
    @Test
    fun `izin gudang lain tidak membuka daftar opname`() {
        val token = tokenWithPermissions("cntno", "inventory.item.view")

        assertThat(getStatus("/api/inventory/counts/open", token)).isEqualTo(403)
    }
}
