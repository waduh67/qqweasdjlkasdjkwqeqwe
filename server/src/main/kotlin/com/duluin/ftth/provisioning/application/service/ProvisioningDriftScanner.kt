package com.duluin.ftth.provisioning.application.service

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.provisioning.application.port.outbound.DeviceObservationRepository
import com.duluin.ftth.provisioning.application.port.outbound.DriftRecordRepository
import com.duluin.ftth.provisioning.domain.model.DeviceObservation
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.provisioning.domain.model.DeviceSnapshot
import com.duluin.ftth.provisioning.domain.model.DriftRecord
import com.duluin.ftth.provisioning.domain.model.NormalizedDeviceState
import com.duluin.ftth.tenancy.TenantApi
import org.springframework.beans.factory.ObjectProvider
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.UUID

fun interface ProvisioningObservationPort {
    fun observe(device: DeviceReference): NormalizedDeviceState
}

@Component
class ProvisioningDriftScanner(
    private val tenants: TenantApi,
    private val observationPort: ObjectProvider<ProvisioningObservationPort>,
    private val baselines: ProvisioningDriftBaselineReader,
    private val observations: DeviceObservationRepository,
    private val driftRecords: DriftRecordRepository,
    private val classifier: ProvisioningDriftClassifier,
    private val metrics: ProvisioningMetrics,
    private val clock: Clock,
) {
    @Scheduled(fixedDelayString = "\${ftth.provisioning.drift-scan-interval:PT15M}")
    fun scan() {
        val observer = observationPort.ifAvailable ?: return
        tenants.findActiveTenantIds().forEach { tenantId ->
            TenantContext.runAs(tenantId) { scanTenant(tenantId, observer) }
        }
    }

    fun scanTenant(tenantId: UUID, observer: ProvisioningObservationPort): List<DriftRecord> =
        baselines.latestPerDevice().map { baseline -> scanDevice(tenantId, baseline, observer) }

    private fun scanDevice(
        tenantId: UUID,
        baseline: DeviceSnapshot,
        observer: ProvisioningObservationPort,
    ): DriftRecord {
        val observedAt = clock.instant()
        val observation = observations.save(
            DeviceObservation.rehydrate(
                UuidV7.generate(), tenantId, baseline.device, observer.observe(baseline.device), observedAt,
            ),
        )
        val drift = driftRecords.save(
            DriftRecord.rehydrate(
                UuidV7.generate(), tenantId, baseline.device, baseline.id, observation.id,
                classifier.classify(baseline.state, observation.state), observedAt,
            ),
        )
        metrics.driftAge(Duration.between(drift.recordedAt, clock.instant()))
        return drift
    }
}
