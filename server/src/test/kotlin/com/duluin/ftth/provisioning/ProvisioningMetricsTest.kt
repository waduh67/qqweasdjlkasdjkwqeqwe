package com.duluin.ftth.provisioning

import com.duluin.ftth.provisioning.application.service.ProvisioningMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class ProvisioningMetricsTest {
    @Test
    fun `required metrics are exported with bounded labels`() {
        val registry = SimpleMeterRegistry()
        val metrics = ProvisioningMetrics(registry)

        metrics.queueDepth(3)
        metrics.oldestPlanAge(Duration.ofSeconds(12))
        metrics.vendorCall("unknown-vendor-${"x".repeat(80)}", Duration.ofMillis(20), failed = true)
        metrics.verificationFailure()
        metrics.rollback(manual = true)
        metrics.driftAge(Duration.ofSeconds(30))
        metrics.certificationBlock()

        val names = registry.meters.map { it.id.name }.toSet()
        assertThat(names).contains(
            "ftth.provisioning.queue.depth",
            "ftth.provisioning.plan.age",
            "ftth.provisioning.vendor.latency",
            "ftth.provisioning.vendor.errors",
            "ftth.provisioning.verification.failures",
            "ftth.provisioning.rollbacks",
            "ftth.provisioning.manual.reconciliation",
            "ftth.provisioning.drift.age",
            "ftth.provisioning.certification.blocks",
        )
        assertThat(registry.find("ftth.provisioning.vendor.latency").timer()!!.id.getTag("vendor")).isEqualTo("other")
    }
}
