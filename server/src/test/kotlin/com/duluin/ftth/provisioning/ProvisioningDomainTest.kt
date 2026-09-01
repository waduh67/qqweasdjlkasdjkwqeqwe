package com.duluin.ftth.provisioning

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.provisioning.domain.model.AdapterCertification
import com.duluin.ftth.provisioning.domain.model.DeviceKind
import com.duluin.ftth.provisioning.domain.model.DeviceObservation
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.provisioning.domain.model.DeviceSnapshot
import com.duluin.ftth.provisioning.domain.model.DriftStatus
import com.duluin.ftth.provisioning.domain.model.IntentStatus
import com.duluin.ftth.provisioning.domain.model.NormalizedDeviceState
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
    fun `validated plan content cannot mutate and content hash is deterministic`() {
        val plan = plan()
        val originalHash = plan.contentHash
        plan.validate()

        assertThatThrownBy { plan.replaceSteps(listOf(step(2))) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("PLAN_IMMUTABLE")
        assertThat(plan.contentHash).isEqualTo(originalHash)
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
    }

    @Test
    fun `normalized plan snapshot and observation reject secret or raw cli fields`() {
        listOf("password", "apiSecret", "credential", "rawCli", "command").forEach { forbidden ->
            assertThatThrownBy { NormalizedDeviceState.of(mapOf(forbidden to "must-not-persist")) }
                .isInstanceOf(ValidationException::class.java)
                .hasMessageContaining("SENSITIVE_FIELD")
        }

        val state = NormalizedDeviceState.of(mapOf("interfaces" to listOf(mapOf("name" to "ether1"))))
        assertThat(DeviceSnapshot.capture(tenantId, device, UuidV7.generate(), state).state).isEqualTo(state)
        assertThat(DeviceObservation.record(tenantId, device, state).state).isEqualTo(state)
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
