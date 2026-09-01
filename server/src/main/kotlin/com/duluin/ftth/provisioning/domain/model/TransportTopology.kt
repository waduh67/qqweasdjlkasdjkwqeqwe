package com.duluin.ftth.provisioning.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import java.time.Instant
import java.util.UUID

enum class ManagedNodeRole { OLT, ACCESS_SWITCH, AGGREGATION_SWITCH, BRAS }

enum class InterfaceRole { ACCESS, TRUNK, UPLINK, MANAGEMENT }

enum class AdministrativeStatus { ENABLED, DISABLED, EXCLUDED }

enum class TopologyReferenceKind { OLT, PON, ONU, NAS }

data class TopologyReference(val kind: TopologyReferenceKind, val id: UUID)

data class TransportTopologySnapshot(
    val nodes: List<ManagedNode>,
    val interfaces: List<ManagedInterface>,
    val links: List<TransportLink>,
)

class ManagedNode private constructor(
    override val id: UUID,
    val tenantId: UUID,
    val name: String,
    val role: ManagedNodeRole,
    val reference: TopologyReference?,
    val administrativeStatus: AdministrativeStatus,
    val observedAt: Instant,
) : ProvisioningAggregate {
    init {
        if (name.isBlank() || name.length > 120) throw ValidationException("MANAGED_NODE_NAME_INVALID")
        val expectedReferenceKind = when (role) {
            ManagedNodeRole.OLT -> TopologyReferenceKind.OLT
            ManagedNodeRole.BRAS -> TopologyReferenceKind.NAS
            ManagedNodeRole.ACCESS_SWITCH, ManagedNodeRole.AGGREGATION_SWITCH -> null
        }
        if (reference?.kind != expectedReferenceKind) {
            throw ValidationException("TOPOLOGY_REFERENCE_ROLE_MISMATCH")
        }
    }

    companion object {
        fun create(
            tenantId: UUID,
            name: String,
            role: ManagedNodeRole,
            reference: TopologyReference?,
            administrativeStatus: AdministrativeStatus,
            observedAt: Instant,
        ) = ManagedNode(
            UuidV7.generate(),
            tenantId,
            name.trim(),
            role,
            reference,
            administrativeStatus,
            observedAt,
        )

        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            name: String,
            role: ManagedNodeRole,
            reference: TopologyReference?,
            administrativeStatus: AdministrativeStatus,
            observedAt: Instant,
        ) = ManagedNode(id, tenantId, name, role, reference, administrativeStatus, observedAt)
    }
}

class ManagedInterface private constructor(
    override val id: UUID,
    val tenantId: UUID,
    val nodeId: UUID,
    val name: String,
    val role: InterfaceRole,
    val reference: TopologyReference?,
    val administrativeStatus: AdministrativeStatus,
    val observedAt: Instant,
) : ProvisioningAggregate {
    init {
        if (name.isBlank() || name.length > 120) throw ValidationException("MANAGED_INTERFACE_NAME_INVALID")
        if (reference != null && reference.kind !in setOf(TopologyReferenceKind.PON, TopologyReferenceKind.ONU)) {
            throw ValidationException("TOPOLOGY_INTERFACE_REFERENCE_INVALID")
        }
    }

    companion object {
        fun create(
            tenantId: UUID,
            nodeId: UUID,
            name: String,
            role: InterfaceRole,
            reference: TopologyReference? = null,
            administrativeStatus: AdministrativeStatus,
            observedAt: Instant,
        ) = ManagedInterface(
            UuidV7.generate(),
            tenantId,
            nodeId,
            name.trim(),
            role,
            reference,
            administrativeStatus,
            observedAt,
        )

        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            nodeId: UUID,
            name: String,
            role: InterfaceRole,
            reference: TopologyReference?,
            administrativeStatus: AdministrativeStatus,
            observedAt: Instant,
        ) = ManagedInterface(id, tenantId, nodeId, name, role, reference, administrativeStatus, observedAt)
    }
}

class TransportLink private constructor(
    override val id: UUID,
    val tenantId: UUID,
    val interfaceAId: UUID,
    val interfaceZId: UUID,
    val administrativeStatus: AdministrativeStatus,
    val observedAt: Instant,
) : ProvisioningAggregate {
    init {
        if (interfaceAId == interfaceZId) throw ValidationException("TOPOLOGY_SELF_LINK")
    }

    companion object {
        fun create(
            tenantId: UUID,
            interfaceAId: UUID,
            interfaceZId: UUID,
            administrativeStatus: AdministrativeStatus,
            observedAt: Instant,
        ): TransportLink {
            val (first, second) = orderedEndpoints(interfaceAId, interfaceZId)
            return TransportLink(UuidV7.generate(), tenantId, first, second, administrativeStatus, observedAt)
        }

        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            interfaceAId: UUID,
            interfaceZId: UUID,
            administrativeStatus: AdministrativeStatus,
            observedAt: Instant,
        ): TransportLink {
            val (first, second) = orderedEndpoints(interfaceAId, interfaceZId)
            return TransportLink(id, tenantId, first, second, administrativeStatus, observedAt)
        }

        private fun orderedEndpoints(first: UUID, second: UUID): Pair<UUID, UUID> =
            if (first.toString() <= second.toString()) first to second else second to first
    }
}
