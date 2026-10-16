package com.karuhun.ftth.monitoring.application.service

import com.karuhun.ftth.common.integration.CollectorProvisioningChannel
import com.karuhun.ftth.contract.CollectorHeartbeat
import com.karuhun.ftth.contract.ProvisioningAcknowledgement
import com.karuhun.ftth.contract.ProvisioningPlanStepCommand
import com.karuhun.ftth.contract.ProvisioningTarget
import com.karuhun.ftth.contract.deliveryKey
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
                targetsById,
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
                observationOnly = dispatch.observationOnly,
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
