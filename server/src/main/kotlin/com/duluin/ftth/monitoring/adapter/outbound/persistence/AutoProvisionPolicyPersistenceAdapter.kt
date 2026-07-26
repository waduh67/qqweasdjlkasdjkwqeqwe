package com.duluin.ftth.monitoring.adapter.outbound.persistence

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.monitoring.application.port.outbound.AutoProvisionPolicyRepository
import com.duluin.ftth.monitoring.domain.model.AutoProvisionPolicy
import org.springframework.stereotype.Component

@Component
class AutoProvisionPolicyPersistenceAdapter(
    private val jpa: AutoProvisionPolicyJpaRepository,
) : AutoProvisionPolicyRepository {

    override fun save(policy: AutoProvisionPolicy): AutoProvisionPolicy {
        val entity = jpa.findById(policy.id).orElse(null)?.apply {
            enabled = policy.enabled
        } ?: AutoProvisionPolicyJpaEntity(id = policy.id, enabled = policy.enabled)
        return jpa.save(entity).toDomain()
    }

    // Satu baris per tenant; RLS + @TenantId sudah menyaring findAll ke tenant aktif.
    override fun find(): AutoProvisionPolicy? = jpa.findAll().firstOrNull()?.toDomain()
}

private fun AutoProvisionPolicyJpaEntity.toDomain(): AutoProvisionPolicy = AutoProvisionPolicy.rehydrate(
    id = id,
    tenantId = tenantId ?: TenantContext.tenantId(),
    enabled = enabled,
)
