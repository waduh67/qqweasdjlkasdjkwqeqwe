package com.duluin.ftth.tenancy

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
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Uji ekspor data tenant lewat stack HTTP nyata terhadap Postgres lokal (`ftth_test`, role
 * NOSUPERUSER/NOBYPASSRLS → RLS benar-benar aktif).
 *
 * Yang diuji bukan "endpoint membalas 200", melainkan tiga janji yang kalau ingkar tak
 * menimbulkan galat apa pun: arsip berisi data tenant sendiri (RLS terpasang di koneksi yang
 * benar), TIDAK berisi data tenant lain, dan TIDAK berisi rahasia. Dua tenant sengaja dibuat
 * berdampingan supaya kebocoran lintas-tenant punya kesempatan muncul.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TenantDataExportIT {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var onboarding: OnboardTenantUseCase

    private val pass = "secret12345"

    private fun uniq() = UUID.randomUUID().toString().substring(0, 8)

    private fun login(slug: String, email: String): String {
        val body = """{"tenantSlug":"$slug","email":"$email","password":"$pass"}"""
        val json = mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return JsonPath.read(json, "$.accessToken")
    }

    /** Seluruh entry arsip sebagai teks — arsip uji kecil, jadi cukup dibaca ke memori. */
    private fun unzip(bytes: ByteArray): Map<String, String> {
        val entries = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return entries
    }

    @Test
    fun `arsip memuat data tenant sendiri, tanpa rahasia dan tanpa sebaris pun milik tenant lain`() {
        val mineSlug = "exp${uniq()}"
        val otherSlug = "oth${uniq()}"
        val myAdmin = "admin@$mineSlug.test"
        val otherAdmin = "admin@$otherSlug.test"
        onboarding.onboard(OnboardTenantCommand(mineSlug, "Mine ISP", myAdmin, "Admin Mine", pass))
        onboarding.onboard(OnboardTenantCommand(otherSlug, "Other ISP", otherAdmin, "Admin Other", pass))

        val token = login(mineSlug, myAdmin)
        val archive = mockMvc.perform(get("/api/tenant/export").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
            .andReturn().response.contentAsByteArray

        val entries = unzip(archive)

        assertThat(entries).containsKey("BACA-DULU.txt")
        assertThat(entries.keys.filter { it.startsWith("data/") })
            .`as`("arsip harus memuat CSV per tabel").isNotEmpty()

        val users = entries["data/app_user.csv"]
        assertThat(users).`as`("pengguna tenant wajib ikut terekspor").isNotNull()
        assertThat(users).contains(myAdmin)
        assertThat(users)
            .`as`("RLS harus menahan baris tenant lain, bukan sekadar tak ditampilkan")
            .doesNotContain(otherAdmin)
        assertThat(users)
            .`as`("hash kata sandi tak boleh keluar dalam bentuk apa pun")
            .doesNotContain("\$2a\$").contains("[disunting]")

        // Audit ditulis sebelum arsip dibaca — jejaknya bahkan ikut di dalam arsip itu sendiri.
        assertThat(entries["data/audit_log.csv"])
            .`as`("pengunduhan seluruh data wajib meninggalkan jejak audit")
            .contains("tenant.data.exported")

        // Pengecualian yang dibuat harus bisa dibaca penerimanya, bukan diam-diam.
        assertThat(entries).doesNotContainKey("data/accounting_record.csv")
        assertThat(entries["BACA-DULU.txt"]).contains("accounting_record", "TIDAK diekspor")
    }
}
