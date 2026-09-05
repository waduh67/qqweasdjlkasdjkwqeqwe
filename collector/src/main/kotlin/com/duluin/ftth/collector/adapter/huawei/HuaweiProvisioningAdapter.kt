package com.duluin.ftth.collector.adapter.huawei

import com.duluin.ftth.contract.DeviceCapabilityReport
import com.duluin.ftth.contract.DeviceFingerprint
import java.time.Clock

fun interface HuaweiCliTransport {
    fun execute(command: String): String
}

data class HuaweiCapabilityReport(
    val report: DeviceCapabilityReport,
    val certification: HuaweiCertification,
)

class HuaweiProvisioningAdapter(
    private val transport: HuaweiCliTransport,
    private val profiles: HuaweiProfileRegistry = HuaweiProfileRegistry.provisional(),
    private val clock: Clock = Clock.systemUTC(),
) {
    private val appliedMutations = mutableMapOf<String, HuaweiMutationSet>()

    fun capabilityReport(target: HuaweiTarget): HuaweiCapabilityReport {
        val profile = discoverProfile(target)
        return HuaweiCapabilityReport(
            DeviceCapabilityReport(
                targetId = target.deviceId,
                fingerprint = DeviceFingerprint("HUAWEI", profile.key.family, profile.key.firmware, "SSH_CLI"),
                capabilities = profile.capabilities,
                reportedAt = clock.instant(),
                operationClasses = setOf("OLT_GPON_SERVICE_ATTACH", "OLT_GPON_SERVICE_DETACH"),
            ),
            profile.certification,
        )
    }

    @Synchronized
    fun apply(
        target: HuaweiTarget,
        plan: HuaweiServicePlan,
        mode: HuaweiExecutionMode,
    ): HuaweiProvisioningOutcome {
        requireExecutableMode(mode)
        val profile = discoverProfile(target)
        requireCapabilities(profile, plan)
        val before = inspect(profile, plan)
        validateExisting(before, plan)
        val mutations = missing(before, plan)
        val commands = profile.applyCommands(plan, mutations)
        if (mode == HuaweiExecutionMode.DRY_RUN) return HuaweiProvisioningOutcome(mutations.any, before, commands)
        execute(commands)
        val after = inspect(profile, plan)
        if (!after.matches(plan)) {
            throw HuaweiAdapterException(HuaweiFailureCode.VERIFICATION_MISMATCH, "Huawei readback did not match the requested service")
        }
        appliedMutations.putIfAbsent(receiptKey(target, plan), mutations)
        return HuaweiProvisioningOutcome(mutations.any, after, commands)
    }

    @Synchronized
    fun compensate(
        target: HuaweiTarget,
        plan: HuaweiServicePlan,
        mode: HuaweiExecutionMode,
    ): HuaweiProvisioningOutcome {
        requireExecutableMode(mode)
        val profile = discoverProfile(target)
        requireCapabilities(profile, plan)
        val mutations = appliedMutations[receiptKey(target, plan)]
            ?: throw HuaweiAdapterException(HuaweiFailureCode.ROLLBACK_CONFLICT, "No Huawei mutation receipt exists for this operation")
        val current = inspect(profile, plan)
        validateCompensationState(current, plan, mutations)
        val commands = profile.inverseCommands(plan, mutations)
        if (mode == HuaweiExecutionMode.DRY_RUN) return HuaweiProvisioningOutcome(commands.isNotEmpty(), current, commands)
        execute(commands)
        val restored = inspect(profile, plan)
        if (createdResourcesRemain(restored, mutations)) {
            throw HuaweiAdapterException(HuaweiFailureCode.VERIFICATION_MISMATCH, "Huawei inverse readback retained an owned resource")
        }
        appliedMutations.remove(receiptKey(target, plan))
        return HuaweiProvisioningOutcome(commands.isNotEmpty(), restored, commands)
    }

    private fun discoverProfile(target: HuaweiTarget): HuaweiCliProfile {
        val discovered = HuaweiTranscriptParser.profileKey(command("display version"))
        val profile = profiles.require(discovered)
        if (discovered != HuaweiProfileKey(target.expectedFamily, target.expectedFirmware)) {
            throw HuaweiAdapterException(HuaweiFailureCode.FINGERPRINT_MISMATCH, "Huawei identity differs from the exact target fingerprint")
        }
        return profile
    }

    private fun inspect(profile: HuaweiCliProfile, plan: HuaweiServicePlan): HuaweiObservedState {
        val outputs = profile.readCommands(plan).associateWith(::command)
        return HuaweiStateParser.parse(
            plan,
            outputs.getValue("display vlan ${plan.vlanId}"),
            outputs.getValue("display port vlan ${plan.vlanId}"),
            outputs.getValue("display ont-lineprofile gpon profile-id ${plan.lineProfileId}"),
            outputs.getValue("display service-port ${plan.servicePortId}"),
        )
    }

    private fun command(value: String): String = HuaweiTranscriptParser.commandBody(value, transport.execute(value))

    private fun execute(commands: List<String>) {
        commands.forEach { command ->
            val body = command(command)
            if (command == "save" && body != "Save the configuration successfully.") {
                throw HuaweiAdapterException(HuaweiFailureCode.SAVE_FAILED, "Huawei did not confirm a successful configuration save")
            }
        }
    }

    private fun requireExecutableMode(mode: HuaweiExecutionMode) {
        if (mode == HuaweiExecutionMode.PRODUCTION_AUTO_APPLY) {
            throw HuaweiAdapterException(
                HuaweiFailureCode.PRODUCTION_NOT_CERTIFIED,
                "All Huawei profiles are provisional; production auto-apply is denied",
            )
        }
    }

    private fun requireCapabilities(profile: HuaweiCliProfile, plan: HuaweiServicePlan) {
        if (!profile.capabilities.containsAll(REQUIRED_CAPABILITIES) || plan.requiredCapability !in profile.capabilities) {
            throw HuaweiAdapterException(HuaweiFailureCode.UNSUPPORTED_CAPABILITY, "Exact Huawei profile lacks a required service-port capability")
        }
    }

    private fun validateExisting(state: HuaweiObservedState, plan: HuaweiServicePlan) {
        val expectedAssociation = OnuGemTcontObservation(
            plan.lineProfileId,
            plan.tcontId,
            plan.dbaProfileId,
            plan.gemPortId,
            plan.vlanId,
        )
        val expectedService = ServicePortObservation(
            plan.servicePortId,
            plan.vlanId,
            plan.gpon.notation,
            plan.ontId,
            plan.gemPortId,
            plan.userVlanId,
        )
        if ((state.onuGemTcont != null && state.onuGemTcont != expectedAssociation) ||
            (state.servicePort != null && state.servicePort != expectedService)
        ) {
            throw HuaweiAdapterException(HuaweiFailureCode.STALE_PRECONDITION, "An explicit Huawei resource identifier is bound differently")
        }
    }

    private fun validateCompensationState(state: HuaweiObservedState, plan: HuaweiServicePlan, mutations: HuaweiMutationSet) {
        if ((mutations.servicePort && state.servicePort?.servicePortId != plan.servicePortId) ||
            ((mutations.tcont || mutations.gem || mutations.gemMapping) && state.onuGemTcont == null)
        ) {
            throw HuaweiAdapterException(HuaweiFailureCode.ROLLBACK_CONFLICT, "Huawei service state drifted before compensation")
        }
    }

    private fun missing(state: HuaweiObservedState, plan: HuaweiServicePlan): HuaweiMutationSet {
        val association = state.onuGemTcont
        return HuaweiMutationSet(
            vlan = !state.vlanExists,
            uplink = !state.taggedUplinkMember,
            tcont = association?.tcontId != plan.tcontId,
            gem = association?.gemPortId != plan.gemPortId,
            gemMapping = association?.mappedVlanId != plan.vlanId,
            servicePort = state.servicePort == null,
        )
    }

    private fun createdResourcesRemain(state: HuaweiObservedState, mutations: HuaweiMutationSet): Boolean =
        (mutations.vlan && state.vlanExists) || (mutations.uplink && state.taggedUplinkMember) ||
            ((mutations.tcont || mutations.gem || mutations.gemMapping) && state.onuGemTcont != null) ||
            (mutations.servicePort && state.servicePort != null)

    private fun receiptKey(target: HuaweiTarget, plan: HuaweiServicePlan) = "${target.deviceId}:${plan.operationKey}"

    private companion object {
        val REQUIRED_CAPABILITIES = setOf(
            "SINGLE_TAG_802_1Q",
            "TAGGED_UPLINK",
            "GPON_TCONT_GEM_ASSOCIATION",
            "GPON_SERVICE_PORT",
            "PERSISTENT_SAVE",
            "READBACK",
            "INVERSE_COMMANDS",
        )
    }
}
