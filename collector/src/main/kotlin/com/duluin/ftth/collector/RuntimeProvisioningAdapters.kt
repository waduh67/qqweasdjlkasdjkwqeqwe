package com.duluin.ftth.collector

import com.duluin.ftth.collector.adapter.FileRouterOsProvisioningStateStore
import com.duluin.ftth.collector.adapter.OltProvisioningAdapter
import com.duluin.ftth.collector.adapter.OltProvisioningAdapterRegistry
import com.duluin.ftth.collector.adapter.ProvisioningAdapter
import com.duluin.ftth.collector.adapter.ProvisioningAdapterRegistry
import com.duluin.ftth.collector.adapter.RouterOsProvisioningAdapter
import com.duluin.ftth.collector.adapter.hsgq.ProvisionalHsgqProvisioningAdapter
import com.duluin.ftth.collector.adapter.huawei.HuaweiAdapterException
import com.duluin.ftth.collector.adapter.huawei.HuaweiCliTransport
import com.duluin.ftth.collector.adapter.huawei.HuaweiFailureCode
import com.duluin.ftth.collector.adapter.huawei.HuaweiProvisioningAdapter
import com.duluin.ftth.collector.adapter.huawei.HuaweiTarget
import com.duluin.ftth.collector.adapter.iosxe.IosXeNetconfError
import com.duluin.ftth.collector.adapter.iosxe.IosXeNetconfException
import com.duluin.ftth.collector.adapter.iosxe.IosXeNetconfSessionFactory
import com.duluin.ftth.collector.adapter.iosxe.IosXeProvisioningAdapter
import com.duluin.ftth.collector.adapter.junos.JunosNetconfException
import com.duluin.ftth.collector.adapter.junos.JunosNetconfSessionFactory
import com.duluin.ftth.collector.adapter.junos.JunosProvisioningAdapter
import com.duluin.ftth.collector.adapter.zte.ZteAdapterException
import com.duluin.ftth.collector.adapter.zte.ZteCliTransport
import com.duluin.ftth.collector.adapter.zte.ZteFailureCode
import com.duluin.ftth.collector.adapter.zte.ZteProvisioningAdapter
import com.duluin.ftth.collector.adapter.zte.ZteTarget
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

data class RuntimeProvisioningTransports(
    val iosXeSessions: IosXeNetconfSessionFactory = IosXeNetconfSessionFactory { _, _ ->
        throw IosXeNetconfException(IosXeNetconfError.RPC_ERROR)
    },
    val junosSessions: JunosNetconfSessionFactory = JunosNetconfSessionFactory {
        throw JunosNetconfException("NETCONF runtime session is not configured")
    },
    val huaweiTransport: (OltTarget) -> HuaweiCliTransport = {
        HuaweiCliTransport { throw HuaweiAdapterException(HuaweiFailureCode.PRODUCTION_NOT_CERTIFIED, "Huawei runtime transport unavailable") }
    },
    val zteTransport: (OltTarget) -> ZteCliTransport = {
        ZteCliTransport { throw ZteAdapterException(ZteFailureCode.PRODUCTION_NOT_CERTIFIED, "ZTE runtime transport unavailable") }
    },
)

object RuntimeProvisioningAdapterFactory {
    fun create(
        simulatorEnabled: Boolean,
        stateDirectory: Path,
        transports: RuntimeProvisioningTransports = RuntimeProvisioningTransports(),
    ): RuntimeProvisioningRegistries = if (simulatorEnabled) {
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
                    IosXeProvisioningAdapter(transports.iosXeSessions),
                    JunosProvisioningAdapter(transports.junosSessions),
                ),
            ),
            OltProvisioningAdapterRegistry(
                listOf(
                    ProvisionalHsgqProvisioningAdapter(),
                    HuaweiRuntimeOltProvisioningAdapter(transports.huaweiTransport),
                    ZteRuntimeOltProvisioningAdapter(transports.zteTransport),
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

class HuaweiRuntimeOltProvisioningAdapter(
    private val transport: (OltTarget) -> HuaweiCliTransport,
    private val clock: Clock = Clock.systemUTC(),
) : OltProvisioningAdapter {
    override val vendor: String = "HUAWEI"

    override fun capabilityReport(target: OltTarget): DeviceCapabilityReport = HuaweiProvisioningAdapter(
        transport(target),
        clock = clock,
    ).capabilityReport(
        HuaweiTarget(target.oltId, target.host, requireNotNull(target.model), requireNotNull(target.firmware)),
    ).report

    override fun execute(target: OltTarget, command: ProvisioningPlanStepCommand): ProvisioningStepResult =
        rejected(command, target.oltId, clock)
}

class ZteRuntimeOltProvisioningAdapter(
    private val transport: (OltTarget) -> ZteCliTransport,
    private val clock: Clock = Clock.systemUTC(),
) : OltProvisioningAdapter {
    override val vendor: String = "ZTE"

    override fun capabilityReport(target: OltTarget): DeviceCapabilityReport = ZteProvisioningAdapter(
        transport(target),
        clock = clock,
    ).capabilityReport(
        ZteTarget(target.oltId, target.host, requireNotNull(target.model), requireNotNull(target.firmware)),
    ).report

    override fun execute(target: OltTarget, command: ProvisioningPlanStepCommand): ProvisioningStepResult =
        rejected(command, target.oltId, clock)
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
