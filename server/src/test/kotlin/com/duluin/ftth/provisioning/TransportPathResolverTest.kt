package com.duluin.ftth.provisioning

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.provisioning.application.port.outbound.TransportTopologyRepository
import com.duluin.ftth.provisioning.application.service.TransportPathResolver
import com.duluin.ftth.provisioning.application.service.TransportPathValidationCode
import com.duluin.ftth.provisioning.domain.model.AdministrativeStatus
import com.duluin.ftth.provisioning.domain.model.InterfaceRole
import com.duluin.ftth.provisioning.domain.model.ManagedInterface
import com.duluin.ftth.provisioning.domain.model.ManagedNode
import com.duluin.ftth.provisioning.domain.model.ManagedNodeRole
import com.duluin.ftth.provisioning.domain.model.TopologyReference
import com.duluin.ftth.provisioning.domain.model.TopologyReferenceKind
import com.duluin.ftth.provisioning.domain.model.TransportLink
import com.duluin.ftth.provisioning.domain.model.TransportTopologySnapshot
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class TransportPathResolverTest {
    private val tenantId = UuidV7.generate()
    private val now = Instant.parse("2026-09-02T08:00:00Z")

    @Test
    fun `resolves one deterministic ordered OLT access to BRAS path`() {
        val topology = validTopology()

        val path = resolver(topology.snapshot()).resolve(tenantId, topology.startInterface.id)

        assertThat(path.nodes.map { it.role }).containsExactly(
            ManagedNodeRole.OLT,
            ManagedNodeRole.ACCESS_SWITCH,
            ManagedNodeRole.BRAS,
        )
        assertThat(path.hops.map { it.link.id }).containsExactlyElementsOf(topology.pathLinks.map { it.id })
        assertThat(path.hops.first().fromInterface.id).isEqualTo(topology.startInterface.id)
        assertThat(path.hops.last().toInterface.nodeId).isEqualTo(path.nodes.last().id)
    }

    @Test
    fun `rejects a disconnected graph with stable code`() {
        val topology = validTopology()
        topology.links.removeLast()

        assertRejected(TransportPathValidationCode.DISCONNECTED_PATH, topology)
    }

    @Test
    fun `rejects a reachable cycle even when one BRAS route exists`() {
        val topology = validTopology()
        val cycleA = topology.node(ManagedNodeRole.AGGREGATION_SWITCH)
        val cycleB = topology.node(ManagedNodeRole.AGGREGATION_SWITCH)
        topology.connect(topology.accessSwitch, cycleA)
        topology.connect(cycleA, cycleB)
        topology.connect(cycleB, topology.accessSwitch)

        assertRejected(TransportPathValidationCode.CYCLIC_PATH, topology)
    }

    @Test
    fun `rejects two competing active routes as ambiguous`() {
        val topology = validTopology()
        val alternate = topology.node(ManagedNodeRole.AGGREGATION_SWITCH)
        topology.connect(topology.olt, alternate, fromInterface = topology.startInterface)
        topology.connect(alternate, topology.bras)

        assertRejected(TransportPathValidationCode.AMBIGUOUS_PATH, topology)
    }

    @Test
    fun `rejects stale path observations`() {
        val topology = validTopology()
        val staleLink = topology.pathLinks.first()
        topology.links[topology.links.indexOf(staleLink)] = TransportLink.rehydrate(
            staleLink.id,
            staleLink.tenantId,
            staleLink.interfaceAId,
            staleLink.interfaceZId,
            staleLink.administrativeStatus,
            now.minus(Duration.ofMinutes(6)),
        )

        assertRejected(TransportPathValidationCode.STALE_TOPOLOGY, topology)
    }

    @Test
    fun `rejects disabled path records`() {
        val topology = validTopology()
        val disabledLink = topology.pathLinks.first()
        topology.links[topology.links.indexOf(disabledLink)] = TransportLink.rehydrate(
            disabledLink.id,
            disabledLink.tenantId,
            disabledLink.interfaceAId,
            disabledLink.interfaceZId,
            AdministrativeStatus.DISABLED,
            disabledLink.observedAt,
        )

        assertRejected(TransportPathValidationCode.TOPOLOGY_DISABLED, topology)
    }

    @Test
    fun `rejects administratively excluded path records`() {
        val topology = validTopology()
        val access = topology.startInterface
        topology.interfaces[topology.interfaces.indexOf(access)] = ManagedInterface.rehydrate(
            access.id,
            access.tenantId,
            access.nodeId,
            access.name,
            access.role,
            access.reference,
            AdministrativeStatus.EXCLUDED,
            access.observedAt,
        )

        assertRejected(TransportPathValidationCode.ADMINISTRATIVELY_EXCLUDED, topology)
    }

    @Test
    fun `rejects cross tenant records before path traversal`() {
        val topology = validTopology()
        topology.nodes += ManagedNode.create(
            UuidV7.generate(),
            "foreign-switch",
            ManagedNodeRole.ACCESS_SWITCH,
            null,
            AdministrativeStatus.ENABLED,
            now,
        )

        assertRejected(TransportPathValidationCode.CROSS_TENANT_PATH, topology)
    }

    private fun assertRejected(code: TransportPathValidationCode, topology: TopologyBuilder) {
        assertThatThrownBy { resolver(topology.snapshot()).resolve(tenantId, topology.startInterface.id) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessage(code.name)
    }

    private fun resolver(snapshot: TransportTopologySnapshot) = TransportPathResolver(
        topology = InMemoryTopologyRepository(snapshot),
        clock = Clock.fixed(now, ZoneOffset.UTC),
        maxObservationAge = Duration.ofMinutes(5),
    )

    private fun validTopology(): TopologyBuilder {
        val topology = TopologyBuilder(tenantId, now)
        topology.olt = topology.node(ManagedNodeRole.OLT)
        topology.accessSwitch = topology.node(ManagedNodeRole.ACCESS_SWITCH)
        topology.bras = topology.node(ManagedNodeRole.BRAS)
        topology.startInterface = topology.managedInterface(
            topology.olt,
            InterfaceRole.ACCESS,
            TopologyReference(TopologyReferenceKind.PON, UuidV7.generate()),
        )
        topology.pathLinks += topology.connect(
            topology.olt,
            topology.accessSwitch,
            fromInterface = topology.startInterface,
        )
        topology.pathLinks += topology.connect(topology.accessSwitch, topology.bras)
        return topology
    }

    private class TopologyBuilder(
        private val tenantId: UUID,
        private val observedAt: Instant,
    ) {
        val nodes = mutableListOf<ManagedNode>()
        val interfaces = mutableListOf<ManagedInterface>()
        val links = mutableListOf<TransportLink>()
        val pathLinks = mutableListOf<TransportLink>()
        lateinit var olt: ManagedNode
        lateinit var accessSwitch: ManagedNode
        lateinit var bras: ManagedNode
        lateinit var startInterface: ManagedInterface

        fun node(role: ManagedNodeRole): ManagedNode = ManagedNode.create(
            tenantId = tenantId,
            name = "${role.name}-${nodes.size}",
            role = role,
            reference = when (role) {
                ManagedNodeRole.OLT -> TopologyReference(TopologyReferenceKind.OLT, UuidV7.generate())
                ManagedNodeRole.BRAS -> TopologyReference(TopologyReferenceKind.NAS, UuidV7.generate())
                else -> null
            },
            administrativeStatus = AdministrativeStatus.ENABLED,
            observedAt = observedAt,
        ).also(nodes::add)

        fun managedInterface(
            node: ManagedNode,
            role: InterfaceRole,
            reference: TopologyReference? = null,
        ): ManagedInterface = ManagedInterface.create(
            tenantId = tenantId,
            nodeId = node.id,
            name = "${role.name}-${interfaces.size}",
            role = role,
            reference = reference,
            administrativeStatus = AdministrativeStatus.ENABLED,
            observedAt = observedAt,
        ).also(interfaces::add)

        fun connect(
            from: ManagedNode,
            to: ManagedNode,
            fromInterface: ManagedInterface = managedInterface(from, InterfaceRole.UPLINK),
        ): TransportLink {
            val toInterface = managedInterface(
                to,
                if (to.role == ManagedNodeRole.BRAS) InterfaceRole.TRUNK else InterfaceRole.ACCESS,
            )
            return TransportLink.create(
                tenantId,
                fromInterface.id,
                toInterface.id,
                AdministrativeStatus.ENABLED,
                observedAt,
            ).also(links::add)
        }

        fun snapshot() = TransportTopologySnapshot(nodes.toList(), interfaces.toList(), links.toList())
    }

    private class InMemoryTopologyRepository(
        private val value: TransportTopologySnapshot,
    ) : TransportTopologyRepository {
        override fun saveNode(value: ManagedNode) = error("not used")
        override fun saveInterface(value: ManagedInterface) = error("not used")
        override fun saveLink(value: TransportLink) = error("not used")
        override fun snapshot() = value
    }
}
