package com.duluin.ftth.provisioning.application.port.outbound

import com.duluin.ftth.provisioning.domain.model.DeviceSnapshot
import com.duluin.ftth.provisioning.domain.model.NormalizedDeviceState
import java.time.Instant

/** Read-only projection of device state; mutation operations deliberately do not belong to this port. */
fun interface ProvisioningObservationPort {
    fun observe(baseline: DeviceSnapshot): ProvisioningObservationOutcome
}

enum class ProvisioningObservationFailure { READBACK_UNAVAILABLE, READBACK_HASH_MISMATCH }

sealed interface ProvisioningObservationOutcome {
    data object Pending : ProvisioningObservationOutcome
    data class Available(val state: NormalizedDeviceState, val observedAt: Instant) : ProvisioningObservationOutcome
    data class Unavailable(val reason: ProvisioningObservationFailure) : ProvisioningObservationOutcome
}
