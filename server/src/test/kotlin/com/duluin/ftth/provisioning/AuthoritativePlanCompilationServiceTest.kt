package com.duluin.ftth.provisioning

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.provisioning.application.port.inbound.ProvisioningPlanningUseCase
import com.duluin.ftth.provisioning.application.port.outbound.DeviceObservationRepository
import com.duluin.ftth.provisioning.application.port.outbound.ProvisioningSafetyEvidenceRepository
import com.duluin.ftth.provisioning.application.port.outbound.SegmentProfileRepository
import com.duluin.ftth.provisioning.application.port.outbound.ServiceIntentRepository
import com.duluin.ftth.provisioning.application.port.outbound.TransportTopologyRepository
import com.duluin.ftth.provisioning.application.port.outbound.VlanPoolRepository
import com.duluin.ftth.provisioning.application.service.AuthoritativePlanCompilationService
import com.duluin.ftth.provisioning.application.service.CanonicalProvisioningPlanner
import com.duluin.ftth.provisioning.application.service.DedicatedVlanAllocationCommand
import com.duluin.ftth.provisioning.application.service.DeterministicVlanAllocationService
import com.duluin.ftth.provisioning.application.service.PlanCompilationRequest
import com.duluin.ftth.provisioning.application.service.PlanChange
import com.duluin.ftth.provisioning.application.service.ResolvedTransportPath
import com.duluin.ftth.provisioning.application.service.TransportPathHop
import com.duluin.ftth.provisioning.application.service.TransportPathResolver
import com.duluin.ftth.provisioning.domain.model.AdministrativeStatus
import com.duluin.ftth.provisioning.domain.model.DeviceKind
import com.duluin.ftth.provisioning.domain.model.DeviceObservation
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.provisioning.domain.model.InterfaceRole
import com.duluin.ftth.provisioning.domain.model.ManagedInterface
import com.duluin.ftth.provisioning.domain.model.ManagedNode
import com.duluin.ftth.provisioning.domain.model.ManagedNodeRole
import com.duluin.ftth.provisioning.domain.model.NormalizedDeviceState
import com.duluin.ftth.provisioning.domain.model.NormalizedField
import com.duluin.ftth.provisioning.domain.model.NormalizedValue
import com.duluin.ftth.provisioning.domain.model.ProvisionOperation
import com.duluin.ftth.provisioning.domain.model.SegmentProfile
import com.duluin.ftth.provisioning.domain.model.ServiceIntent
import com.duluin.ftth.provisioning.domain.model.TopologyReference
import com.duluin.ftth.provisioning.domain.model.TopologyReferenceKind
import com.duluin.ftth.provisioning.domain.model.TransportLink
import com.duluin.ftth.provisioning.domain.model.VlanAllocationMode
import com.duluin.ftth.provisioning.domain.model.VlanPool
import com.duluin.ftth.provisioning.domain.model.VlanRange
import com.duluin.ftth.provisioning.domain.policy.CapabilityEvidence
import com.duluin.ftth.provisioning.domain.policy.DeviceFingerprint
import com.duluin.ftth.provisioning.domain.policy.ManagementEvidenceSourceType
import com.duluin.ftth.provisioning.domain.policy.ManagementSafetyEvidence
import com.duluin.ftth.provisioning.domain.policy.ProtectedManagementResources
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Instant
import java.util.UUID

class AuthoritativePlanCompilationServiceTest {
    @Test
    fun `generation assembles plan only from persisted intent topology allocation and evidence`() {
        val tenantId = UUID.randomUUID()
        val oltTarget = UUID.randomUUID()
        val ponTarget = UUID.randomUUID()
        val onuTarget = UUID.randomUUID()
        val intent = ServiceIntent.create(
            tenantId, UUID.randomUUID(), UUID.randomUUID(), dedicatedVlanId = 320,
            allocationMode = VlanAllocationMode.DEDICATED,
            accessBinding = com.duluin.ftth.provisioning.domain.model.ServiceAccessBinding(oltTarget, ponTarget, onuTarget),
        )
        val profile = SegmentProfile.create(tenantId, "Business", UUID.randomUUID())
        val brasTarget = UUID.randomUUID()
        val olt = ManagedNode.create(tenantId, "OLT", ManagedNodeRole.OLT, TopologyReference(TopologyReferenceKind.OLT, oltTarget), AdministrativeStatus.ENABLED, NOW)
        val bras = ManagedNode.create(tenantId, "BRAS", ManagedNodeRole.BRAS, TopologyReference(TopologyReferenceKind.NAS, brasTarget), AdministrativeStatus.ENABLED, NOW)
        val otherOlt = ManagedNode.create(tenantId, "Other OLT", ManagedNodeRole.OLT, TopologyReference(TopologyReferenceKind.OLT, UUID.randomUUID()), AdministrativeStatus.ENABLED, NOW)
        val access = ManagedInterface.create(tenantId, olt.id, "pon-1", InterfaceRole.ACCESS, TopologyReference(TopologyReferenceKind.PON, ponTarget), AdministrativeStatus.ENABLED, NOW)
        val otherAccess = ManagedInterface.create(tenantId, otherOlt.id, "pon-other", InterfaceRole.ACCESS, TopologyReference(TopologyReferenceKind.PON, UUID.randomUUID()), AdministrativeStatus.ENABLED, NOW)
        val uplink = ManagedInterface.create(tenantId, bras.id, "ae0", InterfaceRole.TRUNK, null, AdministrativeStatus.ENABLED, NOW)
        val link = TransportLink.create(tenantId, access.id, uplink.id, AdministrativeStatus.ENABLED, NOW)
        val path = ResolvedTransportPath(listOf(olt, bras), listOf(TransportPathHop(link, access, uplink)))
        val topology = mock(TransportTopologyRepository::class.java)
        val intents = mock(ServiceIntentRepository::class.java)
        val profiles = mock(SegmentProfileRepository::class.java)
        val pools = mock(VlanPoolRepository::class.java)
        val resolver = mock(TransportPathResolver::class.java)
        val allocator = mock(DeterministicVlanAllocationService::class.java)
        val safety = mock(ProvisioningSafetyEvidenceRepository::class.java)
        val observations = mock(DeviceObservationRepository::class.java)
        lateinit var captured: PlanCompilationRequest
        val planning = object : ProvisioningPlanningUseCase {
            override fun generate(request: PlanCompilationRequest) = CanonicalProvisioningPlanner().compile(request, 1).also {
                captured = request
            }
            override fun validateProduction(request: PlanCompilationRequest) = generate(request)
            override fun preview(request: PlanCompilationRequest, mode: com.duluin.ftth.provisioning.domain.policy.ExecutionMode) =
                throw UnsupportedOperationException()
        }
        val planRepository = mock(com.duluin.ftth.provisioning.application.port.outbound.ProvisionPlanRepository::class.java)
        val customers = mock(com.duluin.ftth.customer.CustomerApi::class.java)
        val network = mock(com.duluin.ftth.network.NetworkApi::class.java)
        val customerId = UUID.randomUUID()
        val odpId = UUID.randomUUID()
        `when`(customers.findSubscription(requireNotNull(intent.subscriptionId))).thenReturn(
            com.duluin.ftth.customer.SubscriptionRef(requireNotNull(intent.subscriptionId), customerId, null, "Business", 100, "ACTIVE"),
        )
        `when`(customers.placementsForOnus(setOf(onuTarget))).thenReturn(
            listOf(com.duluin.ftth.customer.OnuPlacementRef(onuTarget, customerId, odpId)),
        )
        `when`(network.upstreamOf(odpId)).thenReturn(
            com.duluin.ftth.network.UpstreamPath(
                mock(com.duluin.ftth.network.OdpRef::class.java), null,
                com.duluin.ftth.network.UpstreamHop(ponTarget, "PON", "PON"),
                com.duluin.ftth.network.UpstreamHop(oltTarget, "OLT", "OLT"), null, 0.0,
            ),
        )
        val pool = VlanPool.create(tenantId, "Dedicated", VlanRange(300, 399), emptyList())
        val allocation = pool.allocate(DeviceReference(DeviceKind.OLT, oltTarget), 320, intent.id).also {
            it.addReference("SERVICE_INTENT", intent.id)
        }
        `when`(intents.findById(intent.id)).thenReturn(intent)
        `when`(profiles.findById(intent.segmentProfileId)).thenReturn(profile)
        `when`(pools.findById(profile.poolId)).thenReturn(pool)
        `when`(topology.snapshot()).thenReturn(com.duluin.ftth.provisioning.domain.model.TransportTopologySnapshot(listOf(olt, bras, otherOlt), listOf(access, uplink, otherAccess), listOf(link)))
        `when`(resolver.resolve(tenantId, access.id)).thenReturn(path)
        `when`(
            allocator.allocateDedicated(
                DedicatedVlanAllocationCommand(tenantId, profile.poolId, oltTarget, intent.id, intent.id, 320),
            ),
        ).thenReturn(allocation)
        listOf(olt.device(), bras.device()).forEach { device ->
            `when`(safety.findManagementEvidence(tenantId, device)).thenReturn(management(tenantId, device))
            `when`(observations.findLatestByDevice(device)).thenReturn(observation(tenantId, device))
        }
        `when`(safety.findLatestCapabilityEvidence(tenantId, olt.device(), ProvisionOperation.ENSURE_ACCESS_PORT.name))
            .thenReturn(capability(tenantId, olt.device(), ProvisionOperation.ENSURE_ACCESS_PORT))
        `when`(safety.findLatestCapabilityEvidence(tenantId, bras.device(), ProvisionOperation.ENSURE_PPPOE_TERMINATION.name))
            .thenReturn(capability(tenantId, bras.device(), ProvisionOperation.ENSURE_PPPOE_TERMINATION))
        val service = AuthoritativePlanCompilationService(
            intents, profiles, pools, topology, resolver, allocator, safety, observations, planning, planRepository, customers, network,
        )

        val plan = TenantContext.runAs(tenantId) { service.generate(intent.id, PlanChange.CREATE) }

        assertThat(captured.vlanId).isEqualTo(320)
        assertThat(captured.intent).isSameAs(intent)
        assertThat(plan.steps.map { it.device }).containsExactly(bras.device(), olt.device())
        verify(resolver).resolve(tenantId, access.id)
    }

    private fun capability(tenantId: UUID, device: DeviceReference, operation: ProvisionOperation) = CapabilityEvidence(
        UUID.randomUUID(), tenantId, DeviceFingerprint(device, "SIMULATOR", "QA", "1", "IN_MEMORY", operation.name), true,
        NOW, NOW.plusSeconds(300),
    )

    private fun management(tenantId: UUID, device: DeviceReference) = ManagementSafetyEvidence(
        UUID.randomUUID(), tenantId, device, ProtectedManagementResources(), emptySet(), NOW, NOW.plusSeconds(300), true,
        ManagementEvidenceSourceType.TOPOLOGY_OBSERVATION, device.id,
    )

    private fun observation(tenantId: UUID, device: DeviceReference) = DeviceObservation.rehydrate(
        UUID.randomUUID(), tenantId, device,
        NormalizedDeviceState.of(NormalizedField.VLANS to NormalizedValue.sequence()), NOW,
    )

    private fun ManagedNode.device() = DeviceReference(
        if (role == ManagedNodeRole.OLT) DeviceKind.OLT else DeviceKind.BRAS,
        requireNotNull(reference).id,
    )

    private companion object { val NOW: Instant = Instant.parse("2026-09-03T12:00:00Z") }
}
