package com.duluin.ftth.common

import com.duluin.ftth.common.infrastructure.config.ObservabilityProperties
import com.duluin.ftth.common.infrastructure.observability.JobHealthRegistry
import com.duluin.ftth.common.infrastructure.observability.JobStallWatchdog
import com.duluin.ftth.common.infrastructure.observability.ScheduledJobRecovered
import com.duluin.ftth.common.infrastructure.observability.ScheduledJobStalled
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.time.Duration
import java.time.Instant

/**
 * Perangai penjaga job: kapan ia bersuara, dan — sama pentingnya — kapan ia diam.
 *
 * Peringatan yang terlalu sering berakhir sebagai aturan filter di kotak masuk, dan sejak
 * itu peringatan yang benar-benar penting pun tak pernah sampai. Karena itu jeda pengingat
 * diuji sekeras deteksi macetnya sendiri.
 */
class JobStallWatchdogTest {

    private val bootedAt: Instant = Instant.parse("2026-08-10T00:00:00Z")
    private val published = mutableListOf<Any>()
    private val publisher = ApplicationEventPublisher { event -> published += event }
    private val registry = JobHealthRegistry(stallFactor = 3, stallGrace = Duration.ofMinutes(10), bootedAt = bootedAt)
    private val watchdog = JobStallWatchdog(registry, publisher, ObservabilityProperties())

    @Suppress("unused")
    private class Probe {
        fun poll() = Unit
    }

    private fun declare(interval: Duration?) = registry.declare("${Probe::class.java.name}.poll", interval)

    private fun at(minutes: Long): Instant = bootedAt.plusSeconds(minutes * 60)

    @Test
    fun `diam selama job masih sehat, bersuara sekali ketika macet`() {
        declare(Duration.ofMinutes(5))

        watchdog.check(at(14))
        assertThat(published).isEmpty()

        watchdog.check(at(16))
        assertThat(published).singleElement().isInstanceOfSatisfying(ScheduledJobStalled::class.java) { event ->
            assertThat(event.job.name).isEqualTo("Probe.poll")
            assertThat(event.job.module).isEqualTo("common")
            assertThat(event.repeated).isFalse()
        }

        // Ronde-ronde berikutnya menemukan kemacetan yang sama; sekali kabar sudah cukup.
        watchdog.check(at(21))
        watchdog.check(at(26))
        assertThat(published).hasSize(1)
    }

    @Test
    fun `mengingatkan lagi setelah jeda pengingat lewat`() {
        declare(Duration.ofMinutes(5))

        watchdog.check(at(16))
        watchdog.check(at(16 + 5 * 60)) // 5 jam sesudahnya: belum waktunya menagih ulang
        assertThat(published).hasSize(1)

        watchdog.check(at(16 + 7 * 60))
        assertThat(published).hasSize(2)
        assertThat((published.last() as ScheduledJobStalled).repeated).isTrue()
    }

    @Test
    fun `mengabarkan pemulihan sekali, lalu kembali diam`() {
        declare(Duration.ofMinutes(5))
        watchdog.check(at(16))

        registry.track(Probe(), Probe::class.java.getDeclaredMethod("poll")) { }
        val afterSuccess = Instant.now()
        watchdog.check(afterSuccess)
        watchdog.check(afterSuccess)

        assertThat(published).hasSize(2)
        assertThat(published.last()).isInstanceOfSatisfying(ScheduledJobRecovered::class.java) { event ->
            assertThat(event.job.name).isEqualTo("Probe.poll")
            assertThat(event.job.runs).isEqualTo(1)
        }
    }

    @Test
    fun `job tanpa interval tak pernah memicu peringatan`() {
        declare(null)

        watchdog.check(at(60 * 24))

        assertThat(published).isEmpty()
    }
}
