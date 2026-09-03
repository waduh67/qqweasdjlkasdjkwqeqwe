package com.duluin.ftth.platformbilling

import com.duluin.ftth.billing.application.port.inbound.RecordPaymentUseCase
import com.duluin.ftth.billing.application.port.outbound.PaymentSettlement
import com.duluin.ftth.billing.application.port.outbound.PivotMasterConfigRepository
import com.duluin.ftth.billing.application.port.outbound.TenantPaymentGatewayRepository
import com.duluin.ftth.billing.application.port.outbound.TenantPivotAccountRepository
import com.duluin.ftth.billing.application.service.PivotMasterConfigProvider
import com.duluin.ftth.billing.application.service.TenantPaymentGatewayResolver
import com.duluin.ftth.billing.application.service.TripayPaymentCallbackService
import com.duluin.ftth.billing.config.BillingProperties
import com.duluin.ftth.billing.domain.model.PaymentProvider
import com.duluin.ftth.billing.domain.model.PivotMasterConfig
import com.duluin.ftth.billing.domain.model.TenantPaymentGateway
import com.duluin.ftth.billing.domain.model.TenantPivotAccount
import com.duluin.ftth.billing.domain.model.TripayPaymentConfig
import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.infrastructure.web.GlobalExceptionHandler
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.platformbilling.adapter.inbound.web.TripayCallbackController
import com.duluin.ftth.tenancy.TenantApi
import com.duluin.ftth.tenancy.TenantRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class TripayCallbackControllerTest {

    @AfterEach
    fun clearTenantContext() = TenantContext.clear()

    @Test
    fun `single Spring mapping settles a matched PAID callback only for the signature matched tenant`() {
        val tenant = UuidV7.generate()
        val otherTenant = UuidV7.generate()
        val recorder = RecordingPayments()
        val body = callbackBody(status = "PAID")

        mockMvc(
            tenants = listOf(tenant, otherTenant),
            configs = mapOf(
                tenant to tripayConfig(tenant),
                otherTenant to tripayConfig(otherTenant, privateKey = "other-private-key"),
            ),
            recorder = recorder,
        ).perform(callback(body, sign(body, PRIVATE_KEY)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))

        assertThat(recorder.settlements).containsExactly(
            RecordedSettlement(tenant, "INV-202609-0001"),
        )
    }

    @Test
    fun `bad signature or altered raw bytes never settle`() {
        val tenant = UuidV7.generate()
        val recorder = RecordingPayments()
        val signedBody = callbackBody(status = "PAID")
        val reformattedBody = signedBody.replace("{", "{ ")

        mockMvc(
            tenants = listOf(tenant),
            configs = mapOf(tenant to tripayConfig(tenant)),
            recorder = recorder,
        ).perform(callback(reformattedBody, sign(signedBody, PRIVATE_KEY)))
            .andExpect(status().isBadRequest)

        assertThat(recorder.settlements).isEmpty()

        mockMvc(
            tenants = listOf(tenant),
            configs = mapOf(tenant to tripayConfig(tenant)),
            recorder = recorder,
        ).perform(callback(signedBody, "not-a-hex-hmac"))
            .andExpect(status().isBadRequest)

        assertThat(recorder.settlements).isEmpty()
    }

    @Test
    fun `valid non PAID callback acknowledges success without settlement`() {
        val tenant = UuidV7.generate()
        val recorder = RecordingPayments()
        val body = callbackBody(status = "UNPAID")

        mockMvc(
            tenants = listOf(tenant),
            configs = mapOf(tenant to tripayConfig(tenant)),
            recorder = recorder,
        ).perform(callback(body, sign(body, PRIVATE_KEY)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))

        assertThat(recorder.settlements).isEmpty()
    }

    @Test
    fun `valid PAID callback for an unknown invoice acknowledges success without settlement`() {
        val tenant = UuidV7.generate()
        val recorder = RecordingPayments(ignoredInvoices = setOf("INV-UNKNOWN"))
        val body = callbackBody(status = "PAID", invoiceNumber = "INV-UNKNOWN")

        mockMvc(
            tenants = listOf(tenant),
            configs = mapOf(tenant to tripayConfig(tenant)),
            recorder = recorder,
        ).perform(callback(body, sign(body, PRIVATE_KEY)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))

        assertThat(recorder.settlements).isEmpty()
    }

    @Test
    fun `disabled or incomplete Tripay BYO configuration cannot authenticate a callback`() {
        val disabled = UuidV7.generate()
        val incomplete = UuidV7.generate()
        val recorder = RecordingPayments()
        val body = callbackBody(status = "PAID")

        mockMvc(
            tenants = listOf(disabled, incomplete),
            configs = mapOf(
                disabled to tripayConfig(disabled, enabled = false),
                incomplete to tripayConfig(incomplete, apiKey = null),
            ),
            recorder = recorder,
        ).perform(callback(body, sign(body, PRIVATE_KEY)))
            .andExpect(status().isBadRequest)

        assertThat(recorder.settlements).isEmpty()
    }

    private fun mockMvc(
        tenants: List<UUID>,
        configs: Map<UUID, TenantPaymentGateway>,
        recorder: RecordingPayments,
    ) = MockMvcBuilders.standaloneSetup(
        TripayCallbackController(billingCallbacks(tenants, configs, recorder)),
    ).setControllerAdvice(GlobalExceptionHandler()).build()

    private fun billingCallbacks(
        tenants: List<UUID>,
        configs: Map<UUID, TenantPaymentGateway>,
        recorder: RecordingPayments,
    ) = TripayPaymentCallbackService(
        tenantApi = ActiveTenants(tenants),
        gatewayResolver = TenantPaymentGatewayResolver(
            repo = TenantScopedGatewayRepository(configs),
            subAccounts = EmptyTenantPivotAccounts,
            masterConfig = PivotMasterConfigProvider(EmptyPivotMasterConfigRepository),
            tenantApi = ActiveTenants(tenants),
            props = BillingProperties(),
        ),
        recordPayment = recorder,
        objectMapper = ObjectMapper(),
    )

    private fun callback(body: String, signature: String) =
        post("/api/platform/tripay/callbacks/payment")
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Callback-Signature", signature)
            .content(body)

    private fun callbackBody(
        status: String,
        invoiceNumber: String = "INV-202609-0001",
    ): String =
        """{"reference":"TREF-FIXTURE","merchant_ref":"$invoiceNumber","total_amount":150000,"status":"$status","paid_at":1788220800}"""

    private fun tripayConfig(
        tenantId: UUID,
        privateKey: String = PRIVATE_KEY,
        apiKey: String? = "fixture-api-key",
        enabled: Boolean = true,
    ): TenantPaymentGateway =
        TenantPaymentGateway.defaultFor(tenantId).apply {
            update(
                provider = PaymentProvider.TRIPAY,
                enabled = enabled,
                tripay = TripayPaymentConfig(
                    merchantCode = "fixture-merchant",
                    apiKey = apiKey,
                    privateKey = privateKey,
                ),
            )
        }

    private fun sign(body: String, privateKey: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(privateKey.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(body.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private class RecordingPayments(
        private val ignoredInvoices: Set<String> = emptySet(),
    ) : RecordPaymentUseCase {
        val settlements = mutableListOf<RecordedSettlement>()

        override fun applySettlement(settlement: PaymentSettlement) {
            if (settlement.invoiceNumber !in ignoredInvoices) {
                settlements += RecordedSettlement(TenantContext.tenantId(), settlement.invoiceNumber)
            }
        }

        override fun recordManual(invoiceId: UUID, note: String?) = error("unexpected manual payment")
    }

    private data class RecordedSettlement(val tenantId: UUID, val invoiceNumber: String)

    private class TenantScopedGatewayRepository(
        private val configs: Map<UUID, TenantPaymentGateway>,
    ) : TenantPaymentGatewayRepository {
        override fun find(): TenantPaymentGateway? = TenantContext.tenantIdOrNull()?.let(configs::get)

        override fun save(settings: TenantPaymentGateway): TenantPaymentGateway = settings
    }

    private class ActiveTenants(private val ids: List<UUID>) : TenantApi {
        override fun findActiveTenantIds(): List<UUID> = ids

        override fun findById(id: UUID): TenantRef? = null
        override fun findBySlug(slug: String): TenantRef? = null
        override fun requireById(id: UUID): TenantRef = error("unexpected tenant lookup")
        override fun platformTenantId(): UUID = error("unexpected platform tenant lookup")
        override fun ensureTenant(slug: String, name: String): TenantRef = error("unexpected tenant creation")
        override fun suspend(id: UUID): TenantRef = error("unexpected tenant suspension")
        override fun activate(id: UUID): TenantRef = error("unexpected tenant activation")
    }

    private object EmptyTenantPivotAccounts : TenantPivotAccountRepository {
        override fun find(): TenantPivotAccount? = null
        override fun save(account: TenantPivotAccount): TenantPivotAccount = account
        override fun findByTenant(tenantId: UUID): TenantPivotAccount? = null
    }

    private object EmptyPivotMasterConfigRepository : PivotMasterConfigRepository {
        override fun find(): PivotMasterConfig? = null
        override fun save(config: PivotMasterConfig): PivotMasterConfig = config
    }

    private companion object {
        const val PRIVATE_KEY = "fixture-tripay-private-key"
    }
}
