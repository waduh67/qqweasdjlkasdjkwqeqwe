package com.duluin.ftth.provisioning.application.service

import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.provisioning.application.port.outbound.SegmentProfileRepository
import com.duluin.ftth.provisioning.application.port.outbound.ServiceIntentRepository
import com.duluin.ftth.provisioning.application.port.outbound.TransportTopologyRepository
import com.duluin.ftth.provisioning.application.port.outbound.VlanPoolRepository
import com.duluin.ftth.provisioning.domain.model.AdministrativeStatus
import com.duluin.ftth.provisioning.domain.model.ManagedInterface
import com.duluin.ftth.provisioning.domain.model.ManagedNode
import com.duluin.ftth.provisioning.domain.model.SegmentProfile
import com.duluin.ftth.provisioning.domain.model.ServiceIntent
import com.duluin.ftth.provisioning.domain.model.TopologyReference
import com.duluin.ftth.provisioning.domain.model.TransportLink
import com.duluin.ftth.provisioning.domain.model.ManagedNodeRole
import com.duluin.ftth.provisioning.domain.model.InterfaceRole
import com.duluin.ftth.provisioning.domain.model.VlanEncapsulation
import com.duluin.ftth.provisioning.domain.model.VlanPool
import com.duluin.ftth.provisioning.domain.model.VlanRange
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

data class RevisionedResource<T>(val revision: Int, val value: T)

@Service
@Transactional(readOnly = true)
class ProvisioningResourceService(
    private val topology: TransportTopologyRepository,
    private val pools: VlanPoolRepository,
    private val profiles: SegmentProfileRepository,
    private val intents: ServiceIntentRepository,
    private val revisions: ProvisioningResourceRevisionStore,
    private val currentUser: CurrentUserProvider,
    private val audit: ProvisioningAuditPublisher,
) {
    fun topology() = topology.snapshot()
    fun pools() = pools.findAll().map { RevisionedResource(revisions.current(POOL, it.id), it) }
    fun profiles() = profiles.findAll().map { RevisionedResource(revisions.current(PROFILE, it.id), it) }
    fun intents() = intents.findAll().map { RevisionedResource(revisions.current(INTENT, it.id), it) }

    @Transactional
    fun createNode(
        name: String,
        role: ManagedNodeRole,
        reference: TopologyReference?,
        status: AdministrativeStatus,
    ): RevisionedResource<ManagedNode> = saveNew(
        NODE,
        topology.saveNode(ManagedNode.create(tenantId(), name, role, reference, status, Instant.now())),
    )

    @Transactional
    fun updateNode(
        id: UUID,
        revision: Int,
        name: String,
        role: ManagedNodeRole,
        reference: TopologyReference?,
        status: AdministrativeStatus,
    ): RevisionedResource<ManagedNode> {
        topology.snapshot().nodes.firstOrNull { it.id == id } ?: throw NotFoundException("TOPOLOGY_NODE_NOT_FOUND")
        val next = revisions.advance(NODE, id, revision)
        val saved = topology.saveNode(ManagedNode.rehydrate(id, tenantId(), name, role, reference, status, Instant.now()))
        audit("provisioning.topology-node.updated", id)
        return RevisionedResource(next, saved)
    }

    @Transactional
    fun createInterface(
        nodeId: UUID,
        name: String,
        role: InterfaceRole,
        reference: TopologyReference?,
        status: AdministrativeStatus,
    ): RevisionedResource<ManagedInterface> = saveNew(
        INTERFACE,
        topology.saveInterface(ManagedInterface.create(tenantId(), nodeId, name, role, reference, status, Instant.now())),
    )

    @Transactional
    fun updateInterface(
        id: UUID,
        revision: Int,
        nodeId: UUID,
        name: String,
        role: InterfaceRole,
        reference: TopologyReference?,
        status: AdministrativeStatus,
    ): RevisionedResource<ManagedInterface> {
        topology.snapshot().interfaces.firstOrNull { it.id == id } ?: throw NotFoundException("TOPOLOGY_INTERFACE_NOT_FOUND")
        val next = revisions.advance(INTERFACE, id, revision)
        val saved = topology.saveInterface(
            ManagedInterface.rehydrate(id, tenantId(), nodeId, name, role, reference, status, Instant.now()),
        )
        audit("provisioning.topology-interface.updated", id)
        return RevisionedResource(next, saved)
    }

    @Transactional
    fun createLink(interfaceAId: UUID, interfaceZId: UUID, status: AdministrativeStatus): RevisionedResource<TransportLink> =
        saveNew(LINK, topology.saveLink(TransportLink.create(tenantId(), interfaceAId, interfaceZId, status, Instant.now())))

    @Transactional
    fun updateLink(id: UUID, revision: Int, status: AdministrativeStatus): RevisionedResource<TransportLink> {
        val current = topology.snapshot().links.firstOrNull { it.id == id } ?: throw NotFoundException("TOPOLOGY_LINK_NOT_FOUND")
        val next = revisions.advance(LINK, id, revision)
        val saved = topology.saveLink(
            TransportLink.rehydrate(id, tenantId(), current.interfaceAId, current.interfaceZId, status, Instant.now()),
        )
        audit("provisioning.topology-link.updated", id)
        return RevisionedResource(next, saved)
    }

    @Transactional
    fun deleteTopology(type: String, id: UUID, revision: Int) {
        revisions.remove(type, id, revision)
        when (type) {
            LINK -> topology.deleteLink(id)
            INTERFACE -> topology.deleteInterface(id)
            NODE -> topology.deleteNode(id)
            else -> throw NotFoundException("TOPOLOGY_RESOURCE_NOT_FOUND")
        }
        audit("provisioning.${type.lowercase()}.deleted", id)
    }

    @Transactional
    fun createPool(name: String, start: Int, end: Int, reserved: List<VlanRange>): RevisionedResource<VlanPool> =
        saveNew(POOL, pools.save(VlanPool.create(tenantId(), name, VlanRange(start, end), reserved)))

    @Transactional
    fun updatePool(id: UUID, revision: Int, name: String, start: Int, end: Int, reserved: List<VlanRange>): RevisionedResource<VlanPool> {
        val current = pools.findById(id) ?: throw NotFoundException("VLAN_POOL_NOT_FOUND")
        val next = revisions.advance(POOL, id, revision)
        val saved = pools.save(VlanPool.rehydrate(id, tenantId(), name, VlanRange(start, end), reserved, current.allocations))
        audit("provisioning.vlan-pool.updated", id)
        return RevisionedResource(next, saved)
    }

    @Transactional
    fun deletePool(id: UUID, revision: Int) {
        pools.findById(id) ?: throw NotFoundException("VLAN_POOL_NOT_FOUND")
        revisions.remove(POOL, id, revision)
        pools.deleteById(id)
        audit("provisioning.vlan-pool.deleted", id)
    }

    @Transactional
    fun createProfile(name: String, poolId: UUID): RevisionedResource<SegmentProfile> {
        pools.findById(poolId) ?: throw NotFoundException("VLAN_POOL_NOT_FOUND")
        return saveNew(PROFILE, profiles.save(SegmentProfile.create(tenantId(), name, poolId)))
    }

    @Transactional
    fun updateProfile(id: UUID, revision: Int, name: String, poolId: UUID): RevisionedResource<SegmentProfile> {
        profiles.findById(id) ?: throw NotFoundException("SEGMENT_PROFILE_NOT_FOUND")
        pools.findById(poolId) ?: throw NotFoundException("VLAN_POOL_NOT_FOUND")
        val next = revisions.advance(PROFILE, id, revision)
        val saved = profiles.save(SegmentProfile.rehydrate(id, tenantId(), name, poolId))
        audit("provisioning.segment-profile.updated", id)
        return RevisionedResource(next, saved)
    }

    @Transactional
    fun deleteProfile(id: UUID, revision: Int) {
        profiles.findById(id) ?: throw NotFoundException("SEGMENT_PROFILE_NOT_FOUND")
        revisions.remove(PROFILE, id, revision)
        profiles.deleteById(id)
        audit("provisioning.segment-profile.deleted", id)
    }

    @Transactional
    fun createIntent(subscriptionId: UUID, profileId: UUID, dedicatedVlanId: Int?): RevisionedResource<ServiceIntent> {
        profiles.findById(profileId) ?: throw NotFoundException("SEGMENT_PROFILE_NOT_FOUND")
        return saveNew(INTENT, intents.save(ServiceIntent.create(tenantId(), subscriptionId, profileId, VlanEncapsulation.SINGLE_TAG, dedicatedVlanId)))
    }

    @Transactional
    fun updateIntent(id: UUID, revision: Int, profileId: UUID, status: String): RevisionedResource<ServiceIntent> {
        val current = intents.findById(id) ?: throw NotFoundException("SERVICE_INTENT_NOT_FOUND")
        val next = revisions.advance(INTENT, id, revision)
        val updated = ServiceIntent.rehydrate(
            current.id, tenantId(), current.subscriptionId, current.hotspotSiteId, profileId, current.encapsulation,
            current.dedicatedVlanId, com.duluin.ftth.provisioning.domain.model.IntentStatus.valueOf(status),
        )
        val saved = intents.save(updated)
        audit("provisioning.intent.updated", id)
        return RevisionedResource(next, saved)
    }

    private fun <T : com.duluin.ftth.provisioning.domain.model.ProvisioningAggregate> saveNew(
        type: String,
        value: T,
    ): RevisionedResource<T> {
        revisions.register(type, value.id)
        audit("provisioning.${type.lowercase()}.created", value.id)
        return RevisionedResource(1, value)
    }

    private fun tenantId() = currentUser.current().tenantId
    private fun audit(action: String, id: UUID) {
        val actor = currentUser.current()
        audit.publish(ProvisioningAuditRecord(actor.tenantId, action, "ProvisioningResource", id))
    }

    private companion object {
        const val POOL = "VLAN_POOL"
        const val PROFILE = "SEGMENT_PROFILE"
        const val INTENT = "SERVICE_INTENT"
        const val NODE = "TOPOLOGY_NODE"
        const val INTERFACE = "TOPOLOGY_INTERFACE"
        const val LINK = "TOPOLOGY_LINK"
    }
}
