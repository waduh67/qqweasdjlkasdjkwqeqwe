package com.duluin.ftth.provisioning.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.provisioning.application.port.inbound.ProvisioningPlanningUseCase
import com.duluin.ftth.provisioning.application.port.outbound.DeviceObservationRepository
import com.duluin.ftth.provisioning.application.port.outbound.ProvisioningSafetyEvidenceRepository
import com.duluin.ftth.provisioning.application.port.outbound.ProvisionPlanRepository
import com.duluin.ftth.provisioning.application.port.outbound.SegmentProfileRepository
import com.duluin.ftth.provisioning.application.port.outbound.ServiceIntentRepository
import com.duluin.ftth.provisioning.application.port.outbound.TransportTopologyRepository
import com.duluin.ftth.provisioning.application.port.outbound.VlanPoolRepository
import com.duluin.ftth.provisioning.domain.model.DeviceKind
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.provisioning.domain.model.InterfaceRole
import com.duluin.ftth.provisioning.domain.model.ManagedInterface
import com.duluin.ftth.provisioning.domain.model.ManagedNode
import com.duluin.ftth.provisioning.domain.model.ManagedNodeRole
import com.duluin.ftth.provisioning.domain.model.ProvisionOperation
import com.duluin.ftth.provisioning.domain.model.ProvisionPlan
import com.duluin.ftth.provisioning.domain.model.SharedAllocationKey
import com.duluin.ftth.provisioning.domain.model.VlanAllocation
import com.duluin.ftth.provisioning.domain.model.VlanAllocationMode
import com.duluin.ftth.provisioning.domain.model.ExecutionStatus
import com.duluin.ftth.provisioning.domain.model.IntentStatus
import com.duluin.ftth.provisioning.domain.model.ProvisionExecution
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class AuthoritativePlanCompilationService(
    private val intents: ServiceIntentRepository,
    private val profiles: SegmentProfileRepository,
    private val pools: VlanPoolRepository,
    private val topology: TransportTopologyRepository,
    private val pathResolver: TransportPathResolver,
    private val allocator: DeterministicVlanAllocationService,
    private val evidence: ProvisioningSafetyEvidenceRepository,
    private val observations: DeviceObservationRepository,
    private val planning: ProvisioningPlanningUseCase,
    private val plans: ProvisionPlanRepository,
    private val customers: com.duluin.ftth.customer.CustomerApi,
    private val network: com.duluin.ftth.network.NetworkApi,
) {
    @Transactional
    fun generate(intentId: UUID, change: PlanChange): ProvisionPlan = planning.generate(request(intentId, change))

    @Transactional
    fun completeDeprovision(intentId: UUID) {
        val intent = intents.findById(intentId) ?: throw NotFoundException("SERVICE_INTENT_NOT_FOUND")
        if (intent.status == IntentStatus.DECOMMISSIONED) return
        val profile = profiles.findById(intent.segmentProfileId) ?: throw NotFoundException("SEGMENT_PROFILE_NOT_FOUND")
        val allocation = pools.findById(profile.poolId)?.allocations?.singleOrNull { candidate ->
            candidate.active && candidate.references.any { it.kind == "SERVICE_INTENT" && it.referenceId == intent.id }
        } ?: throw ConflictException("INTENT_ALLOCATION_REQUIRED")
        allocator.release(intent.tenantId, allocation.id, intent.id)
        intent.decommission()
        intents.save(intent)
    }

    @Transactional
    fun completeDeprovisionIfNeeded(execution: ProvisionExecution) {
        if (execution.status != ExecutionStatus.SUCCEEDED) return
        val plan = plans.findById(execution.planId) ?: return
        if (plan.steps.none { it.operation in DEPROVISION_OPERATIONS }) return
        completeDeprovision(execution.intentId)
    }

    @Transactional
    fun request(intentId: UUID, change: PlanChange): PlanCompilationRequest {
        val tenantId = TenantContext.tenantId()
        val intent = intents.findById(intentId) ?: throw NotFoundException("SERVICE_INTENT_NOT_FOUND")
        if (intent.tenantId != tenantId) throw NotFoundException("SERVICE_INTENT_NOT_FOUND")
        val profile = profiles.findById(intent.segmentProfileId) ?: throw NotFoundException("SEGMENT_PROFILE_NOT_FOUND")
        val snapshot = topology.snapshot()
        val nodes = snapshot.nodes.associateBy(ManagedNode::id)
        val binding = intent.accessBinding ?: throw ConflictException("AUTHORITATIVE_ACCESS_BINDING_REQUIRED")
        val subscriptionId = intent.subscriptionId ?: throw ConflictException("FIXED_SUBSCRIPTION_REQUIRED")
        val subscription = customers.findSubscription(subscriptionId) ?: throw ConflictException("ACCESS_SUBSCRIPTION_NOT_FOUND")
        val placement = customers.placementsForOnus(setOf(binding.onuId)).singleOrNull()
            ?.takeIf { it.customerId == subscription.customerId }
            ?: throw ConflictException("ACCESS_ONU_BINDING_MISMATCH")
        val odpId = placement.odpId ?: throw ConflictException("ACCESS_ONU_NOT_ATTACHED")
        val upstream = network.upstreamOf(odpId)
        if (upstream.olt?.id != binding.oltId || upstream.ponPort?.id != binding.ponPortId) {
            throw ConflictException("ACCESS_PATH_BINDING_STALE")
        }
        val access = snapshot.interfaces.singleOrNull { networkInterface ->
            val node = nodes[networkInterface.nodeId]
            networkInterface.role == InterfaceRole.ACCESS &&
                networkInterface.reference?.kind == com.duluin.ftth.provisioning.domain.model.TopologyReferenceKind.PON &&
                networkInterface.reference.id == binding.ponPortId &&
                node?.role == ManagedNodeRole.OLT &&
                node.reference?.kind == com.duluin.ftth.provisioning.domain.model.TopologyReferenceKind.OLT &&
                node.reference.id == binding.oltId
        } ?: throw ConflictException("AUTHORITATIVE_ACCESS_PATH_REQUIRED")
        val path = pathResolver.resolve(tenantId, access.id)
        val allocation = allocation(intent, profile.poolId, path.nodes.first(), path.nodes.last(), change)
        val planNodes = path.nodes.mapIndexed { index, node ->
            val networkInterface = pathInterface(index, access, path)
            val management = evidence.findManagementEvidence(tenantId, node.device())
                ?: throw ConflictException("MANAGEMENT_PROTECTION_REQUIRED")
            PlanTopologyNode(
                node.device(), node.role, node.administrativeStatus, node.observedAt,
                PlanManagementSource(
                    networkInterface.name,
                    networkInterface.role,
                    requireNotNull(management.sourceType),
                    requireNotNull(management.sourceEvidenceId),
                    emptySet(), emptySet(), emptySet(), management.protectedResources.requiredOutOfBandRoutes,
                    emptySet(), management.availableOutOfBandRoutes,
                ),
            )
        }
        val capabilities = path.nodes.map { node -> capability(tenantId, node, change) }
        val currentObservations = path.nodes.map { node ->
            val observed = observations.findLatestByDevice(node.device())
                ?: throw ConflictException("DEVICE_OBSERVATION_REQUIRED")
            PlanObservation(node.device(), observed.state, observed.observedAt)
        }
        return PlanCompilationRequest(
            intent, allocation.vlanId, change, planNodes, capabilities, currentObservations,
            if (change == PlanChange.DELETE) (allocation.referenceCount - 1).coerceAtLeast(0) else allocation.referenceCount,
        )
    }

    private fun allocation(
        intent: com.duluin.ftth.provisioning.domain.model.ServiceIntent,
        poolId: UUID,
        olt: ManagedNode,
        bras: ManagedNode,
        change: PlanChange,
    ): VlanAllocation {
        val existing = pools.findById(poolId)?.allocations?.singleOrNull { allocation ->
            allocation.active && allocation.references.any { it.kind == "SERVICE_INTENT" && it.referenceId == intent.id }
        }
        if (change == PlanChange.DELETE) return existing ?: throw ConflictException("INTENT_ALLOCATION_REQUIRED")
        return when (intent.allocationMode) {
            VlanAllocationMode.DEDICATED -> allocator.allocateDedicated(
                DedicatedVlanAllocationCommand(intent.tenantId, poolId, olt.device().id, intent.id, intent.id, intent.dedicatedVlanId),
            )
            VlanAllocationMode.SHARED -> allocator.allocateShared(
                SharedVlanAllocationCommand(
                    poolId, intent.id,
                    SharedAllocationKey(intent.tenantId, bras.device().id, olt.device().id, olt.device().id, intent.segmentProfileId),
                    intent.id,
                ),
            )
        }
    }

    private fun capability(tenantId: UUID, node: ManagedNode, change: PlanChange): PlanCapability {
        val operations = operations(node.role, change)
        val rows = operations.map { operation ->
            evidence.findLatestCapabilityEvidence(tenantId, node.device(), operation.name)
                ?: throw ConflictException("CAPABILITY_EVIDENCE_REQUIRED")
        }
        val fingerprints = rows.map { it.fingerprint }.distinct()
        if (fingerprints.size != 1) throw ConflictException("CAPABILITY_FINGERPRINT_INCONSISTENT")
        val fingerprint = fingerprints.single()
        return PlanCapability(
            node.device(), fingerprint.vendor, fingerprint.model, fingerprint.firmware, fingerprint.transport,
            operations.mapTo(linkedSetOf()) { it.name }, rows.minOf { it.observedAt },
        )
    }

    private fun operations(role: ManagedNodeRole, change: PlanChange): Set<ProvisionOperation> = when (change) {
        PlanChange.CREATE -> setOf(when (role) {
            ManagedNodeRole.OLT -> ProvisionOperation.ENSURE_ACCESS_PORT
            ManagedNodeRole.BRAS -> ProvisionOperation.ENSURE_PPPOE_TERMINATION
            ManagedNodeRole.ACCESS_SWITCH, ManagedNodeRole.AGGREGATION_SWITCH -> ProvisionOperation.ENSURE_TAGGED_VLAN
        })
        PlanChange.DELETE -> when (role) {
            ManagedNodeRole.OLT -> setOf(ProvisionOperation.REMOVE_ACCESS_PORT)
            ManagedNodeRole.BRAS -> setOf(ProvisionOperation.BLOCK_PPPOE_SESSIONS, ProvisionOperation.REMOVE_PPPOE_TERMINATION)
            ManagedNodeRole.ACCESS_SWITCH, ManagedNodeRole.AGGREGATION_SWITCH -> setOf(ProvisionOperation.REMOVE_TAGGED_VLAN)
        }
    }

    private fun pathInterface(index: Int, access: ManagedInterface, path: ResolvedTransportPath): ManagedInterface = when (index) {
        0 -> access
        path.nodes.lastIndex -> path.hops.last().toInterface
        else -> path.hops[index].fromInterface
    }

    private fun ManagedNode.device() = DeviceReference(
        when (role) {
            ManagedNodeRole.OLT -> DeviceKind.OLT
            ManagedNodeRole.BRAS -> DeviceKind.BRAS
            ManagedNodeRole.ACCESS_SWITCH, ManagedNodeRole.AGGREGATION_SWITCH -> DeviceKind.SWITCH
        },
        reference?.id ?: id,
    )

    private companion object {
        val DEPROVISION_OPERATIONS = setOf(
            ProvisionOperation.BLOCK_PPPOE_SESSIONS,
            ProvisionOperation.REMOVE_ACCESS_PORT,
            ProvisionOperation.REMOVE_TAGGED_VLAN,
            ProvisionOperation.REMOVE_PPPOE_TERMINATION,
        )
    }
}
