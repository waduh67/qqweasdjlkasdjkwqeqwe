package com.duluin.ftth.provisioning

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.provisioning.application.port.outbound.TransportTopologyRepository
import com.duluin.ftth.provisioning.domain.model.AdministrativeStatus
import com.duluin.ftth.provisioning.domain.model.InterfaceRole
import com.duluin.ftth.provisioning.domain.model.ManagedInterface
import com.duluin.ftth.provisioning.domain.model.ManagedNode
import com.duluin.ftth.provisioning.domain.model.ManagedNodeRole
import com.duluin.ftth.provisioning.domain.model.TopologyReference
import com.duluin.ftth.provisioning.domain.model.TopologyReferenceKind
import com.duluin.ftth.provisioning.domain.model.TransportLink
import com.duluin.ftth.tenancy.TenantApi
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hibernate.exception.ConstraintViolationException
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
class TransportTopologyPersistenceIT {
    @Autowired private lateinit var tenantApi: TenantApi
    @Autowired private lateinit var txManager: PlatformTransactionManager
    @Autowired private lateinit var topology: TransportTopologyRepository
    @PersistenceContext private lateinit var em: EntityManager

    private val observedAt = Instant.parse("2026-09-02T08:00:00Z")

    @Test
    fun `topology records round trip with plain references and remain tenant isolated`() {
        val tenantA = tenant("topology-a")
        val tenantB = tenant("topology-b")
        val oltReference = TopologyReference(TopologyReferenceKind.OLT, UuidV7.generate())
        val ponReference = TopologyReference(TopologyReferenceKind.PON, UuidV7.generate())
        lateinit var olt: ManagedNode
        lateinit var accessSwitch: ManagedNode
        lateinit var pon: ManagedInterface
        lateinit var switchPort: ManagedInterface
        lateinit var link: TransportLink

        asTenant(tenantA) {
            olt = topology.saveNode(node(tenantA, "OLT-A", ManagedNodeRole.OLT, oltReference))
            accessSwitch = topology.saveNode(node(tenantA, "SW-A", ManagedNodeRole.ACCESS_SWITCH))
            pon = topology.saveInterface(
                managedInterface(tenantA, olt.id, "PON1", InterfaceRole.ACCESS, ponReference),
            )
            switchPort = topology.saveInterface(
                managedInterface(tenantA, accessSwitch.id, "ge-0/0/1", InterfaceRole.TRUNK),
            )
            link = topology.saveLink(
                TransportLink.create(tenantA, pon.id, switchPort.id, AdministrativeStatus.ENABLED, observedAt),
            )
        }

        val stored = asTenant(tenantA) { topology.snapshot() }
        assertThat(stored.nodes.map { it.id }).containsExactlyInAnyOrder(olt.id, accessSwitch.id)
        assertThat(stored.interfaces.map { it.id }).containsExactlyInAnyOrder(pon.id, switchPort.id)
        assertThat(stored.links.single().id).isEqualTo(link.id)
        assertThat(stored.nodes.single { it.id == olt.id }.reference).isEqualTo(oltReference)
        assertThat(stored.interfaces.single { it.id == pon.id }.reference).isEqualTo(ponReference)

        val hidden = asTenant(tenantB) { topology.snapshot() }
        assertThat(hidden.nodes).isEmpty()
        assertThat(hidden.interfaces).isEmpty()
        assertThat(hidden.links).isEmpty()
    }

    @Test
    fun `database rejects a transport link to another tenant interface`() {
        val tenantA = tenant("topology-fk-a")
        val tenantB = tenant("topology-fk-b")
        val interfaceA = asTenant(tenantA) {
            val node = topology.saveNode(node(tenantA, "SW-A", ManagedNodeRole.ACCESS_SWITCH))
            topology.saveInterface(managedInterface(tenantA, node.id, "uplink-a", InterfaceRole.UPLINK))
        }
        val interfaceB = asTenant(tenantB) {
            val node = topology.saveNode(node(tenantB, "SW-B", ManagedNodeRole.ACCESS_SWITCH))
            topology.saveInterface(managedInterface(tenantB, node.id, "uplink-b", InterfaceRole.UPLINK))
        }

        assertThatThrownBy {
            asTenant(tenantA) {
                em.createNativeQuery(
                    """INSERT INTO provisioning_transport_link
                       (id, tenant_id, interface_a_id, interface_z_id, administrative_status, observed_at)
                       VALUES (:id, :tenant, :a, :z, 'ENABLED', :observedAt)""",
                ).setParameter("id", UuidV7.generate())
                    .setParameter("tenant", tenantA)
                    .setParameter("a", interfaceA.id)
                    .setParameter("z", interfaceB.id)
                    .setParameter("observedAt", observedAt)
                    .executeUpdate()
                em.flush()
            }
        }.isInstanceOf(ConstraintViolationException::class.java)
    }

    private fun node(
        tenantId: UUID,
        name: String,
        role: ManagedNodeRole,
        reference: TopologyReference? = null,
    ) = ManagedNode.create(
        tenantId,
        name,
        role,
        reference,
        AdministrativeStatus.ENABLED,
        observedAt,
    )

    private fun managedInterface(
        tenantId: UUID,
        nodeId: UUID,
        name: String,
        role: InterfaceRole,
        reference: TopologyReference? = null,
    ) = ManagedInterface.create(
        tenantId,
        nodeId,
        name,
        role,
        reference,
        AdministrativeStatus.ENABLED,
        observedAt,
    )

    private fun tenant(prefix: String) = tenantApi.ensureTenant(
        "$prefix-${UUID.randomUUID().toString().take(8)}",
        prefix,
    ).id

    private fun <T> asTenant(tenantId: UUID, block: () -> T): T = TenantContext.runAs(tenantId) {
        TransactionTemplate(txManager).execute { block() }!!
    }
}
