package com.duluin.ftth.provisioning.application.service

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.provisioning.application.port.outbound.DeviceObservationRepository
import com.duluin.ftth.provisioning.application.port.outbound.DriftRecordRepository
import com.duluin.ftth.provisioning.application.port.outbound.ProvisioningObservationPort
import com.duluin.ftth.provisioning.application.port.outbound.ProvisioningObservationOutcome
import com.duluin.ftth.provisioning.domain.model.DeviceObservation
import com.duluin.ftth.provisioning.domain.model.DeviceSnapshot
import com.duluin.ftth.provisioning.domain.model.DriftRecord
import com.duluin.ftth.tenancy.TenantApi
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.UUID

@Component
class ProvisioningDriftScanner(
    private val tenants: TenantApi,
    private val observationPort: ProvisioningObservationPort,
    private val baselines: ProvisioningDriftBaselineReader,
    private val observations: DeviceObservationRepository,
    private val driftRecords: DriftRecordRepository,
    private val classifier: ProvisioningDriftClassifier,
    private val metrics: ProvisioningMetrics,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = $$"${ftth.provisioning.drift-scan-interval:PT15M}")
    fun scan() {
        tenants.findActiveTenantIds().forEach { tenantId ->
            try {
                TenantContext.runAs(tenantId) { scanTenant(tenantId, observationPort) }
            } catch (_: Exception) {
                log.warn("Provisioning drift tenant scan failed: tenantId={}, reason={}", tenantId, OBSERVATION_FAILED)
            }
        }
    }

    fun scanTenant(tenantId: UUID, observer: ProvisioningObservationPort): List<DriftRecord> =
        baselines.latestPerDevice().mapNotNull { baseline ->
            try {
                when (val outcome = observer.observe(baseline)) {
                    is ProvisioningObservationOutcome.Available -> scanDevice(tenantId, baseline, outcome)
                    is ProvisioningObservationOutcome.Pending -> null
                    is ProvisioningObservationOutcome.Unavailable -> {
                        observationFailed(baseline, outcome.reason.name)
                        null
                    }
                }
            } catch (_: Exception) {
                observationFailed(baseline, OBSERVATION_FAILED)
                null
            }
        }

    private fun scanDevice(
        tenantId: UUID,
        baseline: DeviceSnapshot,
        current: ProvisioningObservationOutcome.Available,
    ): DriftRecord {
        val observation = observations.save(
            DeviceObservation.rehydrate(
                UuidV7.generate(), tenantId, baseline.device, current.state, current.observedAt,
            ),
        )
        val drift = driftRecords.save(
            DriftRecord.rehydrate(
                UuidV7.generate(), tenantId, baseline.device, baseline.id, observation.id,
                classifier.classify(baseline.state, observation.state), current.observedAt,
            ),
        )
        metrics.driftAge(Duration.between(drift.recordedAt, clock.instant()))
        return drift
    }

    private fun observationFailed(baseline: DeviceSnapshot, reason: String) {
        metrics.verificationFailure()
        log.warn(
            "Provisioning drift device observation failed: deviceKind={}, deviceId={}, reason={}",
            baseline.device.kind,
            baseline.device.id,
            reason,
        )
    }

    private companion object { const val OBSERVATION_FAILED = "OBSERVATION_FAILED" }
}
