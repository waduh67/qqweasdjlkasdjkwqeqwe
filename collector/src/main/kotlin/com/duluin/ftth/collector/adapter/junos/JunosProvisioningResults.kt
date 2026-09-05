package com.duluin.ftth.collector.adapter.junos

import com.duluin.ftth.contract.ProvisioningApplyResult
import com.duluin.ftth.contract.ProvisioningErrorCode
import com.duluin.ftth.contract.ProvisioningPlanStepCommand
import com.duluin.ftth.contract.ProvisioningPreflightSnapshot
import com.duluin.ftth.contract.ProvisioningResultState
import com.duluin.ftth.contract.ProvisioningRollbackResult
import com.duluin.ftth.contract.ProvisioningStepResult
import com.duluin.ftth.contract.ProvisioningVerificationObservation
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock

internal class JunosProvisioningResults(private val clock: Clock) {
    fun stateHash(state: JunosOperationalObservation): String {
        val canonical = "management=${state.managementReachable};${state.resources.sorted().joinToString(";")}"
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun observation(state: JunosOperationalObservation, matchesExpected: Boolean) =
        ProvisioningVerificationObservation(
            observedAt = clock.instant(),
            matchesExpected = matchesExpected,
            stateHash = stateHash(state),
            state = ProvisioningResultState(state.resources.size),
        )

    fun success(
        command: ProvisioningPlanStepCommand,
        preflight: ProvisioningPreflightSnapshot? = null,
        apply: ProvisioningApplyResult? = null,
        verification: ProvisioningVerificationObservation,
        rollback: ProvisioningRollbackResult? = null,
    ) = ProvisioningStepResult(
        planId = command.planId,
        revision = command.revision,
        stepId = command.stepId,
        attemptId = command.attemptId,
        targetId = command.target.deviceId,
        operationClass = command.operationClass,
        idempotencyKey = command.idempotencyKey,
        fencingEpoch = command.fencingEpoch,
        phase = command.phase,
        success = true,
        completedAt = clock.instant(),
        preflight = preflight,
        apply = apply,
        verification = verification,
        rollback = rollback,
    )

    fun failed(command: ProvisioningPlanStepCommand, code: ProvisioningErrorCode) = ProvisioningStepResult(
        planId = command.planId,
        revision = command.revision,
        stepId = command.stepId,
        attemptId = command.attemptId,
        targetId = command.target.deviceId,
        operationClass = command.operationClass,
        idempotencyKey = command.idempotencyKey,
        fencingEpoch = command.fencingEpoch,
        phase = command.phase,
        success = false,
        completedAt = clock.instant(),
        errorCode = code,
    )

    fun stepKey(command: ProvisioningPlanStepCommand) = "${command.planId}:${command.revision}:${command.stepId}"
}
