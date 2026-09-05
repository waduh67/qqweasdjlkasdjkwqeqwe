package com.duluin.ftth.collector.adapter.junos

import com.duluin.ftth.collector.adapter.ProvisioningAdapter
import com.duluin.ftth.contract.DeviceCapabilityReport
import com.duluin.ftth.contract.DeviceFingerprint
import com.duluin.ftth.contract.NasTarget
import com.duluin.ftth.contract.ProvisioningApplyResult
import com.duluin.ftth.contract.ProvisioningCommandPhase
import com.duluin.ftth.contract.ProvisioningErrorCode
import com.duluin.ftth.contract.ProvisioningPlanStepCommand
import com.duluin.ftth.contract.ProvisioningPreflightSnapshot
import com.duluin.ftth.contract.ProvisioningRollbackResult
import com.duluin.ftth.contract.ProvisioningStepResult
import java.time.Clock

class JunosProvisioningAdapter(
    private val sessionFactory: JunosNetconfSessionFactory,
    private val rollbackJournal: JunosRollbackJournal = InMemoryJunosRollbackJournal(),
    private val clock: Clock = Clock.systemUTC(),
) : ProvisioningAdapter {
    override val vendor: String = "JUNIPER"
    private val results = JunosProvisioningResults(clock)

    override fun capabilityReport(target: NasTarget): DeviceCapabilityReport {
        sessionFactory.open(JunosConnection.from(target)).use { session ->
            val hello = session.hello()
            val profile = JunosCapabilityProfiles.find(hello.identity)
                ?: throw JunosUnsupportedCapabilityException()
            requireCapabilities(hello, profile)
            return DeviceCapabilityReport(
                targetId = target.nasId,
                fingerprint = DeviceFingerprint(vendor, hello.identity.model, hello.identity.firmware, TRANSPORT),
                capabilities = buildSet {
                    addAll(profile.requiredNetconfCapabilities)
                    add("JUNOS_${profile.family.name}")
                    if (profile.provisional) add("CERTIFICATION_PROVISIONAL")
                },
                reportedAt = clock.instant(),
                operationClasses = profile.operations.mapTo(linkedSetOf(), JunosOperation::operationClass),
            )
        }
    }

    override fun execute(target: NasTarget, command: ProvisioningPlanStepCommand): ProvisioningStepResult {
        val error = commandError(target, command)
        if (error != null) return results.failed(command, error)
        return try {
            when (command.phase) {
                ProvisioningCommandPhase.PREFLIGHT -> preflight(target, command)
                ProvisioningCommandPhase.APPLY -> apply(target, command)
                ProvisioningCommandPhase.VERIFY -> verify(target, command)
                ProvisioningCommandPhase.ROLLBACK -> rollback(target, command)
            }
        } catch (_: JunosUnsupportedCapabilityException) {
            results.failed(command, ProvisioningErrorCode.UNSUPPORTED_CAPABILITY)
        } catch (_: JunosLockDeniedException) {
            results.failed(command, ProvisioningErrorCode.STALE_PRECONDITION)
        } catch (_: JunosStalePreconditionException) {
            results.failed(command, ProvisioningErrorCode.STALE_PRECONDITION)
        } catch (_: JunosManagementPathException) {
            results.failed(command, ProvisioningErrorCode.MANAGEMENT_PATH_UNPROVEN)
        } catch (_: JunosValidationException) {
            results.failed(command, ProvisioningErrorCode.VALIDATION_FAILED)
        } catch (_: JunosConfigurationException) {
            results.failed(command, ProvisioningErrorCode.VALIDATION_FAILED)
        } catch (_: JunosVerificationException) {
            results.failed(command, ProvisioningErrorCode.VERIFICATION_MISMATCH)
        } catch (_: JunosConfirmationExpiredException) {
            results.failed(command, ProvisioningErrorCode.TIMEOUT)
        } catch (_: JunosNetconfException) {
            results.failed(command, ProvisioningErrorCode.MANUAL_RECONCILIATION)
        }
    }

    private fun preflight(target: NasTarget, command: ProvisioningPlanStepCommand): ProvisioningStepResult =
        withPreparedSession(target, command) { session, _, change ->
            val current = session.observe(change)
            val verification = results.observation(current, matches(current, change))
            results.success(
                command,
                preflight = ProvisioningPreflightSnapshot(clock.instant(), verification.stateHash, verification.state),
                verification = verification,
            )
        }

    private fun apply(target: NasTarget, command: ProvisioningPlanStepCommand): ProvisioningStepResult =
        withPreparedSession(target, command) { session, profile, change ->
            val before = session.observe(change)
            if (!before.managementReachable) throw JunosManagementPathException()
            if (command.expectedPreconditionHash != null && command.expectedPreconditionHash != results.stateHash(before)) {
                throw JunosStalePreconditionException()
            }
            var locked = false
            var confirmed: JunosConfirmedCommit? = null
            try {
                session.lockCandidate()
                locked = true
                session.editCandidate(change)
                session.validateCandidate()
                confirmed = session.commitConfirmed(profile.confirmationTimeoutSeconds)
                recordPending(command, confirmed, before)
                val after = session.observe(change)
                if (!matches(after, change)) {
                    completeAutomaticRollback(command, session, confirmed, before)
                    throw JunosVerificationException()
                }
                try {
                    session.confirmCommit(confirmed.commitId)
                } catch (expired: JunosConfirmationExpiredException) {
                    completeAutomaticRollback(command, session, confirmed, before)
                    throw expired
                }
                val verification = results.observation(after, matchesExpected = true)
                results.success(
                    command,
                    apply = ProvisioningApplyResult(
                        clock.instant(),
                        results.stateHash(before) != verification.stateHash,
                        verification.stateHash,
                    ),
                    verification = verification,
                )
            } catch (failure: JunosNetconfException) {
                if (locked && confirmed == null) session.discardCandidate()
                throw failure
            } finally {
                if (locked) session.unlockCandidate()
            }
        }

    private fun verify(target: NasTarget, command: ProvisioningPlanStepCommand): ProvisioningStepResult =
        withPreparedSession(target, command) { session, _, change ->
            val current = session.observe(change)
            if (!matches(current, change)) throw JunosVerificationException()
            results.success(command, verification = results.observation(current, matchesExpected = true))
        }

    private fun rollback(target: NasTarget, command: ProvisioningPlanStepCommand): ProvisioningStepResult {
        val record = rollbackJournal.find(results.stepKey(command))
            ?: return results.failed(command, ProvisioningErrorCode.ROLLBACK_CONFLICT)
        if (record.status != JunosRollbackStatus.AUTOMATIC_COMPLETED) {
            return results.failed(command, ProvisioningErrorCode.ROLLBACK_CONFLICT)
        }
        return withPreparedSession(target, command) { session, _, change ->
            val current = session.observe(change)
            if (results.stateHash(current) != results.stateHash(record.before)) throw JunosVerificationException()
            val verification = results.observation(current, matchesExpected = true)
            results.success(
                command,
                verification = verification,
                rollback = ProvisioningRollbackResult(clock.instant(), true, verification.stateHash),
            )
        }
    }

    private fun <T> withPreparedSession(
        target: NasTarget,
        command: ProvisioningPlanStepCommand,
        block: (JunosNetconfSession, JunosCapabilityProfile, JunosCandidateChange) -> T,
    ): T = sessionFactory.open(JunosConnection.from(target)).use { session ->
        val hello = session.hello()
        val profile = JunosCapabilityProfiles.find(hello.identity)
            ?: throw JunosUnsupportedCapabilityException()
        requireCapabilities(hello, profile)
        val operation = JunosOperation.from(command.operationClass)
            ?: throw JunosUnsupportedCapabilityException()
        if (operation !in profile.operations) throw JunosUnsupportedCapabilityException()
        block(session, profile, JunosConfiguration.build(operation, command.payload))
    }

    private fun requireCapabilities(hello: JunosHello, profile: JunosCapabilityProfile) {
        if (!hello.capabilities.containsAll(profile.requiredNetconfCapabilities)) {
            throw JunosUnsupportedCapabilityException()
        }
    }

    private fun completeAutomaticRollback(
        command: ProvisioningPlanStepCommand,
        session: JunosNetconfSession,
        commit: JunosConfirmedCommit,
        before: JunosOperationalObservation,
    ) {
        val receipt = session.awaitAutomaticRollback(commit.rollbackId)
        if (receipt.rollbackId != commit.rollbackId ||
            results.stateHash(receipt.observation) != results.stateHash(before)
        ) {
            throw JunosNetconfException("Automatic rollback could not be verified")
        }
        rollbackJournal.record(
            JunosRollbackRecord(
                results.stepKey(command), commit.commitId, receipt.rollbackId, before,
                JunosRollbackStatus.AUTOMATIC_COMPLETED, clock.instant(),
            ),
        )
    }

    private fun recordPending(
        command: ProvisioningPlanStepCommand,
        commit: JunosConfirmedCommit,
        before: JunosOperationalObservation,
    ) {
        rollbackJournal.record(
            JunosRollbackRecord(
                results.stepKey(command), commit.commitId, commit.rollbackId, before,
                JunosRollbackStatus.PENDING_AUTOMATIC, clock.instant(),
            ),
        )
    }

    private fun matches(observation: JunosOperationalObservation, change: JunosCandidateChange): Boolean =
        observation.managementReachable && observation.resources.containsAll(change.expectedResources)

    private fun commandError(target: NasTarget, command: ProvisioningPlanStepCommand): ProvisioningErrorCode? = when {
        command.target.deviceId != target.nasId -> ProvisioningErrorCode.STALE_PRECONDITION
        !target.vendor.equals(vendor, ignoreCase = true) -> ProvisioningErrorCode.UNSUPPORTED_CAPABILITY
        command.target.transport != TRANSPORT -> ProvisioningErrorCode.UNSUPPORTED_CAPABILITY
        !clock.instant().isBefore(command.deadline) -> ProvisioningErrorCode.TIMEOUT
        else -> null
    }

    private companion object {
        const val TRANSPORT = "NETCONF_SSH"
    }
}
