package com.karuhun.ftth.provisioning.application.port.inbound

import com.karuhun.ftth.provisioning.application.service.PlanCompilationRequest
import com.karuhun.ftth.provisioning.domain.model.ProvisionExecution
import com.karuhun.ftth.provisioning.domain.model.AdapterCertification
import com.karuhun.ftth.provisioning.application.service.CertifyAdapterCommand
import com.karuhun.ftth.provisioning.domain.model.ProvisionPlan
import com.karuhun.ftth.provisioning.domain.policy.ExecutionMode
import com.karuhun.ftth.provisioning.domain.policy.PolicyDecision
import java.util.UUID

interface ProvisioningPlanningUseCase {
    fun generate(request: PlanCompilationRequest): ProvisionPlan
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

fun interface ProvisioningExecutionRunner {
    fun run(executionId: UUID, ownerId: String): ProvisionExecution
}

interface ProvisioningCertificationUseCase {
    fun list(targetTenantId: UUID): List<AdapterCertification>
    fun certify(command: CertifyAdapterCommand): AdapterCertification
    fun revoke(targetTenantId: UUID, certificationId: UUID, revision: Int): AdapterCertification
}
