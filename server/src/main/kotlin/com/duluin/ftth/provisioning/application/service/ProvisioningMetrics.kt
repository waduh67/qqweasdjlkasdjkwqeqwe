package com.duluin.ftth.provisioning.application.service

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@Component
class ProvisioningMetrics(private val registry: MeterRegistry) {
    private val queueDepthValue = AtomicLong()
    private val planAgeSeconds = AtomicLong()
    private val driftAgeSeconds = AtomicLong()
    private val verificationFailures = counter("ftth.provisioning.verification.failures")
    private val rollbacks = counter("ftth.provisioning.rollbacks")
    private val manualReconciliations = counter("ftth.provisioning.manual.reconciliation")
    private val certificationBlocks = counter("ftth.provisioning.certification.blocks")

    init {
        gauge("ftth.provisioning.queue.depth", queueDepthValue)
        gauge("ftth.provisioning.plan.age", planAgeSeconds)
        gauge("ftth.provisioning.drift.age", driftAgeSeconds)
        Timer.builder("ftth.provisioning.vendor.latency").tag("vendor", "other").register(registry)
        counter("ftth.provisioning.vendor.errors", "vendor", "other")
    }

    fun queueDepth(value: Long) = queueDepthValue.set(value.coerceAtLeast(0))
    fun oldestPlanAge(value: Duration) = planAgeSeconds.set(value.seconds.coerceAtLeast(0))
    fun driftAge(value: Duration) = driftAgeSeconds.set(value.seconds.coerceAtLeast(0))
    fun verificationFailure() = verificationFailures.increment()
    fun certificationBlock() = certificationBlocks.increment()

    fun rollback(manual: Boolean) {
        rollbacks.increment()
        if (manual) manualReconciliations.increment()
    }

    fun vendorCall(vendor: String, duration: Duration, failed: Boolean) {
        val tag = vendorTag(vendor)
        Timer.builder("ftth.provisioning.vendor.latency").tag("vendor", tag).register(registry)
            .record(duration.toNanos(), TimeUnit.NANOSECONDS)
        if (failed) counter("ftth.provisioning.vendor.errors", "vendor", tag).increment()
    }

    private fun gauge(name: String, value: AtomicLong) {
        Gauge.builder(name, value) { it.get().toDouble() }.register(registry)
    }

    private fun counter(name: String, vararg tags: String): Counter = Counter.builder(name).tags(*tags).register(registry)

    private fun vendorTag(value: String): String = when (value.trim().lowercase().replace('-', '_')) {
        "mikrotik", "routeros" -> "mikrotik"
        "juniper", "junos" -> "juniper"
        "cisco", "ios_xe" -> "cisco"
        "zte" -> "zte"
        "huawei" -> "huawei"
        "hsgq" -> "hsgq"
        else -> "other"
    }
}
