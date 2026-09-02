package com.duluin.ftth.monitoring.application.service

import com.duluin.ftth.common.integration.CollectorProvisioningChannel
import com.duluin.ftth.contract.CollectorHeartbeat
import com.duluin.ftth.contract.ProvisioningAcknowledgement
import com.duluin.ftth.contract.ProvisioningPlanStepCommand
import com.duluin.ftth.contract.ProvisioningTarget
import com.duluin.ftth.contract.deliveryKey
import org.springframework.stereotype.Component
import java.util.UUID

data class ProvisioningExchangeResult(
    val commands: List<ProvisioningPlanStepCommand>,
    val acknowledgement: ProvisioningAcknowledgement,
)

@Component
class CollectorProvisioningExchange(
    private val channels: List<CollectorProvisioningChannel> = emptyList(),
) {
    fun exchange(
        collectorId: UUID,
        tenantId: UUID,
        heartbeat: CollectorHeartbeat,
        availableTargets: List<ProvisioningTarget>,
    ): ProvisioningExchangeResult {
        val targetsById = availableTargets.associateBy(ProvisioningTarget::deviceId)
        val acknowledgements = channels.map { channel ->
            channel.accept(
                collectorId,
                tenantId,
                targetsById.keys,
                heartbeat.provisioningResults,
                heartbeat.deviceReports,
            )
        }
        val commands = channels.flatMap { it.pendingFor(collectorId, tenantId, targetsById.keys) }.mapNotNull { dispatch ->
            val target = targetsById[dispatch.deviceId]?.copy(deviceKind = dispatch.deviceKind) ?: return@mapNotNull null
            ProvisioningPlanStepCommand(
                planId = dispatch.planId,
                revision = dispatch.revision,
                stepId = dispatch.stepId,
                attemptId = dispatch.attemptId,
                phase = dispatch.phase,
                operationClass = dispatch.operationClass,
                idempotencyKey = dispatch.idempotencyKey,
                fencingEpoch = dispatch.fencingEpoch,
                expectedPreconditionHash = dispatch.expectedPreconditionHash,
                deadline = dispatch.deadline,
                target = target,
                payload = dispatch.payload,
            )
        }
        return ProvisioningExchangeResult(
            commands = commands.distinctBy(ProvisioningPlanStepCommand::deliveryKey),
            acknowledgement = ProvisioningAcknowledgement(
                resultIdempotencyKeys = acknowledgements.flatMap { it.resultIdempotencyKeys }.toSet(),
                resultAttemptIds = acknowledgements.flatMap { it.resultAttemptIds }.toSet(),
                deviceReportKeys = acknowledgements.flatMap { it.deviceReportKeys }.toSet(),
            ),
        )
    }
}
