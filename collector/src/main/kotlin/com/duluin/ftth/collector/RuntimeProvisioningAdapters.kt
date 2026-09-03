package com.duluin.ftth.collector

import com.duluin.ftth.collector.adapter.FileRouterOsProvisioningStateStore
import com.duluin.ftth.collector.adapter.OltProvisioningAdapter
import com.duluin.ftth.collector.adapter.OltProvisioningAdapterRegistry
import com.duluin.ftth.collector.adapter.ProvisioningAdapter
import com.duluin.ftth.collector.adapter.ProvisioningAdapterRegistry
import com.duluin.ftth.collector.adapter.RouterOsProvisioningAdapter
import com.duluin.ftth.collector.adapter.hsgq.ProvisionalHsgqProvisioningAdapter
import com.duluin.ftth.contract.DeviceCapabilityReport
import com.duluin.ftth.contract.DeviceFingerprint
import com.duluin.ftth.contract.NasTarget
import com.duluin.ftth.contract.OltTarget
import com.duluin.ftth.contract.ProvisioningErrorCode
import com.duluin.ftth.contract.ProvisioningPlanStepCommand
import com.duluin.ftth.contract.ProvisioningApplyResult
import com.duluin.ftth.contract.ProvisioningCommandPhase
import com.duluin.ftth.contract.ProvisioningPreflightSnapshot
import com.duluin.ftth.contract.ProvisioningResultState
import com.duluin.ftth.contract.ProvisioningRollbackResult
import com.duluin.ftth.contract.ProvisioningStepResult
import com.duluin.ftth.contract.ProvisioningVerificationObservation
import java.nio.file.Path
import java.time.Clock

data class RuntimeProvisioningRegistries(
    val nas: ProvisioningAdapterRegistry,
    val olt: OltProvisioningAdapterRegistry,
)

object RuntimeProvisioningAdapterFactory {
    fun create(simulatorEnabled: Boolean, stateDirectory: Path): RuntimeProvisioningRegistries = if (simulatorEnabled) {
        RuntimeProvisioningRegistries(
            ProvisioningAdapterRegistry(NAS_VENDORS.map { RuntimeNasAdapter(it, "SIMULATOR", simulator = true) }),
            OltProvisioningAdapterRegistry(OLT_VENDORS.map { RuntimeOltAdapter(it, "SIMULATOR", simulator = true) }),
        )
    } else {
        RuntimeProvisioningRegistries(
            ProvisioningAdapterRegistry(
                listOf(
                    RouterOsProvisioningAdapter(
                        stateStore = FileRouterOsProvisioningStateStore(stateDirectory.resolve("routeros-provisioning-state.json")),
                    ),
                    RuntimeNasAdapter("CISCO", "NETCONF_SSH"),
                    RuntimeNasAdapter("JUNIPER", "NETCONF_SSH"),
                ),
            ),
            OltProvisioningAdapterRegistry(
                listOf(
                    ProvisionalHsgqProvisioningAdapter(),
                    RuntimeOltAdapter("HUAWEI", "SSH_CLI"),
                    RuntimeOltAdapter("ZTE", "SSH_CLI"),
                ),
            ),
        )
    }

    private val NAS_VENDORS = listOf("MIKROTIK", "CISCO", "JUNIPER")
    private val OLT_VENDORS = listOf("HSGQ", "HUAWEI", "ZTE")
}

private class RuntimeNasAdapter(
    override val vendor: String,
    private val transport: String,
    private val simulator: Boolean = false,
    private val clock: Clock = Clock.systemUTC(),
) : ProvisioningAdapter {
    override fun capabilityReport(target: NasTarget) = runtimeReport(target.nasId, vendor, transport, simulator, clock)
    override fun execute(target: NasTarget, command: ProvisioningPlanStepCommand) =
        if (simulator) simulated(command, target.nasId, clock) else rejected(command, target.nasId, clock)
}

private class RuntimeOltAdapter(
    override val vendor: String,
    private val transport: String,
    private val simulator: Boolean = false,
    private val clock: Clock = Clock.systemUTC(),
) : OltProvisioningAdapter {
    override fun capabilityReport(target: OltTarget) = runtimeReport(target.oltId, vendor, transport, simulator, clock)
    override fun execute(target: OltTarget, command: ProvisioningPlanStepCommand) =
        if (simulator) simulated(command, target.oltId, clock) else rejected(command, target.oltId, clock)
}

private fun runtimeReport(targetId: String, vendor: String, transport: String, simulator: Boolean, clock: Clock) = DeviceCapabilityReport(
    targetId,
    DeviceFingerprint(vendor, "PROVISIONAL", "UNCONFIGURED", transport),
    if (simulator) setOf("SIMULATOR") else setOf("CERTIFICATION_PROVISIONAL"),
    clock.instant(),
    if (simulator) SUPPORTED_OPERATIONS else emptySet(),
)

private val SUPPORTED_OPERATIONS = setOf(
    "ENSURE_TAGGED_VLAN", "REMOVE_TAGGED_VLAN", "VERIFY_STATE", "ENSURE_ACCESS_PORT", "REMOVE_ACCESS_PORT",
    "ENSURE_PPPOE_TERMINATION", "REMOVE_PPPOE_TERMINATION",
)

private fun simulated(command: ProvisioningPlanStepCommand, targetId: String, clock: Clock): ProvisioningStepResult {
    val completedAt = clock.instant()
    val vlanIds = command.payload.values.vlanId?.toIntOrNull()?.let(::listOf).orEmpty()
    val state = ProvisioningResultState(managedResourceCount = vlanIds.size, vlanIds = vlanIds)
    val hash = state.observationHash()
    return ProvisioningStepResult(
        planId = command.planId,
        revision = command.revision,
        stepId = command.stepId,
        attemptId = command.attemptId,
        targetId = targetId,
        operationClass = command.operationClass,
        idempotencyKey = command.idempotencyKey,
        fencingEpoch = command.fencingEpoch,
        phase = command.phase,
        success = true,
        completedAt = completedAt,
        preflight = if (command.phase == ProvisioningCommandPhase.PREFLIGHT) ProvisioningPreflightSnapshot(completedAt, hash, state) else null,
        apply = if (command.phase == ProvisioningCommandPhase.APPLY) ProvisioningApplyResult(completedAt, true, hash) else null,
        verification = ProvisioningVerificationObservation(completedAt, true, hash, state),
        rollback = if (command.phase == ProvisioningCommandPhase.ROLLBACK) ProvisioningRollbackResult(completedAt, true, hash) else null,
    )
}

private fun rejected(command: ProvisioningPlanStepCommand, targetId: String, clock: Clock) = ProvisioningStepResult(
    command.planId,
    command.revision,
    command.stepId,
    command.attemptId,
    targetId,
    command.operationClass,
    command.idempotencyKey,
    command.fencingEpoch,
    command.phase,
    false,
    clock.instant(),
    ProvisioningErrorCode.UNCERTIFIED_FINGERPRINT,
)
