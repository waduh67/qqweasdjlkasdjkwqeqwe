package com.duluin.ftth.provisioning.application.service

import com.duluin.ftth.bng.BngProvisioningApi
import com.duluin.ftth.catalog.CatalogApi
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.hotspot.HotspotApi
import com.duluin.ftth.provisioning.domain.model.ServiceIntent
import com.duluin.ftth.provisioning.domain.model.ServiceIntentSubjectKind
import org.springframework.stereotype.Service
import java.util.UUID

data class ResolvedServiceIntent(
    val intentId: UUID,
    val subjectKind: ServiceIntentSubjectKind,
    val subjectId: UUID,
    val nasId: UUID,
    val planId: UUID?,
    val serviceClass: String?,
    val pppoeTerminationCapable: Boolean,
    val accessId: UUID?,
    val accountStatus: String?,
    val activeSessionCount: Int,
)

@Service
class ServiceIntentResolver(
    private val customers: CustomerApi,
    private val bng: BngProvisioningApi,
    private val hotspot: HotspotApi,
    private val catalog: CatalogApi,
) {
    fun resolve(intent: ServiceIntent): ResolvedServiceIntent = when (intent.subjectKind) {
        ServiceIntentSubjectKind.FIXED_SUBSCRIPTION -> resolveFixed(intent)
        ServiceIntentSubjectKind.HOTSPOT_SITE -> resolveHotspot(intent)
    }

    private fun resolveFixed(intent: ServiceIntent): ResolvedServiceIntent {
        val subscriptionId = requireNotNull(intent.subscriptionId)
        val subscription = customers.findSubscription(subscriptionId)
            ?: throw NotFoundException("SUBSCRIPTION_NOT_FOUND")
        val access = bng.findAccess(subscriptionId) ?: throw NotFoundException("SUBSCRIBER_ACCESS_NOT_FOUND")
        val nasId = access.nasId ?: throw ConflictException("SUBSCRIBER_ACCESS_NAS_REQUIRED")
        if (bng.findNas(nasId) == null) throw NotFoundException("NAS_NOT_FOUND")
        val planId = subscription.planId ?: throw ConflictException("SERVICE_CLASS_REQUIRED")
        val serviceClass = catalog.findPlanNetwork(planId) ?: throw NotFoundException("SERVICE_CLASS_NOT_FOUND")
        return ResolvedServiceIntent(
            intent.id, intent.subjectKind, subscriptionId, nasId, planId, serviceClass.name,
            access.pppoeTerminationCapable, access.id, access.accountStatus, access.activeSessionCount,
        )
    }

    private fun resolveHotspot(intent: ServiceIntent): ResolvedServiceIntent {
        val siteId = requireNotNull(intent.hotspotSiteId)
        val site = hotspot.findProvisioningSite(siteId) ?: throw NotFoundException("HOTSPOT_SITE_NOT_FOUND")
        if (site.tenantId != TenantContext.tenantId()) throw ConflictException("TENANT_OWNERSHIP_MISMATCH")
        val nas = bng.findNas(site.nasId) ?: throw NotFoundException("NAS_NOT_FOUND")
        val serviceClass = site.defaultPlanId?.let(catalog::findActiveHotspotPlan)
        return ResolvedServiceIntent(
            intent.id, intent.subjectKind, siteId, nas.id, site.defaultPlanId, serviceClass?.name,
            nas.pppoeTerminationCapable, null, null, 0,
        )
    }
}
