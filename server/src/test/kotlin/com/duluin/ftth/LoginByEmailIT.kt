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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * Uji login TANPA slug tenant (#3: "1 email = 1 tenant"). Server me-resolve tenant dari
 * email lewat `user_directory` (indeks pre-auth di luar RLS) sebelum tenant context ada.
 *
 * Invarian:
 * 1. Email → tenant yang benar walau ada dua tenant; body login cuma email+password.
 * 2. `user_directory` dipelihara di chokepoint UserRepository.save → user yang dibuat
 *    LEWAT /api/users (bukan hanya admin onboarding) juga bisa login by email saja.
 * 3. Email tak dikenal / password salah → 401 (pesan disamakan, tak membocorkan email).
 * 4. Body lama yang MASIH mengirim tenantSlug tetap diterima (properti asing diabaikan)
 *    supaya suite lama tak perlu diubah.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoginByEmailIT {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var onboarding: OnboardTenantUseCase

    private val pass = "secret12345"
    private fun uniq() = UUID.randomUUID().toString().substring(0, 8)

    /** Login hanya dengan email+password (TANPA tenantSlug). */
    private fun loginByEmail(email: String, password: String = pass, expected: Int = 200): String =
        mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"$password"}"""),
        ).andExpect { assertThat(it.response.status).isEqualTo(expected) }
            .andReturn().response.contentAsString

    private fun me(token: String): String =
        mockMvc.perform(get("/api/me").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsString

    @Test
    fun `email me-resolve ke tenant yang benar tanpa slug walau ada dua tenant`() {
        val a = "lba${uniq()}"
        val b = "lbb${uniq()}"
        val adminA = "admin@$a.test"
        val adminB = "admin@$b.test"
        onboarding.onboard(OnboardTenantCommand(a, "Tenant A", adminA, "Admin A", pass))
        onboarding.onboard(OnboardTenantCommand(b, "Tenant B", adminB, "Admin B", pass))

        val meA = me(JsonPath.read(loginByEmail(adminA), "$.accessToken"))
        assertThat(JsonPath.read<String>(meA, "$.tenantSlug")).isEqualTo(a)
        assertThat(JsonPath.read<String>(meA, "$.email")).isEqualTo(adminA)

        val meB = me(JsonPath.read(loginByEmail(adminB), "$.accessToken"))
        assertThat(JsonPath.read<String>(meB, "$.tenantSlug")).isEqualTo(b)
    }

    @Test
    fun `user yang dibuat lewat api-users juga bisa login by email saja`() {
        val slug = "lbu${uniq()}"
        val admin = "admin@$slug.test"
        onboarding.onboard(OnboardTenantCommand(slug, "Dir Co", admin, "Admin", pass))
        val adminToken: String = JsonPath.read(loginByEmail(admin), "$.accessToken")

        val staffEmail = "staff@$slug.test"
        mockMvc.perform(
            post("/api/users").header("Authorization", "Bearer $adminToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$staffEmail","name":"Staff","password":"$pass","roleIds":[]}"""),
        ).andExpect(status().isCreated)

        // Login staff TANPA slug → direktori sudah terisi saat user dibuat.
        val meStaff = me(JsonPath.read(loginByEmail(staffEmail), "$.accessToken"))
        assertThat(JsonPath.read<String>(meStaff, "$.tenantSlug")).isEqualTo(slug)
        assertThat(JsonPath.read<String>(meStaff, "$.email")).isEqualTo(staffEmail)
    }

    @Test
    fun `email tak dikenal atau password salah ditolak 401`() {
        val slug = "lbx${uniq()}"
        val admin = "admin@$slug.test"
        onboarding.onboard(OnboardTenantCommand(slug, "X Co", admin, "Admin", pass))

        loginByEmail("nobody-${uniq()}@nowhere.test", expected = 401)
        loginByEmail(admin, password = "salahsalah", expected = 401)
    }

    @Test
    fun `body lama yang masih mengirim tenantSlug tetap diterima`() {
        val slug = "lbl${uniq()}"
        val admin = "admin@$slug.test"
        onboarding.onboard(OnboardTenantCommand(slug, "Legacy Co", admin, "Admin", pass))

        // tenantSlug kini properti asing → diabaikan, login tetap sukses lewat email.
        val json = mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"tenantSlug":"$slug","email":"$admin","password":"$pass"}"""),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        assertThat(JsonPath.read<String>(json, "$.accessToken")).isNotBlank()
    }
}
