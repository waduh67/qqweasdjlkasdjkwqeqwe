package com.duluin.ftth.collector.adapter.iosxe

import com.duluin.ftth.collector.adapter.ProvisioningAdapter
import com.duluin.ftth.contract.DeviceCapabilityReport
import com.duluin.ftth.contract.DeviceFingerprint
import com.duluin.ftth.contract.NasTarget
import com.duluin.ftth.contract.ProvisioningApplyResult
import com.duluin.ftth.contract.ProvisioningCommandPhase
import com.duluin.ftth.contract.ProvisioningErrorCode
import com.duluin.ftth.contract.ProvisioningPlanStepCommand
import com.duluin.ftth.contract.ProvisioningPreflightSnapshot
import com.duluin.ftth.contract.ProvisioningResultState
import com.duluin.ftth.contract.ProvisioningRollbackResult
import com.duluin.ftth.contract.ProvisioningStepResult
import com.duluin.ftth.contract.ProvisioningVerificationObservation
import java.io.IOException
import java.time.Clock

class IosXeProvisioningAdapter(
    private val sessionFactory: IosXeNetconfSessionFactory,
    private val clock: Clock = Clock.systemUTC(),
    private val confirmTimeoutSeconds: Int = 120,
) : ProvisioningAdapter {
    init {
        require(confirmTimeoutSeconds in 30..600) { "IOS-XE confirmed commit timeout must be 30..600 seconds" }
    }

    override val vendor: String = "CISCO"

    override fun capabilityReport(target: NasTarget): DeviceCapabilityReport = connect(target).use { session ->
        val hello = session.hello()
        val profile = IosXeProfiles.resolve(hello.platform, hello.softwareVersion)
        val supported = profile != null && hello.supports(profile)
        DeviceCapabilityReport(
            targetId = target.nasId,
            fingerprint = DeviceFingerprint(hello.vendor, hello.platform, hello.softwareVersion, TRANSPORT),
            capabilities = if (supported) {
                IosXeCapabilities.PROTOCOL + profile.requiredModules + "CERTIFICATION_PROVISIONAL"
            } else {
                setOf("CERTIFICATION_PROVISIONAL", "IOS_XE_UNSUPPORTED_PROFILE")
            },
            reportedAt = clock.instant(),
            operationClasses = if (supported) profile.supportedOperations else emptySet(),
        )
    }

    override fun execute(target: NasTarget, command: ProvisioningPlanStepCommand): ProvisioningStepResult {
        return try {
            requireTarget(target, command)
            when (command.phase) {
                ProvisioningCommandPhase.PREFLIGHT -> preflight(target, command)
                ProvisioningCommandPhase.APPLY -> apply(target, command)
                ProvisioningCommandPhase.VERIFY -> verify(target, command)
                ProvisioningCommandPhase.ROLLBACK -> failed(command, ProvisioningErrorCode.UNSUPPORTED_CAPABILITY)
            }
        } catch (failure: IosXeAdapterException) {
            failed(command, failure.code)
        } catch (failure: IosXeNetconfException) {
            failed(command, failure.error.toProvisioningError())
        } catch (_: IOException) {
            failed(command, ProvisioningErrorCode.TIMEOUT)
        } catch (_: Exception) {
            failed(command, ProvisioningErrorCode.MANUAL_RECONCILIATION)
        }
    }

    private fun preflight(target: NasTarget, command: ProvisioningPlanStepCommand): ProvisioningStepResult = connect(target).use { session ->
        val desired = desired(command)
        requireProfile(session.hello(), command)
        val state = session.readBaseline()
        val observation = observation(state, state.matches(desired))
        success(
            command,
            preflight = ProvisioningPreflightSnapshot(clock.instant(), state.hash(), state.resultState()),
            verification = observation,
        )
    }

    private fun verify(target: NasTarget, command: ProvisioningPlanStepCommand): ProvisioningStepResult = connect(target).use { session ->
        val desired = desired(command)
        requireProfile(session.hello(), command)
        val state = session.verifyOperational(desired)
        val observation = observation(state, state.matches(desired))
        if (!observation.matchesExpected) failed(command, ProvisioningErrorCode.VERIFICATION_MISMATCH, observation)
        else success(command, verification = observation)
    }

    private fun apply(target: NasTarget, command: ProvisioningPlanStepCommand): ProvisioningStepResult {
        val session = connect(target)
        var baseline: IosXeOperationalState? = null
        var confirmed = false
        var locked = false
        var postCommitFailure: Throwable? = null
        var mismatchObservation: ProvisioningVerificationObservation? = null
        try {
            val desired = desired(command)
            val profile = requireProfile(session.hello(), command)
            try {
                session.lockCandidate()
                locked = true
                session.discardChanges()
                baseline = session.readBaseline()
                requirePrecondition(command, baseline.hash())
                session.editCandidate(profile.renderEdit(desired))
                session.validateCandidate()
                session.confirmedCommit(confirmTimeoutSeconds)
                confirmed = true
                val operational = session.verifyOperational(desired)
                val observation = observation(operational, operational.matches(desired))
                if (!observation.matchesExpected) {
                    mismatchObservation = observation
                    throw IosXeAdapterException(ProvisioningErrorCode.VERIFICATION_MISMATCH)
                }
                session.finalCommit()
                session.unlockCandidate()
                locked = false
                return success(
                    command,
                    apply = ProvisioningApplyResult(clock.instant(), baseline.hash() != operational.hash(), operational.hash()),
                    verification = observation,
                )
            } catch (failure: Exception) {
                if (!confirmed) {
                    abortCandidate(session, locked)
                    throw failure
                }
                postCommitFailure = failure
            }
        } finally {
            session.close()
        }
        val code = postCommitFailure.toProvisioningError()
        return observeTimedRollback(target, command, checkNotNull(baseline), code, mismatchObservation)
    }

    private fun observeTimedRollback(
        target: NasTarget,
        command: ProvisioningPlanStepCommand,
        baseline: IosXeOperationalState,
        code: ProvisioningErrorCode,
        verification: ProvisioningVerificationObservation?,
    ): ProvisioningStepResult = try {
        connect(target).use { recovery ->
            val restored = recovery.awaitDeviceRollback(baseline.hash(), confirmTimeoutSeconds)
            val restoredHash = restored.hash()
            val rollback = if (restoredHash == baseline.hash()) {
                ProvisioningRollbackResult(clock.instant(), true, restoredHash)
            } else {
                ProvisioningRollbackResult(clock.instant(), false, restoredHash, ProvisioningErrorCode.MANUAL_RECONCILIATION)
            }
            failed(command, code, verification, rollback)
        }
    } catch (_: Exception) {
        failed(
            command,
            code,
            verification,
            ProvisioningRollbackResult(clock.instant(), false, errorCode = ProvisioningErrorCode.MANUAL_RECONCILIATION),
        )
    }

    private fun requireProfile(hello: IosXeHello, command: ProvisioningPlanStepCommand): IosXeProfile {
        val profile = IosXeProfiles.resolve(hello.platform, hello.softwareVersion)
            ?: throw IosXeAdapterException(ProvisioningErrorCode.UNSUPPORTED_CAPABILITY)
        if (!hello.supports(profile) || command.operationClass !in profile.supportedOperations) {
            throw IosXeAdapterException(ProvisioningErrorCode.UNSUPPORTED_CAPABILITY)
        }
        return profile
    }

    private fun desired(command: ProvisioningPlanStepCommand): IosXeDesiredConfiguration {
        val values = command.payload.values
        if (values.tagging != "SINGLE_TAG") throw IosXeAdapterException(ProvisioningErrorCode.UNSUPPORTED_CAPABILITY)
        val vlanId = values.vlanId?.toIntOrNull()?.takeIf { it in 2..4094 }
            ?: throw IosXeAdapterException(ProvisioningErrorCode.VALIDATION_FAILED)
        return IosXeDesiredConfiguration(
            vlanId = vlanId,
            trunkInterfaces = values.trunkPorts.csv(),
            accessInterfaces = values.accessPorts.csv(),
            aclName = values.firewallChain,
            remove = command.operationClass.startsWith("REMOVE_"),
        )
    }

    private fun requireTarget(target: NasTarget, command: ProvisioningPlanStepCommand) {
        if (clock.instant() >= command.deadline) throw IosXeAdapterException(ProvisioningErrorCode.TIMEOUT)
        if (target.vendor.uppercase() != vendor || target.nasId != command.target.deviceId || command.target.transport != TRANSPORT) {
            throw IosXeAdapterException(ProvisioningErrorCode.UNSUPPORTED_CAPABILITY)
        }
    }

    private fun connect(target: NasTarget): IosXeNetconfSession {
        if (target.adapterType != "IOS_XE_NETCONF" || target.apiPort != 830 || target.host.isNullOrBlank()) {
            throw IosXeAdapterException(ProvisioningErrorCode.INSECURE_TRANSPORT)
        }
        val username = target.apiUsername?.takeIf(String::isNotBlank)
            ?: throw IosXeAdapterException(ProvisioningErrorCode.INSECURE_TRANSPORT)
        val password = target.apiSecret?.takeIf(String::isNotBlank)
            ?: throw IosXeAdapterException(ProvisioningErrorCode.INSECURE_TRANSPORT)
        return sessionFactory.open(target, IosXeCredentials(username, password))
    }

    private fun abortCandidate(session: IosXeNetconfSession, locked: Boolean) {
        if (!locked) return
        runCatching { session.discardChanges() }
        runCatching { session.unlockCandidate() }
    }

    private fun requirePrecondition(command: ProvisioningPlanStepCommand, actual: String) {
        if (command.expectedPreconditionHash != actual) throw IosXeAdapterException(ProvisioningErrorCode.STALE_PRECONDITION)
    }

    private fun IosXeHello.supports(profile: IosXeProfile): Boolean =
        capabilities.containsAll(IosXeCapabilities.PROTOCOL) && yangModules.containsAll(profile.requiredModules)

    private fun observation(state: IosXeOperationalState, matches: Boolean) = ProvisioningVerificationObservation(
        clock.instant(), matches, state.hash(), state.resultState(),
    )

    private fun IosXeOperationalState.resultState() = ProvisioningResultState(if (vlanPresent) 1 else 0)

    private fun success(
        command: ProvisioningPlanStepCommand,
        preflight: ProvisioningPreflightSnapshot? = null,
        apply: ProvisioningApplyResult? = null,
        verification: ProvisioningVerificationObservation,
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
    )

    private fun failed(
        command: ProvisioningPlanStepCommand,
        code: ProvisioningErrorCode,
        verification: ProvisioningVerificationObservation? = null,
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
        success = false,
        completedAt = clock.instant(),
        errorCode = code,
        verification = verification,
        rollback = rollback,
    )

    private fun Throwable.toProvisioningError(): ProvisioningErrorCode = when (this) {
        is IosXeAdapterException -> code
        is IosXeNetconfException -> error.toProvisioningError()
        is IOException -> ProvisioningErrorCode.TIMEOUT
        else -> ProvisioningErrorCode.MANUAL_RECONCILIATION
    }

    private fun IosXeNetconfError.toProvisioningError(): ProvisioningErrorCode = when (this) {
        IosXeNetconfError.VALIDATION -> ProvisioningErrorCode.VALIDATION_FAILED
        IosXeNetconfError.TIMEOUT -> ProvisioningErrorCode.TIMEOUT
        IosXeNetconfError.LOCK_DENIED, IosXeNetconfError.RPC_ERROR -> ProvisioningErrorCode.MANUAL_RECONCILIATION
    }

    private fun String?.csv(): Set<String> = this?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet().orEmpty()

    private class IosXeAdapterException(val code: ProvisioningErrorCode) : RuntimeException(code.name)

    private companion object {
        const val TRANSPORT = "NETCONF_SSH"
    }
}
