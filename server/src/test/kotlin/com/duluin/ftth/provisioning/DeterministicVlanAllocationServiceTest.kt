package com.duluin.ftth.provisioning

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.provisioning.application.port.outbound.VlanAllocationScopeRepository
import com.duluin.ftth.provisioning.application.port.outbound.VlanPoolRepository
import com.duluin.ftth.provisioning.application.service.DedicatedVlanAllocationCommand
import com.duluin.ftth.provisioning.application.service.DeterministicVlanAllocationService
import com.duluin.ftth.provisioning.application.service.SharedVlanAllocationCommand
import com.duluin.ftth.provisioning.domain.model.SharedAllocationKey
import com.duluin.ftth.provisioning.domain.model.VlanAllocationScope
import com.duluin.ftth.provisioning.domain.model.VlanPool
import com.duluin.ftth.provisioning.domain.model.VlanRange
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class DeterministicVlanAllocationServiceTest {
    private val tenantId = UuidV7.generate()
    private val popId = UuidV7.generate()
    private val oltId = UuidV7.generate()
    private val areaId = UuidV7.generate()

    @Test
    fun `shared residential key reuses one lowest free allocation`() {
        val fixture = fixture(VlanRange(100, 103), listOf(VlanRange(100, 100)))
        val key = sharedKey()

        val first = fixture.service.allocateShared(sharedCommand(fixture.pool.id, key, UuidV7.generate()))
        val second = fixture.service.allocateShared(sharedCommand(fixture.pool.id, key, UuidV7.generate()))

        assertThat(first.id).isEqualTo(second.id)
        assertThat(first.vlanId).isEqualTo(101)
        assertThat(second.referenceCount).isEqualTo(2)
        assertThat(fixture.scopes.values).hasSize(1)
        assertThat(fixture.pools.lockCount).isEqualTo(2)
    }

    @Test
    fun `lowest free selection excludes every reserved VLAN`() {
        val fixture = fixture(VlanRange(100, 103), listOf(VlanRange(100, 102)))

        val allocation = fixture.service.allocateShared(
            sharedCommand(fixture.pool.id, sharedKey(), UuidV7.generate()),
        )

        assertThat(allocation.vlanId).isEqualTo(103)
    }

    @Test
    fun `enterprise intents reserve unique deterministic VLANs and honor an explicit override`() {
        val fixture = fixture(VlanRange(310, 312))
        val firstIntent = UuidV7.generate()
        val secondIntent = UuidV7.generate()

        val override = fixture.service.allocateDedicated(
            dedicatedCommand(fixture.pool.id, firstIntent, requestedVlanId = 310),
        )
        val lowestFree = fixture.service.allocateDedicated(
            dedicatedCommand(fixture.pool.id, secondIntent),
        )
        val retried = fixture.service.allocateDedicated(
            dedicatedCommand(fixture.pool.id, firstIntent, requestedVlanId = 310),
        )

        assertThat(override.vlanId).isEqualTo(310)
        assertThat(lowestFree.vlanId).isEqualTo(311)
        assertThat(retried.id).isEqualTo(override.id)
        assertThat(fixture.scopes.values).hasSize(2)
    }

    @Test
    fun `dedicated VLAN collision is rejected without falling back`() {
        val fixture = fixture(VlanRange(310, 312))
        fixture.service.allocateDedicated(
            dedicatedCommand(fixture.pool.id, UuidV7.generate(), requestedVlanId = 310),
        )

        assertThatThrownBy {
            fixture.service.allocateDedicated(
                dedicatedCommand(fixture.pool.id, UuidV7.generate(), requestedVlanId = 310),
            )
        }.isInstanceOf(ConflictException::class.java)
            .hasMessage("VLAN_ALREADY_ALLOCATED")
    }

    @Test
    fun `exhausted pool returns stable conflict code`() {
        val fixture = fixture(VlanRange(200, 200))
        fixture.service.allocateShared(
            sharedCommand(fixture.pool.id, sharedKey(serviceClassId = UuidV7.generate()), UuidV7.generate()),
        )

        assertThatThrownBy {
            fixture.service.allocateShared(
                sharedCommand(fixture.pool.id, sharedKey(serviceClassId = UuidV7.generate()), UuidV7.generate()),
            )
        }.isInstanceOf(ConflictException::class.java)
            .hasMessage("VLAN_POOL_EXHAUSTED")
    }

    @Test
    fun `shared allocation remains active until its final reference is released then VLAN is reused`() {
        val fixture = fixture(VlanRange(110, 111))
        val firstReference = UuidV7.generate()
        val secondReference = UuidV7.generate()
        val firstKey = sharedKey(serviceClassId = UuidV7.generate())
        val allocation = fixture.service.allocateShared(
            sharedCommand(fixture.pool.id, firstKey, firstReference),
        )
        fixture.service.allocateShared(sharedCommand(fixture.pool.id, firstKey, secondReference))

        val stillShared = fixture.service.release(tenantId, allocation.id, firstReference)
        val next = fixture.service.allocateShared(
            sharedCommand(fixture.pool.id, sharedKey(serviceClassId = UuidV7.generate()), UuidV7.generate()),
        )

        assertThat(stillShared.active).isTrue()
        assertThat(stillShared.referenceCount).isEqualTo(1)
        assertThat(next.vlanId).isEqualTo(111)

        val released = fixture.service.release(tenantId, allocation.id, secondReference)
        val reused = fixture.service.allocateShared(
            sharedCommand(fixture.pool.id, sharedKey(serviceClassId = UuidV7.generate()), UuidV7.generate()),
        )

        assertThat(released.active).isFalse()
        assertThat(reused.vlanId).isEqualTo(110)
        assertThat(reused.id).isNotEqualTo(allocation.id)
    }

    private fun fixture(range: VlanRange, reserved: List<VlanRange> = emptyList()): Fixture {
        val pool = VlanPool.create(tenantId, "explicit-pool", range, reserved)
        val pools = InMemoryVlanPoolRepository(pool)
        val scopes = InMemoryVlanAllocationScopeRepository()
        return Fixture(pool, pools, scopes, DeterministicVlanAllocationService(pools, scopes))
    }

    private fun sharedKey(serviceClassId: UUID = UuidV7.generate()) = SharedAllocationKey(
        tenantId = tenantId,
        popId = popId,
        oltId = oltId,
        areaId = areaId,
        serviceClassId = serviceClassId,
    )

    private fun sharedCommand(poolId: UUID, key: SharedAllocationKey, referenceId: UUID) =
        SharedVlanAllocationCommand(
            poolId = poolId,
            intentId = UuidV7.generate(),
            key = key,
            referenceId = referenceId,
        )

    private fun dedicatedCommand(poolId: UUID, intentId: UUID, requestedVlanId: Int? = null) =
        DedicatedVlanAllocationCommand(
            tenantId = tenantId,
            poolId = poolId,
            oltId = oltId,
            intentId = intentId,
            referenceId = intentId,
            requestedVlanId = requestedVlanId,
        )

    private data class Fixture(
        val pool: VlanPool,
        val pools: InMemoryVlanPoolRepository,
        val scopes: InMemoryVlanAllocationScopeRepository,
        val service: DeterministicVlanAllocationService,
    )

    private class InMemoryVlanPoolRepository(pool: VlanPool) : VlanPoolRepository {
        private val values = mutableMapOf(pool.id to pool)
        var lockCount = 0
            private set

        override fun save(value: VlanPool): VlanPool = value.also { values[it.id] = it }
        override fun findById(id: UUID): VlanPool? = values[id]
        override fun findByIdForUpdate(id: UUID): VlanPool? = values[id].also { lockCount++ }
    }

    private class InMemoryVlanAllocationScopeRepository : VlanAllocationScopeRepository {
        val values = mutableListOf<VlanAllocationScope>()

        override fun save(value: VlanAllocationScope): VlanAllocationScope = value.also {
            values.removeIf { existing -> existing.id == value.id }
            values += value
        }

        override fun findShared(key: SharedAllocationKey): VlanAllocationScope? =
            values.singleOrNull { it.sharedKey == key }

        override fun findDedicated(tenantId: UUID, intentId: UUID): VlanAllocationScope? =
            values.singleOrNull { it.tenantId == tenantId && it.intentId == intentId }

        override fun findByAllocationId(allocationId: UUID): VlanAllocationScope? =
            values.singleOrNull { it.allocationId == allocationId }

        override fun delete(value: VlanAllocationScope) {
            values.removeIf { it.id == value.id }
        }
    }
}
