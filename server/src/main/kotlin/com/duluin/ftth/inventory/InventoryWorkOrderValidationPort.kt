package com.duluin.ftth.inventory

import com.duluin.ftth.common.security.AuthorityFence
import java.util.UUID

interface InventoryWorkOrderValidationPort {
    fun lockAndValidate(context: DeploymentValidationContext): ValidatedWorkOrderContext
}

data class DeploymentValidationContext(
    val binding: DeploymentBinding,
    val authorityFence: AuthorityFence,
    val cutoverFence: TenantCutoverFence,
)

data class ValidatedWorkOrderContext(
    val authorizationId: UUID,
    val workOrderId: UUID,
    val customerId: UUID,
    val actorId: UUID,
    val purpose: DeploymentPurpose,
    val revisions: MaterialRevisions,
    val authorityEpoch: Long,
    val cutoverEpoch: Long,
)
