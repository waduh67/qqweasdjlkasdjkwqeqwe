package com.duluin.ftth.provisioning

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.provisioning.application.port.outbound.DeviceObservationRepository
import com.duluin.ftth.provisioning.application.port.outbound.DriftRecordRepository
import com.duluin.ftth.provisioning.application.service.ProvisioningDriftClassifier
import com.duluin.ftth.provisioning.application.service.ProvisioningDriftScanner
import com.duluin.ftth.provisioning.application.service.ProvisioningDriftBaselineReader
import com.duluin.ftth.provisioning.application.service.ProvisioningMetrics
import com.duluin.ftth.provisioning.application.service.ProvisioningObservationPort
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
import org.springframework.beans.factory.support.StaticListableBeanFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

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
        val observer = ProvisioningObservationPort { observationCalls += 1; NormalizedDeviceState.empty() }
        val provider = StaticListableBeanFactory(mapOf("observer" to observer)).getBeanProvider(ProvisioningObservationPort::class.java)
        val scanner = ProvisioningDriftScanner(
            Tenants(), provider, ProvisioningDriftBaselineReader { listOf(baseline) }, observations, drift, ProvisioningDriftClassifier(),
            ProvisioningMetrics(SimpleMeterRegistry()), Clock.fixed(NOW, ZoneOffset.UTC),
        )

        val records = scanner.scanTenant(tenantId, observer)

        assertThat(observationCalls).isEqualTo(1)
        assertThat(observations.values).hasSize(1)
        assertThat(records.single().status).isEqualTo(DriftStatus.NONE)
    }

    private class Observations : DeviceObservationRepository {
        val values = mutableListOf<DeviceObservation>()
        override fun save(value: DeviceObservation) = value.also(values::add)
        override fun findById(id: UUID) = values.firstOrNull { it.id == id }
    }
    private class Drifts : DriftRecordRepository {
        override fun save(value: DriftRecord) = value
        override fun findById(id: UUID): DriftRecord? = null
    }
    private class Tenants : TenantApi {
        override fun findActiveTenantIds() = emptyList<UUID>()
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
