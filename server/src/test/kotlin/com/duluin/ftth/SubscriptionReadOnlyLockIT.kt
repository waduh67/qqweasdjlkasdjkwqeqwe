package com.duluin.ftth

import com.duluin.ftth.iam.application.port.inbound.OnboardTenantCommand
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantUseCase
import com.duluin.ftth.platformbilling.application.port.inbound.ConfigureSubscriptionCommand
import com.duluin.ftth.platformbilling.application.port.inbound.ManageTenantSubscriptionUseCase
import com.duluin.ftth.platformbilling.application.port.inbound.ManualPaymentCommand
import com.duluin.ftth.platformbilling.application.port.outbound.TenantSubscriptionInvoiceRepository
import com.duluin.ftth.platformbilling.application.port.outbound.TenantSubscriptionRepository
import com.duluin.ftth.platformbilling.application.service.PlatformBillingRunner
import com.duluin.ftth.platformbilling.domain.model.SubscriptionStatus
import com.duluin.ftth.platformbilling.domain.model.TenantSubscriptionInvoice
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
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Tunggakan langganan SaaS = konsol tenant BACA-SAJA, bukan tenant yang dikunci di luar.
 *
 * Bedanya menentukan apakah ISP yang telat bayar masih bisa membayar. Aturan lama men-suspend
 * tenant-nya, dan `AuthenticationService` menolak login tenant non-aktif — tunggakan jadi
 * lubang yang tak punya jalan keluar dari dalam. Yang ditegakkan sekarang:
 *
 *  - membaca tetap 200, menulis 402 ber-`code=SUBSCRIPTION_LOCKED` (bukan 403: pengguna harus
 *    tahu ini soal tagihan, bukan soal izin);
 *  - login tetap berhasil, dan membayar langganan tetap boleh — kalau tidak, kuncinya menelan
 *    dirinya sendiri;
 *  - `GET /api/subscription/lock` menjelaskan sebabnya kepada staf mana pun, termasuk yang tak
 *    punya izin billing;
 *  - pelunasan membuka kunci SEKETIKA, tanpa perlu keluar-masuk aplikasi.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SubscriptionReadOnlyLockIT {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var onboarding: OnboardTenantUseCase
    @Autowired private lateinit var subscriptions: TenantSubscriptionRepository
    @Autowired private lateinit var invoices: TenantSubscriptionInvoiceRepository
    @Autowired private lateinit var billingRunner: PlatformBillingRunner
    @Autowired private lateinit var manageSubscription: ManageTenantSubscriptionUseCase

    private val pass = "secret12345"
    private fun uniq() = UUID.randomUUID().toString().substring(0, 8)

    private data class Tenant(val id: UUID, val slug: String, val email: String, val token: String)

    private fun login(slug: String, email: String, expected: Int = 200): String =
        mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"tenantSlug":"$slug","email":"$email","password":"$pass"}"""),
        ).andExpect { assertThat(it.response.status).isEqualTo(expected) }
            .andReturn().response.contentAsString

    /**
     * Tenant baru yang langganannya sudah menunggak jauh melewati masa tenggang, lalu penegakan
     * dijalankan persis seperti scheduler platform menjalankannya. Sengaja lewat [PlatformBillingRunner]
     * dan bukan dengan menyetel status ke SUSPENDED langsung: kalau tidak, yang teruji cuma
     * kuncinya, bukan hal yang memasangnya.
     *
     * Tagihannya ditulis di sini alih-alih lewat `PlatformInvoiceGenerator` karena nomor terbitannya
     * (`SUB-<yyyymm>-<8 hex pertama tenantId>`) memuat potongan UUIDv7 yang sama untuk tenant-tenant
     * yang lahir berdekatan — dan dalam satu kelas tes, semuanya lahir dalam hitungan detik.
     */
    private fun overdueTenant(prefix: String): Tenant {
        val slug = "$prefix${uniq()}"
        val email = "admin@$slug.test"
        val result = onboarding.onboard(OnboardTenantCommand(slug, "Tenant $slug", email, "Admin", pass))
        val tenantId = result.tenant.id

        // Harga disetel eksplisit supaya tagihannya bernilai — sekaligus memastikan langganan
        // sudah ada walau listener onboarding sempat gagal.
        manageSubscription.configure(tenantId, ConfigureSubscriptionCommand(BigDecimal("150000"), null, null))
        val subscription = subscriptions.findByTenantId(tenantId)!!
        val today = LocalDate.now()
        invoices.save(
            TenantSubscriptionInvoice.create(
                tenantId = tenantId,
                subscriptionId = subscription.id,
                number = "SUB-UJI-${uniq()}",
                periodStart = today.minusDays(OVERDUE_DAYS),
                periodEnd = today.minusDays(OVERDUE_DAYS).plusMonths(1).minusDays(1),
                amount = BigDecimal("150000"),
                dueDate = today.minusDays(OVERDUE_DAYS),
            ),
        )
        billingRunner.enforce(tenantId)

        assertThat(subscriptions.findByTenantId(tenantId)?.status).isEqualTo(SubscriptionStatus.SUSPENDED)
        return Tenant(tenantId, slug, email, JsonPath.read(login(slug, email), "$.accessToken"))
    }

    private fun listCustomers(token: String, expected: Int) =
        mockMvc.perform(get("/api/customers").header("Authorization", "Bearer $token"))
            .andExpect { assertThat(it.response.status).isEqualTo(expected) }
            .andReturn().response.contentAsString

    private fun createCustomer(token: String, expected: Int) =
        mockMvc.perform(
            post("/api/customers").header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(
                    """{"name":"Pelanggan Uji","address":"Jl. Uji No. 1",
                        "location":{"longitude":106.8,"latitude":-6.2}}""",
                ),
        ).andExpect { assertThat(it.response.status).isEqualTo(expected) }
            .andReturn().response.contentAsString

    @Test
    fun `saat menunggak, membaca tetap boleh dan menulis ditolak 402`() {
        val tenant = overdueTenant("lockro")

        // Data tenant tak disandera: seluruh konsol tetap terbaca sepenuhnya.
        listCustomers(tenant.token, expected = 200)

        val denied = createCustomer(tenant.token, expected = 402)
        // Penandanya yang dibaca klien untuk membedakan "bayar dulu" dari "izinmu kurang".
        assertThat(JsonPath.read<String>(denied, "$.code")).isEqualTo("SUBSCRIPTION_LOCKED")
    }

    @Test
    fun `login tetap berhasil dan endpoint kunci menjelaskan tunggakannya`() {
        val tenant = overdueTenant("lockwhy")

        // Bukti bahwa suspend keras sudah tak berlaku: orang yang hendak membayar tetap bisa masuk.
        login(tenant.slug, tenant.email, expected = 200)

        val lock = mockMvc.perform(get("/api/subscription/lock").header("Authorization", "Bearer ${tenant.token}"))
            .andExpect(status().isOk).andReturn().response.contentAsString
        assertThat(JsonPath.read<Boolean>(lock, "$.locked")).isTrue()
        assertThat(JsonPath.read<Int>(lock, "$.daysOverdue")).isGreaterThan(0)
        assertThat(JsonPath.read<Number>(lock, "$.amountDue").toDouble()).isGreaterThan(0.0)
        assertThat(JsonPath.read<String?>(lock, "$.invoiceId")).isNotNull()
    }

    @Test
    fun `membayar langganan tetap boleh walau seluruh aksi tulis lain terkunci`() {
        val tenant = overdueTenant("lockpay")

        // `billing.subscription.renew` adalah satu-satunya izin tulis yang lolos kunci; tanpa
        // pengecualian ini tunggakan tak punya jalan keluar dari dalam konsol.
        val invoice = mockMvc.perform(
            post("/api/subscription/renew").header("Authorization", "Bearer ${tenant.token}"),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        assertThat(JsonPath.read<String>(invoice, "$.number")).isNotBlank()
    }

    @Test
    fun `pelunasan membuka kunci tanpa perlu masuk ulang`() {
        val tenant = overdueTenant("lockpaid")
        createCustomer(tenant.token, expected = 402)

        val subscription = subscriptions.findByTenantId(tenant.id)!!
        invoices.findOutstandingBySubscriptionId(subscription.id).forEach {
            manageSubscription.recordManualPayment(it.id, ManualPaymentCommand(amount = null, note = "uji"))
        }

        // Token yang SAMA — kuncinya bukan bagian dari sesi, jadi tak boleh menuntut login ulang.
        createCustomer(tenant.token, expected = 201)
        assertThat(subscriptions.findByTenantId(tenant.id)?.status).isEqualTo(SubscriptionStatus.ACTIVE)
    }

    private companion object {
        /** Jauh melewati masa tenggang bawaan (7 hari) tanpa bergantung pada nilainya. */
        const val OVERDUE_DAYS = 60L
    }
}
