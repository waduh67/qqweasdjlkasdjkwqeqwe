package com.duluin.ftth.provisioning

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.provisioning.application.port.outbound.SegmentProfileRepository
import com.duluin.ftth.provisioning.application.port.outbound.ServiceIntentRepository
import com.duluin.ftth.provisioning.application.port.outbound.VlanAllocationScopeRepository
import com.duluin.ftth.provisioning.application.port.outbound.VlanPoolRepository
import com.duluin.ftth.provisioning.application.service.DeterministicVlanAllocationService
import com.duluin.ftth.provisioning.application.service.SharedVlanAllocationCommand
import com.duluin.ftth.provisioning.domain.model.SegmentProfile
import com.duluin.ftth.provisioning.domain.model.ServiceIntent
import com.duluin.ftth.provisioning.domain.model.SharedAllocationKey
import com.duluin.ftth.provisioning.domain.model.VlanPool
import com.duluin.ftth.provisioning.domain.model.VlanRange
import com.duluin.ftth.tenancy.TenantApi
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
class VlanAllocatorPersistenceIT {
    @Autowired private lateinit var tenantApi: TenantApi
    @Autowired private lateinit var txManager: PlatformTransactionManager
    @Autowired private lateinit var pools: VlanPoolRepository
    @Autowired private lateinit var profiles: SegmentProfileRepository
    @Autowired private lateinit var intents: ServiceIntentRepository
    @Autowired private lateinit var scopes: VlanAllocationScopeRepository
    @Autowired private lateinit var allocator: DeterministicVlanAllocationService
    @PersistenceContext private lateinit var em: EntityManager

    @Test
    fun `shared scope and reference count persist until safe release then lowest VLAN is reused`() {
        val tenantId = tenantApi.ensureTenant("allocator-${UUID.randomUUID().toString().take(8)}", "allocator").id
        asTenant(tenantId) {
            val pool = pools.save(VlanPool.create(tenantId, "pool-${UUID.randomUUID()}", VlanRange(110, 111)))
            val profile = profiles.save(SegmentProfile.create(tenantId, "residential-${UUID.randomUUID()}", pool.id))
            val firstIntent = intents.save(ServiceIntent.create(tenantId, UuidV7.generate(), profile.id))
            val secondIntent = intents.save(ServiceIntent.create(tenantId, UuidV7.generate(), profile.id))
            val thirdIntent = intents.save(ServiceIntent.create(tenantId, UuidV7.generate(), profile.id))
            val key = SharedAllocationKey(
                tenantId,
                UuidV7.generate(),
                UuidV7.generate(),
                UuidV7.generate(),
                UuidV7.generate(),
            )

            val first = allocator.allocateShared(SharedVlanAllocationCommand(pool.id, firstIntent.id, key, firstIntent.id))
            val shared = allocator.allocateShared(SharedVlanAllocationCommand(pool.id, secondIntent.id, key, secondIntent.id))
            em.flush()

            assertThat(shared.id).isEqualTo(first.id)
            assertThat(shared.vlanId).isEqualTo(110)
            assertThat(referenceCount(first.id)).isEqualTo(2)
            assertThat(scopes.findShared(key)?.allocationId).isEqualTo(first.id)

            val retained = allocator.release(tenantId, first.id, firstIntent.id)
            em.flush()
            assertThat(retained.active).isTrue()
            assertThat(referenceCount(first.id)).isEqualTo(1)

            val released = allocator.release(tenantId, first.id, secondIntent.id)
            em.flush()
            assertThat(released.active).isFalse()
            assertThat(scopes.findByAllocationId(first.id)).isNull()

            val reused = allocator.allocateShared(
                SharedVlanAllocationCommand(
                    pool.id,
                    thirdIntent.id,
                    key.copy(serviceClassId = UuidV7.generate()),
                    thirdIntent.id,
                ),
            )
            assertThat(reused.vlanId).isEqualTo(110)
            assertThat(reused.id).isNotEqualTo(first.id)
        }
    }

    private fun referenceCount(allocationId: UUID): Long =
        (em.createNativeQuery("SELECT reference_count FROM provisioning_vlan_allocation WHERE id = :id")
            .setParameter("id", allocationId)
            .singleResult as Number).toLong()

    private fun <T> asTenant(tenantId: UUID, block: () -> T): T = TenantContext.runAs(tenantId) {
        TransactionTemplate(txManager).execute { block() }!!
    }
}
