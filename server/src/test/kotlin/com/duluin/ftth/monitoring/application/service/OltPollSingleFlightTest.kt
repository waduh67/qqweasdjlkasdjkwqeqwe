package com.duluin.ftth.monitoring.application.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OltPollSingleFlightTest {
    @Test
    fun `same key is busy while another tenant remains independent`() {
        val flight = OltPollSingleFlight()
        val tenantId = UUID.randomUUID()
        val otherTenantId = UUID.randomUUID()
        val oltId = UUID.randomUUID()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val owner = executor.submit(Callable {
                flight.run(tenantId, oltId) {
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    "owner"
                }
            })
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()

            assertThat(flight.run(tenantId, oltId) { "duplicate" })
                .isEqualTo(OltPollSingleFlight.Result.Busy)
            assertThat(flight.run(otherTenantId, oltId) { "other tenant" })
                .isEqualTo(OltPollSingleFlight.Result.Completed("other tenant"))

            release.countDown()
            assertThat(owner.get(5, TimeUnit.SECONDS))
                .isEqualTo(OltPollSingleFlight.Result.Completed("owner"))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `ownership is released when work throws`() {
        val flight = OltPollSingleFlight()
        val tenantId = UUID.randomUUID()
        val oltId = UUID.randomUUID()

        assertThatThrownBy { flight.run(tenantId, oltId) { error("write failed") } }
            .isInstanceOf(IllegalStateException::class.java)

        assertThat(flight.run(tenantId, oltId) { "retried" })
            .isEqualTo(OltPollSingleFlight.Result.Completed("retried"))
    }
}
