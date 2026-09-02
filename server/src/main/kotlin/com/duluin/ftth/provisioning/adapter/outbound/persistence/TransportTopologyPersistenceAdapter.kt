package com.duluin.ftth.provisioning.adapter.outbound.persistence

import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.provisioning.application.port.outbound.TransportTopologyRepository
import com.duluin.ftth.provisioning.domain.model.ManagedInterface
import com.duluin.ftth.provisioning.domain.model.ManagedNode
import com.duluin.ftth.provisioning.domain.model.TopologyReference
import com.duluin.ftth.provisioning.domain.model.TransportLink
import com.duluin.ftth.provisioning.domain.model.TransportTopologySnapshot
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
class TransportTopologyPersistenceAdapter(
    private val nodes: ManagedNodeJpaRepository,
    private val interfaces: ManagedInterfaceJpaRepository,
    private val links: TransportLinkJpaRepository,
) : TransportTopologyRepository {
    override fun saveNode(value: ManagedNode): ManagedNode {
        requireCurrentTenant(value.tenantId)
        val entity = nodes.findById(value.id).orElse(null)?.apply {
            name = value.name
            role = value.role
            referenceKind = value.reference?.kind
            referenceId = value.reference?.id
            administrativeStatus = value.administrativeStatus
            observedAt = value.observedAt
        } ?: ManagedNodeJpaEntity(
            value.id,
            value.name,
            value.role,
            value.reference?.kind,
            value.reference?.id,
            value.administrativeStatus,
            value.observedAt,
        )
        return nodes.save(entity).toDomain()
    }

    override fun saveInterface(value: ManagedInterface): ManagedInterface {
        requireCurrentTenant(value.tenantId)
        val entity = interfaces.findById(value.id).orElse(null)?.apply {
            name = value.name
            role = value.role
            referenceKind = value.reference?.kind
            referenceId = value.reference?.id
            administrativeStatus = value.administrativeStatus
            observedAt = value.observedAt
        } ?: ManagedInterfaceJpaEntity(
            value.id,
            value.nodeId,
            value.name,
            value.role,
            value.reference?.kind,
            value.reference?.id,
            value.administrativeStatus,
            value.observedAt,
        )
        return interfaces.save(entity).toDomain()
    }

    override fun saveLink(value: TransportLink): TransportLink {
        requireCurrentTenant(value.tenantId)
        val entity = links.findById(value.id).orElse(null)?.apply {
            administrativeStatus = value.administrativeStatus
            observedAt = value.observedAt
        } ?: TransportLinkJpaEntity(
            value.id,
            value.interfaceAId,
            value.interfaceZId,
            value.administrativeStatus,
            value.observedAt,
        )
        return links.save(entity).toDomain()
    }

    @Transactional(readOnly = true)
    override fun snapshot() = TransportTopologySnapshot(
        nodes = nodes.findAll().map { it.toDomain() }.sortedBy { it.id.toString() },
        interfaces = interfaces.findAll().map { it.toDomain() }.sortedBy { it.id.toString() },
        links = links.findAll().map { it.toDomain() }.sortedBy { it.id.toString() },
    )

    override fun deleteNode(id: UUID) = nodes.deleteById(id)
    override fun deleteInterface(id: UUID) = interfaces.deleteById(id)
    override fun deleteLink(id: UUID) = links.deleteById(id)

    private fun ManagedNodeJpaEntity.toDomain() = ManagedNode.rehydrate(
        id,
        entityTenant(tenantId),
        name,
        role,
        reference(referenceKind, referenceId),
        administrativeStatus,
        observedAt,
    )

    private fun ManagedInterfaceJpaEntity.toDomain() = ManagedInterface.rehydrate(
        id,
        entityTenant(tenantId),
        nodeId,
        name,
        role,
        reference(referenceKind, referenceId),
        administrativeStatus,
        observedAt,
    )

    private fun TransportLinkJpaEntity.toDomain() = TransportLink.rehydrate(
        id,
        entityTenant(tenantId),
        interfaceAId,
        interfaceZId,
        administrativeStatus,
        observedAt,
    )

    private fun entityTenant(tenantId: UUID?): UUID = tenantId ?: TenantContext.tenantId()

    private fun reference(kind: com.duluin.ftth.provisioning.domain.model.TopologyReferenceKind?, id: UUID?): TopologyReference? =
        if (kind == null || id == null) null else TopologyReference(kind, id)

    private fun requireCurrentTenant(tenantId: UUID) {
        if (tenantId != TenantContext.tenantId()) throw ValidationException("CROSS_TENANT_TOPOLOGY")
    }
}
