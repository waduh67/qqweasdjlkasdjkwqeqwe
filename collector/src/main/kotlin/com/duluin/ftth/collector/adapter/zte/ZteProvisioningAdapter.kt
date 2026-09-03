package com.duluin.ftth.collector.adapter.zte

import com.duluin.ftth.contract.DeviceCapabilityReport
import com.duluin.ftth.contract.DeviceFingerprint
import java.time.Clock

fun interface ZteCliTransport {
    fun execute(command: String): String
}

data class ZteCapabilityReport(
    val report: DeviceCapabilityReport,
    val certification: ZteCertification,
)

class ZteProvisioningAdapter(
    private val transport: ZteCliTransport,
    private val profiles: ZteProfileRegistry = ZteProfileRegistry.provisional(),
    private val clock: Clock = Clock.systemUTC(),
) {
    private val appliedMutations = mutableMapOf<String, ZteMutationSet>()

    fun capabilityReport(target: ZteTarget): ZteCapabilityReport {
        val profile = discoverProfile(target)
        return ZteCapabilityReport(
            report = DeviceCapabilityReport(
                targetId = target.deviceId,
                fingerprint = DeviceFingerprint("ZTE", profile.key.family, profile.key.firmware, "SSH_CLI"),
                capabilities = profile.capabilities,
                reportedAt = clock.instant(),
                operationClasses = setOf("OLT_GPON_SERVICE_ATTACH", "OLT_GPON_SERVICE_DETACH"),
            ),
            certification = profile.certification,
        )
    }

    @Synchronized
    fun apply(target: ZteTarget, plan: ZteServicePlan, mode: ZteExecutionMode): ZteProvisioningOutcome {
        requireExecutableMode(mode)
        val profile = discoverProfile(target)
        requireCapabilities(profile, plan)
        val before = inspect(profile, plan)
        validateExisting(before, plan)
        val mutations = missing(before)
        val commands = profile.applyCommands(plan, mutations)
        if (mode == ZteExecutionMode.DRY_RUN) return ZteProvisioningOutcome(mutations.any, before, commands)
        execute(commands)
        val after = inspect(profile, plan)
        if (!after.matches(plan)) {
            throw ZteAdapterException(ZteFailureCode.VERIFICATION_MISMATCH, "ZTE readback did not match the requested service")
        }
        appliedMutations.putIfAbsent(receiptKey(target, plan), mutations)
        return ZteProvisioningOutcome(mutations.any, after, commands)
    }

    @Synchronized
    fun compensate(target: ZteTarget, plan: ZteServicePlan, mode: ZteExecutionMode): ZteProvisioningOutcome {
        requireExecutableMode(mode)
        val profile = discoverProfile(target)
        requireCapabilities(profile, plan)
        val mutations = appliedMutations[receiptKey(target, plan)]
            ?: throw ZteAdapterException(ZteFailureCode.ROLLBACK_CONFLICT, "No ZTE mutation receipt exists for this operation")
        val current = inspect(profile, plan)
        validateCompensationState(current, plan, mutations)
        val commands = profile.inverseCommands(plan, mutations)
        if (mode == ZteExecutionMode.DRY_RUN) return ZteProvisioningOutcome(commands.isNotEmpty(), current, commands)
        execute(commands)
        val restored = inspect(profile, plan)
        if (createdResourcesRemain(restored, mutations)) {
            throw ZteAdapterException(ZteFailureCode.VERIFICATION_MISMATCH, "ZTE inverse readback retained an owned resource")
        }
        appliedMutations.remove(receiptKey(target, plan))
        return ZteProvisioningOutcome(commands.isNotEmpty(), restored, commands)
    }

    private fun discoverProfile(target: ZteTarget): ZteCliProfile {
        val body = command("show version")
        val discovered = ZteTranscriptParser.profileKey(body)
        val expected = ZteProfileKey(target.expectedFamily, target.expectedFirmware)
        if (discovered != expected) {
            throw ZteAdapterException(
                ZteFailureCode.UNRECOGNIZED_DEVICE_RESPONSE,
                "Discovered ZTE identity does not match the exact target fingerprint",
            )
        }
        return profiles.require(discovered)
    }

    private fun inspect(profile: ZteCliProfile, plan: ZteServicePlan): ZteNormalizedState {
        val outputs = profile.readCommands(plan).associateWith(::command)
        return ZteStateParser.parse(
            plan,
            outputs.getValue("show vlan ${plan.vlanId}"),
            outputs.getValue("show vlan port ${plan.vlanId}"),
            outputs.getValue("show running-config interface ${plan.onu.notation}"),
        )
    }

    private fun command(value: String): String {
        val body = ZteTranscriptParser.commandBody(value, transport.execute(value))
        if (value == "write" && body != WRITE_SUCCEEDED) {
            throw ZteAdapterException(
                ZteFailureCode.UNRECOGNIZED_DEVICE_RESPONSE,
                "ZTE persistence response was not the documented success marker",
            )
        }
        return body
    }

    private fun execute(commands: List<String>) {
        commands.forEach(::command)
    }

    private fun requireExecutableMode(mode: ZteExecutionMode) {
        if (mode == ZteExecutionMode.PRODUCTION) {
            throw ZteAdapterException(
                ZteFailureCode.PRODUCTION_NOT_CERTIFIED,
                "All ZTE profiles are provisional; production auto-apply is denied",
            )
        }
    }

    private fun requireCapabilities(profile: ZteCliProfile, plan: ZteServicePlan) {
        if (!profile.capabilities.containsAll(REQUIRED_CAPABILITIES) || plan.requiredCapability !in profile.capabilities) {
            throw ZteAdapterException(ZteFailureCode.UNSUPPORTED_CAPABILITY, "Exact ZTE profile lacks a required service capability")
        }
    }

    private fun validateExisting(state: ZteNormalizedState, plan: ZteServicePlan) {
        if ((state.tcontProfile != null && state.tcontProfile != plan.tcontProfile) ||
            (state.gemTcontId != null && state.gemTcontId != plan.tcontId) ||
            (state.serviceBinding != null && state.serviceBinding != ZteServiceBinding(plan.gemPortId, plan.userVlanId, plan.vlanId))
        ) {
            throw ZteAdapterException(ZteFailureCode.STALE_PRECONDITION, "An explicit ZTE resource identifier is already bound differently")
        }
    }

    private fun validateCompensationState(state: ZteNormalizedState, plan: ZteServicePlan, mutations: ZteMutationSet) {
        if ((mutations.tcont && state.tcontProfile != plan.tcontProfile) ||
            (mutations.gem && state.gemTcontId != plan.tcontId) ||
            (mutations.service && state.serviceBinding != ZteServiceBinding(plan.gemPortId, plan.userVlanId, plan.vlanId))
        ) {
            throw ZteAdapterException(ZteFailureCode.ROLLBACK_CONFLICT, "ZTE service state drifted before compensation")
        }
    }

    private fun missing(state: ZteNormalizedState) = ZteMutationSet(
        vlan = !state.vlanPresent,
        uplink = !state.uplinkTagged,
        tcont = state.tcontProfile == null,
        gem = state.gemTcontId == null,
        service = state.serviceBinding == null,
    )

    private fun createdResourcesRemain(state: ZteNormalizedState, mutations: ZteMutationSet): Boolean =
        (mutations.vlan && state.vlanPresent) || (mutations.uplink && state.uplinkTagged) ||
            (mutations.tcont && state.tcontProfile != null) || (mutations.gem && state.gemTcontId != null) ||
            (mutations.service && state.serviceBinding != null)

    private fun receiptKey(target: ZteTarget, plan: ZteServicePlan) = "${target.deviceId}:${plan.operationKey}"

    private companion object {
        const val WRITE_SUCCEEDED = "Building configuration......[OK]"
        val REQUIRED_CAPABILITIES = setOf(
            "SINGLE_TAG_802_1Q",
            "TAGGED_UPLINK",
            "GPON_TCONT_GEM_ASSOCIATION",
            "GPON_SERVICE_PORT",
            "PERSISTENT_WRITE",
            "READBACK",
            "INVERSE_COMMANDS",
        )
    }
}
