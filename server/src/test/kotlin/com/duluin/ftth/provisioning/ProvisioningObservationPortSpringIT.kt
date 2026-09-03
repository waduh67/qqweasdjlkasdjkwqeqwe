package com.duluin.ftth.provisioning

import com.duluin.ftth.provisioning.application.service.ProvisioningDriftScanner
import com.duluin.ftth.provisioning.application.port.outbound.ProvisioningObservationPort
import com.duluin.ftth.provisioning.adapter.outbound.persistence.ProvisioningObservationPersistenceAdapter
import com.duluin.ftth.provisioning.application.port.outbound.ProvisioningObservationException
import com.duluin.ftth.provisioning.application.port.outbound.ProvisioningObservationFailure
import com.duluin.ftth.provisioning.domain.model.DeviceKind
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.tenancy.TenantApi
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

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

        assertThatThrownBy {
            TenantContext.runAs(tenantId) {
                observationPort.observe(DeviceReference(DeviceKind.ROUTER, UUID.randomUUID()))
            }
        }.isInstanceOf(ProvisioningObservationException::class.java)
            .extracting("reason")
            .isEqualTo(ProvisioningObservationFailure.READBACK_UNAVAILABLE)
    }
}
