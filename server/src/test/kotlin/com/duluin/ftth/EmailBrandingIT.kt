package com.duluin.ftth

import com.duluin.ftth.iam.application.port.inbound.OnboardTenantCommand
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantUseCase
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * Uji setelan email platform & timpaan tenant lewat HTTP: SMTP + tampilan bawaan disetel
 * admin platform, tenant menimpa yang boleh ditimpanya, dan logo disajikan endpoint publik
 * yang dibaca klien email tanpa auth.
 *
 * Yang ditegakkan di sini adalah janji-janji yang paling mudah patah tanpa disadari:
 *
 *  - password SMTP write-only — dikirim saat menyimpan, tak pernah kembali di respons, dan
 *    tak hilang saat operator menyunting footer;
 *  - kolom kosong tenant MEWARISI platform, bukan mengosongkan;
 *  - logo tenant yang dicabut kembali menyajikan logo platform, bukan 404 — surat yang
 *    terlanjur terkirim menyimpan URL bertenant selamanya;
 *  - relay & tampilan bawaan adalah wewenang platform: tenant yang mengintipnya ditolak 403.
 *
 * Byte logo disimpan [InMemoryObjectStorage] (profil test), jadi tak perlu MinIO/S3.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EmailBrandingIT {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var onboarding: OnboardTenantUseCase

    private val pass = "secret12345"
    private fun uniq() = UUID.randomUUID().toString().substring(0, 8)

    private fun login(slug: String, email: String, password: String = pass): String {
        val json = mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"tenantSlug":"$slug","email":"$email","password":"$password"}"""),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        return JsonPath.read(json, "$.accessToken")
    }

    /** Admin platform — satu-satunya yang boleh menyetel relay & tampilan bawaan. */
    private fun platformToken(): String = login("platform", "root@ftth.local", "rootadmin123")

    private data class Tenant(val id: UUID, val name: String, val token: String)

    private fun newTenant(prefix: String): Tenant {
        val slug = "$prefix${uniq()}"
        val admin = "admin@$slug.test"
        val name = "Tenant $slug"
        val result = onboarding.onboard(OnboardTenantCommand(slug, name, admin, "Admin", pass))
        return Tenant(result.tenant.id, name, login(slug, admin))
    }

    private fun get(url: String, token: String): String =
        mockMvc.perform(get(url).header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsString

    private fun putJson(url: String, token: String, body: String, expected: Int = 200): String =
        mockMvc.perform(
            put(url).header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect { assertThat(it.response.status).isEqualTo(expected) }
            .andReturn().response.contentAsString

    private fun postJson(url: String, token: String, body: String, expected: Int = 200): String =
        mockMvc.perform(
            post(url).header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect { assertThat(it.response.status).isEqualTo(expected) }
            .andReturn().response.contentAsString

    private fun uploadLogo(url: String, token: String, bytes: ByteArray, type: String = "image/png"): String =
        mockMvc.perform(
            multipart(url).file(MockMultipartFile("file", "logo.png", type, bytes))
                .header("Authorization", "Bearer $token"),
        ).andExpect(status().isOk).andReturn().response.contentAsString

    private fun deleteLogo(url: String, token: String): String =
        mockMvc.perform(delete(url).header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andReturn().response.contentAsString

    /** Endpoint publik: sengaja TANPA header Authorization — klien email tak punya token. */
    private fun publicLogo(url: String) = mockMvc.perform(get(url)).andReturn().response

    private fun platformBody(
        smtpHost: String? = null,
        smtpPassword: String? = null,
        fromAddress: String = "no-reply@duluin.net",
        fromName: String = "NetOps Console",
        accentColor: String = "#123456",
        footerText: String = "Email otomatis, mohon tak dibalas",
        publicBaseUrl: String = "https://app.duluin.net",
        subjects: String = "{}",
    ): String = """
        {"smtpHost":${smtpHost.jsonOrNull()},"smtpPort":587,"smtpUsername":null,
         "smtpPassword":${smtpPassword.jsonOrNull()},"smtpAuth":true,"smtpStartTls":true,
         "fromAddress":"$fromAddress","fromName":"$fromName","accentColor":"$accentColor",
         "footerText":"$footerText","signatureText":"Salam, Tim Dukungan",
         "publicBaseUrl":"$publicBaseUrl","subjects":$subjects}
    """.trimIndent()

    private fun String?.jsonOrNull(): String = this?.let { "\"$it\"" } ?: "null"

    /** Satu baris subjek dari daftar `subjects`; dibaca utuh supaya nilai null ikut terlihat. */
    private fun subjectRow(json: String, trigger: String): Map<String, Any?> =
        JsonPath.read<List<Map<String, Any?>>>(json, "$.subjects[?(@.trigger=='$trigger')]").single()

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4)
    private val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".toByteArray()

    @Test
    fun `platform menyetel SMTP dan tampilan bawaan, password tak pernah kembali`() {
        val token = platformToken()

        val saved = putJson(
            "/api/platform/email-settings", token,
            platformBody(smtpHost = "smtp.duluin.net", smtpPassword = "rahasia-smtp"),
        )

        assertThat(JsonPath.read<String>(saved, "$.smtpHost")).isEqualTo("smtp.duluin.net")
        assertThat(JsonPath.read<Boolean>(saved, "$.smtpConfigured")).isTrue()
        assertThat(JsonPath.read<Boolean>(saved, "$.smtpPasswordSet")).isTrue()
        // Write-only: yang keluar hanya penandanya, tak pernah nilainya.
        assertThat(saved).doesNotContain("rahasia-smtp")

        // Menyunting footer tanpa menyebut password tak boleh menghapus kredensialnya.
        val edited = putJson(
            "/api/platform/email-settings", token,
            platformBody(smtpHost = "smtp.duluin.net", footerText = "Alamat kantor baru"),
        )
        assertThat(JsonPath.read<Boolean>(edited, "$.smtpPasswordSet")).isTrue()
        assertThat(JsonPath.read<String>(edited, "$.footerText")).isEqualTo("Alamat kantor baru")

        // Host dikosongkan = kembali memakai setelan env, bukan kondisi galat.
        val cleared = putJson("/api/platform/email-settings", token, platformBody(smtpHost = null))
        assertThat(JsonPath.read<Boolean>(cleared, "$.smtpConfigured")).isFalse()
    }

    @Test
    fun `logo platform terunggah lalu tersaji lewat endpoint publik tanpa auth`() {
        val token = platformToken()
        putJson("/api/platform/email-settings", token, platformBody())

        val saved = uploadLogo("/api/platform/email-settings/logo", token, png)

        assertThat(JsonPath.read<Boolean>(saved, "$.logoSet")).isTrue()
        // URL dirangkai dari base URL yang tersimpan — klien email tak mengerti path relatif.
        assertThat(JsonPath.read<String>(saved, "$.logoUrl"))
            .isEqualTo("https://app.duluin.net/api/public/email-logo")

        val response = publicLogo("/api/public/email-logo")
        assertThat(response.status).isEqualTo(200)
        assertThat(response.contentAsByteArray).isEqualTo(png)
        assertThat(response.contentType).startsWith("image/png")
        // Tanpa cache, satu siaran ke sepuluh ribu pelanggan berarti sepuluh ribu unduhan sama.
        assertThat(response.getHeader("Cache-Control")).contains("max-age=86400").contains("public")

        // Logo dilepas → penyaji publik 404, dan email berjalan tanpa logo (bukan gagal).
        val removed = deleteLogo("/api/platform/email-settings/logo", token)
        assertThat(JsonPath.read<Boolean>(removed, "$.logoSet")).isFalse()
        assertThat(JsonPath.read<String?>(removed, "$.logoUrl")).isNull()
        assertThat(publicLogo("/api/public/email-logo").status).isEqualTo(404)
    }

    @Test
    fun `logo tenant menimpa logo platform lalu kembali ke bawaan saat dicabut`() {
        val platform = platformToken()
        putJson("/api/platform/email-settings", platform, platformBody())
        uploadLogo("/api/platform/email-settings/logo", platform, png)
        val tenant = newTenant("mail")

        // Belum menimpa: alamat bertenant pun menyajikan logo platform.
        assertThat(publicLogo("/api/public/email-logo/${tenant.id}").contentAsByteArray).isEqualTo(png)

        val overridden = uploadLogo("/api/notifications/email-settings/logo", tenant.token, svg, "image/svg+xml")
        assertThat(JsonPath.read<Boolean>(overridden, "$.logoSet")).isTrue()
        assertThat(JsonPath.read<String>(overridden, "$.effectiveLogoUrl"))
            .isEqualTo("https://app.duluin.net/api/public/email-logo/${tenant.id}")

        val tenantLogo = publicLogo("/api/public/email-logo/${tenant.id}")
        assertThat(tenantLogo.contentAsByteArray).isEqualTo(svg)
        assertThat(tenantLogo.contentType).startsWith("image/svg+xml")
        // Logo platform tak ikut tergantikan — timpaan berlaku untuk tenant itu saja.
        assertThat(publicLogo("/api/public/email-logo").contentAsByteArray).isEqualTo(png)

        val restored = deleteLogo("/api/notifications/email-settings/logo", tenant.token)
        assertThat(JsonPath.read<Boolean>(restored, "$.logoSet")).isFalse()
        // Surat yang terlanjur terkirim menyimpan URL bertenant selamanya: ia harus tetap
        // menyajikan gambar, bukan berlubang setelah tenant menekan "kembalikan ke bawaan".
        assertThat(publicLogo("/api/public/email-logo/${tenant.id}").contentAsByteArray).isEqualTo(png)

        deleteLogo("/api/platform/email-settings/logo", platform)
    }

    @Test
    fun `kolom tenant yang kosong mewarisi platform dan yang terisi menimpanya`() {
        val platform = platformToken()
        putJson(
            "/api/platform/email-settings", platform,
            platformBody(
                fromName = "NetOps Console",
                accentColor = "#123456",
                subjects = """{"INVOICE_DUE_SOON":"Tagihan dari penyedia layanan"}""",
            ),
        )
        val tenant = newTenant("mailinherit")

        val saved = putJson(
            "/api/notifications/email-settings", tenant.token,
            """{"replyToAddress":"billing@sinarjaya.id","fromName":"Sinar Jaya Support","accentColor":"#ff8800",
                "footerText":null,"signatureText":null,
                "subjects":{"INVOICE_DUE_SOON":"Tagihan Sinar Jaya"}}""",
        )

        assertThat(JsonPath.read<String>(saved, "$.replyToAddress")).isEqualTo("billing@sinarjaya.id")
        assertThat(JsonPath.read<String>(saved, "$.accentColor")).isEqualTo("#ff8800")
        // Kolom yang dikosongkan tak menyimpan apa-apa; yang berlaku tetap milik platform.
        assertThat(JsonPath.read<String?>(saved, "$.footerText")).isNull()
        assertThat(JsonPath.read<String>(saved, "$.inheritedFooterText"))
            .isEqualTo("Email otomatis, mohon tak dibalas")
        // Nama warisan = nama ISP-nya sendiri, bukan nama platform. Pelanggan yang menerima
        // tagihan internetnya harus melihat nama yang ia kenal, bukan nama penyedia aplikasi.
        assertThat(JsonPath.read<String>(saved, "$.inheritedFromName")).isEqualTo(tenant.name)
        // Alamat pengirim BUKAN warisan yang bisa ditimpa: walau tenant baru saja mengirim
        // alamatnya sendiri di atas, yang berlaku tetap alamat platform. Relay hanya menerima
        // pengirim terverifikasi, jadi alamat tenant di header From = surat batal berangkat.
        assertThat(JsonPath.read<String>(saved, "$.platformFromAddress")).isEqualTo("no-reply@duluin.net")

        val dueSoon = subjectRow(saved, "INVOICE_DUE_SOON")
        assertThat(dueSoon["subject"]).isEqualTo("Tagihan Sinar Jaya")
        // Warisannya = timpaan platform, bukan konstanta kode — itu yang berlaku bila dikosongkan.
        assertThat(dueSoon["inheritedSubject"]).isEqualTo("Tagihan dari penyedia layanan")

        // Pemicu yang tak disebut siapa pun tetap punya subjek dari konstanta kode.
        val activated = subjectRow(saved, "SUBSCRIPTION_ACTIVATED")
        assertThat(activated["subject"]).isNull()
        assertThat(activated["inheritedSubject"]).isEqualTo("Layanan internet Anda sudah aktif")
    }

    @Test
    fun `pratinjau dan kirim uji melewati jalur yang sama dengan email sungguhan`() {
        val platform = platformToken()
        putJson("/api/platform/email-settings", platform, platformBody(footerText = "PT Duluin Nusantara"))
        val tenant = newTenant("mailtest")
        putJson(
            "/api/notifications/email-settings", tenant.token,
            """{"replyToAddress":null,"fromName":"Sinar Jaya Support","accentColor":"#ff8800",
                "footerText":null,"signatureText":null,"subjects":{}}""",
        )

        val preview = get("/api/notifications/email-settings/preview", tenant.token)
        assertThat(preview).contains("<!DOCTYPE html>")
        assertThat(preview).contains("Sinar Jaya Support")
        assertThat(preview).contains("#ff8800")
        // Footer platform ikut terbawa ke pratinjau tenant yang tak menimpanya.
        assertThat(preview).contains("PT Duluin Nusantara")

        // SMTP tak disetel di lingkungan uji ⇒ mode catat-ke-log, tetap dilaporkan terkirim.
        val result = postJson(
            "/api/notifications/email-settings/test", tenant.token,
            """{"to":"ops@contoh.id"}""",
        )
        assertThat(JsonPath.read<Boolean>(result, "$.delivered")).isTrue()
        assertThat(JsonPath.read<String>(result, "$.detail")).isNotBlank()

        // Alamat tujuan yang jelas salah ditolak sebelum menyentuh transport.
        postJson(
            "/api/notifications/email-settings/test", tenant.token,
            """{"to":"bukan-alamat"}""",
            expected = 400,
        )
    }

    @Test
    fun `setelan email platform tertutup bagi operator tenant`() {
        val tenant = newTenant("mailrbac")

        // Relay dan tampilan bawaan ditanggung bersama semua tenant; menyetelnya bukan
        // wewenang salah satu di antaranya.
        listOf(HttpMethod.GET, HttpMethod.PUT).forEach { method ->
            val request = if (method == HttpMethod.GET) {
                get("/api/platform/email-settings")
            } else {
                put("/api/platform/email-settings").contentType(MediaType.APPLICATION_JSON).content(platformBody())
            }
            val status = mockMvc.perform(request.header("Authorization", "Bearer ${tenant.token}"))
                .andReturn().response.status
            assertThat(status).isEqualTo(403)
        }
    }
}
