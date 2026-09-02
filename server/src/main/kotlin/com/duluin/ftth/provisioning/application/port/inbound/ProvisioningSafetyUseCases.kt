package com.duluin.ftth.provisioning.application.port.inbound

import com.duluin.ftth.provisioning.application.service.PlanCompilationRequest
import com.duluin.ftth.provisioning.domain.model.ProvisionExecution
import com.duluin.ftth.provisioning.domain.model.AdapterCertification
import com.duluin.ftth.provisioning.application.service.CertifyAdapterCommand
import com.duluin.ftth.provisioning.domain.model.ProvisionPlan
import com.duluin.ftth.provisioning.domain.policy.ExecutionMode
import com.duluin.ftth.provisioning.domain.policy.PolicyDecision
import java.util.UUID

interface ProvisioningPlanningUseCase {
    fun validateProduction(request: PlanCompilationRequest): ProvisionPlan
    fun preview(request: PlanCompilationRequest, mode: ExecutionMode): ProvisioningPlanEvaluation
}

data class ProvisioningPlanEvaluation(
    val plan: ProvisionPlan,
    val decision: PolicyDecision,
)

interface ProvisioningExecutionAdmissionUseCase {
    fun admit(planId: UUID, keySuffix: String): ProvisionExecution
}

interface ProvisioningCertificationUseCase {
    fun list(targetTenantId: UUID): List<AdapterCertification>
    fun certify(command: CertifyAdapterCommand): AdapterCertification
    fun revoke(targetTenantId: UUID, certificationId: UUID): AdapterCertification
}
