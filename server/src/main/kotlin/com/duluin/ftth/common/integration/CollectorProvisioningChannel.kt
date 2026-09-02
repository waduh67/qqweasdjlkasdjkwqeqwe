package com.duluin.ftth.common.integration

import com.duluin.ftth.contract.DeviceCapabilityReport
import com.duluin.ftth.contract.ProvisioningAcknowledgement
import com.duluin.ftth.contract.ProvisioningCommandPhase
import com.duluin.ftth.contract.ProvisioningPayload
import com.duluin.ftth.contract.ProvisioningStepResult
import java.time.Instant
import java.util.UUID

interface CollectorProvisioningChannel {
    fun pendingFor(collectorId: UUID, tenantId: UUID, availableTargetIds: Set<String>): List<ProvisioningDispatch>

    fun accept(
        collectorId: UUID,
        tenantId: UUID,
        results: List<ProvisioningStepResult>,
        reports: List<DeviceCapabilityReport>,
    ): ProvisioningAcknowledgement
}

data class ProvisioningDispatch(
    val planId: String,
    val revision: Int,
    val stepId: String,
    val attemptId: String,
    val phase: ProvisioningCommandPhase,
    val operationClass: String,
    val idempotencyKey: String,
    val fencingEpoch: Long,
    val expectedPreconditionHash: String?,
    val deadline: Instant,
    val deviceId: String,
    val deviceKind: String,
    val payload: ProvisioningPayload,
)
