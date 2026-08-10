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
import java.util.UUID

/**
 * Uji helpdesk ujung-ke-ujung: pelanggan melapor dari PORTAL, operator menangani dari
 * KONSOL, keluhannya dieskalasi jadi work order, lalu statusnya kembali terbaca pelanggan.
 *
 * Yang dijaga:
 * 1. Satu utas, dua pintu — apa yang ditulis operator terbaca pelanggan (dan sebaliknya),
 *    tapi nama staf tak pernah sampai ke pelanggan.
 * 2. Eskalasi benar-benar menerbitkan work order REPAIR untuk pelanggan yang sama.
 * 3. Tiket pelanggan lain dijawab 404 — bukan 403 — supaya keberadaannya pun tak bocor.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HelpdeskIT {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var onboarding: OnboardTenantUseCase

    private val pass = "secret12345"
    private val portalPass = "portal12345"
    private fun uniq() = UUID.randomUUID().toString().substring(0, 8)

    private data class Tenant(val slug: String, val token: String)

    private fun newTenantAdmin(prefix: String): Tenant {
        val slug = "$prefix${uniq()}"
        val admin = "admin@$slug.test"
        onboarding.onboard(OnboardTenantCommand(slug, "Tenant $slug", admin, "Admin", pass))
        val json = mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$admin","password":"$pass"}"""),
        ).andExpect { assertThat(it.response.status).isEqualTo(200) }.andReturn().response.contentAsString
        return Tenant(slug, JsonPath.read(json, "$.accessToken"))
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

    /** Daftarkan pelanggan lalu beri kredensial portal, kembalikan (customerId, token portal). */
    private fun newPortalCustomer(tenant: Tenant, name: String): Pair<String, String> {
        val s = uniq().uppercase()
        val customerId: String = JsonPath.read(
            post(
                "/api/customers", tenant.token,
                """{"code":"C-$s","name":"$name","address":"Jl. Uji","location":{"longitude":106.9,"latitude":-6.2}}""",
            ),
            "$.id",
        )
        val login = "pelanggan-$s".lowercase()
        post(
            "/api/portal-admin/customers/$customerId/credential", tenant.token,
            """{"login":"$login","password":"$portalPass"}""", expected = 200,
        )
        val loginJson = mockMvc.perform(
            post("/api/portal/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"identifier":"$login","password":"$portalPass","tenant":"${tenant.slug}"}"""),
        ).andExpect { assertThat(it.response.status).isEqualTo(200) }.andReturn().response.contentAsString
        return customerId to JsonPath.read(loginJson, "$.tokens.accessToken")
    }

    private fun submitTicket(portalToken: String, subject: String = "Internet mati sejak pagi"): String =
        post(
            "/api/portal/me/tickets", portalToken,
            """{"category":"KONEKSI_PUTUS","subject":"$subject","description":"Lampu LOS merah, modem sudah direstart."}""",
        )

    @Test
    fun `pelanggan lapor dari portal, operator membalas dan mengeskalasi jadi work order`() {
        val tenant = newTenantAdmin("hd")
        val (customerId, portalToken) = newPortalCustomer(tenant, "Budi")

        // 1. Pelanggan melapor. Tiket lahir terbuka dan langsung punya kode.
        val created = submitTicket(portalToken)
        val ticketId = JsonPath.read<String>(created, "$.ticket.id")
        assertThat(JsonPath.read<String>(created, "$.ticket.status")).isEqualTo("OPEN")
        assertThat(JsonPath.read<String>(created, "$.ticket.code")).startsWith("TKT-")

        // 2. Tiketnya muncul di antrean operator, lengkap dengan nama pelanggannya.
        val queue = get("/api/helpdesk/tickets", tenant.token)
        assertThat(JsonPath.read<List<Any>>(queue, "$.content[*]")).hasSize(1)
        assertThat(JsonPath.read<String>(queue, "$.content[0].customerName")).isEqualTo("Budi")
        assertThat(JsonPath.read<Int>(get("/api/helpdesk/tickets/summary", tenant.token), "$.open")).isEqualTo(1)

        // 3. Operator membalas → tiket berpindah ke "sedang ditangani".
        val replied = post(
            "/api/helpdesk/tickets/$ticketId/replies", tenant.token,
            """{"body":"Kami cek dulu dari sisi jaringan ya, Pak."}""", expected = 200,
        )
        assertThat(JsonPath.read<String>(replied, "$.ticket.status")).isEqualTo("IN_PROGRESS")

        // 4. Pelanggan membaca balasannya — tanpa pernah tahu nama stafnya.
        val seenByCustomer = get("/api/portal/me/tickets/$ticketId", portalToken)
        assertThat(JsonPath.read<List<String>>(seenByCustomer, "$.messages[*].body"))
            .containsExactly("Kami cek dulu dari sisi jaringan ya, Pak.")
        assertThat(JsonPath.read<List<String>>(seenByCustomer, "$.messages[*].authorName"))
            .containsExactly("Tim dukungan")
        assertThat(JsonPath.read<List<String>>(seenByCustomer, "$.messages[*].authorName"))
            .doesNotContain("Admin")

        // 5. Eskalasi: keluhan butuh kunjungan → terbit work order REPAIR untuk pelanggan itu.
        val escalated = post(
            "/api/helpdesk/tickets/$ticketId/escalate", tenant.token,
            """{"priority":"HIGH"}""", expected = 200,
        )
        val woCode = JsonPath.read<String>(escalated, "$.ticket.workOrderCode")
        assertThat(woCode).startsWith("WO-")
        val orders = get("/api/work-orders?customerId=$customerId", tenant.token)
        assertThat(JsonPath.read<List<String>>(orders, "$.content[*].type")).containsExactly("REPAIR")
        assertThat(JsonPath.read<List<String>>(orders, "$.content[*].code")).containsExactly(woCode)
        assertThat(JsonPath.read<String>(orders, "$.content[0].title")).contains("Internet mati sejak pagi")

        // Pelanggan pun melihat keluhannya sudah dijadwalkan.
        assertThat(JsonPath.read<String>(get("/api/portal/me/tickets", portalToken), "$[0].workOrderCode"))
            .isEqualTo(woCode)
    }

    @Test
    fun `tiket yang dinyatakan selesai terbuka lagi kalau pelanggan membantah, lalu bisa ditutup sendiri`() {
        val tenant = newTenantAdmin("hdr")
        val (_, portalToken) = newPortalCustomer(tenant, "Sari")
        val ticketId = JsonPath.read<String>(submitTicket(portalToken), "$.ticket.id")

        post(
            "/api/helpdesk/tickets/$ticketId/status", tenant.token,
            """{"status":"RESOLVED"}""", expected = 200,
        )

        // Pelanggan membantah → tiket hidup kembali di antrean operator.
        val reopened = post(
            "/api/portal/me/tickets/$ticketId/replies", portalToken,
            """{"body":"Masih mati juga, Pak."}""", expected = 200,
        )
        assertThat(JsonPath.read<String>(reopened, "$.ticket.status")).isEqualTo("OPEN")

        // Setelah benar-benar beres, pelanggan menutup sendiri; utasnya berhenti menerima balasan.
        val closed = post("/api/portal/me/tickets/$ticketId/close", portalToken, "{}", expected = 200)
        assertThat(JsonPath.read<String>(closed, "$.ticket.status")).isEqualTo("CLOSED")
        post(
            "/api/portal/me/tickets/$ticketId/replies", portalToken,
            """{"body":"halo?"}""", expected = 409,
        )
    }

    @Test
    fun `laporan pelanggan lain dijawab tidak ditemukan, bukan tidak berhak`() {
        val tenant = newTenantAdmin("hdx")
        val (_, budi) = newPortalCustomer(tenant, "Budi")
        val (_, sari) = newPortalCustomer(tenant, "Sari")
        val milikBudi = JsonPath.read<String>(submitTicket(budi), "$.ticket.id")

        get("/api/portal/me/tickets/$milikBudi", sari, expected = 404)
        // Daftar Sari pun kosong: tiket tetangga tak pernah bocor lewat pintu mana pun.
        assertThat(JsonPath.read<List<Any>>(get("/api/portal/me/tickets", sari), "$[*]")).isEmpty()
    }
}
