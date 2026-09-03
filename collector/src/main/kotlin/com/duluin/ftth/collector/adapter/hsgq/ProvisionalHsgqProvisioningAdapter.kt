package com.duluin.ftth.collector.adapter.hsgq

import com.duluin.ftth.collector.adapter.OltProvisioningAdapter
import com.duluin.ftth.contract.DeviceCapabilityReport
import com.duluin.ftth.contract.DeviceFingerprint
import com.duluin.ftth.contract.OltTarget
import com.duluin.ftth.contract.ProvisioningErrorCode
import com.duluin.ftth.contract.ProvisioningPlanStepCommand
import com.duluin.ftth.contract.ProvisioningStepResult
import java.time.Clock

class ProvisionalHsgqProvisioningAdapter(
    private val clock: Clock = Clock.systemUTC(),
) : OltProvisioningAdapter {
    override val vendor: String = "HSGQ"

    override fun capabilityReport(target: OltTarget) = DeviceCapabilityReport(
        targetId = target.oltId,
        fingerprint = DeviceFingerprint(
            vendor,
            target.model ?: "UNKNOWN",
            target.firmware ?: "UNKNOWN",
            target.managementTransport?.name ?: "UNCONFIGURED",
        ),
        capabilities = setOf("CERTIFICATION_PROVISIONAL"),
        reportedAt = clock.instant(),
        operationClasses = emptySet(),
    )

    override fun execute(target: OltTarget, command: ProvisioningPlanStepCommand) = ProvisioningStepResult(
        planId = command.planId,
        revision = command.revision,
        stepId = command.stepId,
        attemptId = command.attemptId,
        targetId = target.oltId,
        operationClass = command.operationClass,
        idempotencyKey = command.idempotencyKey,
        fencingEpoch = command.fencingEpoch,
        phase = command.phase,
        success = false,
        completedAt = clock.instant(),
        errorCode = ProvisioningErrorCode.UNCERTIFIED_FINGERPRINT,
    )
}
