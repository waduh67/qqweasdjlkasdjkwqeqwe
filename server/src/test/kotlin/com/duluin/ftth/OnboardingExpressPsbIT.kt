package com.duluin.ftth

import com.duluin.ftth.bng.application.port.outbound.BngActionRepository
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.bng.domain.model.BngActionType
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
 * Uji onboarding "Ops mode" slice C: PSB ekspres — satu POST merangkai pendaftaran pelanggan +
 * langganan + akun jaringan + WO PSB dalam SATU transaksi.
 *
 * Dua invarian yang dijaga:
 * 1. Semua entitas terbentuk dan saling bertaut; langganan & akun lahir PENDING sehingga BELUM
 *    ada tulisan ke RADIUS (pelanggan tak bisa online sebelum WO PSB dituntaskan). WO tertaut ke
 *    langganan yang sama (penyelesaiannya kelak mengaktifkan langganan).
 * 2. Atomicity: planId ngawur → seluruh transaksi rollback; pelanggan pun tak ikut tercipta.
 *
 * Query `bng_action` dibungkus [TenantContext.runAs] + transaksi agar GUC `app.tenant_id`
 * terpasang (RLS) — pola sama [BngProvisionOnActivateIT].
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OnboardingExpressPsbIT {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var onboarding: OnboardTenantUseCase
    @Autowired private lateinit var tenantApi: TenantApi
    @Autowired private lateinit var actions: BngActionRepository
    @Autowired private lateinit var txManager: PlatformTransactionManager

    private val pass = "secret12345"
    private fun uniq() = UUID.randomUUID().toString().substring(0, 8)

    @Test
    fun `PSB ekspres membuat pelanggan+langganan+akun+WO dalam satu transaksi tanpa provisi RADIUS`() {
        val slug = "onbp${uniq()}"
        val token = onboard(slug)
        val tenantId = tenantApi.findBySlug(slug)!!.id
        val planId = createPlan(token)

        val result = post(
            "/api/onboarding/psb", token,
            """{"code":"C-PSB${uniq()}","name":"Budi","address":"Jl. Ekspres","location":{"longitude":106.8,"latitude":-6.2},"planId":"$planId","username":"pppoe${uniq()}","secret":"rahasia123"}""",
        )

        val customerId = JsonPath.read<String>(result, "$.customerId")
        val subscriptionId = JsonPath.read<String>(result, "$.subscriptionId")
        val accessId = JsonPath.read<String>(result, "$.accessId")
        val workOrderId = JsonPath.read<String>(result, "$.workOrderId")
        assertThat(JsonPath.read<String>(result, "$.workOrderCode")).isNotBlank()
        assertThat(JsonPath.read<String>(result, "$.username")).isNotBlank()

        // Akun jaringan lahir PENDING → RADIUS belum disentuh sama sekali.
        val access = get("/api/bng/access/$accessId", token)
        assertThat(JsonPath.read<String>(access, "$.status")).isEqualTo("PENDING")
        assertThat(provisioningActions(tenantId)).isEmpty()

        // WO PSB tertaut ke langganan yang sama (penyelesaiannya kelak mengaktifkan langganan).
        val wo = get("/api/work-orders/$workOrderId", token)
        assertThat(JsonPath.read<String>(wo, "$.workOrder.type")).isEqualTo("PSB")
        assertThat(JsonPath.read<String>(wo, "$.workOrder.subscriptionId")).isEqualTo(subscriptionId)
        assertThat(JsonPath.read<String>(wo, "$.workOrder.customerId")).isEqualTo(customerId)
    }

    @Test
    fun `PSB ekspres tanpa BRAS manual memilih BRAS otomatis dari area pelanggan`() {
        val slug = "onba${uniq()}"
        val token = onboard(slug)
        val planId = createPlan(token)

        // Area pelanggan → dipetakan ke sebuah BRAS lewat cakupan area BRAS.
        val areaId = id(post("/api/areas", token, """{"code":"AR${uniq()}","name":"Zona ${uniq()}"}"""))
        val nasId = id(
            post(
                "/api/bng/nas", token,
                """{"name":"BRAS ${uniq()}","vendor":"MIKROTIK","areaIds":["$areaId"]}""",
            ),
        )

        // Onboard TANPA nasId; hanya areaId — server auto-pilih BRAS dari cakupan area.
        val result = post(
            "/api/onboarding/psb", token,
            """{"code":"C-AUTO${uniq()}","name":"Sari","address":"Jl. Auto","location":{"longitude":106.8,"latitude":-6.2},"areaId":"$areaId","planId":"$planId","username":"pppoe${uniq()}","secret":"rahasia123"}""",
        )

        val accessId = JsonPath.read<String>(result, "$.accessId")
        val access = get("/api/bng/access/$accessId", token)
        assertThat(JsonPath.read<String>(access, "$.nasId")).isEqualTo(nasId)
    }

    @Test
    fun `planId ngawur me-rollback seluruh onboarding sehingga pelanggan pun tak tercipta`() {
        val slug = "onbr${uniq()}"
        val token = onboard(slug)
        val code = "C-ROLL${uniq()}"

        // planId acak (tak ada) → openSubscription gagal → rollback total.
        mockMvc.perform(
            post("/api/onboarding/psb").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"code":"$code","name":"Gagal","address":"Jl. Rollback","location":{"longitude":106.8,"latitude":-6.2},"planId":"${UUID.randomUUID()}"}""",
                ),
        ).andExpect { assertThat(it.response.status).isEqualTo(404) }

        // Pelanggan dengan kode itu tak boleh ada — transaksi ikut membatalkan registerCustomer.
        val list = get("/api/customers?query=$code", token)
        assertThat(JsonPath.read<Int>(list, "$.totalElements")).isZero()
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
