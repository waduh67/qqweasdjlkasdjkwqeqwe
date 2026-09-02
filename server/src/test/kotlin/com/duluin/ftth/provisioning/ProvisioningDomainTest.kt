package com.duluin.ftth.provisioning

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.provisioning.domain.model.AdapterCertification
import com.duluin.ftth.provisioning.domain.model.ActiveVlanAllocationPolicy
import com.duluin.ftth.provisioning.domain.model.DeviceKind
import com.duluin.ftth.provisioning.domain.model.DeviceObservation
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.provisioning.domain.model.DeviceSnapshot
import com.duluin.ftth.provisioning.domain.model.DriftStatus
import com.duluin.ftth.provisioning.domain.model.IntentStatus
import com.duluin.ftth.provisioning.domain.model.LengthPrefixedCanonical
import com.duluin.ftth.provisioning.domain.model.NormalizedDeviceState
import com.duluin.ftth.provisioning.domain.model.NormalizedField
import com.duluin.ftth.provisioning.domain.model.NormalizedValue
import com.duluin.ftth.provisioning.domain.model.PlanStatus
import com.duluin.ftth.provisioning.domain.model.ProvisionExecution
import com.duluin.ftth.provisioning.domain.model.ProvisionOperation
import com.duluin.ftth.provisioning.domain.model.ProvisionPlan
import com.duluin.ftth.provisioning.domain.model.ProvisionStep
import com.duluin.ftth.provisioning.domain.model.SegmentProfile
import com.duluin.ftth.provisioning.domain.model.ServiceIntent
import com.duluin.ftth.provisioning.domain.model.VlanEncapsulation
import com.duluin.ftth.provisioning.domain.model.VlanPool
import com.duluin.ftth.provisioning.domain.model.VlanRange
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ProvisioningDomainTest {
    private val tenantId = UuidV7.generate()
    private val device = DeviceReference(DeviceKind.ROUTER, UuidV7.generate())

    @Test
    fun `vlan pool rejects unsafe vlan ids reserved ranges and duplicate active allocation`() {
        val pool = VlanPool.create(tenantId, "POP-A", VlanRange(100, 199), listOf(VlanRange(120, 129)))

        listOf(1, 4095, 120).forEach { vlanId ->
            assertThatThrownBy { pool.allocate(device, vlanId, UuidV7.generate()) }
                .isInstanceOf(ValidationException::class.java)
        }

        pool.allocate(device, 110, UuidV7.generate())
        assertThatThrownBy { pool.allocate(device, 110, UuidV7.generate()) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("VLAN_ALREADY_ALLOCATED")
    }

    @Test
    fun `release one accepts only single tag encapsulation`() {
        listOf(VlanEncapsulation.QINQ, VlanEncapsulation.TRANSLATION, VlanEncapsulation.NATIVE).forEach { mode ->
            assertThatThrownBy {
                ServiceIntent.create(tenantId, UuidV7.generate(), UuidV7.generate(), mode)
            }.isInstanceOf(ValidationException::class.java)
                .hasMessageContaining("UNSUPPORTED_VLAN_MODE")
        }
    }

    @Test
    fun `intent plan and execution enforce exact lifecycle transitions`() {
        val intent = ServiceIntent.create(
            tenantId,
            UuidV7.generate(),
            UuidV7.generate(),
            VlanEncapsulation.SINGLE_TAG,
        )
        intent.activate()
        intent.suspend()
        intent.activate()
        intent.decommission()
        assertThat(intent.status).isEqualTo(IntentStatus.DECOMMISSIONED)
        assertThatThrownBy { intent.activate() }.isInstanceOf(ConflictException::class.java)

        val plan = plan()
        assertThatThrownBy { plan.supersede() }.isInstanceOf(ConflictException::class.java)
        plan.validate()
        plan.supersede()
        assertThat(plan.status).isEqualTo(PlanStatus.SUPERSEDED)

        val execution = ProvisionExecution.queue(tenantId, plan.id, "intent-1-revision-1")
        execution.start()
        execution.verify()
        execution.succeed()
        assertThatThrownBy { execution.fail("late failure") }.isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `plan content is immutable from construction and content hash is deterministic`() {
        val sourceSteps = mutableListOf(step(1))
        val plan = ProvisionPlan.generate(tenantId, UuidV7.generate(), 1, sourceSteps)
        val originalHash = plan.contentHash

        sourceSteps += step(2)

        assertThat(plan.steps).hasSize(1)
        assertThat(plan.contentHash).isEqualTo(originalHash)
        assertThat(ProvisionPlan::class.java.methods.map { it.name }).doesNotContain("replaceSteps")
    }

    @Test
    fun `rehydrated plan rejects content that does not match immutable hash`() {
        val plan = plan()

        assertThatThrownBy {
            ProvisionPlan.rehydrate(
                plan.id,
                plan.tenantId,
                plan.intentId,
                plan.revision,
                listOf(step(2)),
                plan.status,
                plan.contentHash,
            )
        }.isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("PLAN_CONTENT_HASH_MISMATCH")
    }

    @Test
    fun `allocation reference count is derived and duplicate references are rejected`() {
        val allocation = VlanPool.create(tenantId, "POP-A", VlanRange(100, 199))
            .allocate(device, 110, UuidV7.generate())
        val subscriberId = UuidV7.generate()

        allocation.addReference("SUBSCRIPTION", subscriberId)
        allocation.addReference("SERVICE_INTENT", UuidV7.generate())
        assertThat(allocation.referenceCount).isEqualTo(2)
        assertThatThrownBy { allocation.addReference("SUBSCRIPTION", subscriberId) }
            .isInstanceOf(ConflictException::class.java)
        allocation.removeReference("SUBSCRIPTION", subscriberId)
        assertThat(allocation.referenceCount).isEqualTo(1)
        assertThatThrownBy { allocation.release() }
            .isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("ALLOCATION_STILL_REFERENCED")
    }

    @Test
    fun `active allocation policy rejects a duplicate from another pool`() {
        val existing = VlanPool.create(tenantId, "POP-A", VlanRange(100, 199))
            .allocate(device, 110, UuidV7.generate())

        assertThatThrownBy {
            ActiveVlanAllocationPolicy.requireAvailable(tenantId, device, 110, listOf(existing))
        }.isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("VLAN_ALREADY_ALLOCATED")
    }

    @Test
    fun `normalized plan snapshot and observation reject secret or raw cli fields`() {
        listOf("password", "apiSecret", "credential", "rawCli", "command").forEach { forbidden ->
            assertThatThrownBy { NormalizedField.fromWireName(forbidden) }
                .isInstanceOf(ValidationException::class.java)
                .hasMessageContaining("NORMALIZED_FIELD_UNSUPPORTED")
        }

        val state = NormalizedDeviceState.of(
            NormalizedField.INTERFACES to NormalizedValue.sequence(
                NormalizedValue.obj(NormalizedField.NAME to NormalizedValue.identifier("ether1")),
            ),
        )
        assertThat(DeviceSnapshot.capture(tenantId, device, UuidV7.generate(), state).state).isEqualTo(state)
        assertThat(DeviceObservation.record(tenantId, device, state).state).isEqualTo(state)
    }

    @Test
    fun `normalized state cannot be mutated through nested source values`() {
        val source = mutableListOf(NormalizedValue.obj(NormalizedField.NAME to NormalizedValue.identifier("ether1")))
        val state = NormalizedDeviceState.of(
            NormalizedField.INTERFACES to NormalizedValue.Sequence.of(source),
        )

        source += NormalizedValue.obj(NormalizedField.NAME to NormalizedValue.identifier("ether2"))

        val interfaces = state.values.getValue(NormalizedField.INTERFACES) as NormalizedValue.Sequence
        assertThat(interfaces.values).hasSize(1)
    }

    @Test
    fun `normalized state rejects raw device instructions hidden under an innocuous key`() {
        listOf("/interface vlan add name=vlan110", "token-redacted").forEach { unsafeValue ->
            assertThatThrownBy { NormalizedValue.identifier(unsafeValue) }
                .isInstanceOf(ValidationException::class.java)
                .hasMessageContaining("NORMALIZED_TEXT_INVALID")
        }
    }

    @Test
    fun `plan hash distinguishes delimiter shaped attribute values`() {
        val oneValueContainingOldDelimiters = LengthPrefixedCanonical.encode(listOf("a", "x|b=y"))
        val twoFieldsInTheOldShape = LengthPrefixedCanonical.encode(listOf("a", "x", "b", "y"))

        assertThat(oneValueContainingOldDelimiters).isNotEqualTo(twoFieldsInTheOldShape)
    }

    @Test
    fun `plan attributes reject unsupported fields and raw device instructions`() {
        listOf(
            mapOf("payload" to "normalized-value"),
            mapOf("interface" to "/interface vlan add name=vlan110"),
        ).forEach { attributes ->
            assertThatThrownBy {
                ProvisionStep.create(1, device, ProvisionOperation.ENSURE_TAGGED_VLAN, attributes)
            }.isInstanceOf(ValidationException::class.java)
        }
    }

    @Test
    fun `rehydrated pool rejects allocations owned by another tenant or pool`() {
        val poolId = UuidV7.generate()
        val allocation = com.duluin.ftth.provisioning.domain.model.VlanAllocation.rehydrate(
            UuidV7.generate(),
            UuidV7.generate(),
            poolId,
            device,
            110,
            UuidV7.generate(),
            true,
            emptyList(),
        )

        assertThatThrownBy {
            VlanPool.rehydrate(poolId, tenantId, "POP-A", VlanRange(100, 199), emptyList(), listOf(allocation))
        }.isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("TENANT_OWNERSHIP_MISMATCH")
    }

    @Test
    fun `every lifecycle action rejects unsupported terminal states`() {
        IntentStatus.entries.forEach { status ->
            val intent = ServiceIntent.rehydrate(
                UuidV7.generate(), tenantId, UuidV7.generate(), UuidV7.generate(),
                VlanEncapsulation.SINGLE_TAG, null, status,
            )
            if (status !in setOf(IntentStatus.DRAFT, IntentStatus.SUSPENDED)) {
                assertThatThrownBy { intent.activate() }.isInstanceOf(ConflictException::class.java)
            }
        }

        PlanStatus.entries.filterNot { it == PlanStatus.GENERATED }.forEach { status ->
            val generated = plan()
            val candidate = ProvisionPlan.rehydrate(
                generated.id, generated.tenantId, generated.intentId, generated.revision,
                generated.steps, status, generated.contentHash,
            )
            assertThatThrownBy { candidate.validate() }.isInstanceOf(ConflictException::class.java)
            assertThatThrownBy { candidate.reject() }.isInstanceOf(ConflictException::class.java)
        }
        val rejected = plan()
        rejected.reject()
        assertThat(rejected.status).isEqualTo(PlanStatus.REJECTED)
        assertThatThrownBy { rejected.supersede() }.isInstanceOf(ConflictException::class.java)

        val rollback = ProvisionExecution.queue(tenantId, UuidV7.generate(), "rollback-matrix")
        rollback.start()
        rollback.beginRollback()
        rollback.completeRollback()
        assertThatThrownBy { rollback.start() }.isInstanceOf(ConflictException::class.java)

        val failed = ProvisionExecution.queue(tenantId, UuidV7.generate(), "failed-matrix")
        failed.fail("redacted failure")
        failed.requireManualReconciliation("operator review required")
        listOf<(ProvisionExecution) -> Unit>(
            { it.start() },
            { it.verify() },
            { it.succeed() },
            { it.beginRollback() },
            { it.completeRollback() },
            { it.fail("late failure") },
        ).forEach { action ->
            assertThatThrownBy { action(failed) }.isInstanceOf(ConflictException::class.java)
        }
    }

    @Test
    fun `all provisioning aggregate factories issue uuid v7 identities`() {
        val pool = VlanPool.create(tenantId, "POP-A", VlanRange(100, 199))
        val intent = ServiceIntent.create(tenantId, UuidV7.generate(), UuidV7.generate())
        val aggregates = listOf(
            SegmentProfile.create(tenantId, "Residential", pool.id),
            pool,
            intent,
            plan(),
            ProvisionExecution.queue(tenantId, UuidV7.generate(), "unique-key"),
            DeviceSnapshot.capture(tenantId, device, UuidV7.generate(), NormalizedDeviceState.empty()),
            DeviceObservation.record(tenantId, device, NormalizedDeviceState.empty()),
            com.duluin.ftth.provisioning.domain.model.DriftRecord.record(
                tenantId,
                device,
                UuidV7.generate(),
                UuidV7.generate(),
                DriftStatus.NONE,
            ),
            AdapterCertification.certify(tenantId, device, "model", "1.0", "SSH", "VLAN_PROVISION"),
        )

        assertThat(aggregates.map { it.id.version() }).containsOnly(7)
    }

    private fun plan() = ProvisionPlan.generate(tenantId, UuidV7.generate(), 1, listOf(step(1)))

    private fun step(order: Int) = ProvisionStep.create(
        order,
        device,
        ProvisionOperation.ENSURE_TAGGED_VLAN,
        mapOf("vlanId" to "110", "interface" to "ether1"),
    )
}
