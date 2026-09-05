package com.duluin.ftth.provisioning.application.service

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.provisioning.application.port.inbound.ProvisioningExecutionRunner
import com.duluin.ftth.provisioning.application.port.outbound.ProvisionExecutionRepository
import com.duluin.ftth.provisioning.config.ProvisioningRolloutProperties
import com.duluin.ftth.tenancy.TenantApi
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
