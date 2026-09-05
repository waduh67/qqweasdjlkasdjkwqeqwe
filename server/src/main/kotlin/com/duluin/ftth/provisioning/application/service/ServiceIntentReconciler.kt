package com.duluin.ftth.provisioning.application.service

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.tenancy.TenantApi
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class ServiceIntentReconciler(
    private val tenants: TenantApi,
    private val coordinator: ServiceIntentLifecycleCoordinator,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${ftth.provisioning.lifecycle-reconcile-interval:PT15M}")
    fun reconcile() {
        tenants.findActiveTenantIds().forEach { tenantId ->
            try {
                TenantContext.runAs(tenantId) { coordinator.reconcile() }
            } catch (exception: Exception) {
                log.warn("Rekonsiliasi intent layanan gagal untuk tenant {}", tenantId, exception)
            }
        }
    }
}
