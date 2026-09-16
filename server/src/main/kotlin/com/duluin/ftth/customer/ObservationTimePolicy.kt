package com.duluin.ftth.customer

import java.time.Instant

object ObservationTimePolicy {
    fun rejection(time: Instant, receivedAt: Instant): ObservationUnassignedReason? = when {
        time.isBefore(receivedAt.minusSeconds(72 * 3600)) -> ObservationUnassignedReason.TOO_OLD
        time.isAfter(receivedAt.plusSeconds(5 * 60)) -> ObservationUnassignedReason.FUTURE_TIME
        else -> null
    }
}
