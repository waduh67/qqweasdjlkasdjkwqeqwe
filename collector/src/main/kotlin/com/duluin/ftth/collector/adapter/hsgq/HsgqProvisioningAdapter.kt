package com.duluin.ftth.collector.adapter.hsgq

import com.duluin.ftth.collector.adapter.OltProvisioningAdapter
import com.duluin.ftth.contract.DeviceCapabilityReport
import com.duluin.ftth.contract.DeviceFingerprint
import com.duluin.ftth.contract.OltManagementTransport
import com.duluin.ftth.contract.OltTarget
import com.duluin.ftth.contract.ProvisioningApplyResult
import com.duluin.ftth.contract.ProvisioningCommandPhase
import com.duluin.ftth.contract.ProvisioningErrorCode
import com.duluin.ftth.contract.ProvisioningPlanStepCommand
import com.duluin.ftth.contract.ProvisioningPreflightSnapshot
import com.duluin.ftth.contract.ProvisioningResultState
import com.duluin.ftth.contract.ProvisioningRollbackResult
import com.duluin.ftth.contract.ProvisioningStepResult
import com.duluin.ftth.contract.ProvisioningVerificationObservation
import com.duluin.ftth.contract.deliveryKey
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock

class HsgqProvisioningAdapter(
    private val sessionFactory: HsgqManagementSessionFactory,
    private val credentialResolver: HsgqCredentialResolver,
    private val certifications: HsgqCertificationRegistry = HsgqCertificationRegistry(),
    private val stateStore: HsgqProvisioningStateStore,
    private val clock: Clock,
) : OltProvisioningAdapter {
    override val vendor: String = "HSGQ"

    override fun capabilityReport(target: OltTarget): DeviceCapabilityReport = withSession(target) { session ->
        val state = session.discover()
        val fingerprint = fingerprint(target, state)
        val certification = certifications.find(fingerprint)
        DeviceCapabilityReport(
            targetId = target.oltId,
            fingerprint = DeviceFingerprint("HSGQ", state.model, state.firmware, fingerprint.transport.name),
            capabilities = if (certification == null) {
                setOf("CERTIFICATION_PROVISIONAL")
            } else {
                setOf("CERTIFIED:${certification.evidenceSha256}")
            },
            reportedAt = clock.instant(),
            operationClasses = certification?.operationClasses ?: HsgqOperation.supported,
        )
    }

    override fun execute(target: OltTarget, command: ProvisioningPlanStepCommand): ProvisioningStepResult =
        stateStore.withExecutionLock(target.oltId) {
            val digest = commandDigest(target, command)
            try {
                validateEnvelope(target, command)
                when (val cached = stateStore.result(command.deliveryKey(), digest)) {
                    is HsgqResultLookup.Hit -> return@withExecutionLock cached.result
                    HsgqResultLookup.Conflict -> throw HsgqCommandFailure(ProvisioningErrorCode.STALE_PRECONDITION)
                    HsgqResultLookup.Missing -> Unit
                }
                if (!stateStore.acceptFence(target.oltId, command.fencingEpoch)) {
                    throw HsgqCommandFailure(ProvisioningErrorCode.STALE_PRECONDITION)
                }
                val result = withSession(target) { session -> executeSession(target, command, session) }
                stateStore.saveResult(command.deliveryKey(), digest, result)
                result
            } catch (failure: HsgqCommandFailure) {
                failed(command, failure.code).also { stateStore.saveResult(command.deliveryKey(), digest, it) }
            } catch (failure: HsgqTransportFailure) {
                val code = failure.toErrorCode()
                failed(command, code).also { if (code != ProvisioningErrorCode.TIMEOUT) stateStore.saveResult(command.deliveryKey(), digest, it) }
            } catch (_: HsgqStatePersistenceException) {
                failed(command, ProvisioningErrorCode.PERSISTENCE_FAILED)
            }
        }

    private fun executeSession(
        target: OltTarget,
        command: ProvisioningPlanStepCommand,
        session: HsgqManagementSession,
    ): ProvisioningStepResult {
        val desired = desired(command)
        val current = session.discover()
        verifyTargetFingerprint(target, current)
        validateManagementSafety(command, desired, current)
        val certification = requireCertification(target, current, command.operationClass, command.phase)
        return when (command.phase) {
            ProvisioningCommandPhase.PREFLIGHT -> preflight(command, current, desired, certification)
            ProvisioningCommandPhase.APPLY -> apply(command, session, current, desired, certification)
            ProvisioningCommandPhase.VERIFY -> verify(command, current, desired)
            ProvisioningCommandPhase.ROLLBACK -> rollback(command, session, current)
        }
    }

    private fun preflight(
        command: ProvisioningPlanStepCommand,
        state: HsgqDeviceState,
        desired: HsgqDesiredVlan,
        certification: HsgqCertification?,
    ): ProvisioningStepResult {
        val hash = state.sha256()
        val snapshot = stateStore.saveSnapshotIfAbsent(
            stepKey(command),
            HsgqProvisioningSnapshot(hash, state, intentDigest(command, desired)),
        )
        if (snapshot.intentDigest != intentDigest(command, desired)) {
            throw HsgqCommandFailure(ProvisioningErrorCode.STALE_PRECONDITION)
        }
        return success(
            command,
            preflight = ProvisioningPreflightSnapshot(clock.instant(), snapshot.beforeHash, projection(snapshot.before)),
            verification = observation(state, desired.matches(state, command.operationClass)),
        )
    }

    private fun apply(
        command: ProvisioningPlanStepCommand,
        session: HsgqManagementSession,
        before: HsgqDeviceState,
        desired: HsgqDesiredVlan,
        certification: HsgqCertification?,
    ): ProvisioningStepResult {
        if (certification == null) throw HsgqCommandFailure(ProvisioningErrorCode.UNCERTIFIED_FINGERPRINT)
        if (desired.matches(before, command.operationClass)) {
            return success(
                command,
                apply = ProvisioningApplyResult(clock.instant(), false, before.sha256()),
                verification = observation(before, true),
            )
        }
        val digest = intentDigest(command, desired)
        val snapshot = stateStore.snapshot(stepKey(command)) ?: stateStore.saveSnapshotIfAbsent(
            stepKey(command),
            HsgqProvisioningSnapshot(before.sha256(), before, digest),
        )
        if (snapshot.intentDigest != digest ||
            (command.expectedPreconditionHash != null && command.expectedPreconditionHash != snapshot.beforeHash)
        ) {
            throw HsgqCommandFailure(ProvisioningErrorCode.STALE_PRECONDITION)
        }
        mutateAndPersist(session, desired, command.operationClass, snapshot.before)
        val after = session.discover()
        requireManagementUnchanged(snapshot.before, after)
        if (!desired.matches(after, command.operationClass)) {
            restoreAfterFailure(session, snapshot.before)
            throw HsgqCommandFailure(ProvisioningErrorCode.VERIFICATION_MISMATCH)
        }
        val afterHash = after.sha256()
        stateStore.markApplied(stepKey(command), afterHash)
        return success(
            command,
            apply = ProvisioningApplyResult(clock.instant(), true, afterHash),
            verification = observation(after, true),
        )
    }

    private fun mutateAndPersist(
        session: HsgqManagementSession,
        desired: HsgqDesiredVlan,
        operationClass: String,
        baseline: HsgqDeviceState,
    ) {
        try {
            when (operationClass) {
                HsgqOperation.ENSURE_TAGGED_VLAN -> {
                    session.ensureSubscriberVlan(desired)
                    desired.taggedUplinks.sorted().forEach { session.ensureTaggedUplink(desired, it) }
                }
                HsgqOperation.REMOVE_TAGGED_VLAN -> {
                    session.removeSubscriberVlan(desired)
                    desired.taggedUplinks.sorted().forEach { session.removeTaggedUplink(desired, it) }
                }
                else -> throw HsgqCommandFailure(ProvisioningErrorCode.UNSUPPORTED_CAPABILITY)
            }
            session.persist()
            session.reconnect()
        } catch (failure: Exception) {
            restoreAfterFailure(session, baseline)
            throw failure
        }
    }

    private fun restoreAfterFailure(session: HsgqManagementSession, baseline: HsgqDeviceState) {
        runCatching {
            session.restore(baseline)
            session.persist()
            session.reconnect()
        }
    }

    private fun verify(
        command: ProvisioningPlanStepCommand,
        state: HsgqDeviceState,
        desired: HsgqDesiredVlan,
    ): ProvisioningStepResult {
        val matches = desired.matches(state, command.operationClass)
        if (!matches) throw HsgqCommandFailure(ProvisioningErrorCode.VERIFICATION_MISMATCH)
        return success(command, verification = observation(state, true))
    }

    private fun rollback(
        command: ProvisioningPlanStepCommand,
        session: HsgqManagementSession,
        current: HsgqDeviceState,
    ): ProvisioningStepResult {
        val snapshot = stateStore.snapshot(stepKey(command))
            ?: throw HsgqCommandFailure(ProvisioningErrorCode.ROLLBACK_CONFLICT)
        if (current.sha256() != snapshot.beforeHash) {
            session.restore(snapshot.before)
            session.persist()
            session.reconnect()
        }
        val restored = session.discover()
        requireManagementUnchanged(snapshot.before, restored)
        if (restored.sha256() != snapshot.beforeHash) {
            throw HsgqCommandFailure(ProvisioningErrorCode.MANUAL_RECONCILIATION)
        }
        return success(
            command,
            verification = observation(restored, true),
            rollback = ProvisioningRollbackResult(clock.instant(), true, restored.sha256()),
        )
    }

    private fun requireCertification(
        target: OltTarget,
        state: HsgqDeviceState,
        operationClass: String,
        phase: ProvisioningCommandPhase,
    ): HsgqCertification? {
        val certification = certifications.find(fingerprint(target, state))
        if (phase == ProvisioningCommandPhase.PREFLIGHT) return certification
        if (phase == ProvisioningCommandPhase.ROLLBACK) return certification
        if (certification == null || operationClass !in certification.operationClasses) {
            throw HsgqCommandFailure(ProvisioningErrorCode.UNCERTIFIED_FINGERPRINT)
        }
        return certification
    }

    private fun validateEnvelope(target: OltTarget, command: ProvisioningPlanStepCommand) {
        if (target.vendor.uppercase() != "HSGQ" || command.target.deviceId != target.oltId || command.target.deviceKind != "OLT") {
            throw HsgqCommandFailure(ProvisioningErrorCode.STALE_PRECONDITION)
        }
        if (!clock.instant().isBefore(command.deadline)) throw HsgqCommandFailure(ProvisioningErrorCode.TIMEOUT)
        when (target.managementTransport) {
            OltManagementTransport.TELNET -> throw HsgqCommandFailure(ProvisioningErrorCode.REQUIRES_MANUAL)
            OltManagementTransport.HTTPS_API, OltManagementTransport.SSH -> Unit
            null -> throw HsgqCommandFailure(ProvisioningErrorCode.INSECURE_TRANSPORT)
        }
        if (target.managementPort !in 1..65535) throw HsgqCommandFailure(ProvisioningErrorCode.INSECURE_TRANSPORT)
    }

    private fun desired(command: ProvisioningPlanStepCommand): HsgqDesiredVlan {
        val values = command.payload.values
        val vlanId = values.vlanId?.toIntOrNull()?.takeIf { it in 2..4094 }
            ?: throw HsgqCommandFailure(ProvisioningErrorCode.VALIDATION_FAILED)
        if (values.tagging != "SINGLE_TAG") throw HsgqCommandFailure(ProvisioningErrorCode.UNSUPPORTED_CAPABILITY)
        val subscriber = csv(values.accessPorts).singleOrNull()
            ?: throw HsgqCommandFailure(ProvisioningErrorCode.VALIDATION_FAILED)
        val uplinks = csv(values.trunkPorts).toSet()
        if (uplinks.isEmpty()) throw HsgqCommandFailure(ProvisioningErrorCode.VALIDATION_FAILED)
        return HsgqDesiredVlan(vlanId, subscriber, uplinks)
    }

    private fun validateManagementSafety(
        command: ProvisioningPlanStepCommand,
        desired: HsgqDesiredVlan,
        state: HsgqDeviceState,
    ) {
        val values = command.payload.values
        if (values.managementPathProven != "true") throw HsgqCommandFailure(ProvisioningErrorCode.MANAGEMENT_PATH_UNPROVEN)
        val protectedVlans = csv(values.protectedVlanIds).mapNotNull(String::toIntOrNull).toSet()
        val protectedInterfaces = csv(values.protectedInterfaces).toSet()
        if (desired.vlanId == state.managementVlanId || desired.vlanId in protectedVlans ||
            desired.subscriberPort == state.managementInterface || desired.subscriberPort in protectedInterfaces ||
            desired.taggedUplinks.any { it == state.managementInterface || it in protectedInterfaces }
        ) {
            throw HsgqCommandFailure(ProvisioningErrorCode.PROTECTED_RESOURCE)
        }
    }

    private fun verifyTargetFingerprint(target: OltTarget, state: HsgqDeviceState) {
        if (target.model != state.model || target.firmware != state.firmware) {
            throw HsgqCommandFailure(ProvisioningErrorCode.UNCERTIFIED_FINGERPRINT)
        }
    }

    private fun requireManagementUnchanged(before: HsgqDeviceState, after: HsgqDeviceState) {
        if (before.managementVlanId != after.managementVlanId || before.managementInterface != after.managementInterface) {
            throw HsgqCommandFailure(ProvisioningErrorCode.PROTECTED_RESOURCE)
        }
    }

    private fun fingerprint(target: OltTarget, state: HsgqDeviceState) = HsgqFirmwareFingerprint(
        state.model,
        state.firmware,
        requireNotNull(target.managementTransport),
    )

    private fun <T> withSession(target: OltTarget, block: (HsgqManagementSession) -> T): T {
        val reference = target.managementCredentialRef
            ?: throw HsgqCommandFailure(ProvisioningErrorCode.AUTHENTICATION_FAILED)
        val credentials = credentialResolver.resolve(reference)
            ?: throw HsgqCommandFailure(ProvisioningErrorCode.AUTHENTICATION_FAILED)
        return sessionFactory.open(target, credentials).use(block)
    }

    private fun observation(state: HsgqDeviceState, matches: Boolean) = ProvisioningVerificationObservation(
        clock.instant(),
        matches,
        state.sha256(),
        projection(state),
    )

    private fun projection(state: HsgqDeviceState) = ProvisioningResultState(state.managedResourceCount())

    private fun success(
        command: ProvisioningPlanStepCommand,
        preflight: ProvisioningPreflightSnapshot? = null,
        apply: ProvisioningApplyResult? = null,
        verification: ProvisioningVerificationObservation,
        rollback: ProvisioningRollbackResult? = null,
    ) = ProvisioningStepResult(
        command.planId, command.revision, command.stepId, command.attemptId, command.target.deviceId,
        command.operationClass, command.idempotencyKey, command.fencingEpoch, command.phase, true, clock.instant(),
        preflight = preflight, apply = apply, verification = verification, rollback = rollback,
    )

    private fun failed(command: ProvisioningPlanStepCommand, code: ProvisioningErrorCode) = ProvisioningStepResult(
        command.planId, command.revision, command.stepId, command.attemptId, command.target.deviceId,
        command.operationClass, command.idempotencyKey, command.fencingEpoch, command.phase, false, clock.instant(), code,
    )

    private fun commandDigest(target: OltTarget, command: ProvisioningPlanStepCommand): String {
        val values = command.payload.values
        return sha256(
            listOf(
                target.oltId, target.model, target.firmware, target.managementTransport, target.managementPort,
                command.planId, command.revision, command.stepId, command.attemptId, command.phase,
                command.operationClass, command.idempotencyKey, command.fencingEpoch,
                command.expectedPreconditionHash, command.deadline, command.target,
                values.tenantId, values.intentId, values.bridge, values.vlanId, values.tagging,
                values.trunkPorts, values.accessPorts, values.vlanInterface, values.vlanParent,
                values.pppoeInterface, values.pppoeServiceName, values.pppoeVlanRange,
                values.poolName, values.poolRanges, values.interfaceList, values.firewallChain,
                values.managementPathProven, values.managementSourceId, values.managementSourceType,
                values.protectedInterfaces, values.protectedVlanIds,
            ).joinToString("|"),
        )
    }

    private fun intentDigest(command: ProvisioningPlanStepCommand, desired: HsgqDesiredVlan): String = sha256(
        listOf(command.planId, command.revision, command.stepId, command.operationClass, desired).joinToString("|"),
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun stepKey(command: ProvisioningPlanStepCommand) = "${command.planId}:${command.revision}:${command.stepId}"

    private fun csv(value: String?): List<String> = value.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
}

private class HsgqCommandFailure(val code: ProvisioningErrorCode) : RuntimeException(code.name)

private fun HsgqTransportFailure.toErrorCode(): ProvisioningErrorCode = when (kind) {
    HsgqFailureKind.AUTHENTICATION -> ProvisioningErrorCode.AUTHENTICATION_FAILED
    HsgqFailureKind.TIMEOUT -> ProvisioningErrorCode.TIMEOUT
    HsgqFailureKind.PERSISTENCE -> ProvisioningErrorCode.PERSISTENCE_FAILED
    HsgqFailureKind.TRANSPORT -> ProvisioningErrorCode.MANUAL_RECONCILIATION
}
