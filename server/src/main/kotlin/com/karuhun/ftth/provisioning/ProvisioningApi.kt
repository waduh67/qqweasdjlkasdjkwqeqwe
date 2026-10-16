package com.karuhun.ftth.provisioning

import com.karuhun.ftth.provisioning.application.port.inbound.ProvisioningPlanEvaluation
import com.karuhun.ftth.provisioning.application.port.inbound.ProvisioningPlanningUseCase
import com.karuhun.ftth.provisioning.application.service.PlanCompilationRequest
import com.karuhun.ftth.provisioning.domain.model.ProvisionPlan
import com.karuhun.ftth.provisioning.domain.policy.ExecutionMode
import org.springframework.stereotype.Component

@Component
class ProvisioningApi(
    private val planning: ProvisioningPlanningUseCase,
) {
    fun validateProduction(request: PlanCompilationRequest): ProvisionPlan = planning.validateProduction(request)

    fun preview(request: PlanCompilationRequest, mode: ExecutionMode): ProvisioningPlanEvaluation =
        planning.preview(request, mode)
}
