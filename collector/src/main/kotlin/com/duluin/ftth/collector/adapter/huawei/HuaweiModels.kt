package com.duluin.ftth.collector.adapter.huawei

import com.duluin.ftth.contract.ProvisioningResultState

enum class HuaweiExecutionMode { DRY_RUN, SIMULATOR, PRODUCTION_AUTO_APPLY }

enum class HuaweiCertification { PROVISIONAL }

enum class HuaweiFailureCode {
    UNKNOWN_PROFILE,
    FINGERPRINT_MISMATCH,
    AMBIGUOUS_GPON_INDEX,
    UNSUPPORTED_CAPABILITY,
    UNSAFE_PROMPT,
    COMMAND_ERROR,
    UNRECOGNIZED_TRANSCRIPT,
    AMBIGUOUS_READBACK,
    SAVE_FAILED,
    PRODUCTION_NOT_CERTIFIED,
    STALE_PRECONDITION,
    VERIFICATION_MISMATCH,
    ROLLBACK_CONFLICT,
}

class HuaweiAdapterException(
    val code: HuaweiFailureCode,
    detail: String,
) : RuntimeException("${code.name}: $detail")

data class HuaweiProfileKey(val family: String, val firmware: String)

@JvmInline
value class HuaweiGponPort private constructor(val notation: String) {
    val frame: Int get() = notation.substringBefore('/').toInt()
    val slot: Int get() = notation.substringAfter('/').substringBefore('/').toInt()
    val port: Int get() = notation.substringAfterLast('/').toInt()

    companion object {
        private val FORMAT = Regex("^(?:0|[1-9][0-9]*)/(?:0|[1-9][0-9]*)/(?:0|[1-9][0-9]*)$")

        fun parse(notation: String): HuaweiGponPort {
            if (!FORMAT.matches(notation)) {
                throw HuaweiAdapterException(HuaweiFailureCode.AMBIGUOUS_GPON_INDEX, "Huawei GPON index must be explicit F/S/P")
            }
            return HuaweiGponPort(notation)
        }
    }
}

data class HuaweiTarget(
    val deviceId: String,
    val managementAddress: String,
    val expectedFamily: String,
    val expectedFirmware: String,
)

class HuaweiServicePlan private constructor(
    val operationKey: String,
    val vlanId: Int,
    val uplink: HuaweiGponPort,
    val gpon: HuaweiGponPort,
    val ontId: Int,
    val lineProfileId: Int,
    val tcontId: Int,
    val dbaProfileId: Int,
    val gemPortId: Int,
    val servicePortId: Int,
    val userVlanId: Int,
    val requiredCapability: String,
) {
    companion object {
        private val CAPABILITY = Regex("^[A-Z][A-Z0-9_]{1,63}$")

        fun create(
            operationKey: String,
            vlanId: Int,
            uplinkNotation: String,
            gponNotation: String,
            ontId: Int,
            lineProfileId: Int,
            tcontId: Int,
            dbaProfileId: Int,
            gemPortId: Int,
            servicePortId: Int,
            userVlanId: Int,
            requiredCapability: String = "GPON_SERVICE_PORT",
        ): HuaweiServicePlan {
            if (operationKey.isBlank() || vlanId !in 2..4094 || userVlanId !in 2..4094 ||
                ontId !in 0..127 || lineProfileId !in 1..1024 || tcontId !in 1..8 ||
                dbaProfileId !in 1..1024 || gemPortId !in 1..255 || servicePortId !in 0..16383 ||
                !CAPABILITY.matches(requiredCapability)
            ) {
                throw HuaweiAdapterException(HuaweiFailureCode.AMBIGUOUS_GPON_INDEX, "Invalid explicit Huawei service parameters")
            }
            return HuaweiServicePlan(
                operationKey,
                vlanId,
                HuaweiGponPort.parse(uplinkNotation),
                HuaweiGponPort.parse(gponNotation),
                ontId,
                lineProfileId,
                tcontId,
                dbaProfileId,
                gemPortId,
                servicePortId,
                userVlanId,
                requiredCapability,
            )
        }
    }
}

data class OnuGemTcontObservation(
    val lineProfileId: Int,
    val tcontId: Int,
    val dbaProfileId: Int,
    val gemPortId: Int,
    val mappedVlanId: Int,
)

data class ServicePortObservation(
    val servicePortId: Int,
    val vlanId: Int,
    val gponPort: String,
    val onuId: Int,
    val gemPortId: Int,
    val userVlanId: Int,
)

data class HuaweiObservedState(
    val vlanExists: Boolean,
    val taggedUplinkMember: Boolean,
    val onuGemTcont: OnuGemTcontObservation?,
    val servicePort: ServicePortObservation?,
) {
    fun matches(plan: HuaweiServicePlan): Boolean =
        vlanExists && taggedUplinkMember &&
            onuGemTcont == OnuGemTcontObservation(
                plan.lineProfileId,
                plan.tcontId,
                plan.dbaProfileId,
                plan.gemPortId,
                plan.vlanId,
            ) && servicePort == ServicePortObservation(
                plan.servicePortId,
                plan.vlanId,
                plan.gpon.notation,
                plan.ontId,
                plan.gemPortId,
                plan.userVlanId,
            )

    val managedResourceCount: Int
        get() = listOf(vlanExists, taggedUplinkMember, onuGemTcont != null, servicePort != null).count { it }
}

data class HuaweiMutationSet(
    val vlan: Boolean,
    val uplink: Boolean,
    val tcont: Boolean,
    val gem: Boolean,
    val gemMapping: Boolean,
    val servicePort: Boolean,
) {
    val any: Boolean get() = vlan || uplink || tcont || gem || gemMapping || servicePort

    companion object {
        fun all() = HuaweiMutationSet(
            vlan = true,
            uplink = true,
            tcont = true,
            gem = true,
            gemMapping = true,
            servicePort = true,
        )
    }
}

data class HuaweiProvisioningOutcome(
    val changed: Boolean,
    val observation: HuaweiObservedState,
    val commands: List<String>,
) {
    val resultState = ProvisioningResultState(observation.managedResourceCount)
}
