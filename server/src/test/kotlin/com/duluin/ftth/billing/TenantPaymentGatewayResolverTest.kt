package com.duluin.ftth.billing

import com.duluin.ftth.billing.application.port.outbound.PivotMasterConfigRepository
import com.duluin.ftth.billing.application.port.outbound.TenantPaymentGatewayRepository
import com.duluin.ftth.billing.application.port.outbound.TenantPivotAccountRepository
import com.duluin.ftth.billing.application.service.PivotMasterConfigProvider
import com.duluin.ftth.billing.application.service.TenantPaymentGatewayResolver
import com.duluin.ftth.billing.config.BillingProperties
import com.duluin.ftth.billing.domain.model.GatewayMode
import com.duluin.ftth.billing.domain.model.PaymentProvider
import com.duluin.ftth.billing.domain.model.PivotMasterConfig
import com.duluin.ftth.billing.domain.model.TenantPaymentGateway
import com.duluin.ftth.billing.domain.model.TenantPivotAccount
import com.duluin.ftth.billing.domain.model.TripayPaymentConfig
import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.tenancy.TenantApi
import com.duluin.ftth.tenancy.TenantRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class TenantPaymentGatewayResolverTest {

    private val tenantId = UuidV7.generate()

    @BeforeEach
    fun setUp() = TenantContext.set(tenantId)

    @AfterEach
    fun tearDown() = TenantContext.clear()

    @Test
    fun `resolve mengembalikan konteks BYO Tripay yang siap dipakai`() {
        val settings = TenantPaymentGateway.defaultFor(tenantId).apply {
            update(
                provider = PaymentProvider.TRIPAY,
                enabled = true,
                tripay = TripayPaymentConfig(
                    merchantCode = "merchant-1",
                    apiKey = "api-key-1",
                    privateKey = "private-key-1",
                    sandbox = false,
                ),
            )
        }
        val resolver = TenantPaymentGatewayResolver(
            repo = SingleGatewayRepository(settings),
            subAccounts = EmptySubAccountRepository,
            masterConfig = PivotMasterConfigProvider(EmptyPivotMasterConfigRepository),
            tenantApi = UnusedTenantApi,
            props = BillingProperties(webhookSecret = "manual-secret"),
        )

        val resolved = resolver.resolve()

        assertThat(resolved.provider).isEqualTo("TRIPAY")
        assertThat(resolved.mode).isEqualTo(GatewayMode.BYO)
        assertThat(resolved.merchantCode).isEqualTo("merchant-1")
        assertThat(resolved.apiKey).isEqualTo("api-key-1")
        assertThat(resolved.secretKey).isEqualTo("private-key-1")
        assertThat(resolved.sandbox).isFalse()
    }

    private class SingleGatewayRepository(
        private val settings: TenantPaymentGateway,
    ) : TenantPaymentGatewayRepository {
        override fun find(): TenantPaymentGateway = settings

        override fun save(settings: TenantPaymentGateway): TenantPaymentGateway = settings
    }

    private object EmptySubAccountRepository : TenantPivotAccountRepository {
        override fun find(): TenantPivotAccount? = null

        override fun save(account: TenantPivotAccount): TenantPivotAccount = account

        override fun findByTenant(tenantId: UUID): TenantPivotAccount? = null
    }

    private object EmptyPivotMasterConfigRepository : PivotMasterConfigRepository {
        override fun find(): PivotMasterConfig? = null

        override fun save(config: PivotMasterConfig): PivotMasterConfig = config
    }

    private object UnusedTenantApi : TenantApi {
        override fun findById(id: UUID): TenantRef? = error("unexpected tenant lookup")

        override fun findBySlug(slug: String): TenantRef? = error("unexpected tenant lookup")

        override fun requireById(id: UUID): TenantRef = error("unexpected tenant lookup")

        override fun platformTenantId(): UUID = error("unexpected tenant lookup")

        override fun findActiveTenantIds(): List<UUID> = error("unexpected tenant lookup")

        override fun ensureTenant(slug: String, name: String): TenantRef = error("unexpected tenant lookup")

        override fun suspend(id: UUID): TenantRef = error("unexpected tenant lookup")

        override fun activate(id: UUID): TenantRef = error("unexpected tenant lookup")
    }
}
