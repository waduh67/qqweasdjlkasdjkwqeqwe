package com.karuhun.ftth.provisioning.application.service

import com.karuhun.ftth.common.tenant.TenantContext
import com.karuhun.ftth.provisioning.application.port.inbound.ProvisioningExecutionRunner
import com.karuhun.ftth.provisioning.application.port.outbound.ProvisionExecutionRepository
import com.karuhun.ftth.provisioning.config.ProvisioningRolloutProperties
import com.karuhun.ftth.tenancy.TenantApi
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class ProvisioningExecutionQueueWorker(
    private val tenants: TenantApi,
    private val executions: ProvisionExecutionRepository,
    private val runner: ProvisioningExecutionRunner,
    private val rollout: ProvisioningRolloutProperties,
    private val planCompilation: AuthoritativePlanCompilationService,
    @Value("${'$'}{ftth.provisioning.execution-batch-size:10}") private val batchSize: Int,
) {
    @Scheduled(fixedDelayString = "${'$'}{ftth.provisioning.execution-dispatch-interval:PT5S}")
    fun drain() {
        if (!rollout.autoApplyEnabled) return
        tenants.findActiveTenantIds().forEach { tenantId ->
            runCatching { TenantContext.runAs(tenantId) {
                executions.findRunnable(batchSize).forEach { execution ->
                    runCatching {
                        runner.run(execution.id, "provisioning-queue").also(planCompilation::completeDeprovisionIfNeeded)
                    }
                }
            } }
        }
    }
}
