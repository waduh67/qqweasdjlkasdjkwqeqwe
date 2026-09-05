package com.duluin.ftth.collector.adapter.zte

import com.duluin.ftth.contract.ProvisioningResultState

enum class ZteExecutionMode { DRY_RUN, SIMULATOR, PRODUCTION }

enum class ZteCertification { PROVISIONAL }

enum class ZteFailureCode {
    UNRECOGNIZED_DEVICE_RESPONSE,
    UNKNOWN_NOTATION,
    UNSUPPORTED_CAPABILITY,
    DESTRUCTIVE_PROMPT,
    PRODUCTION_NOT_CERTIFIED,
    STALE_PRECONDITION,
    VERIFICATION_MISMATCH,
    ROLLBACK_CONFLICT,
}

class ZteAdapterException(
    val code: ZteFailureCode,
    detail: String,
) : RuntimeException("${code.name}: $detail")

data class ZteProfileKey(val family: String, val firmware: String)

@JvmInline
value class ZteUplinkPort private constructor(val notation: String) {
    companion object {
        private val FORMAT = Regex("^gei_[1-9][0-9]*/[1-9][0-9]*/[1-9][0-9]*$")

        fun parse(notation: String): ZteUplinkPort {
            if (!FORMAT.matches(notation)) {
                throw ZteAdapterException(ZteFailureCode.UNKNOWN_NOTATION, "Unsupported ZTE uplink notation")
            }
            return ZteUplinkPort(notation)
        }
    }
}

@JvmInline
value class ZteOnuPort private constructor(val notation: String) {
    companion object {
        private val FORMAT = Regex("^gpon-onu_[1-9][0-9]*/[1-9][0-9]*/[1-9][0-9]*:[1-9][0-9]*$")

        fun parse(notation: String): ZteOnuPort {
            if (!FORMAT.matches(notation)) {
                throw ZteAdapterException(ZteFailureCode.UNKNOWN_NOTATION, "Unsupported ZTE ONU notation")
            }
            return ZteOnuPort(notation)
        }
    }
}

data class ZteTarget(
    val deviceId: String,
    val managementAddress: String,
    val expectedFamily: String,
    val expectedFirmware: String,
)

class ZteServicePlan private constructor(
    val operationKey: String,
    val vlanId: Int,
    val uplink: ZteUplinkPort,
    val onu: ZteOnuPort,
    val tcontId: Int,
    val tcontProfile: String,
    val gemPortId: Int,
    val servicePortId: Int,
    val userVlanId: Int,
    val requiredCapability: String,
) {
    companion object {
        private val PROFILE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,31}$")

        fun create(
            operationKey: String,
            vlanId: Int,
            uplinkNotation: String,
            onuNotation: String,
            tcontId: Int,
            tcontProfile: String,
            gemPortId: Int,
            servicePortId: Int,
            userVlanId: Int,
            requiredCapability: String = "GPON_SERVICE_PORT",
        ): ZteServicePlan {
            if (operationKey.isBlank() || vlanId !in 2..4094 || userVlanId !in 2..4094 ||
                tcontId !in 1..7 || gemPortId !in 1..32 || servicePortId !in 1..128 ||
                !PROFILE_NAME.matches(tcontProfile) || !PROFILE_NAME.matches(requiredCapability)
            ) {
                throw ZteAdapterException(ZteFailureCode.UNKNOWN_NOTATION, "Invalid explicit ZTE service parameters")
            }
            return ZteServicePlan(
                operationKey,
                vlanId,
                ZteUplinkPort.parse(uplinkNotation),
                ZteOnuPort.parse(onuNotation),
                tcontId,
                tcontProfile,
                gemPortId,
                servicePortId,
                userVlanId,
                requiredCapability,
            )
        }
    }
}

data class ZteServiceBinding(val vport: Int, val userVlanId: Int, val vlanId: Int)

data class ZteNormalizedState(
    val vlanPresent: Boolean,
    val uplinkTagged: Boolean,
    val tcontProfile: String?,
    val gemTcontId: Int?,
    val serviceBinding: ZteServiceBinding?,
) {
    fun matches(plan: ZteServicePlan): Boolean =
        vlanPresent && uplinkTagged && tcontProfile == plan.tcontProfile && gemTcontId == plan.tcontId &&
            serviceBinding == ZteServiceBinding(plan.gemPortId, plan.userVlanId, plan.vlanId)

    val managedResourceCount: Int
        get() = listOf(vlanPresent, uplinkTagged, tcontProfile != null, gemTcontId != null, serviceBinding != null).count { it }
}

data class ZteMutationSet(
    val vlan: Boolean,
    val uplink: Boolean,
    val tcont: Boolean,
    val gem: Boolean,
    val service: Boolean,
) {
    val any: Boolean get() = vlan || uplink || tcont || gem || service

    companion object {
        fun all() = ZteMutationSet(vlan = true, uplink = true, tcont = true, gem = true, service = true)
    }
}

data class ZteProvisioningOutcome(
    val changed: Boolean,
    val state: ZteNormalizedState,
    val commands: List<String>,
) {
    val resultState = ProvisioningResultState(state.managedResourceCount)
}
