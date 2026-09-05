package com.duluin.ftth.provisioning

import com.duluin.ftth.provisioning.application.port.inbound.ProvisioningPlanEvaluation
import com.duluin.ftth.provisioning.application.port.inbound.ProvisioningPlanningUseCase
import com.duluin.ftth.provisioning.application.service.PlanCompilationRequest
import com.duluin.ftth.provisioning.domain.model.ProvisionPlan
import com.duluin.ftth.provisioning.domain.policy.ExecutionMode
import org.springframework.stereotype.Component

@Component
class ProvisioningApi(
    private val planning: ProvisioningPlanningUseCase,
) {
    fun validateProduction(request: PlanCompilationRequest): ProvisionPlan = planning.validateProduction(request)

    fun preview(request: PlanCompilationRequest, mode: ExecutionMode): ProvisioningPlanEvaluation =
        planning.preview(request, mode)
}
