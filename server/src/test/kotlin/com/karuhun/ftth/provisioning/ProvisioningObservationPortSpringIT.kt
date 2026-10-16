package com.karuhun.ftth.provisioning

import com.karuhun.ftth.provisioning.application.service.ProvisioningDriftScanner
import com.karuhun.ftth.provisioning.application.service.ProvisioningExecutionEngine
import com.karuhun.ftth.provisioning.application.service.ProvisioningExecutionQueueWorker
import com.karuhun.ftth.provisioning.application.service.ProvisioningWorkflowService
import com.karuhun.ftth.provisioning.application.port.inbound.ProvisioningExecutionRunner
import com.karuhun.ftth.provisioning.application.service.ProvisioningDeviceGateway
import com.karuhun.ftth.provisioning.application.port.outbound.ProvisioningObservationPort
import com.karuhun.ftth.provisioning.adapter.outbound.persistence.ProvisioningObservationPersistenceAdapter
import com.karuhun.ftth.provisioning.application.port.outbound.ProvisioningObservationFailure
import com.karuhun.ftth.provisioning.application.port.outbound.ProvisioningObservationOutcome
import com.karuhun.ftth.provisioning.domain.model.DeviceKind
import com.karuhun.ftth.provisioning.domain.model.DeviceReference
import com.karuhun.ftth.provisioning.domain.model.DeviceSnapshot
import com.karuhun.ftth.provisioning.domain.model.NormalizedDeviceState
import com.karuhun.ftth.common.tenant.TenantContext
import com.karuhun.ftth.common.integration.CollectorProvisioningChannel
import com.karuhun.ftth.tenancy.TenantApi
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
    @Autowired private lateinit var executionEngine: ProvisioningExecutionEngine
    @Autowired private lateinit var executionRunner: ProvisioningExecutionRunner
    @Autowired private lateinit var executionQueue: ProvisioningExecutionQueueWorker
    @Autowired private lateinit var workflow: ProvisioningWorkflowService
    @Autowired private lateinit var deviceGateway: ProvisioningDeviceGateway

    @Test
    fun `production context wires one read only provisioning observer`() {
        assertThat(observationPort).isNotNull
        assertThat(applicationContext.getBeansOfType(ProvisioningObservationPort::class.java)).hasSize(1)
        assertThat(applicationContext.getBeansOfType(CollectorProvisioningChannel::class.java).values)
            .anyMatch { it is com.karuhun.ftth.provisioning.adapter.outbound.persistence.ProvisioningObservationChannelAdapter }
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
    fun `production context wires collector backed execution and queue`() {
        assertThat(executionEngine).isNotNull
        assertThat(executionRunner).isNotNull
        assertThat(executionQueue).isNotNull
        assertThat(workflow).isNotNull
        assertThat(deviceGateway).isInstanceOf(
            com.karuhun.ftth.provisioning.adapter.outbound.persistence.CollectorBackedProvisioningDeviceGateway::class.java,
        )
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
