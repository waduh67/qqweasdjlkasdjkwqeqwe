package com.duluin.ftth.provisioning

import com.duluin.ftth.bng.BngNasRef
import com.duluin.ftth.bng.BngProvisioningApi
import com.duluin.ftth.catalog.CatalogApi
import com.duluin.ftth.catalog.PlanNetworkRef
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.customer.SubscriptionActivated
import com.duluin.ftth.hotspot.HotspotApi
import com.duluin.ftth.hotspot.HotspotSiteRef
import com.duluin.ftth.provisioning.adapter.outbound.persistence.ServiceSegmentStateAdapter
import com.duluin.ftth.provisioning.application.port.outbound.ProvisionExecutionRepository
import com.duluin.ftth.provisioning.application.port.outbound.ProvisionPlanRepository
import com.duluin.ftth.provisioning.application.port.outbound.ServiceSegmentState
import com.duluin.ftth.provisioning.application.service.ServiceIntentResolver
import com.duluin.ftth.provisioning.domain.model.DeviceKind
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.provisioning.domain.model.ExecutionStatus
import com.duluin.ftth.provisioning.domain.model.ProvisionExecution
import com.duluin.ftth.provisioning.domain.model.ProvisionOperation
import com.duluin.ftth.provisioning.domain.model.ProvisionPlan
import com.duluin.ftth.provisioning.domain.model.ProvisionStep
import com.duluin.ftth.provisioning.domain.model.ServiceIntent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.UUID

class ServiceIntentResolverTest {
    private val tenantId = UUID.randomUUID()
    private val siteId = UUID.randomUUID()
    private val nasId = UUID.randomUUID()
    private val planId = UUID.randomUUID()

    @Test
    fun `applied hotspot resolves site NAS and service class without voucher credentials`() = TenantContext.runAs(tenantId) {
        val intent = ServiceIntent.createHotspot(tenantId, siteId, UUID.randomUUID())
        val bng = mock(BngProvisioningApi::class.java)
        val hotspot = mock(HotspotApi::class.java)
        val catalog = mock(CatalogApi::class.java)
        `when`(hotspot.findProvisioningSite(siteId)).thenReturn(
            HotspotSiteRef(siteId, tenantId, nasId, planId, "NETOPS_HOSTED"),
        )
        `when`(bng.findNas(nasId)).thenReturn(
            BngNasRef(id = nasId, enabled = true, pppoeTerminationCapable = true),
        )
        `when`(catalog.findActiveHotspotPlan(planId)).thenReturn(hotspotPlan())
        val resolver = ServiceIntentResolver(mock(CustomerApi::class.java), bng, hotspot, catalog)
        val segmentState = appliedSegment(intent)

        val resolved = resolver.resolve(intent)
        val event = SubscriptionActivated(tenantId, UUID.randomUUID(), UUID.randomUUID())

        assertThat(segmentState).isEqualTo(ServiceSegmentState.APPLIED)
        assertThat(resolved.nasId).isEqualTo(nasId)
        assertThat(resolved.serviceClass).isEqualTo("Voucher 1 Jam")
        assertThat(resolved.accessId).isNull()
        assertThat(listOf(intent, event, resolved).flatMap { it.javaClass.declaredFields.map { field -> field.name } })
            .doesNotContain("secret", "credential", "password", "username")
        assertThat(listOf(intent, event, resolved).joinToString()).doesNotContain("voucher-secret", "pppoe-secret")
        Unit
    }

    private fun appliedSegment(intent: ServiceIntent): ServiceSegmentState {
        val executions = mock(ProvisionExecutionRepository::class.java)
        val plans = mock(ProvisionPlanRepository::class.java)
        val execution = mock(ProvisionExecution::class.java)
        val plan = mock(ProvisionPlan::class.java)
        val step = mock(ProvisionStep::class.java)
        `when`(execution.status).thenReturn(ExecutionStatus.SUCCEEDED)
        `when`(execution.planId).thenReturn(UUID.randomUUID())
        `when`(plans.findById(execution.planId)).thenReturn(plan)
        `when`(step.operation).thenReturn(ProvisionOperation.ENSURE_ACCESS_PORT)
        `when`(plan.steps).thenReturn(listOf(step))
        `when`(executions.findLatestByIntentId(intent.id)).thenReturn(execution)
        return ServiceSegmentStateAdapter(executions, plans).stateOf(intent.id)
    }

    private fun hotspotPlan() = PlanNetworkRef(
        planId, "Voucher 1 Jam", 20, 5, "5M/20M", 1, false, null, null, null, null, setOf("HOTSPOT"),
    )
}
