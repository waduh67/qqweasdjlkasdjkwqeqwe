package com.duluin.ftth.provisioning

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.provisioning.application.port.outbound.DeviceObservationRepository
import com.duluin.ftth.provisioning.application.port.outbound.DriftRecordRepository
import com.duluin.ftth.provisioning.application.port.outbound.ProvisioningObservationPort
import com.duluin.ftth.provisioning.application.port.outbound.ProvisioningObservationOutcome
import com.duluin.ftth.provisioning.application.service.ProvisioningDriftClassifier
import com.duluin.ftth.provisioning.application.service.ProvisioningDriftScanner
import com.duluin.ftth.provisioning.application.service.ProvisioningDriftBaselineReader
import com.duluin.ftth.provisioning.application.service.ProvisioningMetrics
import com.duluin.ftth.provisioning.domain.model.DeviceKind
import com.duluin.ftth.provisioning.domain.model.DeviceObservation
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.provisioning.domain.model.DeviceSnapshot
import com.duluin.ftth.provisioning.domain.model.DriftRecord
import com.duluin.ftth.provisioning.domain.model.DriftStatus
import com.duluin.ftth.provisioning.domain.model.NormalizedDeviceState
import com.duluin.ftth.tenancy.TenantApi
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@ExtendWith(OutputCaptureExtension::class)
class ProvisioningDriftScannerTest {
    @Test
    fun `scheduled scan records observation and drift without exposing mutation methods`() {
        val tenantId = UuidV7.generate()
        val baseline = DeviceSnapshot.rehydrate(
            UuidV7.generate(), tenantId, DeviceReference(DeviceKind.ROUTER, UuidV7.generate()), UuidV7.generate(),
            NormalizedDeviceState.empty(), NOW,
        )
        val observations = Observations()
        val drift = Drifts()
        var observationCalls = 0
        val observer: ProvisioningObservationPort = {
            observationCalls += 1
            ProvisioningObservationOutcome.Available(NormalizedDeviceState.empty(), NOW)
        }
        val baselines: ProvisioningDriftBaselineReader = { listOf(baseline) }
        val scanner = ProvisioningDriftScanner(
            Tenants(), observer, baselines, observations, drift, ProvisioningDriftClassifier(),
            ProvisioningMetrics(SimpleMeterRegistry()), Clock.fixed(NOW, ZoneOffset.UTC),
        )

        val records = scanner.scanTenant(tenantId, observer)

        assertThat(observationCalls).isEqualTo(1)
        assertThat(observations.values).hasSize(1)
        assertThat(records.single().status).isEqualTo(DriftStatus.NONE)
    }

    @Test
    fun `failed first device does not create drift or prevent second observation`(output: CapturedOutput) {
        val tenantId = UuidV7.generate()
        val first = baseline(tenantId)
        val second = baseline(tenantId)
        val observations = Observations()
        val drift = Drifts()
        val meters = SimpleMeterRegistry()
        val attempted = mutableListOf<DeviceReference>()
        val observer: ProvisioningObservationPort = { snapshot ->
            attempted += snapshot.device
            if (snapshot.device == first.device) error("transport-secret-canary")
            ProvisioningObservationOutcome.Available(NormalizedDeviceState.empty(), NOW)
        }
        val baselines: ProvisioningDriftBaselineReader = { listOf(first, second) }
        val scanner = ProvisioningDriftScanner(
            Tenants(), observer, baselines, observations, drift,
            ProvisioningDriftClassifier(), ProvisioningMetrics(meters), Clock.fixed(NOW, ZoneOffset.UTC),
        )

        val records = scanner.scanTenant(tenantId, observer)

        assertThat(attempted).containsExactly(first.device, second.device)
        assertThat(observations.values).hasSize(1)
        assertThat(records).hasSize(1)
        assertThat(meters.get("ftth.provisioning.verification.failures").counter().count()).isEqualTo(1.0)
        assertThat(output.all).contains("reason=OBSERVATION_FAILED").doesNotContain("transport-secret-canary")
    }

    @Test
    fun `failed tenant does not prevent later tenant scan`() {
        val firstTenant = UuidV7.generate()
        val secondTenant = UuidV7.generate()
        val observations = Observations()
        val drift = Drifts()
        val baselines: ProvisioningDriftBaselineReader = {
            if (TenantContext.tenantId() == firstTenant) error("database-secret-canary")
            listOf(baseline(secondTenant))
        }
        val scanner = ProvisioningDriftScanner(
            Tenants(listOf(firstTenant, secondTenant)),
            { ProvisioningObservationOutcome.Available(NormalizedDeviceState.empty(), NOW) },
            baselines,
            observations,
            drift,
            ProvisioningDriftClassifier(),
            ProvisioningMetrics(SimpleMeterRegistry()),
            Clock.fixed(NOW, ZoneOffset.UTC),
        )

        scanner.scan()

        assertThat(observations.values).hasSize(1)
        assertThat(observations.values.single().tenantId).isEqualTo(secondTenant)
    }

    private fun baseline(tenantId: UUID) = DeviceSnapshot.rehydrate(
        UuidV7.generate(), tenantId, DeviceReference(DeviceKind.ROUTER, UuidV7.generate()), UuidV7.generate(),
        NormalizedDeviceState.empty(), NOW,
    )

    private class Observations : DeviceObservationRepository {
        val values = mutableListOf<DeviceObservation>()
        override fun save(value: DeviceObservation) = value.also(values::add)
        override fun findById(id: UUID) = values.firstOrNull { it.id == id }
    }
    private class Drifts : DriftRecordRepository {
        override fun save(value: DriftRecord) = value
        override fun findById(id: UUID): DriftRecord? = null
    }
    private class Tenants(private val active: List<UUID> = emptyList()) : TenantApi {
        override fun findActiveTenantIds() = active
        override fun findById(id: UUID) = null
        override fun findBySlug(slug: String) = null
        override fun requireById(id: UUID) = error("unused")
        override fun ensureTenant(slug: String, name: String) = error("unused")
        override fun platformTenantId() = error("unused")
        override fun suspend(id: UUID) = error("unused")
        override fun activate(id: UUID) = error("unused")
    }

    private companion object { val NOW: Instant = Instant.parse("2026-09-03T10:00:00Z") }
}
