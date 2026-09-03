package com.duluin.ftth.provisioning.application.port.outbound

import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.provisioning.domain.model.NormalizedDeviceState

/** Read-only projection of device state; mutation operations deliberately do not belong to this port. */
fun interface ProvisioningObservationPort {
    fun observe(device: DeviceReference): NormalizedDeviceState
}

enum class ProvisioningObservationFailure { READBACK_UNAVAILABLE, READBACK_STALE, READBACK_HASH_MISMATCH }

class ProvisioningObservationException(val reason: ProvisioningObservationFailure) : RuntimeException(reason.name)
