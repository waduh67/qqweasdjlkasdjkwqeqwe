package com.duluin.ftth.provisioning

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.provisioning.domain.model.AdministrativeStatus
import com.duluin.ftth.provisioning.domain.model.InterfaceRole
import com.duluin.ftth.provisioning.domain.model.ManagedInterface
import com.duluin.ftth.provisioning.domain.model.ManagedNode
import com.duluin.ftth.provisioning.domain.model.ManagedNodeRole
import com.duluin.ftth.provisioning.domain.model.TopologyReference
import com.duluin.ftth.provisioning.domain.model.TopologyReferenceKind
import com.duluin.ftth.provisioning.domain.model.TransportLink
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class TransportTopologyDomainTest {
    private val tenantId = UuidV7.generate()
    private val observedAt = Instant.parse("2026-09-02T08:00:00Z")

    @Test
    fun `managed records retain tenant administrative state observation and plain inventory references`() {
        val olt = ManagedNode.create(
            tenantId = tenantId,
            name = "OLT-A",
            role = ManagedNodeRole.OLT,
            reference = TopologyReference(TopologyReferenceKind.OLT, UuidV7.generate()),
            administrativeStatus = AdministrativeStatus.ENABLED,
            observedAt = observedAt,
        )
        val pon = ManagedInterface.create(
            tenantId = tenantId,
            nodeId = olt.id,
            name = "PON1",
            role = InterfaceRole.ACCESS,
            reference = TopologyReference(TopologyReferenceKind.PON, UuidV7.generate()),
            administrativeStatus = AdministrativeStatus.ENABLED,
            observedAt = observedAt,
        )
        val onu = ManagedInterface.create(
            tenantId = tenantId,
            nodeId = olt.id,
            name = "ONU-1",
            role = InterfaceRole.ACCESS,
            reference = TopologyReference(TopologyReferenceKind.ONU, UuidV7.generate()),
            administrativeStatus = AdministrativeStatus.EXCLUDED,
            observedAt = observedAt,
        )
        val bras = ManagedNode.create(
            tenantId = tenantId,
            name = "BRAS-A",
            role = ManagedNodeRole.BRAS,
            reference = TopologyReference(TopologyReferenceKind.NAS, UuidV7.generate()),
            administrativeStatus = AdministrativeStatus.DISABLED,
            observedAt = observedAt,
        )
        val link = TransportLink.create(
            tenantId = tenantId,
            interfaceAId = pon.id,
            interfaceZId = onu.id,
            administrativeStatus = AdministrativeStatus.ENABLED,
            observedAt = observedAt,
        )

        assertThat(olt.tenantId).isEqualTo(tenantId)
        assertThat(olt.observedAt).isEqualTo(observedAt)
        assertThat(pon.reference?.kind).isEqualTo(TopologyReferenceKind.PON)
        assertThat(onu.reference?.kind).isEqualTo(TopologyReferenceKind.ONU)
        assertThat(bras.reference?.kind).isEqualTo(TopologyReferenceKind.NAS)
        assertThat(bras.administrativeStatus).isEqualTo(AdministrativeStatus.DISABLED)
        assertThat(link.tenantId).isEqualTo(tenantId)
    }

    @Test
    fun `managed node roles require compatible plain references`() {
        assertThatThrownBy {
            ManagedNode.create(
                tenantId = tenantId,
                name = "Wrong OLT",
                role = ManagedNodeRole.OLT,
                reference = TopologyReference(TopologyReferenceKind.NAS, UuidV7.generate()),
                administrativeStatus = AdministrativeStatus.ENABLED,
                observedAt = observedAt,
            )
        }.isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("TOPOLOGY_REFERENCE_ROLE_MISMATCH")

        listOf(ManagedNodeRole.ACCESS_SWITCH, ManagedNodeRole.AGGREGATION_SWITCH).forEach { role ->
            val switch = ManagedNode.create(
                tenantId = tenantId,
                name = role.name,
                role = role,
                reference = null,
                administrativeStatus = AdministrativeStatus.ENABLED,
                observedAt = observedAt,
            )
            assertThat(switch.reference).isNull()
        }
    }

    @Test
    fun `transport link rejects an interface linked to itself`() {
        val interfaceId = UuidV7.generate()

        assertThatThrownBy {
            TransportLink.create(
                tenantId = tenantId,
                interfaceAId = interfaceId,
                interfaceZId = interfaceId,
                administrativeStatus = AdministrativeStatus.ENABLED,
                observedAt = observedAt,
            )
        }.isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("TOPOLOGY_SELF_LINK")
    }
}
