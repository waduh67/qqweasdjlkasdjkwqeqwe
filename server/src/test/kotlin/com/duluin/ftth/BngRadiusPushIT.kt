package com.duluin.ftth

import com.duluin.ftth.bng.application.port.outbound.BngActionRepository
import com.duluin.ftth.bng.domain.model.BngAction
import com.duluin.ftth.bng.domain.model.BngActionType
import com.duluin.ftth.common.tenant.TenantContext
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
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * Uji bahwa perubahan yang terlihat berhasil di layar BENAR-BENAR sampai ke RADIUS.
 *
 * Layar akses menyimpan ke DB aplikasi, sedangkan yang diperiksa BRAS saat pelanggan
 * dial adalah baris di radius-db. Bila keduanya tak dijahit, layar mengabarkan sukses
 * sementara pelanggan tetap ditolak — kegagalan diam yang paling menyesatkan justru
 * karena orang yang menekan tombolnya sedang menolong pelanggan yang tak bisa online.
 *
 * Query `bng_action` dibungkus [TenantContext.runAs] + transaksi agar GUC `app.tenant_id`
 * terpasang (RLS) — pola sama [BngProvisionOnActivateIT].
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BngRadiusPushIT {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var onboarding: OnboardTenantUseCase
    @Autowired private lateinit var tenantApi: TenantApi
    @Autowired private lateinit var actions: BngActionRepository
    @Autowired private lateinit var txManager: PlatformTransactionManager

    private val pass = "secret12345"
    private fun uniq() = UUID.randomUUID().toString().substring(0, 8)

    @Test
    fun `ganti password mendorong kredensial baru ke RADIUS`() {
        val slug = "bngr${uniq()}"
        val token = onboard(slug)
        val tenantId = tenantApi.findBySlug(slug)!!.id

        val planId = createPlan(token)
        val nasId = id(post("/api/bng/nas", token, """{"name":"BRAS-${uniq()}","vendor":"MIKROTIK"}"""))
        val sub = activeSubscription(token, planId, code = "C-RESET")

        val username = "pppoe${uniq()}"
        val accessId = id(
            post(
                "/api/bng/access", token,
                """{"subscriptionId":"$sub","username":"$username","secret":"rahasia123","planId":"$planId","nasId":"$nasId"}""",
            ),
        )
        // Pembuatan akun aktif sudah menulis kredensial pertama ke RADIUS.
        assertThat(provisionsFor(tenantId, username)).hasSize(1)

        // Ganti password → kredensial ditulis ULANG, bukan cuma tersimpan di DB aplikasi.
        post("/api/bng/access/$accessId/reset-secret", token, """{"secret":"gantiPass456"}""", expected = 200)
        assertThat(provisionsFor(tenantId, username)).hasSize(2)
    }

    @Test
    fun `ganti password akun PENDING tetap tak menyentuh RADIUS`() {
        val slug = "bngq${uniq()}"
        val token = onboard(slug)
        val tenantId = tenantApi.findBySlug(slug)!!.id

        val planId = createPlan(token)
        val nasId = id(post("/api/bng/nas", token, """{"name":"BRAS-${uniq()}","vendor":"MIKROTIK"}"""))
        // Langganan belum diaktifkan (WO PSB belum ditutup) → akun berdiri PENDING.
        val sub = createCustomerWithPlan(token, code = "C-PENDING", planId = planId)

        val username = "pppoe${uniq()}"
        val accessId = id(
            post(
                "/api/bng/access", token,
                """{"subscriptionId":"$sub","username":"$username","secret":"rahasia123","planId":"$planId","nasId":"$nasId"}""",
            ),
        )
        post("/api/bng/access/$accessId/reset-secret", token, """{"secret":"gantiPass456"}""", expected = 200)

        // Ganti password bukan pintu belakang untuk mengadakan akun yang instalasinya
        // belum selesai — ia baru ditulis saat langganan diaktifkan.
        assertThat(provisioningActions(tenantId)).isEmpty()
    }

    @Test
    fun `pindah BRAS tak mencabut akun dari RADIUS, lepas dari BRAS mencabutnya`() {
        val slug = "bngm${uniq()}"
        val token = onboard(slug)
        val tenantId = tenantApi.findBySlug(slug)!!.id

        val planId = createPlan(token)
        val nasLama = id(post("/api/bng/nas", token, """{"name":"BRAS-Lama-${uniq()}","vendor":"MIKROTIK"}"""))
        val nasBaru = id(post("/api/bng/nas", token, """{"name":"BRAS-Baru-${uniq()}","vendor":"MIKROTIK"}"""))
        val sub = activeSubscription(token, planId, code = "C-MOVE")

        val username = "pppoe${uniq()}"
        val accessId = id(
            post(
                "/api/bng/access", token,
                """{"subscriptionId":"$sub","username":"$username","secret":"rahasia123","planId":"$planId","nasId":"$nasLama"}""",
            ),
        )

        // Pindah BRAS: kredensial ditulis ulang, TAK ada pencabutan. Otorisasi RADIUS tak
        // per-BRAS — mencabut "di BRAS lama" akan menghapus akun yang baru saja ditulis.
        put("/api/bng/access/$accessId", token, """{"planId":"$planId","nasId":"$nasBaru"}""")
        assertThat(provisionsFor(tenantId, username)).hasSize(2)
        assertThat(deprovisionsFor(tenantId, username)).isEmpty()

        // Dilepas dari BRAS (tak dilayani BRAS mana pun) → barulah dicabut dari RADIUS.
        put("/api/bng/access/$accessId", token, """{"planId":"$planId","nasId":null}""")
        assertThat(deprovisionsFor(tenantId, username)).hasSize(1)
    }

    // --- Perkakas HTTP ---

    private fun onboard(slug: String): String {
        val admin = "admin@$slug.test"
        onboarding.onboard(OnboardTenantCommand(slug, "Tenant $slug", admin, "Admin", pass))
        val json = mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"tenantSlug":"$slug","email":"$admin","password":"$pass"}"""),
        ).andReturn().response.contentAsString
        return JsonPath.read(json, "$.accessToken")
    }

    private fun createPlan(token: String): String =
        id(
            post(
                "/api/catalog/plans", token,
                """{"name":"Paket ${uniq()}","description":null,"price":150000,"downMbps":20,"upMbps":10,"serviceTypes":["PPPOE"]}""",
            ),
        )

    /** Pelanggan + langganan PENDING sekali daftar; kembalikan id langganannya. */
    private fun createCustomerWithPlan(token: String, code: String, planId: String): String =
        JsonPath.read(
            post(
                "/api/customers", token,
                """{"code":"$code","name":"Pelanggan ${uniq()}","address":"Jl. Uji",
                    "location":{"longitude":106.99,"latitude":-6.24},"planId":"$planId"}""",
            ),
            "$.subscription.id",
        )

    /** Pelanggan + langganan yang sudah diaktifkan (WO PSB dianggap selesai). */
    private fun activeSubscription(token: String, planId: String, code: String): String {
        val sub = createCustomerWithPlan(token, code, planId)
        post("/api/customers/subscriptions/$sub/activate", token, "", expected = 200)
        return sub
    }

    /** Aksi jalur-data RADIUS (PROVISION/DEPROVISION/SYNC_GROUP) yang tertunda untuk tenant. */
    private fun provisioningActions(tenantId: UUID): List<BngAction> =
        TenantContext.runAs(tenantId) {
            TransactionTemplate(txManager).execute {
                actions.findServerProvisioningPending(100)
            }!!
        }

    private fun provisionsFor(tenantId: UUID, username: String): List<BngAction> =
        actionsFor(tenantId, username, BngActionType.PROVISION)

    private fun deprovisionsFor(tenantId: UUID, username: String): List<BngAction> =
        actionsFor(tenantId, username, BngActionType.DEPROVISION)

    private fun actionsFor(tenantId: UUID, username: String, type: BngActionType): List<BngAction> =
        provisioningActions(tenantId).filter { it.action == type && it.username == username }

    private fun post(url: String, token: String, body: String, expected: Int = 201): String =
        mockMvc.perform(
            post(url).header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect { assertThat(it.response.status).isEqualTo(expected) }
            .andReturn().response.contentAsString

    private fun put(url: String, token: String, body: String, expected: Int = 200): String =
        mockMvc.perform(
            put(url).header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect { assertThat(it.response.status).isEqualTo(expected) }
            .andReturn().response.contentAsString

    private fun id(json: String): String = JsonPath.read(json, "$.id")
}
