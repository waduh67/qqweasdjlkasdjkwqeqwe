package com.duluin.ftth.monitoring

import com.duluin.ftth.customer.ObservationTimePolicy
import com.duluin.ftth.customer.ObservationUnassignedReason
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class WarehouseDiscoveryITClock {
    @Test
    fun `collector trust window includes exact age and skew boundaries only`() {
        val now = Instant.parse("2026-09-16T00:00:00Z")
        assertThat(ObservationTimePolicy.rejection(now.minusSeconds(72 * 3600), now)).isNull()
        assertThat(ObservationTimePolicy.rejection(now.plusSeconds(300), now)).isNull()
        assertThat(ObservationTimePolicy.rejection(now.minusSeconds(72 * 3600).minusNanos(1), now)).isEqualTo(ObservationUnassignedReason.TOO_OLD)
        assertThat(ObservationTimePolicy.rejection(now.plusSeconds(300).plusNanos(1), now)).isEqualTo(ObservationUnassignedReason.FUTURE_TIME)
    }
}
