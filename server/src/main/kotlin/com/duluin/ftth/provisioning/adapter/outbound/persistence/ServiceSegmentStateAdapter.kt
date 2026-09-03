package com.duluin.ftth.provisioning.adapter.outbound.persistence

import com.duluin.ftth.provisioning.application.port.outbound.ProvisionExecutionRepository
import com.duluin.ftth.provisioning.application.port.outbound.ProvisionPlanRepository
import com.duluin.ftth.provisioning.application.port.outbound.ServiceSegmentState
import com.duluin.ftth.provisioning.application.port.outbound.ServiceSegmentStatePort
import com.duluin.ftth.provisioning.domain.model.ExecutionStatus
import com.duluin.ftth.provisioning.domain.model.ProvisionOperation
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class ServiceSegmentStateAdapter(
    private val executions: ProvisionExecutionRepository,
    private val plans: ProvisionPlanRepository,
) : ServiceSegmentStatePort {
    override fun stateOf(intentId: UUID): ServiceSegmentState {
        val execution = executions.findLatestByIntentId(intentId)
            ?.takeIf { it.status == ExecutionStatus.SUCCEEDED } ?: return ServiceSegmentState.PENDING
        val operations = plans.findById(execution.planId)?.steps?.map { it.operation }.orEmpty()
        return when {
            ProvisionOperation.REMOVE_ACCESS_PORT in operations -> ServiceSegmentState.REMOVED
            ProvisionOperation.ENSURE_ACCESS_PORT in operations -> ServiceSegmentState.APPLIED
            else -> ServiceSegmentState.PENDING
        }
    }
}
