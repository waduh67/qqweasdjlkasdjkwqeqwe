package com.duluin.ftth.provisioning

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.provisioning.application.service.CanonicalProvisioningPlanner
import com.duluin.ftth.provisioning.application.service.ImmutableProvisioningPlanService
import com.duluin.ftth.provisioning.application.service.PlanCapability
import com.duluin.ftth.provisioning.application.service.PlanChange
import com.duluin.ftth.provisioning.application.service.PlanCompilationRequest
import com.duluin.ftth.provisioning.application.service.PlanObservation
import com.duluin.ftth.provisioning.application.service.PlanTopologyNode
import com.duluin.ftth.provisioning.application.port.outbound.ProvisionPlanRepository
import com.duluin.ftth.provisioning.domain.model.AdministrativeStatus
import com.duluin.ftth.provisioning.domain.model.DeviceKind
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.provisioning.domain.model.ManagedNodeRole
import com.duluin.ftth.provisioning.domain.model.NormalizedDeviceState
import com.duluin.ftth.provisioning.domain.model.NormalizedField
import com.duluin.ftth.provisioning.domain.model.NormalizedValue
import com.duluin.ftth.provisioning.domain.model.PlanStatus
import com.duluin.ftth.provisioning.domain.model.ProvisionOperation
import com.duluin.ftth.provisioning.domain.model.ProvisionPlan
import com.duluin.ftth.provisioning.domain.model.ServiceIntent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class CanonicalProvisioningPlannerTest {
    private val tenantId = UUID.fromString("00000000-0000-7000-8000-000000000001")
    private val intent = ServiceIntent.rehydrate(
        UUID.fromString("00000000-0000-7000-8000-000000000002"),
        tenantId,
        UUID.fromString("00000000-0000-7000-8000-000000000003"),
        UUID.fromString("00000000-0000-7000-8000-000000000004"),
        com.duluin.ftth.provisioning.domain.model.VlanEncapsulation.SINGLE_TAG,
        null,
        com.duluin.ftth.provisioning.domain.model.IntentStatus.ACTIVE,
    )
    private val olt = DeviceReference(DeviceKind.OLT, UUID.fromString("00000000-0000-7000-8000-000000000010"))
    private val transit = DeviceReference(DeviceKind.SWITCH, UUID.fromString("00000000-0000-7000-8000-000000000011"))
    private val bras = DeviceReference(DeviceKind.BRAS, UUID.fromString("00000000-0000-7000-8000-000000000012"))
    private val observedAt = Instant.parse("2026-01-02T03:04:05Z")
    private val planner = CanonicalProvisioningPlanner()

    @Test
    fun `identical logical inputs produce byte stable plans and sha256 hashes`() {
        val first = planner.compile(request(), revision = 1)
        val reordered = planner.compile(request(reverseInputOrder = true), revision = 1)

        assertThat(first.canonicalPayload()).isEqualTo(reordered.canonicalPayload())
        assertThat(first.id).isEqualTo(reordered.id)
        assertThat(first.steps.map { it.id }).isEqualTo(reordered.steps.map { it.id })
        assertThat(first.contentHash).matches("^[a-f0-9]{64}$")
        assertThat(first.preconditionHash).matches("^[a-f0-9]{64}$")
        assertThat(first.steps.map { it.preconditionHash }).allMatch { it.matches(Regex("^[a-f0-9]{64}$")) }
    }

    @Test
    fun `create and delete use safety ordering and only clean unreferenced bras resources`() {
        val create = planner.compile(request(), revision = 1)
        assertThat(create.steps.map { it.device }).containsExactly(bras, transit, olt)
        assertThat(create.steps.map { it.operation }).containsExactly(
            ProvisionOperation.ENSURE_PPPOE_TERMINATION,
            ProvisionOperation.ENSURE_TAGGED_VLAN,
            ProvisionOperation.ENSURE_ACCESS_PORT,
        )

        val retainedDelete = planner.compile(request(change = PlanChange.DELETE, brasReferenceCount = 1), revision = 2)
        assertThat(retainedDelete.steps.map { it.operation }).containsExactly(
            ProvisionOperation.BLOCK_PPPOE_SESSIONS,
            ProvisionOperation.REMOVE_ACCESS_PORT,
            ProvisionOperation.REMOVE_TAGGED_VLAN,
        )

        val cleanupDelete = planner.compile(request(change = PlanChange.DELETE, brasReferenceCount = 0), revision = 2)
        assertThat(cleanupDelete.steps.map { it.device }).containsExactly(bras, olt, transit, bras)
        assertThat(cleanupDelete.steps.last().operation).isEqualTo(ProvisionOperation.REMOVE_PPPOE_TERMINATION)
    }

    @Test
    fun `changed source creates a new revision and supersedes without mutating old payload`() {
        val repository = InMemoryPlanRepository()
        val service = ImmutableProvisioningPlanService(planner, repository)
        val original = service.plan(request())
        original.validate()
        repository.save(original)
        val originalPayload = original.canonicalPayload()

        val unchanged = service.plan(request())
        val replacement = service.plan(request(vlanId = 321))

        assertThat(unchanged.id).isEqualTo(original.id)
        assertThat(original.status).isEqualTo(PlanStatus.SUPERSEDED)
        assertThat(original.canonicalPayload()).isEqualTo(originalPayload)
        assertThat(replacement.id).isNotEqualTo(original.id)
        assertThat(replacement.revision).isEqualTo(2)
        assertThat(replacement.contentHash).isNotEqualTo(original.contentHash)
        assertThat(replacement.preconditionHash).isNotEqualTo(original.preconditionHash)
    }

    private fun request(
        change: PlanChange = PlanChange.CREATE,
        brasReferenceCount: Int = 0,
        vlanId: Int = 320,
        reverseInputOrder: Boolean = false,
    ): PlanCompilationRequest {
        val topology = listOf(
            PlanTopologyNode(olt, ManagedNodeRole.OLT, AdministrativeStatus.ENABLED, observedAt, "pon-1"),
            PlanTopologyNode(transit, ManagedNodeRole.ACCESS_SWITCH, AdministrativeStatus.ENABLED, observedAt, "xe-0/0/1"),
            PlanTopologyNode(bras, ManagedNodeRole.BRAS, AdministrativeStatus.ENABLED, observedAt, "ae0"),
        )
        val capabilities = listOf(
            PlanCapability(olt, "zte|c320|1.2|ssh", setOf("access-vlan"), observedAt),
            PlanCapability(transit, "junos|ex|22|netconf", setOf("tagged-vlan", "verify"), observedAt),
            PlanCapability(bras, "mikrotik|ccr|7|rest", setOf("pppoe", "firewall"), observedAt),
        )
        val observations = listOf(
            PlanObservation(
                olt,
                NormalizedDeviceState.of(
                    NormalizedField.PORT to NormalizedValue.identifier("pon-1"),
                    NormalizedField.VLANS to NormalizedValue.sequence(NormalizedValue.number(99), NormalizedValue.number(100)),
                ),
                observedAt,
            ),
            PlanObservation(
                transit,
                NormalizedDeviceState.of(
                    NormalizedField.VLANS to NormalizedValue.sequence(NormalizedValue.number(100), NormalizedValue.number(99)),
                    NormalizedField.PORT to NormalizedValue.identifier("xe-0/0/1"),
                ),
                observedAt,
            ),
            PlanObservation(
                bras,
                NormalizedDeviceState.of(
                    NormalizedField.ENABLED to NormalizedValue.flag(true),
                    NormalizedField.VLANS to NormalizedValue.sequence(NormalizedValue.number(100)),
                ),
                observedAt,
            ),
        )
        return PlanCompilationRequest(
            intent = intent,
            vlanId = vlanId,
            change = change,
            topology = topology,
            capabilities = if (reverseInputOrder) capabilities.reversed().map { it.copy(capabilities = it.capabilities.reversed().toSet()) } else capabilities,
            observations = if (reverseInputOrder) observations.reversed() else observations,
            brasReferenceCount = brasReferenceCount,
        )
    }

    private class InMemoryPlanRepository : ProvisionPlanRepository {
        private val values = linkedMapOf<UUID, ProvisionPlan>()

        override fun save(value: ProvisionPlan): ProvisionPlan = value.also { values[it.id] = it }
        override fun findById(id: UUID): ProvisionPlan? = values[id]
        override fun findLatestByIntentId(intentId: UUID): ProvisionPlan? =
            values.values.filter { it.intentId == intentId }.maxByOrNull { it.revision }
    }
}
