package com.karuhun.ftth.bng

import com.karuhun.ftth.bng.application.port.inbound.ManageSubscriberAccessUseCase
import com.karuhun.ftth.bng.application.port.outbound.BngActionRepository
import com.karuhun.ftth.bng.application.port.outbound.NasAreaCoverageRepository
import com.karuhun.ftth.bng.application.port.outbound.NasRepository
import com.karuhun.ftth.bng.application.port.outbound.RadiusAccountingReadPort
import com.karuhun.ftth.bng.application.port.outbound.RadiusSessionRepository
import com.karuhun.ftth.bng.application.port.outbound.RouterOsPort
import com.karuhun.ftth.bng.application.port.outbound.SubscriberAccessRepository
import com.karuhun.ftth.bng.application.service.BngApiService
import com.karuhun.ftth.catalog.CatalogApi
import com.karuhun.ftth.catalog.PlanCommercialRef
import com.karuhun.ftth.catalog.PlanNetworkRef
import com.karuhun.ftth.common.domain.error.ValidationException
import com.karuhun.ftth.common.security.SecretCipher
import com.karuhun.ftth.tenancy.TenantApi
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.UUID

class BngVoucherCredentialServiceTest {
    @Test
    fun `inactive HOTSPOT plan is rejected before any BNG action is enqueued`() {
        val actions = RecordingActions()
        val service = service(actions, eligiblePlan = null)

        assertThatThrownBy { service.provisionVoucherCredential(command()) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("Paket HOTSPOT aktif")
        actions.assertNothingSaved()
    }

    @Test
    fun `active non-HOTSPOT plan is rejected before any BNG action is enqueued`() {
        val actions = RecordingActions()
        val service = service(actions, eligiblePlan = null)

        assertThatThrownBy { service.provisionVoucherCredential(command()) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("tidak eligible untuk voucher")
        actions.assertNothingSaved()
    }

    private fun service(actions: RecordingActions, eligiblePlan: PlanNetworkRef?): BngApiService = BngApiService(
        subscriberAccessRepository = unsupported(),
        radiusSessionRepository = unsupported(),
        nasRepository = unsupported(),
        coverageRepository = unsupported(),
        catalogApi = object : CatalogApi {
            override fun findPlanCommercial(planId: UUID): PlanCommercialRef? = null
            override fun findPlanByName(name: String): PlanCommercialRef? = null
            override fun findPlanNetwork(planId: UUID): PlanNetworkRef? = error("must use findActiveHotspotPlan")
            override fun findActiveHotspotPlan(planId: UUID): PlanNetworkRef? = eligiblePlan
            override fun findActivePlans(): List<PlanCommercialRef> = emptyList()
        },
        manageAccess = unsupported(),
        routerOs = unsupported(),
        bngActionRepository = actions,
        radiusAccounting = unsupported(),
        secretCipher = object : SecretCipher {
            override fun encrypt(plaintext: String): String = plaintext
            override fun decrypt(ciphertext: String): String = ciphertext
        },
        tenantApi = unsupported(),
        sessionStaleAfter = Duration.ofMinutes(3),
    )

    private fun command() = VoucherCredentialSpec(
        externalId = "voucher:opaque",
        username = "voucher-user",
        credential = "only-input",
        planId = UUID.randomUUID(),
        nasId = UUID.randomUUID(),
    )

    private inline fun <reified T> unsupported(): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { _, method, _ -> throw UnsupportedOperationException("${method.name} must not be called") } as T

    private class RecordingActions : BngActionRepository {
        private var saves = 0
        override fun save(action: com.karuhun.ftth.bng.domain.model.BngAction): com.karuhun.ftth.bng.domain.model.BngAction {
            saves++
            return action
        }
        override fun findById(id: UUID): com.karuhun.ftth.bng.domain.model.BngAction? = null
        override fun findDispatchableByNasIds(nasIds: Collection<UUID>) = emptyList<com.karuhun.ftth.bng.domain.model.BngAction>()
        override fun findServerProvisioningPending(limit: Int) = emptyList<com.karuhun.ftth.bng.domain.model.BngAction>()
        override fun findServerSessionControlPending(nasIds: Collection<UUID>, limit: Int) = emptyList<com.karuhun.ftth.bng.domain.model.BngAction>()
        override fun findAccessIdsWithPendingProvisioning(subscriberAccessIds: Collection<UUID>) = emptySet<UUID>()
        fun assertNothingSaved() = org.assertj.core.api.Assertions.assertThat(saves).isZero()
    }
}
