package com.duluin.ftth.provisioning

import com.duluin.ftth.provisioning.application.service.ProvisioningDriftScanner
import com.duluin.ftth.provisioning.application.port.outbound.ProvisioningObservationPort
import com.duluin.ftth.provisioning.adapter.outbound.persistence.ProvisioningObservationPersistenceAdapter
import com.duluin.ftth.provisioning.application.port.outbound.ProvisioningObservationFailure
import com.duluin.ftth.provisioning.application.port.outbound.ProvisioningObservationOutcome
import com.duluin.ftth.provisioning.domain.model.DeviceKind
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.provisioning.domain.model.DeviceSnapshot
import com.duluin.ftth.provisioning.domain.model.NormalizedDeviceState
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.common.integration.CollectorProvisioningChannel
import com.duluin.ftth.tenancy.TenantApi
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import java.time.Instant

@SpringBootTest
@ActiveProfiles("test")
class ProvisioningObservationPortSpringIT {
    @Autowired private lateinit var observationPort: ProvisioningObservationPort
    @Autowired private lateinit var scanner: ProvisioningDriftScanner
    @Autowired private lateinit var applicationContext: ApplicationContext
    @Autowired private lateinit var tenants: TenantApi

    @Test
    fun `production context wires one read only provisioning observer`() {
        assertThat(observationPort).isNotNull
        assertThat(applicationContext.getBeansOfType(ProvisioningObservationPort::class.java)).hasSize(1)
        assertThat(applicationContext.getBeansOfType(CollectorProvisioningChannel::class.java).values)
            .anyMatch { it is com.duluin.ftth.provisioning.adapter.outbound.persistence.ProvisioningObservationChannelAdapter }
        assertThat(observationPort).isInstanceOf(ProvisioningObservationPersistenceAdapter::class.java)
        assertThat(observationPort.javaClass.declaredMethods.map { it.name })
            .contains("observe")
            .doesNotContain("apply", "compensate")
        assertThat(scanner).isNotNull
        assertThat(ProvisioningObservationPort::class.java.methods.map { it.name })
            .contains("observe")
            .doesNotContain("apply", "compensate")
    }

    @Test
    fun `device without verified readback fails closed`() {
        val tenantId = requireNotNull(tenants.findBySlug("demo")).id

        val outcome = TenantContext.runAs(tenantId) {
            observationPort.observe(
                DeviceSnapshot.rehydrate(
                    UUID.randomUUID(), tenantId, DeviceReference(DeviceKind.ROUTER, UUID.randomUUID()),
                    UUID.randomUUID(), NormalizedDeviceState.empty(), Instant.now(),
                ),
            )
        }

        assertThat(outcome).isEqualTo(
            ProvisioningObservationOutcome.Unavailable(ProvisioningObservationFailure.READBACK_UNAVAILABLE),
        )
    }
}
