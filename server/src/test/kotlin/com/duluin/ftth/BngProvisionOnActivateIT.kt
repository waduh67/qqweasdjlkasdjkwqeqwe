package com.duluin.ftth

import com.duluin.ftth.bng.application.port.outbound.BngActionRepository
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * Uji onboarding "Ops mode" slice B: akun jaringan yang dibuat SEBELUM instalasi selesai
 * (langganan masih PENDING) berdiri sebagai akun PENDING dan BELUM ditulis ke RADIUS —
 * pelanggan tak bisa online duluan. Begitu WO PSB selesai (langganan diaktifkan), akun
 * dialihkan ke ACTIVE dan barulah diprovisikan ke RADIUS (SYNC_GROUP + PROVISION).
 *
 * Sekaligus menegakkan kebijakan kredensial "auto-generate, boleh override": akun dibuat
 * tanpa username/password menerima kredensial yang di-generate server-side.
 *
 * Query `bng_action` dibungkus [TenantContext.runAs] + transaksi agar GUC `app.tenant_id`
 * terpasang (RLS) — pola sama [BngProvisioningClaimSplitIT].
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BngProvisionOnActivateIT {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var onboarding: OnboardTenantUseCase
    @Autowired private lateinit var tenantApi: TenantApi
    @Autowired private lateinit var actions: BngActionRepository
    @Autowired private lateinit var txManager: PlatformTransactionManager

    private val pass = "secret12345"
    private fun uniq() = UUID.randomUUID().toString().substring(0, 8)

    @Test
    fun `akun untuk langganan PENDING belum diprovisikan sampai WO PSB selesai`() {
        val slug = "bngp${uniq()}"
        val token = onboard(slug)
        val tenantId = tenantApi.findBySlug(slug)!!.id

        val planId = createPlan(token)
        val customerId = createCustomer(token, code = "C-ONBOARD")
        val sub = id(post("/api/customers/$customerId/subscriptions", token, """{"planId":"$planId"}"""))
        val nasId = id(post("/api/bng/nas", token, """{"name":"BRAS-${uniq()}","vendor":"MIKROTIK"}"""))

        // Buat akun saat langganan masih PENDING → akun PENDING, RADIUS belum disentuh.
        val access = post(
            "/api/bng/access", token,
            """{"subscriptionId":"$sub","username":"pppoe${uniq()}","secret":"rahasia123","planId":"$planId","nasId":"$nasId"}""",
        )
        val accessId = id(access)
        assertThat(JsonPath.read<String>(access, "$.status")).isEqualTo("PENDING")
        assertThat(provisioningActions(tenantId)).isEmpty()

        // WO PSB selesai = langganan diaktifkan → akun ACTIVE + diprovisikan ke RADIUS.
        post("/api/customers/subscriptions/$sub/activate", token, "", 200)

        val activated = get("/api/bng/access/$accessId", token)
        assertThat(JsonPath.read<String>(activated, "$.status")).isEqualTo("ACTIVE")
        assertThat(provisioningActions(tenantId))
            .containsExactlyInAnyOrder(BngActionType.SYNC_GROUP, BngActionType.PROVISION)
    }

    @Test
    fun `kredensial dikosongkan di-generate server-side dari kode pelanggan`() {
        val slug = "bngg${uniq()}"
        val token = onboard(slug)

        val planId = createPlan(token)
        val customerId = createCustomer(token, code = "C-AUTO")
        val sub = id(post("/api/customers/$customerId/subscriptions", token, """{"planId":"$planId"}"""))

        // username + secret dikosongkan → di-generate; username turun dari kode pelanggan.
        val access = post("/api/bng/access", token, """{"subscriptionId":"$sub","planId":"$planId","nasId":null}""")
        assertThat(JsonPath.read<String>(access, "$.username")).isEqualTo("c-auto")
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

    private fun createCustomer(token: String, code: String): String =
        id(
            post(
                "/api/customers", token,
                """{"code":"$code","name":"Pelanggan ${uniq()}","address":"Jl. Uji","location":{"longitude":106.99,"latitude":-6.24}}""",
            ),
        )

    /** Aksi jalur-data RADIUS (PROVISION/DEPROVISION/SYNC_GROUP) yang tertunda untuk tenant. */
    private fun provisioningActions(tenantId: UUID): List<BngActionType> =
        TenantContext.runAs(tenantId) {
            TransactionTemplate(txManager).execute {
                actions.findServerProvisioningPending(100).map { it.action }
            }!!
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

    private fun id(json: String): String = JsonPath.read(json, "$.id")
}
